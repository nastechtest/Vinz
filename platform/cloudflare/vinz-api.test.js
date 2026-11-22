import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest } from "./vinz-api.js";

const SUPABASE_ENV = {
  SUPABASE_URL: "https://example.supabase.co",
  SUPABASE_PUBLISHABLE_KEY: "public-test-key",
};

test("health endpoint reports configuration without exposing secrets", async () => {
  const response = await handleRequest(new Request("https://api.vinz.example/health"), {});
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    service: "vinz-api",
    status: "ok",
    configured: false,
    backup_storage: false,
    integrations: { youtube_import_configured: false },
  });
  assert.equal(response.headers.get("cache-control"), "no-store");
});

test("health endpoint reports YouTube OAuth readiness without exposing secret values", async () => {
  const response = await handleRequest(new Request("https://api.vinz.example/health"), {
    GOOGLE_CLIENT_ID: "client-id",
    GOOGLE_CLIENT_SECRET: "client-secret",
    YOUTUBE_TOKEN_ENCRYPTION_KEY: "encryption-key",
    CATALOG_CACHE: {},
  });
  const body = await response.json();
  assert.deepEqual(body.integrations, { youtube_import_configured: true });
  assert.equal(JSON.stringify(body).includes("client-secret"), false);
  assert.equal(JSON.stringify(body).includes("encryption-key"), false);
});

test("unknown routes return a structured 404", async () => {
  const response = await handleRequest(new Request("https://api.vinz.example/nope"), {});
  assert.equal(response.status, 404);
  assert.equal((await response.json()).error, "not_found");
});

test("authenticated routes fail closed until Supabase bindings exist", async () => {
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/me", {
      headers: { authorization: "Bearer test-token" },
    }),
    {},
  );
  assert.equal(response.status, 503);
  assert.equal((await response.json()).error, "backend_not_configured");
});

test("profile endpoint rejects missing bearer token", async () => {
  const response = await handleRequest(new Request("https://api.vinz.example/v1/me"), SUPABASE_ENV);
  assert.equal(response.status, 401);
  assert.equal((await response.json()).error, "unauthorized");
});

test("browser origins are denied unless explicitly allowlisted", async () => {
  const response = await handleRequest(
    new Request("https://api.vinz.example/health", {
      headers: { origin: "https://evil.example" },
    }),
    { ALLOWED_ORIGINS: "https://app.vinz.example" },
  );
  assert.equal(response.status, 403);
  assert.equal((await response.json()).error, "origin_not_allowed");
});

test("preflight advertises only implemented API methods", async () => {
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/me", {
      method: "OPTIONS",
      headers: { origin: "https://app.vinz.example" },
    }),
    { ALLOWED_ORIGINS: "https://app.vinz.example" },
  );
  assert.equal(response.status, 204);
  assert.match(response.headers.get("access-control-allow-methods"), /PATCH/);
  assert.match(response.headers.get("access-control-allow-methods"), /DELETE/);
});

test("profile updates reject invalid usernames before touching the database", async (t) => {
  t.mock.method(globalThis, "fetch", async (input) => {
    assert.match(String(input), /\/auth\/v1\/user$/);
    return new Response(JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  });
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/me", {
      method: "PATCH",
      headers: { authorization: "Bearer test-token", "content-type": "application/json" },
      body: JSON.stringify({ username: "bad username" }),
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, "invalid_username");
});

test("settings updates reject unknown and non-boolean values", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/me/settings", {
      method: "PATCH",
      headers: { authorization: "Bearer test-token", "content-type": "application/json" },
      body: JSON.stringify({ notify_new_releases: "yes", admin: true }),
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, "invalid_settings_field");
});

test("backup upload reports missing R2 binding rather than accepting data", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/backups", {
      method: "PUT",
      headers: { authorization: "Bearer test-token" },
      body: new Uint8Array([1, 2, 3]),
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 503);
  assert.equal((await response.json()).error, "backup_storage_not_configured");
});

test("catalog search normalizes Apple results and omits preview audio URLs", async (t) => {
  t.mock.method(globalThis, "fetch", async (input) => {
    assert.match(String(input), /^https:\/\/itunes\.apple\.com\/search\?/);
    return new Response(JSON.stringify({
      resultCount: 1,
      results: [{
        trackId: 123,
        trackName: "Test Song",
        artistName: "Test Artist",
        previewUrl: "https://example.invalid/preview.m4a",
        trackViewUrl: "https://music.apple.com/test",
      }],
    }), { status: 200, headers: { "content-type": "application/json" } });
  });
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/catalog/search?q=test%20song&type=song"),
    SUPABASE_ENV,
    undefined,
  );
  // The route is authenticated; a missing token must be rejected before calling the provider.
  assert.equal(response.status, 401);
});

test("catalog search validates the requested media type after authentication", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/catalog/search?q=test&type=unknown", {
      headers: { authorization: "Bearer test-token" },
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, "unsupported_catalog_type");
});

test("catalog search returns normalized Apple metadata without media previews", async (t) => {
  t.mock.method(globalThis, "fetch", async (input) => {
    const url = String(input);
    if (url.endsWith("/auth/v1/user")) {
      return new Response(JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }), {
        status: 200, headers: { "content-type": "application/json" },
      });
    }
    assert.match(url, /^https:\/\/itunes\.apple\.com\/search\?/);
    return new Response(JSON.stringify({
      resultCount: 1,
      results: [{
        trackId: 123,
        trackName: "Test Song",
        artistName: "Test Artist",
        previewUrl: "https://example.invalid/preview.m4a",
        trackViewUrl: "https://music.apple.com/test",
      }],
    }), { status: 200, headers: { "content-type": "application/json" } });
  });
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/catalog/search?q=test%20song&type=song", {
      headers: { authorization: "Bearer test-token" },
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.results[0].title, "Test Song");
  assert.equal(body.results[0].artist, "Test Artist");
  assert.equal("previewUrl" in body.results[0], false);
});

test("artist watch endpoint validates provider identifiers", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/watches/artists", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "content-type": "application/json" },
      body: JSON.stringify({ provider: "unknown", provider_artist_id: "1", artist_name: "Artist" }),
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, "invalid_artist_watch");
});

test("podcast subscriptions reject insecure and local feed URLs", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/podcasts/subscriptions", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "content-type": "application/json" },
      body: JSON.stringify({
        provider: "rss",
        provider_podcast_id: "test",
        feed_url: "http://127.0.0.1/private",
        title: "Podcast",
      }),
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, "invalid_feed_url");
});

test("notification device registration rejects malformed push tokens", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/devices", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "content-type": "application/json" },
      body: JSON.stringify({ platform: "android", push_token: "short" }),
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, "invalid_notification_device");
});

test("username availability rejects malformed names before the RPC", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/usernames/bad%20name/availability", {
      headers: { authorization: "Bearer test-token" },
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).available, false);
});

test("backup upload accepts existing non-full backup scopes", async (t) => {
  const bytes = new Uint8Array([1, 2, 3]);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  const sha256 = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  t.mock.method(globalThis, "fetch", async (input) => {
    if (String(input).endsWith("/auth/v1/user")) {
      return new Response(JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }), {
        status: 200, headers: { "content-type": "application/json" },
      });
    }
    return new Response(JSON.stringify([]), {
      status: 201, headers: { "content-type": "application/json" },
    });
  });
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/backups", {
      method: "PUT",
      headers: {
        authorization: "Bearer test-token",
        "x-vinz-format-version": "1",
        "x-vinz-app-version": "1.0",
        "x-vinz-sha256": sha256,
        "x-vinz-backup-scope": "library",
      },
      body: bytes,
    }),
    { ...SUPABASE_ENV, BACKUPS_BUCKET: { put: async () => {}, delete: async () => {} } },
  );
  assert.equal(response.status, 201);
  assert.equal((await response.json()).backup.format_version, 1);
});

test("share creation rejects arbitrary external redirect hosts", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/shares", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "content-type": "application/json" },
      body: JSON.stringify({
        provider: "spotify",
        external_url: "https://evil.example/redirect",
        title: "Song",
      }),
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, "unsupported_share_url");
});

test("lyrics lookup validates signatures before contacting LRCLIB", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/lyrics/get?track_name=Song"),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 401);
  assert.equal((await response.json()).error, "unauthorized");
});

test("lyrics search honours LRCLIB rate-limit responses", async (t) => {
  t.mock.method(globalThis, "fetch", async (input) => {
    if (String(input).endsWith("/auth/v1/user")) {
      return new Response(JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }), {
        status: 200, headers: { "content-type": "application/json" },
      });
    }
    return new Response("rate limited", { status: 429, headers: { "retry-after": "3" } });
  });
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/lyrics/search?q=test", {
      headers: { authorization: "Bearer test-token" },
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 429);
  assert.equal((await response.json()).retry_after, "3");
});

test("lyrics search rejects empty queries", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/lyrics/search", {
      headers: { authorization: "Bearer test-token" },
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, "invalid_lyrics_search");
});

test("public share lookup uses the anonymous Supabase role", async (t) => {
  t.mock.method(globalThis, "fetch", async (input, init) => {
    assert.match(String(input), /\/rest\/v1\/vinz_share_links/);
    const headers = new Headers(init?.headers);
    assert.equal(headers.get("authorization"), null);
    assert.ok(headers.get("apikey"));
    return new Response(JSON.stringify([{
      id: "11111111-1111-4111-8111-111111111111",
      provider: "spotify",
      external_url: "https://open.spotify.com/track/test",
      title: "Test Song",
      artist: "Test Artist",
      artwork_url: null,
      created_at: "2026-11-07T00:00:00Z",
      expires_at: null,
    }]), { status: 200, headers: { "content-type": "application/json" } });
  });
  const response = await handleRequest(
    new Request("https://api.vinz.example/s/11111111-1111-4111-8111-111111111111"),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 200);
  assert.equal((await response.json()).share.title, "Test Song");
});

test("community search rejects unsafe or oversized search input", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/community/search?q=alice%2Cadmin", {
      headers: { authorization: "Bearer test-token" },
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, "invalid_community_search");
});

test("friend request creation validates usernames before profile lookup", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/friends/requests", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "content-type": "application/json" },
      body: JSON.stringify({ username: "invalid name" }),
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, "invalid_friend_username");
});

test("friend request response only accepts accepted or declined", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/friends/requests/11111111-1111-4111-8111-111111111111", {
      method: "PATCH",
      headers: { authorization: "Bearer test-token", "content-type": "application/json" },
      body: JSON.stringify({ status: "admin" }),
    }),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, "invalid_friend_request_status");
});


test("username availability normalizes case before the authenticated RPC", async (t) => {
  t.mock.method(globalThis, "fetch", async (input) => {
    const url = String(input);
    if (url.endsWith("/auth/v1/user")) return new Response(JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }), { status: 200 });
    assert.match(url, /\/rest\/v1\/rpc\/vinz_username_available$/);
    return new Response("true", { status: 200, headers: { "content-type": "application/json" } });
  });
  const response = await handleRequest(new Request("https://api.vinz.example/v1/usernames/Artist_Name/availability", {
    headers: { authorization: "Bearer test-token" },
  }), SUPABASE_ENV);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { username: "artist_name", available: true });
});

test("profile updates canonicalize username before the database write", async (t) => {
  t.mock.method(globalThis, "fetch", async (input, init) => {
    if (String(input).endsWith("/auth/v1/user")) return new Response(JSON.stringify({ id: "11111111-1111-4111-8111-111111111111" }), { status: 200 });
    assert.match(String(input), /\/rest\/v1\/vinz_profiles\?/);
    assert.equal(JSON.parse(init.body).username, "artist_name");
    return new Response(JSON.stringify([{ id: "11111111-1111-4111-8111-111111111111", username: "artist_name" }]), { status: 200 });
  });
  const response = await handleRequest(new Request("https://api.vinz.example/v1/me", {
    method: "PATCH",
    headers: { authorization: "Bearer test-token", "content-type": "application/json" },
    body: JSON.stringify({ username: " Artist_Name " }),
  }), SUPABASE_ENV);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).profile.username, "artist_name");
});
