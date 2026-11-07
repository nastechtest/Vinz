"use client";

import { useEffect, useState } from "react";
import { createClient } from "@supabase/supabase-js";

export default function AuthCallback() {
  const [message, setMessage] = useState("Confirming your email…");
  useEffect(() => {
    const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
    const key = process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY;
    if (!url || !key) { setMessage("Account service is not configured."); return; }
    const supabase = createClient(url, key);
    const code = new URLSearchParams(window.location.search).get("code");
    const finish = async () => {
      if (code) {
        const { error } = await supabase.auth.exchangeCodeForSession(code);
        if (error) { setMessage("Verification link could not be completed. Return to Vinz and request a new code."); return; }
      }
      const { data, error } = await supabase.auth.getSession();
      if (error || !data.session) { setMessage("No active session was created. Return to Vinz and sign in."); return; }
      const next = new URLSearchParams(window.location.search).get("next");\n      window.location.replace(next && next.startsWith("/") && !next.startsWith("//") ? next : "/");
    };
    void finish();
  }, []);
  return <main className="callback"><div className="brand"><span className="brand-mark">V</span><span>vinz</span></div><p>{message}</p><a href="/">Return to Vinz</a></main>;
}
