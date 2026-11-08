import test from "node:test";
import assert from "node:assert/strict";
import { handleYouTubeCallback, handleYouTubeRoute } from "./youtube-import.js";

const CONFIG = { url: "https://supabase.example", key: "public-key" };
const AUTH = {
  user: { id: "11111111-1111-4111-8111-111111111111", email: "listener@example.com" },
  authorization: "Bearer vinz-user-jwt",
};
const ENV = {
  GOOGLE_CLIENT_ID: "google-client-id.apps.googleusercontent.com",
  GOOGLE_CLIENT_SECRET: "test-secret",
  YOUTUBE_TOKEN_ENCRYPTION_KEY: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  CATALOG_CACHE: {
    values: new Map(),
    async put(key, value, options) { this.values.set(key, { value, options }); },
    async get(key) { return this.values.get(key)?.value ?? null; },
    async delete(key) { this.values.delete(key); },
  },
};

test("YouTube connect creates a short-lived PKCE state and uses the Vinz email as login hint", async () => {
  const response = await handleYouTubeRoute(
    new Request("https://api.vinzmusic.workers.dev/v1/integrations/youtube/connect"),
    new URL("https://api.vinzmusic.workers.dev/v1/integrations/youtube/connect"),
    structuredClone(ENV),
    CONFIG,
    AUTH,
  );
  assert.equal(response.status, 200);
  const body = await response.json();
  const authorization = new URL(body.authorization_url);
  assert.equal(authorization.origin, "https://accounts.google.com");
  assert.equal(authorization.searchParams.get("login_hint"), "listener@example.com");
  assert.equal(authorization.searchParams.get("scope"), "https://www.googleapis.com/auth/youtube.readonly");
  assert.equal(authorization.searchParams.get("redirect_uri"), "https://api.vinzmusic.workers.dev/v1/integrations/youtube/callback");
  assert.equal(authorization.searchParams.get("code_challenge_method"), "S256");
  assert.equal(authorization.searchParams.get("access_type"), "offline");
});

test("YouTube connect fails closed when Google OAuth secrets are absent", async () => {
  const response = await handleYouTubeRoute(
    new Request("https://api.vinzmusic.workers.dev/v1/integrations/youtube/connect"),
    new URL("https://api.vinzmusic.workers.dev/v1/integrations/youtube/connect"),
    { CATALOG_CACHE: ENV.CATALOG_CACHE },
    CONFIG,
    AUTH,
  );
  assert.equal(response.status, 503);
  assert.equal((await response.json()).error, "youtube_import_not_configured");
});

test("YouTube callback rejects expired or unknown OAuth state", async () => {
  const response = await handleYouTubeCallback(
    new Request("https://api.vinzmusic.workers.dev/v1/integrations/youtube/callback?code=code&state=unknown"),
    new URL("https://api.vinzmusic.workers.dev/v1/integrations/youtube/callback?code=code&state=unknown"),
    ENV,
    CONFIG,
  );
  assert.equal(response.status, 302);
  assert.match(response.headers.get("location"), /youtube=expired/);
});

test("YouTube status exposes connection metadata but not encrypted credentials", async (t) => {
  t.mock.method(globalThis, "fetch", async (input) => {
    assert.match(String(input), /vinz_youtube_connections/);
    return new Response(JSON.stringify([{
      user_id: AUTH.user.id,
      channel_id: "UC123",
      channel_title: "Vinz Listener",
      scopes: ["https://www.googleapis.com/auth/youtube.readonly"],
      connected_at: "2026-11-08T00:00:00.000Z",
    }]), { status: 200, headers: { "content-type": "application/json" } });
  });
  const response = await handleYouTubeRoute(
    new Request("https://api.vinzmusic.workers.dev/v1/integrations/youtube/status"),
    new URL("https://api.vinzmusic.workers.dev/v1/integrations/youtube/status"),
    ENV,
    CONFIG,
    AUTH,
  );
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.connected, true);
  assert.equal(body.channel.title, "Vinz Listener");
  assert.equal("encrypted_access_token" in body, false);
});

test("YouTube import refuses to run without an existing connection", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response("[]", {
    status: 200,
    headers: { "content-type": "application/json" },
  }));
  const response = await handleYouTubeRoute(
    new Request("https://api.vinzmusic.workers.dev/v1/integrations/youtube/import", { method: "POST" }),
    new URL("https://api.vinzmusic.workers.dev/v1/integrations/youtube/import"),
    ENV,
    CONFIG,
    AUTH,
  );
  assert.equal(response.status, 409);
  assert.equal((await response.json()).error, "youtube_not_connected");
});
