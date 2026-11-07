/**
 * Vinz API — Cloudflare Worker entry point.
 *
 * This is an edge API for account/profile operations. Supabase remains the
 * source of truth. Never put a service-role key in this Worker or the app.
 */
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
};

function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...JSON_HEADERS, ...headers },
  });
}

function allowedOrigin(request, env) {
  const origin = request.headers.get("origin");
  if (!origin) return null; // Native clients do not send browser Origin headers.
  const allowed = (env.ALLOWED_ORIGINS || "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  return allowed.includes(origin) ? origin : false;
}

function corsHeaders(origin) {
  return origin
    ? {
        "access-control-allow-origin": origin,
        "access-control-allow-methods": "GET, OPTIONS",
        "access-control-allow-headers": "authorization, content-type, apikey",
        "access-control-max-age": "86400",
        vary: "Origin",
      }
    : {};
}

function configuration(env) {
  if (!env.SUPABASE_URL || !env.SUPABASE_PUBLISHABLE_KEY) return null;
  try {
    const url = new URL(env.SUPABASE_URL);
    if (url.protocol !== "https:") return null;
    return { url: url.origin, key: env.SUPABASE_PUBLISHABLE_KEY };
  } catch {
    return null;
  }
}

async function currentUser(request, config) {
  const authorization = request.headers.get("authorization") || "";
  if (!/^Bearer\s+\S+$/i.test(authorization)) return null;

  const response = await fetch(`${config.url}/auth/v1/user`, {
    method: "GET",
    headers: {
      apikey: config.key,
      authorization,
      accept: "application/json",
    },
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) return null;

  const user = await response.json();
  return typeof user.id === "string" ? user : null;
}

async function getOwnProfile(user, config) {
  const query = new URLSearchParams({
    select: "id,username,display_name,bio,avatar_path,is_public,discoverable,created_at,updated_at",
    id: `eq.${user.id}`,
    limit: "1",
  });
  const response = await fetch(`${config.url}/rest/v1/vinz_profiles?${query}`, {
    method: "GET",
    headers: {
      apikey: config.key,
      authorization: `Bearer ${user.access_token || ""}`,
      accept: "application/json",
    },
    signal: AbortSignal.timeout(8000),
  });
  if (!response.ok) throw new Error("profile_upstream_error");
  const rows = await response.json();
  return Array.isArray(rows) ? rows[0] || null : null;
}

export async function handleRequest(request, env = {}) {
  const requestId = request.headers.get("cf-ray") || crypto.randomUUID();
  const origin = allowedOrigin(request, env);

  if (origin === false) return json({ error: "origin_not_allowed", request_id: requestId }, 403);
  const headers = { ...corsHeaders(origin), "x-request-id": requestId };

  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers });
  }

  const url = new URL(request.url);
  if (url.pathname === "/health" && request.method === "GET") {
    return json(
      { service: "vinz-api", status: "ok", configured: Boolean(configuration(env)) },
      200,
      headers,
    );
  }

  if (url.pathname === "/v1/me") {
    if (request.method !== "GET") {
      return json({ error: "method_not_allowed", request_id: requestId }, 405, {
        ...headers,
        allow: "GET, OPTIONS",
      });
    }

    const config = configuration(env);
    if (!config) {
      return json({ error: "backend_not_configured", request_id: requestId }, 503, headers);
    }

    try {
      const authorization = request.headers.get("authorization") || "";
      if (!/^Bearer\s+\S+$/i.test(authorization)) {
        return json({ error: "unauthorized", request_id: requestId }, 401, headers);
      }

      const authResponse = await fetch(`${config.url}/auth/v1/user`, {
        method: "GET",
        headers: {
          apikey: config.key,
          authorization,
          accept: "application/json",
        },
        signal: AbortSignal.timeout(8000),
      });
      if (authResponse.status === 401 || authResponse.status === 403) {
        return json({ error: "unauthorized", request_id: requestId }, 401, headers);
      }
      if (!authResponse.ok) {
        return json({ error: "identity_provider_unavailable", request_id: requestId }, 502, headers);
      }

      const user = await authResponse.json();
      if (typeof user.id !== "string") {
        return json({ error: "unauthorized", request_id: requestId }, 401, headers);
      }

      const query = new URLSearchParams({
        select: "id,username,display_name,bio,avatar_path,is_public,discoverable,created_at,updated_at",
        id: `eq.${user.id}`,
        limit: "1",
      });
      const profileResponse = await fetch(`${config.url}/rest/v1/vinz_profiles?${query}`, {
        method: "GET",
        headers: {
          apikey: config.key,
          authorization,
          accept: "application/json",
        },
        signal: AbortSignal.timeout(8000),
      });
      if (!profileResponse.ok) {
        return json({ error: "profile_service_unavailable", request_id: requestId }, 502, headers);
      }

      const rows = await profileResponse.json();
      const profile = Array.isArray(rows) ? rows[0] || null : null;
      return json({ user: { id: user.id }, profile }, 200, headers);
    } catch {
      return json({ error: "upstream_timeout_or_failure", request_id: requestId }, 502, headers);
    }
  }

  return json({ error: "not_found", request_id: requestId }, 404, headers);
}

export default {
  fetch(request, env) {
    return handleRequest(request, env);
  },
};
