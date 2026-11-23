import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

// Stream large integration diffs. An unsuccessful producer must fail closed.
export function scanDiff(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { ...options, stdio: ["ignore", "pipe", "ignore"] });
    const urlLines = [];
    const riskyLines = [];
    const lines = createInterface({ input: child.stdout, crlfDelay: Infinity });
    lines.on("line", (line) => {
      if (!/^[+-]/.test(line) || /^[+-]{3}/.test(line)) return;
      // Diff content can contain credentials. Report the change direction, not values.
      if (/https?:\/\//.test(line)) urlLines.push(line[0] + " [content redacted]");
      if (/(localhost|127\.0\.0\.1|vercel\.app|workers\.dev|supabase\.co|ALLOWED_ORIGINS|SUPABASE_URL)/i.test(line)) riskyLines.push(line[0] + " [content redacted]");
    });
    child.once("error", reject);
    child.stdout.once("error", reject);
    child.once("close", (code, signal) => {
      if (code !== 0) reject(new Error(`Diff command failed (${signal || code})`));
      else resolve({ urlLines, riskyLines });
    });
  });
}

export function parseJsonc(source) {
  return source
    .replace(/^\s*\/\/.*$/gm, "")
    .replace(/,\s*([}\]])/g, "$1");
}

export function parseOriginList(value) {
  return String(value || "")
    .split(/[;,\s]+/)
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const url = new URL(entry);
      if (url.protocol !== "https:" || url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
        throw new Error("CORS entries must be HTTPS origins without credentials, paths, queries or fragments");
      }
      return url.origin;
    });
}

export function duplicateValues(values) {
  const seen = new Set();
  const duplicates = new Set();
  for (const value of values) {
    if (seen.has(value)) duplicates.add(value);
    seen.add(value);
  }
  return [...duplicates];
}

export function isExpectedPrivateTableDenial(status, payload) {
  return (status === 401 || status === 403) &&
    payload?.code === "42501" &&
    /permission denied for table vinz_profiles/i.test(payload?.message || "");
}

export function isLocalHost(hostname) {
  return hostname === "localhost" ||
    hostname === "127.0.0.1" ||
    hostname === "0.0.0.0" ||
    hostname === "::1" ||
    hostname.endsWith(".localhost");
}
