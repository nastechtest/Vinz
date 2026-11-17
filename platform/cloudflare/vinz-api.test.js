import test from "node:test";
import assert from "node:assert/strict";
import { handleRequest } from "./vinz-api.js";

const SUPABASE_ENV = {
  SUPABASE_URL: "https://example.supabase.co",
  SUPABASE_PUBLISHABLE_KEY: "public-test-key",
};
const VERIFIED_USER = {
  id: "11111111-1111-4111-8111-111111111111",
  email_confirmed_at: "2026-11-01T00:00:00.000Z",
};


function mockBackupRecordFetch(t, record, options = {}) {
  const deleteStatus = options.deleteStatus ?? 204;
  t.mock.method(globalThis, "fetch", async (input, init = {}) => {
    const url = new URL(String(input));
    if (url.pathname.endsWith("/auth/v1/user")) {
      return new Response(JSON.stringify(VERIFIED_USER), {
        status: 200, headers: { "content-type": "application/json" },
      });
    }
    if (url.pathname.endsWith("/rest/v1/vinz_backups")) {
      if (init.method === "DELETE") {
        options.onDelete?.();
        return new Response(null, { status: deleteStatus });
      }
      return new Response(JSON.stringify(record ? [record] : []), {
        status: 200, headers: { "content-type": "application/json" },
      });
    }
    throw new Error("Unexpected upstream request: " + url.pathname);
  });
}

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
test("account API rejects unverified email before reading account data", async (t) => {
  let upstreamCalls = 0;
  t.mock.method(globalThis, "fetch", async (input) => {
    upstreamCalls++;
    assert.match(String(input), /\/auth\/v1\/user$/);
    return new Response(JSON.stringify({
      id: "11111111-1111-4111-8111-111111111111",
      email_confirmed_at: null,
      confirmed_at: null,
    }), { status: 200, headers: { "content-type": "application/json" } });
  });
  const response = await handleRequest(new Request("https://api.vinz.example/v1/me", {
    headers: { authorization: "Bearer test-token" },
  }), SUPABASE_ENV);
  assert.equal(response.status, 403);
  assert.equal((await response.json()).error, "email_not_confirmed");
  assert.equal(upstreamCalls, 1);
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
    return new Response(JSON.stringify(VERIFIED_USER), {
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
    JSON.stringify(VERIFIED_USER),
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
    JSON.stringify(VERIFIED_USER),
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
    JSON.stringify(VERIFIED_USER),
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
      return new Response(JSON.stringify(VERIFIED_USER), {
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
    JSON.stringify(VERIFIED_USER),
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
    JSON.stringify(VERIFIED_USER),
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
    JSON.stringify(VERIFIED_USER),
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

test("username availability rejects malformed names before rate limiting or RPC", async () => {
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/usernames/bad%20name/availability"),
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
      return new Response(JSON.stringify(VERIFIED_USER), {
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
        "x-vinz-request-id": "66666666-6666-4666-8666-666666666666",
        "x-vinz-backup-scope": "library",
      },
      body: bytes,
    }),
    { ...SUPABASE_ENV, BACKUPS_BUCKET: { put: async () => {}, delete: async () => {} } },
  );
  assert.equal(response.status, 201);
  assert.equal((await response.json()).backup.format_version, 1);
});

test("backup upload rejects a declared archive above 50 MiB before touching R2", async (t) => {
  let authCalls = 0;
  let objectWrites = 0;
  t.mock.method(globalThis, "fetch", async (input) => {
    authCalls++;
    assert.match(String(input), /\/auth\/v1\/user$/);
    return new Response(JSON.stringify(VERIFIED_USER), {
      status: 200, headers: { "content-type": "application/json" },
    });
  });
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/backups", {
      method: "PUT",
      headers: {
        authorization: "Bearer test-token",
        "content-length": String(50 * 1024 * 1024 + 1),
        "x-vinz-request-id": "99999999-9999-4999-8999-999999999999",
        "x-vinz-format-version": "2",
        "x-vinz-app-version": "1.8.4",
        "x-vinz-backup-scope": "full",
        "x-vinz-sha256": "a".repeat(64),
      },
      body: new Uint8Array([1, 2, 3]),
    }),
    {
      ...SUPABASE_ENV,
      BACKUPS_BUCKET: { put: async () => { objectWrites++; } },
    },
  );
  assert.equal(response.status, 413);
  const body = await response.json();
  assert.equal(body.error, "backup_too_large");
  assert.equal(body.max_bytes, 50 * 1024 * 1024);
  assert.match(body.request_id, /^[0-9a-f-]{36}$/i);
  assert.equal(authCalls, 1);
  assert.equal(objectWrites, 0);
});

test("backup upload is idempotent for the same owner, request ID and archive", async (t) => {
  const bytes = new Uint8Array([4, 5, 6, 7]);
  const digest = [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))]
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
  const backupId = "77777777-7777-4777-8777-777777777777";
  const userId = VERIFIED_USER.id;
  let record = null;
  let writes = 0;
  let metadataInserts = 0;
  const objects = new Map();
  t.mock.method(globalThis, "fetch", async (input, init = {}) => {
    const url = new URL(String(input));
    if (url.pathname.endsWith("/auth/v1/user")) {
      return new Response(JSON.stringify(VERIFIED_USER), {
        status: 200, headers: { "content-type": "application/json" },
      });
    }
    if (url.pathname.endsWith("/rest/v1/vinz_backups") && (init.method || "GET") === "GET") {
      const requestedId = (url.searchParams.get("id") || "").replace(/^eq\./, "");
      return new Response(JSON.stringify(record?.id === requestedId ? [record] : []), {
        status: 200, headers: { "content-type": "application/json" },
      });
    }
    if (url.pathname.endsWith("/rest/v1/vinz_backups") && init.method === "POST") {
      metadataInserts++;
      const inputRecord = JSON.parse(init.body);
      record = { ...inputRecord, created_at: "2026-11-14T14:00:00Z" };
      return new Response(JSON.stringify([record]), {
        status: 201, headers: { "content-type": "application/json" },
      });
    }
    throw new Error("Unexpected upstream request: " + url.pathname);
  });
  const bucket = {
    put: async (path, value, options) => {
      writes++;
      objects.set(path, {
        size: value.byteLength,
        customMetadata: options.customMetadata,
      });
    },
    head: async (path) => objects.get(path) || null,
    delete: async (path) => { objects.delete(path); },
  };
  const upload = () => handleRequest(new Request("https://api.vinz.example/v1/backups", {
    method: "PUT",
    headers: {
      authorization: "Bearer test-token",
      "x-vinz-request-id": backupId,
      "x-vinz-format-version": "2",
      "x-vinz-app-version": "1.8.4",
      "x-vinz-backup-scope": "full",
      "x-vinz-sha256": digest,
    },
    body: bytes,
  }), { ...SUPABASE_ENV, BACKUPS_BUCKET: bucket });

  const first = await upload();
  const replay = await upload();
  assert.equal(first.status, 201);
  assert.equal(replay.status, 200);
  assert.equal((await first.json()).backup.id, backupId);
  const replayBody = await replay.json();
  assert.equal(replayBody.backup.id, backupId);
  assert.equal(replayBody.idempotent_replay, true);
  assert.equal(writes, 1);
  assert.equal(metadataInserts, 1);
  assert.equal(record.user_id, userId);
});

test("backup upload rejects an idempotency key reused for different bytes", async (t) => {
  const original = new Uint8Array([1, 2, 3]);
  const digest = [...new Uint8Array(await crypto.subtle.digest("SHA-256", original))]
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
  const backupId = "88888888-8888-4888-8888-888888888888";
  let record = null;
  let writes = 0;
  const objects = new Map();
  t.mock.method(globalThis, "fetch", async (input, init = {}) => {
    const url = new URL(String(input));
    if (url.pathname.endsWith("/auth/v1/user")) {
      return new Response(JSON.stringify(VERIFIED_USER), {
        status: 200, headers: { "content-type": "application/json" },
      });
    }
    if (url.pathname.endsWith("/rest/v1/vinz_backups") && (init.method || "GET") === "GET") {
      return new Response(JSON.stringify(record ? [record] : []), {
        status: 200, headers: { "content-type": "application/json" },
      });
    }
    if (url.pathname.endsWith("/rest/v1/vinz_backups") && init.method === "POST") {
      record = { ...JSON.parse(init.body), created_at: "2026-11-14T14:00:00Z" };
      return new Response(JSON.stringify([record]), { status: 201 });
    }
    throw new Error("Unexpected upstream request: " + url.pathname);
  });
  const bucket = {
    put: async (path, value, options) => {
      writes++;
      objects.set(path, { size: value.byteLength, customMetadata: options.customMetadata });
    },
    head: async (path) => objects.get(path) || null,
    delete: async (path) => { objects.delete(path); },
  };
  const upload = (body, hash) => handleRequest(new Request("https://api.vinz.example/v1/backups", {
    method: "PUT",
    headers: {
      authorization: "Bearer test-token",
      "x-vinz-request-id": backupId,
      "x-vinz-format-version": "2",
      "x-vinz-app-version": "1.8.4",
      "x-vinz-backup-scope": "full",
      "x-vinz-sha256": hash,
    },
    body,
  }), { ...SUPABASE_ENV, BACKUPS_BUCKET: bucket });

  const first = await upload(original, digest);
  const changed = new Uint8Array([9, 9, 9]);
  const changedHash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", changed))]
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
  const conflict = await upload(changed, changedHash);
  assert.equal(first.status, 201);
  assert.equal(conflict.status, 409);
  assert.equal((await conflict.json()).error, "backup_request_conflict");
  assert.equal(writes, 1);
});


test("backup download returns only a size- and checksum-verified owner archive", async (t) => {
  const bytes = new Uint8Array([1, 3, 5, 7]);
  const sha256 = [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))]
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
  const backupId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const objectPath = VERIFIED_USER.id + "/" + backupId + "-" + sha256 + "-2.backup";
  mockBackupRecordFetch(t, {
    id: backupId, object_path: objectPath, byte_size: bytes.byteLength, sha256,
    format_version: 2, app_version: "1.8.4",
  });
  let reads = 0;
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/backups/" + backupId, {
      headers: { authorization: "Bearer test-token" },
    }),
    { ...SUPABASE_ENV, BACKUPS_BUCKET: { get: async (path) => {
      assert.equal(path, objectPath);
      return { size: bytes.byteLength, arrayBuffer: async () => { reads++; return bytes.buffer; } };
    } } },
  );
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("content-length"), String(bytes.byteLength));
  assert.equal(response.headers.get("x-vinz-sha256"), sha256);
  assert.deepEqual([...new Uint8Array(await response.arrayBuffer())], [...bytes]);
  assert.equal(reads, 1);
});

test("backup download does not read an object outside the authenticated owner's prefix", async (t) => {
  const backupId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  mockBackupRecordFetch(t, {
    id: backupId, object_path: "22222222-2222-4222-8222-222222222222/" + backupId + ".backup",
    byte_size: 3, sha256: "a".repeat(64), format_version: 1, app_version: "1.8.4",
  });
  let reads = 0;
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/backups/" + backupId, {
      headers: { authorization: "Bearer test-token" },
    }),
    { ...SUPABASE_ENV, BACKUPS_BUCKET: { get: async () => { reads++; return null; } } },
  );
  assert.equal(response.status, 404);
  assert.equal((await response.json()).error, "backup_not_found");
  assert.equal(reads, 0);
});

test("backup download rejects oversized metadata before reading the R2 object", async (t) => {
  const backupId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
  const size = 50 * 1024 * 1024 + 1;
  mockBackupRecordFetch(t, {
    id: backupId, object_path: VERIFIED_USER.id + "/" + backupId + ".backup",
    byte_size: size, sha256: "b".repeat(64), format_version: 1, app_version: "1.8.4",
  });
  let reads = 0;
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/backups/" + backupId, {
      headers: { authorization: "Bearer test-token" },
    }),
    { ...SUPABASE_ENV, BACKUPS_BUCKET: { get: async () => ({
      size, arrayBuffer: async () => { reads++; throw new Error("must not read oversized object"); },
    }) } },
  );
  assert.equal(response.status, 422);
  assert.equal((await response.json()).error, "backup_integrity_check_failed");
  assert.equal(reads, 0);
});

test("backup download rejects an R2 object whose size differs from metadata before reading", async (t) => {
  const backupId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
  mockBackupRecordFetch(t, {
    id: backupId, object_path: VERIFIED_USER.id + "/" + backupId + ".backup",
    byte_size: 3, sha256: "c".repeat(64), format_version: 1, app_version: "1.8.4",
  });
  let reads = 0;
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/backups/" + backupId, {
      headers: { authorization: "Bearer test-token" },
    }),
    { ...SUPABASE_ENV, BACKUPS_BUCKET: { get: async () => ({
      size: 4, arrayBuffer: async () => { reads++; throw new Error("must not read mismatched object"); },
    }) } },
  );
  assert.equal(response.status, 422);
  assert.equal((await response.json()).error, "backup_integrity_check_failed");
  assert.equal(reads, 0);
});

test("backup download rejects a checksum mismatch after size validation", async (t) => {
  const bytes = new Uint8Array([2, 4, 6]);
  const wrongHash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", new Uint8Array([9, 9, 9])))]
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
  const backupId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
  mockBackupRecordFetch(t, {
    id: backupId, object_path: VERIFIED_USER.id + "/" + backupId + ".backup",
    byte_size: bytes.byteLength, sha256: wrongHash, format_version: 1, app_version: "1.8.4",
  });
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/backups/" + backupId, {
      headers: { authorization: "Bearer test-token" },
    }),
    { ...SUPABASE_ENV, BACKUPS_BUCKET: { get: async () => ({
      size: bytes.byteLength, arrayBuffer: async () => bytes.buffer,
    }) } },
  );
  assert.equal(response.status, 422);
  assert.equal((await response.json()).error, "backup_integrity_check_failed");
});

test("backup delete removes metadata before cleaning the owner's R2 object", async (t) => {
  const backupId = "ffffffff-ffff-4fff-8fff-ffffffffffff";
  const objectPath = VERIFIED_USER.id + "/" + backupId + ".backup";
  const events = [];
  mockBackupRecordFetch(t, { id: backupId, object_path: objectPath }, {
    onDelete: () => events.push("metadata"),
  });
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/backups/" + backupId, {
      method: "DELETE", headers: { authorization: "Bearer test-token" },
    }),
    { ...SUPABASE_ENV, BACKUPS_BUCKET: { delete: async (path) => {
      assert.equal(path, objectPath);
      events.push("object");
    } } },
  );
  assert.equal(response.status, 200);
  assert.equal((await response.json()).deleted, true);
  assert.deepEqual(events, ["metadata", "object"]);
});

test("backup delete preserves the R2 object when metadata deletion fails", async (t) => {
  const backupId = "99999999-9999-4999-8999-999999999999";
  const objectPath = VERIFIED_USER.id + "/" + backupId + ".backup";
  mockBackupRecordFetch(t, { id: backupId, object_path: objectPath }, { deleteStatus: 500 });
  let deletes = 0;
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/backups/" + backupId, {
      method: "DELETE", headers: { authorization: "Bearer test-token" },
    }),
    { ...SUPABASE_ENV, BACKUPS_BUCKET: { delete: async () => { deletes++; } } },
  );
  assert.equal(response.status, 502);
  assert.equal((await response.json()).error, "backup_delete_failed");
  assert.equal(deletes, 0);
});

test("share creation rejects arbitrary external redirect hosts", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response(
    JSON.stringify(VERIFIED_USER),
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
    JSON.stringify(VERIFIED_USER),
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
      return new Response(JSON.stringify(VERIFIED_USER), {
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
    JSON.stringify(VERIFIED_USER),
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
    JSON.stringify(VERIFIED_USER),
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
    JSON.stringify(VERIFIED_USER),
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
    JSON.stringify(VERIFIED_USER),
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


test("signup username availability uses an anonymous boolean RPC behind a hashed-IP rate limit", async (t) => {
  let rateKey = "";
  t.mock.method(globalThis, "fetch", async (input, init) => {
    assert.match(String(input), /\/rest\/v1\/rpc\/vinz_username_available_for_signup$/);
    const headers = new Headers(init?.headers);
    assert.equal(headers.get("authorization"), `Bearer ${SUPABASE_ENV.SUPABASE_PUBLISHABLE_KEY}`);
    assert.equal(headers.get("apikey"), SUPABASE_ENV.SUPABASE_PUBLISHABLE_KEY);
    return new Response("true", { status: 200, headers: { "content-type": "application/json" } });
  });
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/usernames/Artist_Name/availability", {
      headers: { "cf-connecting-ip": "203.0.113.10" },
    }),
    {
      ...SUPABASE_ENV,
      USERNAME_CHECK_LIMITER: {
        limit: async ({ key }) => { rateKey = key; return { success: true }; },
      },
    },
  );
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { username: "artist_name", available: true });
  assert.match(rateKey, /^username-check:[a-f0-9]{32}$/);
  assert.equal(rateKey.includes("203.0.113.10"), false);
});

test("username availability stops before Supabase when the edge rate limit is exceeded", async (t) => {
  let upstreamCalls = 0;
  t.mock.method(globalThis, "fetch", async () => {
    upstreamCalls++;
    return new Response("true", { status: 200 });
  });
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/usernames/artist_7/availability", {
      headers: { "cf-connecting-ip": "203.0.113.11" },
    }),
    { ...SUPABASE_ENV, USERNAME_CHECK_LIMITER: { limit: async () => ({ success: false }) } },
  );
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("retry-after"), "60");
  assert.equal(upstreamCalls, 0);
});

test("username availability fails closed when its rate-limit binding is absent", async () => {
  const response = await handleRequest(
    new Request("https://api.vinz.example/v1/usernames/artist_7/availability"),
    SUPABASE_ENV,
  );
  assert.equal(response.status, 503);
  assert.equal((await response.json()).error, "username_check_unavailable");
});

test("profile updates canonicalize username before the database write", async (t) => {
  t.mock.method(globalThis, "fetch", async (input, init) => {
    if (String(input).endsWith("/auth/v1/user")) return new Response(JSON.stringify(VERIFIED_USER), { status: 200 });
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
