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
