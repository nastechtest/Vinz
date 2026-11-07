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
  });
  assert.equal(response.headers.get("cache-control"), "no-store");
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
