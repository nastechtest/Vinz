import fs from "node:fs";

const ACCOUNT_ID = process.env.CLOUDFLARE_ACCOUNT_ID || "6ac32c2af3e1d65a98b3733b29bee4ea";
const TOKEN = process.env.CLOUDFLARE_API_TOKEN || "";
const API = "https://api.cloudflare.com/client/v4";
const checks = [];
const findings = [];
const add = (name, status, detail, evidence = "") => checks.push({ name, status, detail, evidence });
const fail = (name, detail, evidence = "") => {
  findings.push({ name, detail, evidence });
  add(name, "FAIL", detail, evidence);
};

async function cf(path) {
  const response = await fetch(API + path, {
    headers: { Authorization: "Bearer " + TOKEN, Accept: "application/json" },
    signal: AbortSignal.timeout(12000),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || body.success === false) {
    const message = (body.errors || []).map((item) => item.message).join("; ") || "Cloudflare API request failed";
    throw new Error("HTTP " + response.status + ": " + message);
  }
  return body.result;
}

if (!TOKEN) {
  fail("Cloudflare control-plane authentication", "CLOUDFLARE_API_TOKEN is missing from GitHub Actions secrets");
} else {
  try {
    const [scripts, namespaces, buckets, accountSubdomain] = await Promise.all([
      cf("/accounts/" + ACCOUNT_ID + "/workers/scripts"),
      cf("/accounts/" + ACCOUNT_ID + "/storage/kv/namespaces"),
      cf("/accounts/" + ACCOUNT_ID + "/r2/buckets"),
      cf("/accounts/" + ACCOUNT_ID + "/workers/subdomain"),
    ]);
    const scriptNames = new Set((scripts || []).map((item) => item.id));
    for (const name of ["home", "api", "vinz"]) {
      if (scriptNames.has(name)) add("Cloudflare Worker exists: " + name, "PASS", "Worker script exists in the connected Vinz account");
      else fail("Cloudflare Worker exists: " + name, "Required Worker script is missing", name);
    }
    if (accountSubdomain?.subdomain === "vinzmusic") add("Cloudflare workers.dev account", "PASS", "Expected Vinz workers.dev subdomain is configured");
    else fail("Cloudflare workers.dev account", "Unexpected or missing workers.dev account subdomain", accountSubdomain?.subdomain || "missing");

    const namespace = (namespaces || []).find((item) => item.title === "VINZ_CATALOG_CACHE");
    if (namespace?.id === "dc7be60ce85f490bb473ef321dec9e5d") add("Cloudflare KV namespace", "PASS", "VINZ_CATALOG_CACHE exists and matches the live Worker binding", namespace.id);
    else fail("Cloudflare KV namespace", "Missing or mismatched VINZ_CATALOG_CACHE namespace", namespace?.id || "missing");
    const bucketList = buckets?.buckets || buckets || [];
    if (bucketList.some((item) => item.name === "vinz-backups")) add("Cloudflare R2 backup bucket", "PASS", "vinz-backups bucket exists");
    else fail("Cloudflare R2 backup bucket", "Required vinz-backups bucket is missing");

    const [apiSettings, apiSubdomain, vinzSettings, vinzSecrets, vinzSubdomain] = await Promise.all([
      cf("/accounts/" + ACCOUNT_ID + "/workers/scripts/api/settings"),
      cf("/accounts/" + ACCOUNT_ID + "/workers/scripts/api/subdomain"),
      cf("/accounts/" + ACCOUNT_ID + "/workers/scripts/vinz/settings"),
      cf("/accounts/" + ACCOUNT_ID + "/workers/scripts/vinz/secrets"),
      cf("/accounts/" + ACCOUNT_ID + "/workers/scripts/vinz/subdomain"),
    ]);
    const apiBindings = apiSettings?.bindings || [];
    const service = apiBindings.find((item) => item.name === "VINZ" && item.type === "service");
    if (service?.service === "vinz") add("Cloudflare API service binding", "PASS", "api Worker forwards to the vinz service Worker");
    else fail("Cloudflare API service binding", "api Worker is not bound to the expected vinz service", JSON.stringify(service || null));
    if (apiSubdomain?.enabled === true) add("Cloudflare API workers.dev route", "PASS", "api Worker workers.dev route is enabled");
    else fail("Cloudflare API workers.dev route", "api Worker workers.dev route is disabled");

    const bindings = vinzSettings?.bindings || [];
    const binding = (name) => bindings.find((item) => item.name === name);
    const origin = binding("ALLOWED_ORIGINS")?.text || "";
    if (origin === "https://home.vinzmusic.workers.dev") add("Live Cloudflare CORS origin", "PASS", "Exact Vinz home origin is configured");
    else fail("Live Cloudflare CORS origin", "Live vinz Worker still has missing, stale, duplicate or unexpected origins", origin || "missing");
    if (binding("SUPABASE_URL")?.text === "https://iszghebmetrbkfhnnoqq.supabase.co") add("Live Cloudflare Supabase URL", "PASS", "Worker points to the intended Supabase project");
    else fail("Live Cloudflare Supabase URL", "Worker Supabase URL is missing or points to another project", binding("SUPABASE_URL")?.text || "missing");
    if (binding("SUPABASE_PUBLISHABLE_KEY")?.text?.startsWith("sb_publishable_")) add("Live Cloudflare Supabase publishable key", "PASS", "Publishable key binding exists (value hidden)");
    else fail("Live Cloudflare Supabase publishable key", "Missing modern Supabase publishable key binding");

    const kvBinding = binding("CATALOG_CACHE");
    if (kvBinding?.type === "kv_namespace" && kvBinding.namespace_id === namespace?.id) add("Live Worker KV binding", "PASS", "CATALOG_CACHE points to the live VINZ_CATALOG_CACHE namespace");
    else fail("Live Worker KV binding", "CATALOG_CACHE binding is missing or points to the wrong namespace", JSON.stringify(kvBinding || null));
    const r2Binding = binding("BACKUPS_BUCKET");
    if (r2Binding?.type === "r2_bucket" && r2Binding.bucket_name === "vinz-backups") add("Live Worker R2 binding", "PASS", "BACKUPS_BUCKET points to vinz-backups");
    else fail("Live Worker R2 binding", "BACKUPS_BUCKET binding is missing or points to the wrong bucket", JSON.stringify(r2Binding || null));
    if (vinzSubdomain?.enabled === true) add("Vinz service workers.dev route", "PASS", "vinz service Worker route is enabled");
    else fail("Vinz service workers.dev route", "vinz service Worker workers.dev route is disabled");

    const secretNames = new Set((vinzSecrets || []).map((item) => item.name));
    for (const name of ["GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET", "YOUTUBE_TOKEN_ENCRYPTION_KEY"]) {
      if (secretNames.has(name)) add("Cloudflare secret binding: " + name, "PASS", "Secret binding exists; value is never read or printed");
      else fail("Cloudflare secret binding: " + name, "Required secret is not bound to the vinz Worker", name);
    }

    const [homeProbe, apiProbe] = await Promise.all([
      fetch("https://home.vinzmusic.workers.dev", { redirect: "manual", signal: AbortSignal.timeout(12000) }),
      fetch("https://api.vinzmusic.workers.dev/health", { redirect: "manual", signal: AbortSignal.timeout(12000) }),
    ]);
    if (homeProbe.ok && /text\\/html/i.test(homeProbe.headers.get("content-type") || "")) add("Cloudflare home route", "PASS", "Home URL serves an HTML response", "HTTP " + homeProbe.status);
    else fail("Cloudflare home route", "Home URL is not serving the deployed web application", "HTTP " + homeProbe.status);
    const health = await apiProbe.json().catch(() => ({}));
    if (apiProbe.ok && health.service === "vinz-api" && health.status === "ok" && health.integrations?.youtube_import_configured === true) add("Cloudflare API health and YouTube secrets", "PASS", "Live API reports core services and YouTube OAuth configured");
    else fail("Cloudflare API health and YouTube secrets", "Live API health does not confirm YouTube integration readiness", JSON.stringify(health));
  } catch (error) {
    fail("Cloudflare control-plane audit", "Could not complete live Cloudflare resource inspection", error instanceof Error ? error.message : String(error));
  }
}

const report = {
  account_id: ACCOUNT_ID,
  checked_at: new Date().toISOString(),
  checks,
  findings,
  status: findings.length ? "FAIL" : "PASS",
};
fs.mkdirSync("artifacts", { recursive: true });
fs.writeFileSync("artifacts/vinz-cloudflare-audit.json", JSON.stringify(report, null, 2));
const markdown = [
  "## Cloudflare live configuration audit",
  "",
  "| Check | Status | Details | Evidence |",
  "|---|---|---|---|",
  ...checks.map((item) => "| " + item.name + " | " + item.status + " | " + item.detail.replaceAll("|", "\\|") + " | " + item.evidence.replaceAll("|", "\\|") + " |"),
  "",
  findings.length ? "### Blocking findings" : "No Cloudflare configuration blockers found.",
  ...findings.map((item) => "- **" + item.name + ":** " + item.detail + " (" + item.evidence + ")"),
].join("\n");
if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, markdown + "\n");
console.log(markdown);
if (findings.length) process.exitCode = 1;
