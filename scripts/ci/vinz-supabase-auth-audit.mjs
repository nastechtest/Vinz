import fs from "node:fs";

const PROJECT_REF = process.env.SUPABASE_PROJECT_REF || "iszghebmetrbkfhnnoqq";
const TOKEN = process.env.SUPABASE_ACCESS_TOKEN || "";
const API = "https://api.supabase.com/v1";
const checks = [];
const findings = [];
const add = (name, status, detail) => checks.push({ name, status, detail });
const fail = (name, detail) => {
  findings.push({ name, detail });
  add(name, "FAIL", detail);
};

if (!TOKEN) {
  fail("Supabase Management API authentication", "SUPABASE_ACCESS_TOKEN is missing from GitHub Actions secrets");
} else {
  try {
    const response = await fetch(API + "/projects/" + encodeURIComponent(PROJECT_REF) + "/config/auth", {
      headers: { Authorization: "Bearer " + TOKEN, Accept: "application/json" },
      signal: AbortSignal.timeout(15000),
    });
    const auth = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error("Management API returned HTTP " + response.status);
    const siteUrl = String(auth.site_url || "").replace(/\/$/, "");
    if (siteUrl === "https://home.vinzmusic.workers.dev") add("Supabase Auth Site URL", "PASS", "Exact approved Vinz Cloudflare host is configured");
    else fail("Supabase Auth Site URL", "Site URL is missing or points to a different host");

    const redirects = String(auth.uri_allow_list || "").split(/[\s,]+/).filter(Boolean);
    const callback = "https://home.vinzmusic.workers.dev/auth/callback";
    if (redirects.includes(callback)) add("Supabase Auth callback allowlist", "PASS", "Exact web auth callback is allowlisted");
    else fail("Supabase Auth callback allowlist", "Required /auth/callback URL is missing from the Supabase redirect allowlist");
    const staleRedirects = redirects.filter((value) => /vercel\.app|localhost|127\.0\.0\.1/i.test(value));
    if (staleRedirects.length) fail("Supabase Auth stale redirects", "Old Vercel or localhost redirect URLs remain configured: " + staleRedirects.length + " entry/entries");
    else add("Supabase Auth stale redirects", "PASS", "No Vercel or localhost redirect entries detected");

    if (auth.external_google_enabled === true && auth.external_google_client_id) add("Supabase Google provider", "PASS", "Google OAuth provider and client ID are configured");
    else fail("Supabase Google provider", "Google OAuth provider is disabled or its client ID is missing");
    if (auth.mailer_autoconfirm === false) add("Supabase email verification", "PASS", "Email auto-confirm is disabled; users must verify");
    else fail("Supabase email verification", "Email auto-confirm is enabled; Vinz requires verified email accounts");

    const smtpHost = typeof auth.smtp_host === "string" && auth.smtp_host.trim().length > 0;
    const smtpUser = typeof auth.smtp_user === "string" && auth.smtp_user.trim().length > 0;
    const smtpPass = typeof auth.smtp_pass === "string" && auth.smtp_pass.length > 0;
    const smtpSender = typeof auth.smtp_admin_email === "string" && auth.smtp_admin_email.includes("@");
    const smtpPort = ["465", "587"].includes(String(auth.smtp_port || ""));
    if (smtpHost && smtpUser && smtpPass && smtpSender && smtpPort) add("Supabase custom SMTP", "PASS", "SMTP host, username, password, sender and secure submission port are configured; values withheld");
    else fail("Supabase custom SMTP", "SMTP is incomplete; one or more of host, username, password, sender or secure port is missing");
    if (auth.password_hibp_enabled === true) add("Supabase leaked-password protection", "PASS", "Leaked-password protection is enabled");
    else fail("Supabase leaked-password protection", "Leaked-password protection is disabled");
    if (auth.refresh_token_rotation_enabled === true) add("Supabase refresh-token rotation", "PASS", "Refresh-token rotation is enabled");
    else fail("Supabase refresh-token rotation", "Refresh-token rotation is disabled");
  } catch (error) {
    fail("Supabase Auth configuration audit", error instanceof Error ? error.message : String(error));
  }
}

const report = {
  project_ref: PROJECT_REF,
  checked_at: new Date().toISOString(),
  checks,
  findings,
  status: findings.length ? "FAIL" : "PASS",
};
fs.mkdirSync("artifacts", { recursive: true });
fs.writeFileSync("artifacts/vinz-supabase-auth-audit.json", JSON.stringify(report, null, 2));
const markdown = [
  "## Supabase Auth configuration audit",
  "",
  "| Check | Status | Details |",
  "|---|---|---|",
  ...checks.map((item) => "| " + item.name + " | " + item.status + " | " + item.detail.replaceAll("|", "\\|") + " |"),
  "",
  findings.length ? "### Blocking findings" : "No Supabase Auth configuration blockers found.",
  ...findings.map((item) => "- **" + item.name + ":** " + item.detail),
].join("\n");
if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, markdown + "\n");
console.log(markdown);
if (findings.length) process.exitCode = 1;
