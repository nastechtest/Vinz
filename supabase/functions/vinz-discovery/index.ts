import { XMLParser } from "npm:fast-xml-parser@4.5.3";

const MAX_WATCHES_PER_RUN = 8;
const MAX_PODCASTS_PER_RUN = 5;
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


function xmlText(value: unknown): string {
  if (typeof value === "string" || typeof value === "number") return String(value);
  if (value && typeof value === "object" && "#text" in value) return String((value as Record<string, unknown>)["#text"] || "");
  return "";
}

function feedLink(item: Record<string, unknown>): string {
  const link = item.link;
  if (typeof link === "string") return link;
  if (Array.isArray(link)) {
    const preferred = link.find((entry) => entry && typeof entry === "object" &&
      ((entry as Record<string, unknown>)["@_rel"] === "alternate" || !(entry as Record<string, unknown>)["@_rel"]));
    return preferred && typeof preferred === "object" ? String((preferred as Record<string, unknown>)["@_href"] || "") : "";
  }
  if (link && typeof link === "object") return String((link as Record<string, unknown>)["@_href"] || "");
  return "";
}

function safePublicFeedUrl(value: string): URL | null {
  try {
    const url = new URL(value);
    const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, "");
    if (url.protocol !== "https:" || url.username || url.password ||
        (url.port && url.port !== "443") ||
        host === "localhost" || host.endsWith(".local") || host.endsWith(".internal") ||
        !host.includes(".") ||
        /^\d{1,3}(\.\d{1,3}){3}$/.test(host) ||
        host.includes(":") || host === "0.0.0.0") return null;
    return url;
  } catch {
    return null;
  }
}

async function readFeedBody(response: Response): Promise<string> {
  if (!response.body) throw new Error("empty_feed_body");
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > 2 * 1024 * 1024) {
      await reader.cancel();
      throw new Error("feed_too_large");
    }
    chunks.push(value);
  }
  const joined = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    joined.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(joined);
}

async function parsePodcastFeed(feedUrl: string) {
  let url = safePublicFeedUrl(feedUrl);
  if (!url) throw new Error("unsafe_feed_url");
  let response = await fetch(url, {
    redirect: "manual",
    headers: { accept: "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9" },
    signal: AbortSignal.timeout(7000),
  });
  if (response.status >= 300 && response.status < 400) {
    const location = response.headers.get("location");
    if (!location) throw new Error("invalid_feed_redirect");
    const redirected = safePublicFeedUrl(new URL(location, url).toString());
    if (!redirected || redirected.hostname !== url.hostname) throw new Error("unsafe_feed_redirect");
    url = redirected;
    response = await fetch(url, {
      redirect: "manual",
      headers: { accept: "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9" },
      signal: AbortSignal.timeout(7000),
    });
  }
  if (!response.ok) throw new Error(`feed_http_${response.status}`);
  const xml = await readFeedBody(response);
  const parser = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: "@_",
    textNodeName: "#text",
    parseTagValue: false,
    trimValues: true,
  });
  const document = parser.parse(xml);
  const raw = document?.rss?.channel?.item ?? document?.feed?.entry ?? [];
  const items = (Array.isArray(raw) ? raw : [raw])
    .filter((item) => item && typeof item === "object")
    .map((item: Record<string, unknown>) => {
      const title = xmlText(item.title);
      const published = xmlText(item.pubDate || item.published || item.updated);
      const guid = xmlText(item.guid || item.id) || feedLink(item) || `${title}:${published}`;
      const enclosure = item.enclosure && typeof item.enclosure === "object"
        ? String((item.enclosure as Record<string, unknown>)["@_url"] || "")
        : "";
      return {
        guid,
        title: title || "New episode",
        published_at: published,
        published_ms: Date.parse(published) || 0,
        link: feedLink(item),
        enclosure_url: enclosure,
      };
    })
    .filter((item) => item.guid)
    .sort((a, b) => b.published_ms - a.published_ms);
  return items.slice(0, 30);
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
        signal: AbortSignal.timeout(7000),
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


  const podcastCutoff = new Date(Date.now() - 12 * 60 * 60 * 1000).toISOString();
  const podcastQuery = new URLSearchParams({
    select: "id,user_id,provider,provider_podcast_id,feed_url,title,last_checked_at,last_seen_episode_guid",
    notify_new_episodes: "eq.true",
    or: `(last_checked_at.is.null,last_checked_at.lt.${podcastCutoff})`,
    order: "last_checked_at.asc.nullsfirst",
    limit: String(MAX_PODCASTS_PER_RUN),
  });
  const podcastResponse = await rest(base, serviceKey, `vinz_podcast_subscriptions?${podcastQuery}`);
  if (!podcastResponse.ok) errors.push("podcast_subscription_query_failed");
  const subscriptions = podcastResponse.ok ? await podcastResponse.json() : [];
  let podcastsChecked = 0;
  let podcastNotifications = 0;

  for (const subscription of subscriptions as Array<Record<string, unknown>>) {
    try {
      const settingsQuery = new URLSearchParams({
        select: "notify_podcast_updates",
        user_id: `eq.${String(subscription.user_id)}`,
        limit: "1",
      });
      const settingsResponse = await rest(base, serviceKey, `vinz_account_settings?${settingsQuery}`);
      if (!settingsResponse.ok) throw new Error("podcast_settings_lookup_failed");
      const settings = await settingsResponse.json();
      const allowed = Array.isArray(settings) && settings[0]?.notify_podcast_updates === true;

      const episodes = await parsePodcastFeed(String(subscription.feed_url || ""));
      const newest = episodes[0];
      if (!newest) {
        await rest(base, serviceKey, `vinz_podcast_subscriptions?id=eq.${subscription.id}`, {
          method: "PATCH",
          headers: { "content-type": "application/json", prefer: "return=minimal" },
          body: JSON.stringify({ last_checked_at: new Date().toISOString() }),
        });
        podcastsChecked++;
        continue;
      }

      const previousGuid = subscription.last_seen_episode_guid ? String(subscription.last_seen_episode_guid) : null;
      const previousCheck = subscription.last_checked_at ? Date.parse(String(subscription.last_checked_at)) : 0;
      let fresh = [] as typeof episodes;
      if (previousGuid) {
        const index = episodes.findIndex((episode) => episode.guid === previousGuid);
        fresh = index >= 0
          ? episodes.slice(0, index)
          : episodes.filter((episode) => episode.published_ms > previousCheck && episode.published_ms > 0);
      }

      if (allowed && fresh.length) {
        const rows = fresh.map((episode) => ({
          user_id: subscription.user_id,
          kind: "podcast_new_episode",
          title: `New episode: ${episode.title}`,
          body: String(subscription.title || "Podcast"),
          deep_link: `vinz://podcast/${String(subscription.provider_podcast_id)}/episode/${encodeURIComponent(episode.guid)}`,
          dedupe_key: `podcast-episode:${String(subscription.provider)}:${String(subscription.provider_podcast_id)}:${episode.guid}`,
          payload: {
            provider: String(subscription.provider),
            podcast_id: String(subscription.provider_podcast_id),
            episode_guid: episode.guid,
            published_at: episode.published_at,
            episode_url: episode.link,
            enclosure_url: episode.enclosure_url,
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
        if (!insertResponse.ok) throw new Error("podcast_notification_insert_failed");
        podcastNotifications += rows.length;
      }

      const update = await rest(base, serviceKey, `vinz_podcast_subscriptions?id=eq.${subscription.id}`, {
        method: "PATCH",
        headers: { "content-type": "application/json", prefer: "return=minimal" },
        body: JSON.stringify({
          last_checked_at: new Date().toISOString(),
          last_seen_episode_guid: newest.guid,
        }),
      });
      if (!update.ok) throw new Error("podcast_subscription_update_failed");
      podcastsChecked++;
    } catch (error) {
      errors.push(String(error).slice(0, 120));
    }
  }

  return response({
    success: errors.length === 0,
    checked,
    notifications,
    podcasts_checked: podcastsChecked,
    podcast_notifications: podcastNotifications,
    skipped,
    failed: errors.length,
    errors,
    remaining_due_work: watches.length === MAX_WATCHES_PER_RUN,
  });
});
