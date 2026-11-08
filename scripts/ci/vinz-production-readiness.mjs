import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { pathToFileURL } from "node:url";
import {
  parseJsonc,
  parseOriginList,
  duplicateValues,
  isExpectedPrivateTableDenial,
  isLocalHost,
} from "./vinz-readiness-lib.mjs";

const ROOT = process.cwd();
const HOME_URL = process.env.VINZ_HOME_URL || "https://home.vinzmusic.workers.dev";
const API_URL = process.env.VINZ_API_URL || "https://api.vinzmusic.workers.dev";
const SUPABASE_URL = process.env.SUPABASE_URL || "https://iszghebmetrbkfhnnoqq.supabase.co";
const timeoutMs = Number(process.env.READINESS_TIMEOUT_MS || 15000);
const checks = [];
const findings = [];
const add = (name, status, detail, evidence = "") => checks.push({ name, status, detail, evidence });
const fail = (name, detail, evidence = "") => {
  findings.push({ name, detail, evidence });
  add(name, "FAIL", detail, evidence);
};
const read = (file) => {
  try { return fs.readFileSync(path.join(ROOT, file), "utf8"); } catch { return null; }
};
const requiredFiles = [
  ".github/workflows/cloudflare-web.yml",
  ".github/workflows/vinz-production-readiness.yml",
  "web/cloudflare.config.ts",
  "web/package.json",
  "web/.env.example",
  "web/lib/vinz.ts",
  "platform/cloudflare/wrangler.production.jsonc",
  "platform/cloudflare/vinz-api.js",
  "supabase/migrations/20261107131815_vinz_account_foundation.sql",
  "supabase/migrations/20261107200000_vinz_account_2_username_and_avatar_hardening.sql",\n  "supabase/tests/vinz_account_access_contract.sql",
  "supabase/migrations/20261108100000_vinz_youtube_music_import.sql",
  "platform/cloudflare/youtube-import.js",
  "platform/cloudflare/youtube-import.test.js",
  "docs/YOUTUBE_MUSIC_IMPORT.md",
  "web/app/account/page.tsx",
  "web/app/account/profile/page.tsx",
  "web/app/account/security/page.tsx",
  "web/app/account/connections/page.tsx",
  "web/app/u/[username]/page.tsx",
  "web/app/auth/callback/page.tsx",
  "web/app/auth/reset-password/page.tsx",
  "app/src/main/java/com/vinz/music/ui/screens/VinzCloudAccountScreen.kt",
  "app/src/main/java/com/vinz/music/ui/screens/account/CloudAccountController.kt",
  "app/src/test/java/com/vinz/music/CloudAccountScreenTest.kt",
];
for (const file of requiredFiles) {
  if (read(file) === null) fail("Required file inventory", "Required production file is missing", file);
  else add("Required file inventory: " + file, "PASS", "Present");
}

function validateEndpoint(value, label, expectedHost) {
  try {
    const url = new URL(value);
    if (url.protocol !== "https:") fail(label, "Production endpoint must use HTTPS", value);
    if (url.username || url.password || url.search || url.hash) fail(label, "Endpoint must not contain credentials, query, or fragment", value);
    if (url.hostname !== expectedHost) fail(label, "Unexpected production hostname", value);
    if (url.pathname !== "/" && url.pathname !== "") fail(label, "Base endpoint must not contain a path", value);
    return url;
  } catch {
    fail(label, "Missing or invalid URL", String(value));
    return null;
  }
}
const home = validateEndpoint(HOME_URL, "VINZ_HOME_URL", "home.vinzmusic.workers.dev");
const api = validateEndpoint(API_URL, "VINZ_API_URL", "api.vinzmusic.workers.dev");
const supabase = validateEndpoint(SUPABASE_URL, "SUPABASE_URL", "iszghebmetrbkfhnnoqq.supabase.co");

async function request(name, value, headers = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(value, { headers, redirect: "manual", signal: controller.signal });
    const body = await response.text();
    if (response.status >= 300 && response.status < 400) {
      fail(name, "Unexpected redirect; check the configured host and callback URL", response.headers.get("location") || "redirect without location");
      return null;
    }
    return { response, body };
  } catch (error) {
    fail(name, error.name === "AbortError" ? `Timed out after ${timeoutMs}ms` : "Connection failed", error.cause?.code || error.name || "network_error");
    return null;
  } finally {
    clearTimeout(timer);
  }
}

if (home) {
  const result = await request("Vinz home live", HOME_URL);
  if (result) {
    const type = result.response.headers.get("content-type") || "";
    if (!result.response.ok) fail("Vinz home live", `HTTP ${result.response.status}`, HOME_URL);
    else if (!/text\/html/i.test(type) || !/<html[\s>]/i.test(result.body)) fail("Vinz home live", "Expected a real HTML page", type || "missing content-type");
    else if (!/vinz/i.test(result.body)) fail("Vinz home live", "HTML does not contain Vinz page identity", HOME_URL);
    else add("Vinz home live", "PASS", `HTTP ${result.response.status}; Vinz HTML served`, HOME_URL);
  }
}
if (api) {
  const result = await request("Vinz API live", new URL("/health", API_URL).toString());
  if (result) {
    let data = {};
    try { data = JSON.parse(result.body); } catch {}
    if (!result.response.ok) fail("Vinz API live", `HTTP ${result.response.status}`, result.response.url || API_URL);
    else if (data.service !== "vinz-api" || data.status !== "ok" || data.configured !== true || data.backup_storage !== true) {
      fail("Vinz API live", "Health payload does not confirm service, configuration and backup storage", JSON.stringify(data));
    } else add("Vinz API live", "PASS", "API, Supabase configuration and R2 backup binding report healthy", API_URL + "/health");
  }
}

if (supabase) {
  const wrangler = read("platform/cloudflare/wrangler.production.jsonc") || "";
  let publishableKey = "";
  try { publishableKey = JSON.parse(parseJsonc(wrangler)).vars?.SUPABASE_PUBLISHABLE_KEY || ""; } catch {}
  publishableKey = process.env.SUPABASE_PUBLISHABLE_KEY || process.env.SUPABASE_ANON_KEY || publishableKey;
  if (!publishableKey) fail("Supabase publishable key", "Missing publishable/anon key; cannot authenticate public API probes");
  else {
    const auth = await request("Supabase Auth live", new URL("/auth/v1/health", SUPABASE_URL).toString(), { apikey: publishableKey });
    if (auth) {
      let payload = {};
      try { payload = JSON.parse(auth.body); } catch {}
      if (!auth.response.ok) fail("Supabase Auth live", `HTTP ${auth.response.status}`, auth.body.slice(0, 240));
      else if (payload.name !== "GoTrue") fail("Supabase Auth live", "Expected GoTrue health response", auth.body.slice(0, 240));
      else add("Supabase Auth live", "PASS", `GoTrue ${payload.version || "reachable"}`, SUPABASE_URL + "/auth/v1/health");
    }
    // vinz_profiles is intentionally not readable with the public anon key.
    // 42501/401 is the expected privacy result; authenticated access is checked
    // separately from the live database catalog and the migration policy contract.
    const rest = await request("Supabase REST private-profile boundary", new URL("/rest/v1/vinz_profiles?select=id&limit=0", SUPABASE_URL).toString(), { apikey: publishableKey });
    if (rest) {
      let payload = {};
      try { payload = JSON.parse(rest.body); } catch {}
      if (isExpectedPrivateTableDenial(rest.response.status, payload)) {
        add("Supabase REST private-profile boundary", "PASS", "PostgREST is reachable and correctly denies anonymous profile-table access (42501)", SUPABASE_URL + "/rest/v1/vinz_profiles");
      } else if (rest.response.ok) {
        fail("Supabase REST private-profile boundary", "Anonymous role can read a private account table; verify RLS and grants", `HTTP ${rest.response.status}`);
      } else {
        fail("Supabase REST private-profile boundary", `Unexpected HTTP ${rest.response.status}`, rest.body.slice(0, 300));
      }
    }
  }
}

const cloudflareConfig = read("web/cloudflare.config.ts") || "";
for (const [label, pattern] of [
  ["Cloudflare home Worker name", /name:\s*"home"/],
  ["Cloudflare workers.dev enabled", /workersDev:\s*true/],
  ["Cloudflare observability logs", /logs:\s*\{\s*enabled:\s*true/],
  ["Cloudflare observability traces", /traces:\s*\{\s*enabled:\s*true/],
]) {
  if (pattern.test(cloudflareConfig)) add(label, "PASS", "Expected setting present");
  else fail(label, "Required setting missing or incorrect", "web/cloudflare.config.ts");
}
const webPackage = read("web/package.json") || "";
for (const [label, pattern] of [
  ["Cloudflare vinext build", /"build:vinext":\s*"vinext build"/],
  ["Cloudflare deploy command", /"deploy:cloudflare":\s*"vinext-cloudflare deploy"/],
]) {
  if (pattern.test(webPackage)) add(label, "PASS", "Expected script present");
  else fail(label, "Required package script missing or incorrect", "web/package.json");
}
const apiConfigText = read("platform/cloudflare/wrangler.production.jsonc") || "";
let apiConfig = {};
try { apiConfig = JSON.parse(parseJsonc(apiConfigText)); }
catch { fail("Cloudflare API config", "Production Wrangler JSONC is invalid", "platform/cloudflare/wrangler.production.jsonc"); }
const apiVars = apiConfig.vars || {};
if (apiVars.SUPABASE_URL === SUPABASE_URL) add("Cloudflare API Supabase URL", "PASS", "Matches approved Supabase project");
else fail("Cloudflare API Supabase URL", "Missing or wrong Supabase URL", String(apiVars.SUPABASE_URL));
if (apiVars.SUPABASE_PUBLISHABLE_KEY) add("Cloudflare API publishable key", "PASS", "Public publishable key is configured (value not printed)");
else fail("Cloudflare API publishable key", "Missing publishable key binding", "SUPABASE_PUBLISHABLE_KEY");
if (apiVars.ALLOWED_ORIGINS) {
  const origins = parseOriginList(apiVars.ALLOWED_ORIGINS);
  for (const duplicate of duplicateValues(origins)) fail("Duplicate CORS origin", "Duplicate origin in ALLOWED_ORIGINS", duplicate);
  if (origins.length === 1 && origins[0] === "https://home.vinzmusic.workers.dev") add("Cloudflare CORS allowlist", "PASS", "Exact approved Vinz web origin only");
  else fail("Cloudflare CORS allowlist", "Missing, duplicated or unexpected production origin", origins.join(", "));
} else fail("Cloudflare CORS allowlist", "Missing ALLOWED_ORIGINS", "platform/cloudflare/wrangler.production.jsonc");

const envExample = read("web/.env.example") || "";
for (const key of ["NEXT_PUBLIC_SUPABASE_URL", "NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY", "NEXT_PUBLIC_VINZ_API_URL"]) {
  if (new RegExp("^" + key + "=", "m").test(envExample)) add("Web environment: " + key, "PASS", "Documented in web/.env.example");
  else fail("Web environment: " + key, "Required environment variable is missing", "web/.env.example");
}

// Cross-platform account acceptance contract: do not mark YouTube integration ready
// merely because the settings page exists. The OAuth backend and both clients must be wired.
for (const route of [
  "web/app/account/page.tsx",
  "web/app/account/profile/page.tsx",
  "web/app/account/security/page.tsx",
  "web/app/account/connections/page.tsx",
  "web/app/u/[username]/page.tsx",
  "web/app/auth/callback/page.tsx",
  "web/app/auth/reset-password/page.tsx",
]) {
  if (read(route) !== null) add("Account route: " + route, "PASS", "Route exists on the production web source");
  else fail("Account route: " + route, "Required account route is missing", route);
}
const androidAccount = read("app/src/main/java/com/vinz/music/ui/screens/VinzCloudAccountScreen.kt") || "";
if (androidAccount.includes("R.drawable.vinz_logo") && androidAccount.includes("rememberInfiniteTransition")) {
  add("Android account branding", "PASS", "Official logo and animated brand equalizer are present");
} else fail("Android account branding", "Official logo or branded motion is missing", "VinzCloudAccountScreen.kt");
const webGoogleSignIn = read("web/app/page.tsx") || "";
if (webGoogleSignIn.includes("signInWithOAuth") && webGoogleSignIn.includes('provider: "google"')) {
  add("Web Google sign-in", "PASS", "Google sign-in is connected to the Vinz Supabase identity flow");
} else fail("Web Google sign-in", "Google sign-in provider flow is missing", "web/app/page.tsx");
const webConnections = read("web/app/account/connections/page.tsx") || "";
if (webConnections.includes("/v1/integrations/youtube/") && /Import YouTube Music/i.test(webConnections) && !/disabled>\s*YouTube import setup pending/i.test(webConnections)) {
  add("Web YouTube Music import", "PASS", "Connections page calls the authenticated YouTube import service");
} else fail("Web YouTube Music import", "YouTube import is still disabled or has no authenticated OAuth/import API route", "web/app/account/connections/page.tsx");
const youtubeApi = read("platform/cloudflare/youtube-import.js") || "";
const youtubeMigration = read("supabase/migrations/20261108100000_vinz_youtube_music_import.sql") || "";
if (youtubeApi.includes("code_challenge_method") && youtubeApi.includes("AES-GCM") && youtubeApi.includes("youtube.readonly")) {
  add("YouTube OAuth backend", "PASS", "PKCE, read-only scope and encrypted token storage are implemented");
} else fail("YouTube OAuth backend", "OAuth backend is missing PKCE, read-only scope or token encryption", "platform/cloudflare/youtube-import.js");
if (youtubeMigration.includes("enable row level security") && youtubeMigration.includes("Vinz YouTube connection owner access") && youtubeMigration.includes("Vinz YouTube imports owner access")) {
  add("YouTube database security", "PASS", "Connection and imported metadata tables use owner-only RLS");
} else fail("YouTube database security", "YouTube tables are missing owner-only RLS", "supabase/migrations/20261108100000_vinz_youtube_music_import.sql");
const androidController = read("app/src/main/java/com/vinz/music/ui/screens/account/CloudAccountController.kt") || "";
if (androidController.includes("/v1/integrations/youtube/") && /youtube/i.test(androidAccount)) {
  add("Android YouTube Music import", "PASS", "Native account flow is wired to the authenticated YouTube import service");
} else fail("Android YouTube Music import", "Native account does not yet connect to the YouTube OAuth/import service", "CloudAccountController.kt");
if (/linkIdentity|GoogleSignIn|CredentialManager|google/i.test(androidController)) {
  add("Android Google identity", "PASS", "Native account has a Google identity integration hook");
} else fail("Android Google identity", "Native Google sign-in/linking is not implemented", "CloudAccountController.kt");

const productionFiles = [
  ".github/workflows/cloudflare-web.yml",
  ".github/workflows/vinz-production-readiness.yml",
  "web/cloudflare.config.ts",
  "web/.env.example",
  "web/lib/vinz.ts",
  "platform/cloudflare/wrangler.production.jsonc",
  "platform/cloudflare/vinz-api.js",
];
const urlInventory = [];
for (const file of productionFiles) {
  const source = read(file);
  if (source === null) continue;
  const matches = [...source.matchAll(/https?:\/\/[^\s"'\x60<>]+/g)];
  for (const match of matches) {
    const raw = match[0].replace(/[),;]+$/, "");
    try {
      const parsed = new URL(raw);
      urlInventory.push({ file, raw, origin: parsed.origin });
      if (isLocalHost(parsed.hostname)) fail("Production URL audit", "Localhost URL found in production-sensitive configuration", file + ": " + raw);
      if (parsed.protocol !== "https:") fail("Production URL audit", "Non-HTTPS URL found in production-sensitive configuration", file + ": " + raw);
      if (/vercel\.app$/i.test(parsed.hostname) && file !== "web/vercel.json") fail("Production URL audit", "Legacy Vercel URL found in active production configuration", file + ": " + raw);
    } catch {
      fail("Production URL audit", "Malformed URL literal", file + ": " + raw);
    }
  }
}
add("Production URL inventory", "PASS", `Scanned ${urlInventory.length} URL literals across ${productionFiles.length} production configuration files`);
const byOrigin = new Map();
for (const item of urlInventory) {
  const list = byOrigin.get(item.origin) || [];
  list.push(item.file);
  byOrigin.set(item.origin, list);
}
for (const [origin, files] of byOrigin) {
  const uniqueFiles = [...new Set(files)];
  if (files.length > 1) add("Shared endpoint inventory", "INFO", `Endpoint appears ${files.length} times across ${uniqueFiles.length} file(s)`, origin);
}

let changedFiles = [];
let changeSummary = [];
try {
  const baseRef = process.env.GITHUB_BASE_REF || "main";
  execFileSync("git", ["fetch", "--no-tags", "origin", baseRef], { stdio: "ignore" });
  const base = "origin/" + baseRef;
  const nameStatus = execFileSync("git", ["diff", "--name-status", base + "...HEAD"], { encoding: "utf8" }).trim();
  changedFiles = nameStatus ? nameStatus.split("\n").map(line => line.split("\t")) : [];
  const diff = execFileSync("git", ["diff", "--unified=0", base + "...HEAD"], { encoding: "utf8", maxBuffer: 20 * 1024 * 1024 });
  const urlLines = diff.split("\n").filter(line => /^[+-].*https?:\/\//.test(line) && !/^[+-]{3}/.test(line));
  const riskyLines = diff.split("\n").filter(line => /^[+-].*(localhost|127\.0\.0\.1|vercel\.app|workers\.dev|supabase\.co|ALLOWED_ORIGINS|SUPABASE_URL)/i.test(line) && !/^[+-]{3}/.test(line));
  changeSummary = [
    ...changedFiles.map(([status, file]) => `${status} ${file}`),
    ...urlLines.map(line => "URL " + line.slice(0, 260)),
    ...riskyLines.map(line => "CONFIG " + line.slice(0, 260)),
  ];
  add("Changed-file and URL change audit", "PASS", `${changedFiles.length} changed files; ${urlLines.length} URL changes; ${riskyLines.length} hosting/configuration changes detected`, changeSummary.join("\n") || "No changes detected");
} catch (error) {
  fail("Changed-file and URL change audit", "Could not calculate a reliable base-to-head diff", error.message);
}

const report = [
  "# Vinz production readiness report",
  "",
  `- Commit: ${process.env.GITHUB_SHA || "local"}`,
  `- Workflow: ${process.env.GITHUB_SERVER_URL || "https://github.com"}/${process.env.GITHUB_REPOSITORY || "nastechtest/Vinz"}/actions/runs/${process.env.GITHUB_RUN_ID || "local"}`,
  "",
  "| Check | Status | Details | Evidence |",
  "|---|---|---|---|",
  ...checks.map((c) => `| ${c.name} | ${c.status} | ${c.detail.replaceAll("|", "\\|")} | ${(c.evidence || "").replaceAll("|", "\\|").replaceAll("\n", "<br>")} |`),
  "",
  "## Changed files and configuration changes",
  "",
  ...(changeSummary.length ? changeSummary.map((line) => "- " + line) : ["- No changed files detected."]),
  "",
  "## Blocking findings",
  "",
  ...(findings.length ? findings.map((f) => `- **${f.name}:** ${f.detail} — ${f.evidence}`) : ["- None."]),
  "",
  "The anonymous profile-table denial is intentional and is not a service outage. This workflow does not claim to prove a signed-in user's session or OAuth consent without a test identity.",
].join("\n");
if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, report + "\n");
fs.mkdirSync("artifacts", { recursive: true });
fs.writeFileSync("artifacts/vinz-readiness.json", JSON.stringify({ checks, findings, changedFiles, changeSummary, urlInventory }, null, 2));
console.log(report);
if (findings.length) process.exitCode = 1;

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  // The script is intentionally executable directly by GitHub Actions.
}
