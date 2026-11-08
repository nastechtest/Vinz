import test from "node:test";
import assert from "node:assert/strict";
import {
  parseJsonc,
  parseOriginList,
  duplicateValues,
  isExpectedPrivateTableDenial,
  isLocalHost,
} from "./vinz-readiness-lib.mjs";

test("JSONC parser strips comments and trailing commas", () => {
  assert.deepEqual(JSON.parse(parseJsonc('{\n // comment\n "vars": {"A": true,},\n}')), { vars: { A: true } });
});

test("CORS origins are parsed as exact HTTPS origins", () => {
  assert.deepEqual(parseOriginList("https://home.vinzmusic.workers.dev"), ["https://home.vinzmusic.workers.dev"]);
  assert.notDeepEqual(parseOriginList("https://home.vinzmusic.workers.dev.evil.test"), ["https://home.vinzmusic.workers.dev"]);
  assert.throws(() => parseOriginList("http://home.vinzmusic.workers.dev"));
  assert.throws(() => parseOriginList("https://home.vinzmusic.workers.dev/path"));
});

test("duplicate origin detection catches repeated entries", () => {
  assert.deepEqual(duplicateValues(["https://a.test", "https://b.test", "https://a.test"]), ["https://a.test"]);
  assert.deepEqual(duplicateValues(["https://a.test"]), []);
});

test("private profile anonymous denial is recognized only for Postgres privilege error", () => {
  assert.equal(isExpectedPrivateTableDenial(401, { code: "42501", message: "permission denied for table vinz_profiles" }), true);
  assert.equal(isExpectedPrivateTableDenial(401, { code: "PGRST301", message: "Invalid JWT" }), false);
  assert.equal(isExpectedPrivateTableDenial(200, { code: "42501", message: "permission denied for table vinz_profiles" }), false);
});

test("localhost variants are detected without matching unrelated hosts", () => {
  for (const host of ["localhost", "api.localhost", "127.0.0.1", "0.0.0.0", "::1"]) assert.equal(isLocalHost(host), true);
  assert.equal(isLocalHost("home.vinzmusic.workers.dev"), false);
  assert.equal(isLocalHost("notlocalhost.com"), false);
});
