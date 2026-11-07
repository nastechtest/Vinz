/**
 * Vinz API — Cloudflare Worker entry point.
 *
 * Supabase remains the source of truth. Never put a service-role key in
 * this Worker or the Android application.
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
  if (!origin) return null; // Native Android clients do not send browser Origin headers.
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

/**
 * Server-side operational telemetry only. The event/property allowlist is
 * deliberately fixed: no user IDs, tokens, email, music queries, lyrics,
 * track names, audio, or request bodies are sent to PostHog.
 */
function recordApiError(env, ctx, errorType, status) {
  if (!env.POSTHOG_PROJECT_KEY || typeof ctx?.waitUntil !== "function") return;
  const host = (env.POSTHOG_HOST || "https://us.i.posthog.com").replace(/\/+$/, "");
  const payload = {
    api_key: env.POSTHOG_PROJECT_KEY,
    event: "vinz_api_error",
    distinct_id: "vinz-api",
    properties: {
      service: "cloudflare-worker",
      route: "/v1/me",
      error_type: errorType,
      status_code: status,
    },
  };
  ctx.waitUntil(
    fetch(`${host}/capture/`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload),
    }).catch(() => undefined),
  );
}

export async function handleRequest(request, env = {}, ctx = undefined) {
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
        recordApiError(env, ctx, "identity_provider_unavailable", 502);
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
        recordApiError(env, ctx, "profile_service_unavailable", 502);
        return json({ error: "profile_service_unavailable", request_id: requestId }, 502, headers);
      }

      const rows = await profileResponse.json();
      const profile = Array.isArray(rows) ? rows[0] || null : null;
      return json({ user: { id: user.id }, profile }, 200, headers);
    } catch {
      recordApiError(env, ctx, "upstream_timeout_or_failure", 502);
      return json({ error: "upstream_timeout_or_failure", request_id: requestId }, 502, headers);
    }
  }

  return json({ error: "not_found", request_id: requestId }, 404, headers);
}

export default {
  fetch(request, env, ctx) {
    return handleRequest(request, env, ctx);
  },
};
