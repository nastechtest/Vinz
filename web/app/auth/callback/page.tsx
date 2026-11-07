"use client";

import { useEffect, useState } from "react";
import { createClient, type EmailOtpType } from "@supabase/supabase-js";

export default function AuthCallback() {
  const [message, setMessage] = useState("Confirming your email…");

  useEffect(() => {
    let active = true;
    const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
    const key = process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY;

    if (!url || !key) {
      setMessage("Account service is not configured. Return to Vinz and try again.");
      return;
    }

    const supabase = createClient(url, key, {
      auth: {
        autoRefreshToken: true,
        persistSession: true,
        detectSessionInUrl: true,
        flowType: "pkce",
      },
    });

    async function finish() {
      const currentUrl = new URL(window.location.href);
      const query = currentUrl.searchParams;
      const hash = new URLSearchParams(currentUrl.hash.replace(/^#/, ""));
      const oauthError = query.get("error_description") || hash.get("error_description");

      if (oauthError) {
        if (active) setMessage(decodeURIComponent(oauthError.replace(/\+/g, " ")));
        return;
      }

      const code = query.get("code");
      const tokenHash = query.get("token_hash");
      const otpType = query.get("type") as EmailOtpType | null;
      const accessToken = hash.get("access_token");
      const refreshToken = hash.get("refresh_token");

      if (code) {
        const { error } = await supabase.auth.exchangeCodeForSession(code);
        if (error) throw error;
      } else if (tokenHash && otpType) {
        const { error } = await supabase.auth.verifyOtp({ token_hash: tokenHash, type: otpType });
        if (error) throw error;
      } else if (accessToken && refreshToken) {
        const { error } = await supabase.auth.setSession({
          access_token: accessToken,
          refresh_token: refreshToken,
        });
        if (error) throw error;
      }

      const { data, error } = await supabase.auth.getSession();
      if (error) throw error;
      if (!data.session) {
        throw new Error("No active session was created. The confirmation link may have expired.");
      }

      // Remove one-time credentials from the address bar before navigating.
      window.history.replaceState({}, document.title, "/auth/callback");
      window.location.replace("/");
    }

    void finish().catch((error: unknown) => {
      if (active) {
        setMessage(error instanceof Error
          ? error.message
          : "Verification link could not be completed. Return to Vinz and request a new code.");
      }
    });

    return () => { active = false; };
  }, []);

  return <main className="callback"><div className="brand"><span className="brand-mark">V</span><span>vinz</span></div><p>{message}</p><a href="/">Return to Vinz</a></main>;
}
