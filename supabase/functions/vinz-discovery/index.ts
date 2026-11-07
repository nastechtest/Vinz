const MAX_WATCHES_PER_RUN = 15;
const MIN_REQUEST_GAP_MS = 3100;

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
    },
  });
}

function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function restHeaders(serviceKey: string, extra: Record<string, string> = {}) {
  return {
    apikey: serviceKey,
    authorization: `Bearer ${serviceKey}`,
    accept: "application/json",
    ...extra,
  };
}

async function rest(
  base: string,
  serviceKey: string,
  path: string,
  init: RequestInit = {},
): Promise<Response> {
  return fetch(`${base}/rest/v1/${path}`, {
    ...init,
    headers: restHeaders(serviceKey, (init.headers || {}) as Record<string, string>),
    signal: AbortSignal.timeout(12000),
  });
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

Deno.serve(async (request: Request) => {
  if (request.method !== "POST") return response({ error: "method_not_allowed" }, 405);

  const base = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!base || !serviceKey) return response({ error: "discovery_not_configured" }, 503);

  const supplied = request.headers.get("x-vinz-cron-secret") || "";
  if (supplied.length < 32 || supplied.length > 256) return response({ error: "unauthorized" }, 401);
  const verifyResponse = await rest(base, serviceKey, "rpc/vinz_verify_discovery_secret", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ p_secret: supplied }),
  });
  if (!verifyResponse.ok || await verifyResponse.json() !== true) return response({ error: "unauthorized" }, 401);

  const cutoff = new Date(Date.now() - 12 * 60 * 60 * 1000).toISOString();
  const watchQuery = new URLSearchParams({
    select: "id,user_id,provider,provider_artist_id,artist_name,last_checked_at,last_seen_release_id,last_seen_release_date",
    notify_new_releases: "eq.true",
    or: `(last_checked_at.is.null,last_checked_at.lt.${cutoff})`,
    order: "last_checked_at.asc.nullsfirst",
    limit: String(MAX_WATCHES_PER_RUN),
  });

  const watchResponse = await rest(base, serviceKey, `vinz_artist_watches?${watchQuery}`);
  if (!watchResponse.ok) return response({ error: "watch_query_failed" }, 502);
  const watches = await watchResponse.json();

  let checked = 0;
  let notifications = 0;
  let skipped = 0;
  const errors: string[] = [];

  for (const watch of watches as Array<Record<string, unknown>>) {
    if (watch.provider !== "apple_itunes") {
      skipped++;
      continue;
    }

    const artistId = String(watch.provider_artist_id || "");
    if (!/^\d{1,20}$/.test(artistId)) {
      skipped++;
      continue;
    }

    try {
      const settingsQuery = new URLSearchParams({
        select: "notify_new_releases",
        user_id: `eq.${String(watch.user_id)}`,
        limit: "1",
      });
      const settingsResponse = await rest(base, serviceKey, `vinz_account_settings?${settingsQuery}`);
      if (!settingsResponse.ok) throw new Error("settings_lookup_failed");
      const settings = await settingsResponse.json();
      const allowed = Array.isArray(settings) && settings[0]?.notify_new_releases === true;

      const lookup = new URLSearchParams({ id: artistId, entity: "album", limit: "25" });
      const appleResponse = await fetch(`https://itunes.apple.com/lookup?${lookup}`, {
        headers: {
          accept: "application/json",
          "user-agent": "Vinz/1.0 (https://github.com/nastechtest/Vinz)",
        },
        signal: AbortSignal.timeout(12000),
      });
      if (!appleResponse.ok) throw new Error(`apple_http_${appleResponse.status}`);
      const apple = await appleResponse.json();
      const releases = (Array.isArray(apple.results) ? apple.results : [])
        .filter((item: Record<string, unknown>) => item.collectionId && item.releaseDate)
        .sort((a: Record<string, unknown>, b: Record<string, unknown>) =>
          String(b.releaseDate).localeCompare(String(a.releaseDate))
        );

      const newest = releases[0];
      if (!newest) {
        await rest(base, serviceKey, `vinz_artist_watches?id=eq.${watch.id}`, {
          method: "PATCH",
          headers: { "content-type": "application/json", prefer: "return=minimal" },
          body: JSON.stringify({ last_checked_at: new Date().toISOString() }),
        });
        checked++;
        await delay(MIN_REQUEST_GAP_MS);
        continue;
      }

      // First successful check establishes a baseline; it does not notify users
      // about the artist's entire historical catalogue.
      const baselineId = watch.last_seen_release_id ? String(watch.last_seen_release_id) : null;
      const baselineDate = watch.last_seen_release_date ? String(watch.last_seen_release_date) : null;
      const fresh = baselineId
        ? releases.filter((item: Record<string, unknown>) =>
            String(item.releaseDate).slice(0, 10) > (baselineDate || "") ||
            (String(item.releaseDate).slice(0, 10) === baselineDate && String(item.collectionId) !== baselineId)
          )
        : [];

      if (allowed && fresh.length) {
        const rows = fresh.map((item: Record<string, unknown>) => ({
          user_id: watch.user_id,
          kind: "artist_new_release",
          title: `New release: ${String(item.collectionName || "New album")}`,
          body: `${String(item.artistName || watch.artist_name)} • ${String(item.releaseDate || "").slice(0, 10)}`,
          deep_link: `vinz://release/${String(item.collectionId)}`,
          dedupe_key: `artist-release:apple-itunes:${String(item.collectionId)}`,
          payload: {
            provider: "apple_itunes",
            artist_id: artistId,
            release_id: String(item.collectionId),
            release_date: String(item.releaseDate || ""),
            store_url: String(item.collectionViewUrl || ""),
            artwork_url: String(item.artworkUrl100 || ""),
          },
        }));
        const insertQuery = new URLSearchParams({ on_conflict: "user_id,dedupe_key" });
        const insertResponse = await rest(base, serviceKey, `vinz_notifications?${insertQuery}`, {
          method: "POST",
          headers: {
            "content-type": "application/json",
            prefer: "resolution=ignore-duplicates,return=minimal",
          },
          body: JSON.stringify(rows),
        });
        if (!insertResponse.ok) throw new Error("notification_insert_failed");
        notifications += rows.length;
      }

      const update = await rest(base, serviceKey, `vinz_artist_watches?id=eq.${watch.id}`, {
        method: "PATCH",
        headers: { "content-type": "application/json", prefer: "return=minimal" },
        body: JSON.stringify({
          last_checked_at: new Date().toISOString(),
          last_seen_release_id: String(newest.collectionId),
          last_seen_release_date: String(newest.releaseDate).slice(0, 10),
        }),
      });
      if (!update.ok) throw new Error("watch_update_failed");
      checked++;
    } catch (error) {
      errors.push(String(error).slice(0, 120));
    }

    await delay(MIN_REQUEST_GAP_MS);
  }

  return response({
    success: errors.length === 0,
    checked,
    notifications,
    skipped,
    failed: errors.length,
    errors,
    remaining_due_work: watches.length === MAX_WATCHES_PER_RUN,
  });
});
