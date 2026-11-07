import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest } from "./vinz-api.js";

test("health endpoint reports missing backend configuration without exposing secrets", async () => {
  const response = await handleRequest(new Request("https://api.vinz.example/health"), {});
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    service: "vinz-api",
    status: "ok",
    configured: false,
  });
  assert.equal(response.headers.get("cache-control"), "no-store");
});

test("unknown routes return a structured 404", async () => {
  const response = await handleRequest(new Request("https://api.vinz.example/nope"), {});
  assert.equal(response.status, 404);
  assert.equal((await response.json()).error, "not_found");
});

test("profile endpoint fails closed until Supabase bindings exist", async () => {
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
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/me"),
    { SUPABASE_URL: "https://example.supabase.co", SUPABASE_PUBLISHABLE_KEY: "public-test-key" },
  );
  assert.equal(response.status, 401);
  assert.equal((await response.json()).error, "unauthorized");
});

test("browser origins are denied unless explicitly allowlisted", async () => {
  const response = await handleRequest(
    new Request("https://api.vinz.example/health", { headers: { origin: "https://evil.example" } }),
    { ALLOWED_ORIGINS: "https://app.vinz.example" },
  );
  assert.equal(response.status, 403);
  assert.equal((await response.json()).error, "origin_not_allowed");
});
