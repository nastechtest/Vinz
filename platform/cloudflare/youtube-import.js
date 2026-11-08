const CALLBACK_URL = "https://api.vinzmusic.workers.dev/v1/integrations/youtube/callback";
const RETURN_URL = "https://home.vinzmusic.workers.dev/account/connections";
const YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube.readonly";

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}
function randomToken(bytes = 32) {
  const value = crypto.getRandomValues(new Uint8Array(bytes));
  return btoa(String.fromCharCode(...value)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function decodeBase64Url(value) {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(normalized + "=".repeat((4 - normalized.length % 4) % 4)), (c) => c.charCodeAt(0));
}
function encodeBase64Url(value) {
  return btoa(String.fromCharCode(...new Uint8Array(value))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
async function encryptionKey(env) {
  if (!env.YOUTUBE_TOKEN_ENCRYPTION_KEY) throw new Error("youtube_encryption_not_configured");
  const raw = decodeBase64Url(env.YOUTUBE_TOKEN_ENCRYPTION_KEY);
  if (raw.byteLength !== 32) throw new Error("youtube_encryption_key_must_be_32_bytes_base64url");
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}
async function encryptSecret(value, env) {
  const key = await encryptionKey(env);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, new TextEncoder().encode(value));
  return encodeBase64Url(iv) + "." + encodeBase64Url(encrypted);
}
async function decryptSecret(value, env) {
  if (!value) return null;
  const [iv, ciphertext] = value.split(".");
  if (!iv || !ciphertext) throw new Error("invalid_encrypted_youtube_token");
  const key = await encryptionKey(env);
  const decrypted = await crypto.subtle.decrypt({ name: "AES-GCM", iv: decodeBase64Url(iv) }, key, decodeBase64Url(ciphertext));
  return new TextDecoder().decode(decrypted);
}
function callbackResult(reason) {
  const url = new URL(RETURN_URL);
  url.searchParams.set("youtube", reason);
  return Response.redirect(url.toString(), 302);
}
function configReady(env) {
  return Boolean(env.GOOGLE_CLIENT_ID && env.GOOGLE_CLIENT_SECRET && env.YOUTUBE_TOKEN_ENCRYPTION_KEY && env.CATALOG_CACHE);
}
async function databaseRequest(config, authorization, path, init = {}) {
  return fetch(config.url + "/rest/v1/" + path, {
    ...init,
    headers: {
      apikey: config.key,
      authorization,
      accept: "application/json",
      ...(init.headers || {}),
    },
    signal: init.signal || AbortSignal.timeout(10000),
  });
}
async function getConnection(config, authorization, userId, includeTokens = false) {
  const columns = includeTokens
    ? "user_id,channel_id,channel_title,encrypted_access_token,encrypted_refresh_token,access_token_expires_at,scopes,connected_at"
    : "user_id,channel_id,channel_title,scopes,connected_at,updated_at";
  const query = new URLSearchParams({ select: columns, user_id: "eq." + userId, limit: "1" });
  const response = await databaseRequest(config, authorization, "vinz_youtube_connections?" + query);
  if (!response.ok) throw new Error("youtube_connection_lookup_failed");
  const rows = await response.json();
  return Array.isArray(rows) ? rows[0] || null : null;
}
async function saveConnection(config, authorization, record) {
  const response = await databaseRequest(config, authorization, "vinz_youtube_connections?on_conflict=user_id", {
    method: "POST",
    headers: { "content-type": "application/json", prefer: "resolution=merge-duplicates,return=minimal" },
    body: JSON.stringify(record),
  });
  if (!response.ok) throw new Error("youtube_connection_save_failed");
}
async function exchangeCode(code, verifier, env) {
  const body = new URLSearchParams({
    code,
    client_id: env.GOOGLE_CLIENT_ID,
    client_secret: env.GOOGLE_CLIENT_SECRET,
    redirect_uri: CALLBACK_URL,
    grant_type: "authorization_code",
    code_verifier: verifier,
  });
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body,
    signal: AbortSignal.timeout(10000),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || !payload.access_token) throw new Error("youtube_oauth_exchange_failed");
  return payload;
}
async function refreshAccessToken(refreshToken, env) {
  const body = new URLSearchParams({
    refresh_token: refreshToken,
    client_id: env.GOOGLE_CLIENT_ID,
    client_secret: env.GOOGLE_CLIENT_SECRET,
    grant_type: "refresh_token",
  });
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body,
    signal: AbortSignal.timeout(10000),
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok || !payload.access_token) throw new Error("youtube_reauthorization_required");
  return payload;
}
async function youtubeRequest(path, accessToken) {
  return fetch("https://www.googleapis.com/youtube/v3/" + path, {
    headers: { authorization: "Bearer " + accessToken, accept: "application/json" },
    signal: AbortSignal.timeout(12000),
  });
}
async function listPlaylistItems(playlist, accessToken, userId) {
  const output = [];
  let pageToken = "";
  for (let page = 0; page < 2; page++) {
    const params = new URLSearchParams({
      part: "snippet,contentDetails",
      playlistId: playlist.id,
      maxResults: "50",
    });
    if (pageToken) params.set("pageToken", pageToken);
    const response = await youtubeRequest("playlistItems?" + params, accessToken);
    if (!response.ok) throw new Error("youtube_playlist_items_failed");
    const payload = await response.json();
    for (const item of payload.items || []) {
      const videoId = item.contentDetails?.videoId || item.snippet?.resourceId?.videoId;
      const title = String(item.snippet?.title || "").trim();
      if (!videoId || !title || title === "Deleted video" || title === "Private video") continue;
      const thumbs = item.snippet?.thumbnails || {};
      const thumb = thumbs.high?.url || thumbs.medium?.url || thumbs.default?.url || null;
      output.push({
        user_id: userId,
        playlist_id: playlist.id,
        playlist_title: String(playlist.snippet?.title || "YouTube playlist").slice(0, 200),
        video_id: videoId,
        title: title.slice(0, 200),
        artist: String(item.snippet?.videoOwnerChannelTitle || "").slice(0, 200),
        thumbnail_url: typeof thumb === "string" && thumb.startsWith("https://i.ytimg.com/") ? thumb : null,
        published_at: item.contentDetails?.videoPublishedAt || null,
        imported_at: new Date().toISOString(),
      });
    }
    pageToken = payload.nextPageToken || "";
    if (!pageToken) break;
  }
  return output;
}

export async function handleYouTubeCallback(request, url, env, config) {
  if (!configReady(env) || !config) return callbackResult("not_configured");
  const state = url.searchParams.get("state") || "";
  const code = url.searchParams.get("code") || "";
  if (!state || !code || url.searchParams.has("error")) return callbackResult("cancelled");
  const key = "vinz:youtube:oauth:" + state;
  const saved = await env.CATALOG_CACHE.get(key);
  if (!saved) return callbackResult("expired");
  await env.CATALOG_CACHE.delete(key);
  let pending;
  try { pending = JSON.parse(saved); } catch { return callbackResult("invalid_state"); }
  if (!pending.userId || !pending.authorization || !pending.codeVerifier) return callbackResult("invalid_state");
  try {
    const identityResponse = await fetch(config.url + "/auth/v1/user", {
      headers: { apikey: config.key, authorization: pending.authorization, accept: "application/json" },
      signal: AbortSignal.timeout(8000),
    });
    if (!identityResponse.ok) return callbackResult("session_expired");
    const identity = await identityResponse.json();
    if (identity.id !== pending.userId) return callbackResult("identity_mismatch");
    const token = await exchangeCode(code, pending.codeVerifier, env);
    const channelResponse = await youtubeRequest("channels?part=id,snippet&mine=true&maxResults=1", token.access_token);
    if (!channelResponse.ok) return callbackResult("youtube_scope_denied");
    const channelPayload = await channelResponse.json();
    const channel = channelPayload.items?.[0] || null;
    const previous = await getConnection(config, pending.authorization, pending.userId, true).catch(() => null);
    const refreshToken = token.refresh_token || (previous ? await decryptSecret(previous.encrypted_refresh_token, env).catch(() => null) : null);
    const record = {
      user_id: pending.userId,
      channel_id: channel?.id || null,
      channel_title: String(channel?.snippet?.title || "").slice(0, 200),
      encrypted_access_token: await encryptSecret(token.access_token, env),
      encrypted_refresh_token: refreshToken ? await encryptSecret(refreshToken, env) : null,
      access_token_expires_at: new Date(Date.now() + Number(token.expires_in || 3600) * 1000).toISOString(),
      scopes: String(token.scope || YOUTUBE_SCOPE).split(" "),
      connected_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    };
    await saveConnection(config, pending.authorization, record);
    return callbackResult("connected");
  } catch {
    return callbackResult("failed");
  }
}

export async function handleYouTubeRoute(request, url, env, config, auth) {
  if (!config) return json({ error: "backend_not_configured" }, 503);
  if (!configReady(env)) return json({ error: "youtube_import_not_configured" }, 503);
  if (url.pathname === "/v1/integrations/youtube/connect" && request.method === "GET") {
    const state = randomToken(32);
    const verifier = randomToken(48);
    const challenge = encodeBase64Url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
    const pending = JSON.stringify({ userId: auth.user.id, authorization: auth.authorization, codeVerifier: verifier });
    await env.CATALOG_CACHE.put("vinz:youtube:oauth:" + state, pending, { expirationTtl: 600 });
    const authorizationUrl = new URL("https://accounts.google.com/o/oauth2/v2/auth");
    authorizationUrl.search = new URLSearchParams({
      client_id: env.GOOGLE_CLIENT_ID,
      redirect_uri: CALLBACK_URL,
      response_type: "code",
      scope: YOUTUBE_SCOPE,
      access_type: "offline",
      include_granted_scopes: "true",
      prompt: "consent",
      state,
      code_challenge: challenge,
      code_challenge_method: "S256",
      ...(auth.user.email ? { login_hint: auth.user.email } : {}),
    }).toString();
    return json({ authorization_url: authorizationUrl.toString() });
  }
  if (url.pathname === "/v1/integrations/youtube/status" && request.method === "GET") {
    try {
      const connection = await getConnection(config, auth.authorization, auth.user.id);
      return json({
        connected: Boolean(connection),
        channel: connection ? { id: connection.channel_id, title: connection.channel_title } : null,
        scopes: connection?.scopes || [],
        connected_at: connection?.connected_at || null,
      });
    } catch { return json({ error: "youtube_status_unavailable" }, 502); }
  }
  if (url.pathname === "/v1/integrations/youtube/connection" && request.method === "DELETE") {
    const query = new URLSearchParams({ user_id: "eq." + auth.user.id });
    const response = await databaseRequest(config, auth.authorization, "vinz_youtube_connections?" + query, { method: "DELETE" });
    if (!response.ok) return json({ error: "youtube_disconnect_failed" }, 502);
    return json({ connected: false });
  }
  if (url.pathname === "/v1/integrations/youtube/import" && request.method === "POST") {
    try {
      const connection = await getConnection(config, auth.authorization, auth.user.id, true);
      if (!connection) return json({ error: "youtube_not_connected" }, 409);
      let accessToken = await decryptSecret(connection.encrypted_access_token, env);
      let refreshToken = await decryptSecret(connection.encrypted_refresh_token, env);
      let expiry = new Date(connection.access_token_expires_at).getTime();
      if (!Number.isFinite(expiry) || expiry < Date.now() + 60000) {
        if (!refreshToken) return json({ error: "youtube_reauthorization_required" }, 401);
        const refreshed = await refreshAccessToken(refreshToken, env);
        accessToken = refreshed.access_token;
        expiry = Date.now() + Number(refreshed.expires_in || 3600) * 1000;
        await saveConnection(config, auth.authorization, {
          ...connection,
          encrypted_access_token: await encryptSecret(accessToken, env),
          access_token_expires_at: new Date(expiry).toISOString(),
          updated_at: new Date().toISOString(),
        });
      }
      const playlistsResponse = await youtubeRequest("playlists?" + new URLSearchParams({
        part: "snippet,contentDetails",
        mine: "true",
        maxResults: "50",
      }), accessToken);
      if (!playlistsResponse.ok) return json({ error: "youtube_playlists_unavailable" }, 502);
      const playlistsPayload = await playlistsResponse.json();
      const playlists = (playlistsPayload.items || []).slice(0, 10);
      let records = [];
      for (const playlist of playlists) {
        records = records.concat(await listPlaylistItems(playlist, accessToken, auth.user.id));
      }
      let imported = 0;
      for (let offset = 0; offset < records.length; offset += 100) {
        const chunk = records.slice(offset, offset + 100);
        const query = new URLSearchParams({ on_conflict: "user_id,playlist_id,video_id" });
        const response = await databaseRequest(config, auth.authorization, "vinz_youtube_imports?" + query, {
          method: "POST",
          headers: { "content-type": "application/json", prefer: "resolution=merge-duplicates,return=minimal" },
          body: JSON.stringify(chunk),
        });
        if (!response.ok) throw new Error("youtube_import_save_failed");
        imported += chunk.length;
      }
      return json({
        imported_count: imported,
        playlist_count: playlists.length,
        playlists: playlists.map((playlist) => ({ id: playlist.id, title: playlist.snippet?.title || "YouTube playlist" })),
      });
    } catch (error) {
      return json({ error: error.message === "youtube_reauthorization_required" ? error.message : "youtube_import_failed" }, error.message === "youtube_reauthorization_required" ? 401 : 502);
    }
  }
  return json({ error: "not_found" }, 404);
}
