/**
 * Vinz API — Cloudflare Worker entry point.
 *
 * Supabase is the source of truth. User tokens are passed through to
 * PostgREST so Row Level Security applies to every account operation.
 * R2 stores opaque backup blobs; the database stores only their metadata.
 */
const MAX_BACKUP_BYTES = 50 * 1024 * 1024;
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
};

function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...JSON_HEADERS, ...headers },
  });
}

function allowedOrigin(request, env) {
  const origin = request.headers.get("origin");
  if (!origin) return null;
  const allowed = (env.ALLOWED_ORIGINS || "")
    .split(",").map((value) => value.trim()).filter(Boolean);
  return allowed.includes(origin) ? origin : false;
}

function corsHeaders(origin) {
  return origin ? {
    "access-control-allow-origin": origin,
    "access-control-allow-methods": "GET, PUT, PATCH, DELETE, OPTIONS",
    "access-control-allow-headers": "authorization, content-type, apikey, x-vinz-format-version, x-vinz-app-version, x-vinz-sha256, x-vinz-backup-scope",
    "access-control-max-age": "86400",
    vary: "Origin",
  } : {};
}

function configuration(env) {
  if (!env.SUPABASE_URL || !env.SUPABASE_PUBLISHABLE_KEY) return null;
  try {
    const url = new URL(env.SUPABASE_URL);
    if (url.protocol !== "https:") return null;
    return { url: url.origin, key: env.SUPABASE_PUBLISHABLE_KEY };
  } catch {
    return null;
  }
}

/**
 * Fixed operational telemetry allowlist. Never capture user IDs, emails,
 * request bodies, tokens, music queries, track/artist names, lyrics or audio.
 */
function recordApiError(env, ctx, route, errorType, status) {
  if (status < 500 || !env.POSTHOG_PROJECT_KEY || !env.POSTHOG_HOST || typeof ctx?.waitUntil !== "function") return;
  let host;
  try {
    const parsed = new URL(env.POSTHOG_HOST);
    if (parsed.protocol !== "https:") return;
    host = parsed.origin;
  } catch {
    return;
  }
  const payload = {
    api_key: env.POSTHOG_PROJECT_KEY,
    event: "vinz_api_error",
    distinct_id: "vinz-api",
    properties: {
      service: "cloudflare-worker",
      route,
      error_type: errorType,
      status_code: status,
    },
  };
  ctx.waitUntil(fetch(`${host}/capture/`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  }).catch(() => undefined));
}

async function authenticate(request, config) {
  const authorization = request.headers.get("authorization") || "";
  if (!/^Bearer\s+\S+$/i.test(authorization)) return { error: "unauthorized", status: 401 };
  const response = await fetch(`${config.url}/auth/v1/user`, {
    method: "GET",
    headers: { apikey: config.key, authorization, accept: "application/json" },
    signal: AbortSignal.timeout(8000),
  });
  if (response.status === 401 || response.status === 403) return { error: "unauthorized", status: 401 };
  if (!response.ok) return { error: "identity_provider_unavailable", status: 502 };
  const user = await response.json();
  if (typeof user.id !== "string") return { error: "unauthorized", status: 401 };
  return { user: { id: user.id }, authorization };
}

function restHeaders(config, authorization, extra = {}) {
  return {
    apikey: config.key,
    authorization,
    accept: "application/json",
    ...extra,
  };
}

async function restRequest(config, authorization, path, init = {}) {
  return fetch(`${config.url}/rest/v1/${path}`, {
    ...init,
    headers: restHeaders(config, authorization, init.headers || {}),
    signal: init.signal || AbortSignal.timeout(8000),
  });
}

async function readOwnProfile(config, authorization, userId) {
  const query = new URLSearchParams({
    select: "id,username,display_name,bio,avatar_path,is_public,discoverable,created_at,updated_at",
    id: `eq.${userId}`,
    limit: "1",
  });
  const response = await restRequest(config, authorization, `vinz_profiles?${query}`);
  if (!response.ok) throw new Error("profile_service_unavailable");
  const rows = await response.json();
  return Array.isArray(rows) ? rows[0] || null : null;
}

async function updateProfile(request, config, auth) {
  let input;
  try { input = await request.json(); } catch { return { error: "invalid_json", status: 400 }; }
  if (!input || typeof input !== "object" || Array.isArray(input)) return { error: "invalid_profile", status: 400 };

  const update = {};
  const allowed = ["username", "display_name", "bio", "avatar_path", "is_public", "discoverable"];
  for (const key of Object.keys(input)) {
    if (!allowed.includes(key)) return { error: "field_not_allowed", status: 400 };
  }
  if ("username" in input) {
    if (typeof input.username !== "string" || !/^[a-z0-9_]{3,24}$/.test(input.username)) {
      return { error: "invalid_username", status: 400 };
    }
    update.username = input.username;
  }
  if ("display_name" in input) {
    if (typeof input.display_name !== "string" || input.display_name.trim().length < 1 || input.display_name.length > 60) {
      return { error: "invalid_display_name", status: 400 };
    }
    update.display_name = input.display_name.trim();
  }
  if ("bio" in input) {
    if (typeof input.bio !== "string" || input.bio.length > 160) return { error: "invalid_bio", status: 400 };
    update.bio = input.bio;
  }
  if ("avatar_path" in input) {
    if (input.avatar_path !== null && (typeof input.avatar_path !== "string" || input.avatar_path.length > 512 || input.avatar_path.startsWith("http"))) {
      return { error: "invalid_avatar_path", status: 400 };
    }
    update.avatar_path = input.avatar_path;
  }
  for (const key of ["is_public", "discoverable"]) {
    if (key in input) {
      if (typeof input[key] !== "boolean") return { error: "invalid_boolean", status: 400 };
      update[key] = input[key];
    }
  }
  if (Object.keys(update).length === 0) return { error: "empty_update", status: 400 };

  const query = new URLSearchParams({ id: `eq.${auth.user.id}` });
  const response = await restRequest(config, auth.authorization, `vinz_profiles?${query}`, {
    method: "PATCH",
    headers: { "content-type": "application/json", prefer: "return=representation" },
    body: JSON.stringify(update),
  });
  if (response.status === 409) return { error: "username_taken", status: 409 };
  if (!response.ok) return { error: "profile_update_failed", status: 502 };
  const rows = await response.json();
  return { profile: Array.isArray(rows) ? rows[0] || null : null };
}

async function updateSettings(request, config, auth) {
  let input;
  try { input = await request.json(); } catch { return { error: "invalid_json", status: 400 }; }
  const allowed = [
    "allow_friend_requests", "show_listening_activity", "notify_new_releases",
    "notify_friend_activity", "notify_podcast_updates",
  ];
  if (!input || typeof input !== "object" || Array.isArray(input)) return { error: "invalid_settings", status: 400 };
  const update = {};
  for (const [key, value] of Object.entries(input)) {
    if (!allowed.includes(key) || typeof value !== "boolean") return { error: "invalid_settings_field", status: 400 };
    update[key] = value;
  }
  if (!Object.keys(update).length) return { error: "empty_update", status: 400 };
  const query = new URLSearchParams({ user_id: `eq.${auth.user.id}` });
  const response = await restRequest(config, auth.authorization, `vinz_account_settings?${query}`, {
    method: "PATCH",
    headers: { "content-type": "application/json", prefer: "return=representation" },
    body: JSON.stringify({ ...update, updated_at: new Date().toISOString() }),
  });
  if (!response.ok) return { error: "settings_update_failed", status: 502 };
  const rows = await response.json();
  return { settings: Array.isArray(rows) ? rows[0] || null : null };
}

function hex(buffer) {
  return [...new Uint8Array(buffer)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function uploadBackup(request, env, config, auth, requestId, ctx) {
  if (!env.BACKUPS_BUCKET) return json({ error: "backup_storage_not_configured", request_id: requestId }, 503);
  const length = Number(request.headers.get("content-length") || 0);
  if (length > MAX_BACKUP_BYTES) return json({ error: "backup_too_large", max_bytes: MAX_BACKUP_BYTES, request_id: requestId }, 413);
  const formatVersion = Number(request.headers.get("x-vinz-format-version"));
  const appVersion = request.headers.get("x-vinz-app-version") || "";
  const scope = request.headers.get("x-vinz-backup-scope") || "full";
  const expectedHash = (request.headers.get("x-vinz-sha256") || "").toLowerCase();
  if (!Number.isInteger(formatVersion) || formatVersion < 1 || appVersion.length > 50 || !["full", "library", "settings", "history"].includes(scope) || !/^[a-f0-9]{64}$/.test(expectedHash)) {
    return json({ error: "invalid_backup_metadata", request_id: requestId }, 400);
  }
  const bytes = await request.arrayBuffer();
  if (bytes.byteLength === 0 || bytes.byteLength > MAX_BACKUP_BYTES) {
    return json({ error: bytes.byteLength > MAX_BACKUP_BYTES ? "backup_too_large" : "empty_backup", max_bytes: MAX_BACKUP_BYTES, request_id: requestId }, bytes.byteLength > MAX_BACKUP_BYTES ? 413 : 400);
  }
  const actualHash = hex(await crypto.subtle.digest("SHA-256", bytes));
  if (actualHash !== expectedHash) return json({ error: "backup_checksum_mismatch", request_id: requestId }, 422);

  const id = crypto.randomUUID();
  const objectPath = `${auth.user.id}/${id}.backup`;
  await env.BACKUPS_BUCKET.put(objectPath, bytes, {
    httpMetadata: { contentType: "application/octet-stream" },
    customMetadata: { sha256: actualHash, formatVersion: String(formatVersion) },
  });

  const metadata = {
    user_id: auth.user.id,
    object_path: objectPath,
    byte_size: bytes.byteLength,
    sha256: actualHash,
    format_version: formatVersion,
    app_version: appVersion,
    backup_scope: scope,
  };
  const response = await restRequest(config, auth.authorization, "vinz_backups", {
    method: "POST",
    headers: { "content-type": "application/json", prefer: "return=representation" },
    body: JSON.stringify(metadata),
  });
  if (!response.ok) {
    await env.BACKUPS_BUCKET.delete(objectPath).catch(() => undefined);
    recordApiError(env, ctx, "/v1/backups", "backup_metadata_write_failed", 502);
    return json({ error: "backup_metadata_write_failed", request_id: requestId }, 502);
  }
  const rows = await response.json();
  const record = Array.isArray(rows) ? rows[0] : null;
  return json({ backup: record ? {
    id: record.id,
    byte_size: record.byte_size,
    sha256: record.sha256,
    format_version: record.format_version,
    app_version: record.app_version,
    created_at: record.created_at,
  } : { id, byte_size: bytes.byteLength, sha256: actualHash, format_version: formatVersion } }, 201);
}

async function listBackups(config, auth) {
  const query = new URLSearchParams({
    select: "id,byte_size,sha256,format_version,app_version,backup_scope,created_at",
    order: "created_at.desc",
    limit: "20",
  });
  const response = await restRequest(config, auth.authorization, `vinz_backups?${query}`);
  if (!response.ok) return json({ error: "backup_list_failed" }, 502);
  return json({ backups: await response.json() });
}

async function downloadBackup(env, config, auth, backupId, requestId) {
  if (!env.BACKUPS_BUCKET) return json({ error: "backup_storage_not_configured", request_id: requestId }, 503);
  if (!/^[0-9a-f-]{36}$/i.test(backupId)) return json({ error: "invalid_backup_id", request_id: requestId }, 400);
  const query = new URLSearchParams({
    select: "id,object_path,byte_size,sha256,format_version,app_version,created_at",
    id: `eq.${backupId}`,
    limit: "1",
  });
  const response = await restRequest(config, auth.authorization, `vinz_backups?${query}`);
  if (!response.ok) return json({ error: "backup_lookup_failed", request_id: requestId }, 502);
  const rows = await response.json();
  const record = Array.isArray(rows) ? rows[0] : null;
  if (!record || !record.object_path.startsWith(`${auth.user.id}/`)) return json({ error: "backup_not_found", request_id: requestId }, 404);
  const object = await env.BACKUPS_BUCKET.get(record.object_path);
  if (!object) return json({ error: "backup_object_missing", request_id: requestId }, 410);
  return new Response(object.body, {
    status: 200,
    headers: {
      "content-type": "application/octet-stream",
      "content-length": String(record.byte_size),
      "content-disposition": `attachment; filename="vinz-backup-${record.id}.bin"`,
      "cache-control": "no-store",
      "x-vinz-sha256": record.sha256,
      "x-vinz-format-version": String(record.format_version),
      "x-vinz-app-version": record.app_version || "",
      "x-request-id": requestId,
      "x-content-type-options": "nosniff",
    },
  });
}

async function deleteBackup(env, config, auth, backupId, requestId) {
  if (!env.BACKUPS_BUCKET) return json({ error: "backup_storage_not_configured", request_id: requestId }, 503);
  if (!/^[0-9a-f-]{36}$/i.test(backupId)) return json({ error: "invalid_backup_id", request_id: requestId }, 400);
  const query = new URLSearchParams({ select: "id,object_path", id: `eq.${backupId}`, limit: "1" });
  const lookup = await restRequest(config, auth.authorization, `vinz_backups?${query}`);
  if (!lookup.ok) return json({ error: "backup_lookup_failed", request_id: requestId }, 502);
  const rows = await lookup.json();
  const record = Array.isArray(rows) ? rows[0] : null;
  if (!record || !record.object_path.startsWith(`${auth.user.id}/`)) return json({ error: "backup_not_found", request_id: requestId }, 404);
  const removeQuery = new URLSearchParams({ id: `eq.${backupId}` });
  const removed = await restRequest(config, auth.authorization, `vinz_backups?${removeQuery}`, { method: "DELETE" });
  if (!removed.ok) return json({ error: "backup_delete_failed", request_id: requestId }, 502);
  // Database metadata is removed first; a failed object cleanup leaves only an
  // inaccessible orphan, never a visible backup record pointing at missing data.
  await env.BACKUPS_BUCKET.delete(record.object_path).catch(() => undefined);
  return json({ deleted: true, id: backupId }, 200);
}


const ITUNES_TYPES = {
  song: { media: "music", entity: "song" },
  album: { media: "music", entity: "album" },
  artist: { media: "music", entity: "musicArtist" },
  podcast: { media: "podcast", entity: "podcast" },
};

async function cacheRead(env, key) {
  if (!env.CATALOG_CACHE) return null;
  const value = await env.CATALOG_CACHE.get(key);
  if (!value) return null;
  try { return JSON.parse(value); } catch { return null; }
}

async function cacheWrite(env, key, value, ttl) {
  if (!env.CATALOG_CACHE) return;
  await env.CATALOG_CACHE.put(key, JSON.stringify(value), { expirationTtl: ttl }).catch(() => undefined);
}

async function cacheKey(prefix, value) {
  return `${prefix}:${hex(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)))}`;
}

function normalizeAppleResult(item, type) {
  if (type === "artist") return {
    provider: "apple_itunes",
    provider_id: String(item.artistId || item.artistName || ""),
    title: item.artistName || "",
    genre: item.primaryGenreName || null,
    artwork_url: item.artworkUrl100 || null,
    store_url: item.artistViewUrl || null,
  };
  if (type === "podcast") return {
    provider: "apple_itunes",
    provider_id: String(item.collectionId || ""),
    title: item.collectionName || "",
    publisher: item.artistName || "",
    episode_count: item.trackCount || null,
    artwork_url: item.artworkUrl600 || item.artworkUrl100 || null,
    store_url: item.collectionViewUrl || null,
    feed_url: item.feedUrl || null,
    genre: item.primaryGenreName || null,
  };
  return {
    provider: "apple_itunes",
    provider_id: String(item.trackId || item.collectionId || ""),
    title: item.trackName || item.collectionName || "",
    artist: item.artistName || "",
    album: item.collectionName || null,
    release_date: item.releaseDate || null,
    duration_ms: item.trackTimeMillis || null,
    artwork_url: item.artworkUrl100 || null,
    store_url: item.trackViewUrl || item.collectionViewUrl || null,
    genre: item.primaryGenreName || null,
    explicit: item.trackExplicitness === "explicit" || item.collectionExplicitness === "explicit",
  };
}

async function appleRequest(url) {
  const response = await fetch(url, {
    headers: {
      accept: "application/json",
      "user-agent": "Vinz/1.0 (https://github.com/nastechtest/Vinz)",
    },
    signal: AbortSignal.timeout(9000),
  });
  if (!response.ok) throw new Error(`apple_catalog_http_${response.status}`);
  return response.json();
}

async function searchCatalog(url, env, requestId) {
  const term = (url.searchParams.get("q") || "").trim();
  const type = (url.searchParams.get("type") || "song").toLowerCase();
  const country = (url.searchParams.get("country") || "").toLowerCase();
  const limit = Math.min(25, Math.max(1, Number(url.searchParams.get("limit") || 20)));
  if (term.length < 2 || term.length > 100) return json({ error: "invalid_search_term", request_id: requestId }, 400);
  if (!ITUNES_TYPES[type]) return json({ error: "unsupported_catalog_type", supported: Object.keys(ITUNES_TYPES), request_id: requestId }, 400);
  if (country && !/^[a-z]{2}$/.test(country)) return json({ error: "invalid_storefront", request_id: requestId }, 400);

  const cacheId = await cacheKey("itunes-search-v1", JSON.stringify({ term: term.toLowerCase(), type, country, limit }));
  const cached = await cacheRead(env, cacheId);
  if (cached) return json({ ...cached, cached: true });

  const options = ITUNES_TYPES[type];
  const params = new URLSearchParams({ term, media: options.media, entity: options.entity, limit: String(limit) });
  if (country) params.set("country", country);
  try {
    const result = await appleRequest(`https://itunes.apple.com/search?${params}`);
    const payload = {
      provider: "apple_itunes",
      attribution: "Apple iTunes Search API",
      result_count: Number(result.resultCount || 0),
      results: (Array.isArray(result.results) ? result.results : []).map((item) => normalizeAppleResult(item, type)),
    };
    await cacheWrite(env, cacheId, payload, 21600);
    return json({ ...payload, cached: false });
  } catch {
    return json({ error: "catalog_provider_unavailable", provider: "apple_itunes", request_id: requestId }, 502);
  }
}

async function artistReleases(url, env, artistId, requestId) {
  if (!/^[0-9]{1,20}$/.test(artistId)) return json({ error: "invalid_artist_id", request_id: requestId }, 400);
  const country = (url.searchParams.get("country") || "").toLowerCase();
  if (country && !/^[a-z]{2}$/.test(country)) return json({ error: "invalid_storefront", request_id: requestId }, 400);
  const cacheId = await cacheKey("itunes-artist-releases-v1", `${artistId}:${country}`);
  const cached = await cacheRead(env, cacheId);
  if (cached) return json({ ...cached, cached: true });

  const params = new URLSearchParams({ id: artistId, entity: "album", limit: "200" });
  if (country) params.set("country", country);
  try {
    const result = await appleRequest(`https://itunes.apple.com/lookup?${params}`);
    const results = (Array.isArray(result.results) ? result.results : [])
      .filter((item) => item.wrapperType === "collection" || item.collectionId)
      .map((item) => normalizeAppleResult(item, "album"))
      .sort((a, b) => String(b.release_date || "").localeCompare(String(a.release_date || "")));
    const payload = {
      provider: "apple_itunes",
      attribution: "Apple iTunes Search API",
      artist_id: artistId,
      result_count: results.length,
      releases: results,
    };
    await cacheWrite(env, cacheId, payload, 43200);
    return json({ ...payload, cached: false });
  } catch {
    return json({ error: "catalog_provider_unavailable", provider: "apple_itunes", request_id: requestId }, 502);
  }
}


function withHeaders(response, extra) {
  const merged = new Headers(response.headers);
  for (const [key, value] of Object.entries(extra || {})) merged.set(key, value);
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers: merged });
}

async function bodyJson(request) {
  try {
    const value = await request.json();
    return value && typeof value === "object" && !Array.isArray(value) ? value : null;
  } catch {
    return null;
  }
}

async function collectionList(config, auth, table, select) {
  const query = new URLSearchParams({ select, order: "created_at.desc", limit: "100" });
  const response = await restRequest(config, auth.authorization, `${table}?${query}`);
  if (!response.ok) return json({ error: "collection_lookup_failed" }, 502);
  return json({ items: await response.json() });
}

async function createArtistWatch(request, config, auth) {
  const input = await bodyJson(request);
  if (!input || !["apple_itunes", "musicbrainz"].includes(input.provider) ||
      typeof input.provider_artist_id !== "string" || !/^[a-zA-Z0-9-]{1,100}$/.test(input.provider_artist_id) ||
      typeof input.artist_name !== "string" || !input.artist_name.trim() || input.artist_name.length > 150 ||
      (input.notify_new_releases !== undefined && typeof input.notify_new_releases !== "boolean")) {
    return json({ error: "invalid_artist_watch" }, 400);
  }
  const payload = {
    user_id: auth.user.id,
    provider: input.provider,
    provider_artist_id: input.provider_artist_id,
    artist_name: input.artist_name.trim(),
    notify_new_releases: input.notify_new_releases ?? true,
  };
  const response = await restRequest(config, auth.authorization, "vinz_artist_watches", {
    method: "POST",
    headers: { "content-type": "application/json", prefer: "return=representation" },
    body: JSON.stringify(payload),
  });
  if (response.status === 409) return json({ error: "artist_already_watched" }, 409);
  if (!response.ok) return json({ error: "artist_watch_create_failed" }, 502);
  const rows = await response.json();
  return json({ watch: Array.isArray(rows) ? rows[0] || null : null }, 201);
}

async function createPodcastSubscription(request, config, auth) {
  const input = await bodyJson(request);
  if (!input || !["apple_itunes", "podcast_index", "rss"].includes(input.provider) ||
      typeof input.provider_podcast_id !== "string" || !/^[a-zA-Z0-9._:-]{1,128}$/.test(input.provider_podcast_id) ||
      typeof input.title !== "string" || !input.title.trim() || input.title.length > 200 ||
      (input.publisher !== undefined && (typeof input.publisher !== "string" || input.publisher.length > 200)) ||
      (input.notify_new_episodes !== undefined && typeof input.notify_new_episodes !== "boolean")) {
    return json({ error: "invalid_podcast_subscription" }, 400);
  }
  let feed;
  try {
    feed = new URL(input.feed_url);
    if (feed.protocol !== "https:" || feed.username || feed.password ||
        ["localhost", "127.0.0.1", "::1"].includes(feed.hostname) ||
        !feed.hostname.includes(".")) return json({ error: "invalid_feed_url" }, 400);
  } catch {
    return json({ error: "invalid_feed_url" }, 400);
  }
  const payload = {
    user_id: auth.user.id,
    provider: input.provider,
    provider_podcast_id: input.provider_podcast_id,
    feed_url: feed.toString(),
    title: input.title.trim(),
    publisher: input.publisher || "",
    notify_new_episodes: input.notify_new_episodes ?? true,
  };
  const response = await restRequest(config, auth.authorization, "vinz_podcast_subscriptions", {
    method: "POST",
    headers: { "content-type": "application/json", prefer: "return=representation" },
    body: JSON.stringify(payload),
  });
  if (response.status === 409) return json({ error: "podcast_already_subscribed" }, 409);
  if (!response.ok) return json({ error: "podcast_subscription_create_failed" }, 502);
  const rows = await response.json();
  return json({ subscription: Array.isArray(rows) ? rows[0] || null : null }, 201);
}

async function registerDevice(request, config, auth) {
  const input = await bodyJson(request);
  if (!input || !["android", "ios", "web"].includes(input.platform) ||
      typeof input.push_token !== "string" || input.push_token.length < 20 || input.push_token.length > 4096 ||
      (input.device_label !== undefined && (typeof input.device_label !== "string" || input.device_label.length > 100))) {
    return json({ error: "invalid_notification_device" }, 400);
  }
  const payload = {
    user_id: auth.user.id,
    platform: input.platform,
    push_token: input.push_token,
    device_label: input.device_label || "",
    last_seen_at: new Date().toISOString(),
  };
  const query = new URLSearchParams({ on_conflict: "user_id,push_token" });
  const response = await restRequest(config, auth.authorization, `vinz_notification_devices?${query}`, {
    method: "POST",
    headers: { "content-type": "application/json", prefer: "resolution=merge-duplicates,return=minimal" },
    body: JSON.stringify(payload),
  });
  if (!response.ok) return json({ error: "notification_device_register_failed" }, 502);
  return json({ registered: true }, 201);
}

async function deleteOwnedRow(config, auth, table, id) {
  if (!/^[0-9a-f-]{36}$/i.test(id)) return json({ error: "invalid_id" }, 400);
  const query = new URLSearchParams({ id: `eq.${id}` });
  const response = await restRequest(config, auth.authorization, `${table}?${query}`, { method: "DELETE" });
  if (!response.ok) return json({ error: "delete_failed" }, 502);
  return json({ deleted: true, id });
}

async function listNotifications(config, auth) {
  const query = new URLSearchParams({
    select: "id,kind,title,body,deep_link,payload,read_at,created_at",
    order: "created_at.desc",
    limit: "50",
  });
  const response = await restRequest(config, auth.authorization, `vinz_notifications?${query}`);
  if (!response.ok) return json({ error: "notifications_lookup_failed" }, 502);
  return json({ notifications: await response.json() });
}

async function markNotificationRead(config, auth, id) {
  if (!/^[0-9a-f-]{36}$/i.test(id)) return json({ error: "invalid_notification_id" }, 400);
  const query = new URLSearchParams({ id: `eq.${id}` });
  const response = await restRequest(config, auth.authorization, `vinz_notifications?${query}`, {
    method: "PATCH",
    headers: { "content-type": "application/json", prefer: "return=minimal" },
    body: JSON.stringify({ read_at: new Date().toISOString() }),
  });
  if (!response.ok) return json({ error: "notification_update_failed" }, 502);
  return json({ updated: true, id });
}

export async function handleRequest(request, env = {}, ctx = undefined) {
  const requestId = request.headers.get("cf-ray") || crypto.randomUUID();
  const origin = allowedOrigin(request, env);
  if (origin === false) return json({ error: "origin_not_allowed", request_id: requestId }, 403);
  const headers = { ...corsHeaders(origin), "x-request-id": requestId };
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers });

  const url = new URL(request.url);
  if (url.pathname === "/health" && request.method === "GET") {
    return json({ service: "vinz-api", status: "ok", configured: Boolean(configuration(env)), backup_storage: Boolean(env.BACKUPS_BUCKET) }, 200, headers);
  }
  const config = configuration(env);
  if (!config && url.pathname.startsWith("/v1/")) {
    return json({ error: "backend_not_configured", request_id: requestId }, 503, headers);
  }

  if (url.pathname === "/v1/profiles" || url.pathname.startsWith("/v1/profiles/")) {
    if (request.method !== "GET") return json({ error: "method_not_allowed", request_id: requestId }, 405, { ...headers, allow: "GET, OPTIONS" });
    const auth = await authenticate(request, config);
    if (auth.error) return json({ error: auth.error, request_id: requestId }, auth.status, headers);
    const username = decodeURIComponent(url.pathname.slice("/v1/profiles/".length));
    if (!username || !/^[a-z0-9_]{3,24}$/.test(username)) return json({ error: "invalid_username", request_id: requestId }, 400, headers);
    const query = new URLSearchParams({
      select: "username,display_name,bio,avatar_path",
      username: `eq.${username}`,
      is_public: "eq.true",
      discoverable: "eq.true",
      limit: "1",
    });
    const response = await restRequest(config, auth.authorization, `vinz_profiles?${query}`);
    if (!response.ok) return json({ error: "profile_lookup_failed", request_id: requestId }, 502, headers);
    const rows = await response.json();
    return json({ profile: Array.isArray(rows) ? rows[0] || null : null }, 200, headers);
  }

  if (url.pathname.startsWith("/v1/")) {
    const auth = await authenticate(request, config);
    if (auth.error) {
      recordApiError(env, ctx, url.pathname, auth.error, auth.status);
      return json({ error: auth.error, request_id: requestId }, auth.status, headers);
    }

    if (url.pathname === "/v1/watches/artists" && request.method === "GET") {
      return withHeaders(await collectionList(config, auth, "vinz_artist_watches", "id,provider,provider_artist_id,artist_name,notify_new_releases,last_checked_at,last_seen_release_id,last_seen_release_date,created_at"), headers);
    }
    if (url.pathname === "/v1/watches/artists" && request.method === "POST") {
      return withHeaders(await createArtistWatch(request, config, auth), headers);
    }
    const artistWatchMatch = url.pathname.match(/^\/v1\/watches\/artists\/([^/]+)$/);
    if (artistWatchMatch && request.method === "DELETE") {
      return withHeaders(await deleteOwnedRow(config, auth, "vinz_artist_watches", artistWatchMatch[1]), headers);
    }

    if (url.pathname === "/v1/podcasts/subscriptions" && request.method === "GET") {
      return withHeaders(await collectionList(config, auth, "vinz_podcast_subscriptions", "id,provider,provider_podcast_id,feed_url,title,publisher,notify_new_episodes,last_checked_at,last_seen_episode_guid,created_at"), headers);
    }
    if (url.pathname === "/v1/podcasts/subscriptions" && request.method === "POST") {
      return withHeaders(await createPodcastSubscription(request, config, auth), headers);
    }
    const podcastSubMatch = url.pathname.match(/^\/v1\/podcasts\/subscriptions\/([^/]+)$/);
    if (podcastSubMatch && request.method === "DELETE") {
      return withHeaders(await deleteOwnedRow(config, auth, "vinz_podcast_subscriptions", podcastSubMatch[1]), headers);
    }

    if (url.pathname === "/v1/devices" && request.method === "POST") {
      return withHeaders(await registerDevice(request, config, auth), headers);
    }
    const deviceMatch = url.pathname.match(/^\/v1\/devices\/([^/]+)$/);
    if (deviceMatch && request.method === "DELETE") {
      return withHeaders(await deleteOwnedRow(config, auth, "vinz_notification_devices", deviceMatch[1]), headers);
    }

    if (url.pathname === "/v1/notifications" && request.method === "GET") {
      return withHeaders(await listNotifications(config, auth), headers);
    }
    const notificationMatch = url.pathname.match(/^\/v1\/notifications\/([^/]+)$/);
    if (notificationMatch && request.method === "PATCH") {
      return withHeaders(await markNotificationRead(config, auth, notificationMatch[1]), headers);
    }

    if (url.pathname === "/v1/catalog/search" && request.method === "GET") {
      return withHeaders(await searchCatalog(url, env, requestId), headers);
    }
    const artistReleasesMatch = url.pathname.match(/^\/v1\/catalog\/artists\/([0-9]+)\/releases$/);
    if (artistReleasesMatch && request.method === "GET") {
      return withHeaders(await artistReleases(url, env, artistReleasesMatch[1], requestId), headers);
    }

    if (url.pathname === "/v1/me" && request.method === "GET") {
      try {
        const profile = await readOwnProfile(config, auth.authorization, auth.user.id);
        return json({ user: auth.user, profile }, 200, headers);
      } catch {
        recordApiError(env, ctx, "/v1/me", "profile_service_unavailable", 502);
        return json({ error: "profile_service_unavailable", request_id: requestId }, 502, headers);
      }
    }

    if (url.pathname === "/v1/me" && request.method === "PATCH") {
      const result = await updateProfile(request, config, auth);
      if (result.error) return json({ error: result.error, request_id: requestId }, result.status, headers);
      return json(result, 200, headers);
    }

    if (url.pathname === "/v1/me/settings" && request.method === "GET") {
      const query = new URLSearchParams({ select: "user_id,allow_friend_requests,show_listening_activity,notify_new_releases,notify_friend_activity,notify_podcast_updates,updated_at", user_id: `eq.${auth.user.id}`, limit: "1" });
      const response = await restRequest(config, auth.authorization, `vinz_account_settings?${query}`);
      if (!response.ok) return json({ error: "settings_lookup_failed", request_id: requestId }, 502, headers);
      const rows = await response.json();
      return json({ settings: Array.isArray(rows) ? rows[0] || null : null }, 200, headers);
    }

    if (url.pathname === "/v1/me/settings" && request.method === "PATCH") {
      const result = await updateSettings(request, config, auth);
      if (result.error) return json({ error: result.error, request_id: requestId }, result.status, headers);
      return json(result, 200, headers);
    }

    if (url.pathname === "/v1/backups" && request.method === "GET") {
      return withHeaders(await listBackups(config, auth), headers);
    }
    if (url.pathname === "/v1/backups" && request.method === "PUT") {
      try { return withHeaders(await uploadBackup(request, env, config, auth, requestId, ctx), headers); }
      catch {
        recordApiError(env, ctx, "/v1/backups", "backup_upload_failed", 502);
        return json({ error: "backup_upload_failed", request_id: requestId }, 502, headers);
      }
    }
    const backupMatch = url.pathname.match(/^\/v1\/backups\/([^/]+)$/);
    if (backupMatch && request.method === "GET") {
      try { return withHeaders(await downloadBackup(env, config, auth, backupMatch[1], requestId), headers); }
      catch {
        recordApiError(env, ctx, "/v1/backups/:id", "backup_download_failed", 502);
        return json({ error: "backup_download_failed", request_id: requestId }, 502, headers);
      }
    }
    if (backupMatch && request.method === "DELETE") {
      try { return withHeaders(await deleteBackup(env, config, auth, backupMatch[1], requestId), headers); }
      catch {
        recordApiError(env, ctx, "/v1/backups/:id", "backup_delete_failed", 502);
        return json({ error: "backup_delete_failed", request_id: requestId }, 502, headers);
      }
    }
    if (url.pathname.startsWith("/v1/")) return json({ error: "not_found", request_id: requestId }, 404, headers);
  }

  return json({ error: "not_found", request_id: requestId }, 404, headers);
}

export default {
  fetch(request, env, ctx) {
    return handleRequest(request, env, ctx);
  },
};
