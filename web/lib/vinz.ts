import { createClient, type SupabaseClient } from "@supabase/supabase-js";

let browserClient: SupabaseClient | null | undefined;
export function vinzClient(): SupabaseClient | null {
  if (typeof window === "undefined") return null;
  if (browserClient !== undefined) return browserClient;
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const key = process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY;
  browserClient = url && key ? createClient(url, key, { auth: { autoRefreshToken: true, persistSession: true, detectSessionInUrl: true } }) : null;
  return browserClient;
}

export async function vinzApi(path: string, init: RequestInit = {}) {
  const api = process.env.NEXT_PUBLIC_VINZ_API_URL;
  const client = vinzClient();
  const sessionResult = client ? await client.auth.getSession() : null;
  const token = sessionResult?.data.session?.access_token;
  if (!api || !token) throw new Error("Your Vinz account session or API connection is unavailable.");
  const response = await fetch(new URL(path, api), {
    ...init,
    cache: "no-store",
    headers: { authorization: "Bearer " + token, ...(init.body ? { "content-type": "application/json" } : {}), ...init.headers },
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || "Vinz account request failed.");
  return payload;
}
