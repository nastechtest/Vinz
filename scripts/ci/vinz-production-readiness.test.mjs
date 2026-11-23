import test from "node:test";
import fs from "node:fs";
import assert from "node:assert/strict";
import {
  parseJsonc,
  parseOriginList,
  duplicateValues,
  isExpectedPrivateTableDenial,
  isLocalHost,
  scanDiff,
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

test("SQL account access contract asserts profile privacy and share-link column grants", () => {
  const sql = fs.readFileSync("supabase/tests/vinz_account_access_contract.sql", "utf8");
  assert.match(sql, /vinz_profiles must have RLS enabled/);
  assert.match(sql, /authenticated must have profile SELECT privilege/);
  assert.match(sql, /anon must not read share-link owner IDs/);
  assert.match(sql, /public share-link expiry policy is missing/);
});

test("SQL YouTube access contract requires owner-only RLS for provider tokens and imports", () => {
  const sql = fs.readFileSync("supabase/tests/vinz_youtube_access_contract.sql", "utf8");
  assert.match(sql, /anon must not read encrypted YouTube tokens/);
  assert.match(sql, /YouTube connection owner policy is missing/);
  assert.match(sql, /YouTube import owner policy is missing/);
});

test("localhost variants are detected without matching unrelated hosts", () => {
  for (const host of ["localhost", "api.localhost", "127.0.0.1", "0.0.0.0", "::1"]) assert.equal(isLocalHost(host), true);
  assert.equal(isLocalHost("home.vinzmusic.workers.dev"), false);
  assert.equal(isLocalHost("notlocalhost.com"), false);
});

test("large diffs are scanned through to the end without buffering", async () => {
  const script = `
    process.stdout.write('--- a/file\\n+++ b/file\\n');
    for (let i = 0; i < 22; i++) process.stdout.write(' x'.repeat(524288) + '\\n');
    process.stdout.write('+endpoint=https://api.example.test\\n-ALLOWED_ORIGINS=old\\n');
  `;
  const result = await scanDiff(process.execPath, ["-e", script]);
  assert.deepEqual(result.urlLines, ["+ [content redacted]"]);
  assert.deepEqual(result.riskyLines, ["- [content redacted]"]);
});

test("diff producer errors and unsuccessful exits fail closed", async () => {
  await assert.rejects(scanDiff(process.execPath, ["-e", "process.stdout.write('+https://example.test\\n'); process.exit(7)"]), /failed \(7\)/);
  await assert.rejects(scanDiff("/definitely/not/a/command", []));
});
