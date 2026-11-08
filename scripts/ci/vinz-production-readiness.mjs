import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const HOME_URL = process.env.VINZ_HOME_URL || "https://home.vinzmusic.workers.dev";
const API_URL = process.env.VINZ_API_URL || "https://api.vinzmusic.workers.dev";
const SUPABASE_URL = process.env.SUPABASE_URL || "https://iszghebmetrbkfhnnoqq.supabase.co";
const SUPABASE_ANON_KEY = process.env.SUPABASE_ANON_KEY || (() => {
  try { return JSON.parse(parseJsonc(fs.readFileSync("platform/cloudflare/wrangler.production.jsonc", "utf8"))).vars?.SUPABASE_PUBLISHABLE_KEY || ""; }
  catch { return ""; }
})();
const timeoutMs = Number(process.env.READINESS_TIMEOUT_MS || 15000);
const findings = [];
const checks = [];
const add = (name, status, detail, evidence = "") => checks.push({ name, status, detail, evidence });
const fail = (name, detail, evidence = "") => { findings.push({ name, detail, evidence }); add(name, "FAIL", detail, evidence); };

function parseJsonc(source) {
  return source.replace(/^\s*\/\/.*$/gm, "").replace(/,\s*([}\]])/g, "$1");
}
function readIfExists(file) {
  try { return fs.readFileSync(file, "utf8"); } catch { return ""; }
}
function safeUrl(value, label) {
  try {
    const url = new URL(value);
    if (url.protocol !== "https:") fail(label, "Must use HTTPS", value);
    if (url.username || url.password || url.search || url.hash) fail(label, "URL must not contain credentials, query strings or fragments", value);
    return url;
  } catch {
    fail(label, "Missing or invalid URL", String(value));
    return null;
  }
}
async function request(name, url, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { ...options, redirect: "follow", signal: controller.signal });
    const finalUrl = new URL(response.url);
    const requested = new URL(url);
    if (finalUrl.host !== requested.host) fail(name, "Unexpected cross-host redirect", response.url);
    const body = await response.text();
    return { response, body };
  } catch (error) {
    fail(name, error.name === "AbortError" ? `Timed out after ${timeoutMs}ms` : "Connection failed", error.cause?.code || error.name || "network_error");
    return null;
  } finally { clearTimeout(timer); }
}

const expected = {
  home: safeUrl(HOME_URL, "VINZ_HOME_URL"),
  api: safeUrl(API_URL, "VINZ_API_URL"),
  supabase: safeUrl(SUPABASE_URL, "SUPABASE_URL"),
};
if (expected.home && expected.home.hostname !== "home.vinzmusic.workers.dev") fail("VINZ_HOME_URL", "Production home host differs from approved Cloudflare hostname", HOME_URL);
if (expected.api && expected.api.hostname !== "api.vinzmusic.workers.dev") fail("VINZ_API_URL", "Production API host differs from approved Cloudflare hostname", API_URL);
if (expected.supabase && expected.supabase.hostname !== "iszghebmetrbkfhnnoqq.supabase.co") fail("SUPABASE_URL", "Supabase project host differs from approved project", SUPABASE_URL);

if (expected.home) {
  const result = await request("Vinz home live", HOME_URL);
  if (result) {
    const { response, body } = result;
    if (!response.ok) fail("Vinz home live", `HTTP ${response.status}`, response.url);
    else if (!/text\/html/i.test(response.headers.get("content-type") || "") || !/<html[\s>]/i.test(body)) fail("Vinz home live", "Expected an HTML document", response.headers.get("content-type") || "missing content-type");
    else add("Vinz home live", "PASS", `HTTP ${response.status}; HTML served`, response.url);
  }
}
if (expected.api) {
  const result = await request("Vinz API live", new URL("/health", API_URL).toString());
  if (result) {
    const { response, body } = result;
    let data = {};
    try { data = JSON.parse(body); } catch {}
    if (!response.ok) fail("Vinz API live", `HTTP ${response.status}`, response.url);
    else if (data.service !== "vinz-api" || data.status !== "ok" || data.configured !== true || data.backup_storage !== true)
      fail("Vinz API live", "Health response does not confirm API, configuration and backup storage", JSON.stringify(data));
    else add("Vinz API live", "PASS", "API, configuration and backup storage report healthy", response.url);
  }
}
if (expected.supabase) {
  const headers = SUPABASE_ANON_KEY ? { apikey: SUPABASE_ANON_KEY } : {};
  const result = await request("Supabase Auth live", new URL("/auth/v1/health", SUPABASE_URL).toString(), { headers });
  if (result) {
    const { response, body } = result;
    let data = {};
    try { data = JSON.parse(body); } catch {}
    if (!response.ok) fail("Supabase Auth live", `HTTP ${response.status}`, response.url);
    else if (data.name !== "GoTrue") fail("Supabase Auth live", "Expected GoTrue health payload; endpoint may be a generic proxy response", body.slice(0, 300));
    else add("Supabase Auth live", "PASS", `GoTrue reachable (${data.version || "version not reported"})`, response.url);
  }
  if (!SUPABASE_ANON_KEY) add("Supabase API key", "SKIP", "SUPABASE_ANON_KEY is not configured; Auth liveness was checked without an API key");
  else {
    const rest = await request("Supabase REST live", new URL("/rest/v1/", SUPABASE_URL).toString(), { headers });
    if (rest) {
      if (!rest.response.ok) fail("Supabase REST live", `HTTP ${rest.response.status}`, rest.response.url);
      else add("Supabase REST live", "PASS", `HTTP ${rest.response.status}`, rest.response.url);
    }
  }
}

const configFiles = [
  "platform/cloudflare/wrangler.production.jsonc",
  "web/.env.example",
  "web/.env.production.example",
  "app/src/main/java/com/vinz/music/data/VinzCloudAccount.kt",
  "web/lib/vinz.ts",
];
const inventory = [];
for (const file of configFiles) {
  const source = readIfExists(file);
  if (!source) { add(`Config file: ${file}`, "SKIP", "File not present in this repository revision"); continue; }
  const matches = [...source.matchAll(/https?:\/\/[^\s"'\x60,)]+/g)].map(m => m[0].replace(/[),;]+$/, ""));
  for (const value of matches) {
    let parsed;
    try { parsed = new URL(value); } catch { fail("URL inventory", "Malformed URL literal", `${file}: ${value}`); continue; }
    inventory.push({ file, url: value, origin: parsed.origin });
    if (/localhost|127\.0\.0\.1|0\.0\.0\.0/i.test(parsed.hostname))
      fail("Production URL inventory", "Localhost URL found in production-sensitive source", `${file}: ${value}`);
    if (/vercel\.app$/i.test(parsed.hostname) && file.includes("production"))
      fail("Production URL inventory", "Legacy Vercel host found in production-sensitive source", `${file}: ${value}`);
  }
  if (file.endsWith("wrangler.production.jsonc")) {
    let config;
    try { config = JSON.parse(parseJsonc(source)); } catch { fail("Cloudflare production config", "Invalid JSONC configuration", file); continue; }
    const origins = config.vars?.ALLOWED_ORIGINS || "";
    if (!origins.includes("https://home.vinzmusic.workers.dev")) fail("Cloudflare CORS origin", "Approved Vinz home origin missing from production allowlist", String(origins));
    if (/vercel\.app|localhost/i.test(origins)) fail("Cloudflare CORS origin", "Unexpected legacy or local origin in production allowlist", origins);
  }
}
const byOrigin = new Map();
for (const item of inventory) {
  const list = byOrigin.get(item.origin) || [];
  list.push(item.file);
  byOrigin.set(item.origin, list);
}
for (const [origin, files] of byOrigin) {
  if (new Set(files).size > 1 && !origin.includes("supabase.co") && !origin.includes("workers.dev"))
    add("Duplicate URL inventory", "WARN", `Same external origin appears in multiple config files: ${[...new Set(files)].join(", ")}`, origin);
}
add("URL inventory", "PASS", `Inspected ${configFiles.length} production-sensitive files; ${inventory.length} URL literals found`);

let changedFiles = [];
try {
  const base = process.env.GITHUB_BASE_REF;
  if (base) {
    execFileSync("git", ["fetch", "--no-tags", "origin", base], { stdio: "ignore" });
    changedFiles = execFileSync("git", ["diff", "--name-only", `origin/${base}...HEAD`], { encoding: "utf8" }).trim().split("\n").filter(Boolean);
  } else {
    changedFiles = execFileSync("git", ["diff", "--name-only", "HEAD~1...HEAD"], { encoding: "utf8" }).trim().split("\n").filter(Boolean);
  }
} catch { changedFiles = []; }
const changeLines = [];
for (const file of changedFiles) {
  let diff = "";
  try { diff = execFileSync("git", ["diff", "--unified=0", "HEAD~1...HEAD", "--", file], { encoding: "utf8" }); } catch {}
  const urls = diff.split("\n").filter(line => /^[+-].*https?:\/\//.test(line) && !/^[+-]{3}/.test(line));
  for (const line of urls) changeLines.push(`${file}: ${line.slice(0, 300)}`);
}
add("Changed-file inventory", "INFO", `${changedFiles.length} changed files detected; ${changeLines.length} URL changes detected`, changeLines.join("\n") || "No URL changes detected");

const report = [
  "# Vinz production readiness report",
  "",
  `- Commit: ${process.env.GITHUB_SHA || "local"}`,
  `- Run: ${process.env.GITHUB_SERVER_URL || "https://github.com"}/${process.env.GITHUB_REPOSITORY || "nastechtest/Vinz"}/actions/runs/${process.env.GITHUB_RUN_ID || "local"}`,
  "",
  "| Check | Status | Details | Evidence |",
  "|---|---|---|---|",
  ...checks.map(c => `| ${c.name} | ${c.status} | ${c.detail.replaceAll("|", "\\|")} | ${(c.evidence || "").replaceAll("|", "\\|").replaceAll("\n", "<br>")} |`),
  "",
  "## Changed URLs",
  "",
  ...(changeLines.length ? changeLines.map(x => `- ${x}`) : ["- No URL changes detected."]),
  "",
  "## Findings",
  "",
  ...(findings.length ? findings.map(f => `- **${f.name}:** ${f.detail} (${f.evidence})`) : ["- No blocking findings."]),
  "",
  "This report checks public endpoint reachability from the GitHub Actions runner. It does not prove successful user login, database RLS correctness, or OAuth consent completion.",
].join("\n");
if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, report + "\n");
fs.mkdirSync("artifacts", { recursive: true });
fs.writeFileSync("artifacts/vinz-readiness.json", JSON.stringify({ checks, findings, changedFiles, changeLines }, null, 2));
console.log(report);
if (findings.length) process.exitCode = 1;
