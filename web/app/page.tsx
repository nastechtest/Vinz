"use client";

import { FormEvent, useEffect, useMemo, useState } from "react";
import { createClient, type SupabaseClient, type User } from "@supabase/supabase-js";

function getSupabase(): SupabaseClient | null {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const key = process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY;
  if (!url || !key) return null;
  return createClient(url, key, {
    auth: { autoRefreshToken: true, persistSession: true, detectSessionInUrl: true },
  });
}

export default function Home() {
  const supabase = useMemo(getSupabase, []);
  const [user, setUser] = useState<User | null>(null);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [otp, setOtp] = useState("");
  const [mode, setMode] = useState<"login" | "signup">("signup");
  const [needsOtp, setNeedsOtp] = useState(false);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [apiStatus, setApiStatus] = useState<"checking" | "online" | "offline">("checking");

  useEffect(() => {
    if (!supabase) return;
    let alive = true;
    supabase.auth.getSession().then(({ data }) => { if (alive) setUser(data.session?.user ?? null); });
    const { data: listener } = supabase.auth.onAuthStateChange((_event, session) => {
      if (alive) setUser(session?.user ?? null);
    });
  return () => { alive = false; listener.subscription.unsubscribe(); };
  }, [supabase]);

  useEffect(() => {
    const api = process.env.NEXT_PUBLIC_VINZ_API_URL;
    if (!api) { setApiStatus("offline"); return; }
    fetch(new URL("/health", api), { cache: "no-store" })
      .then(async (response) => {
        const payload = await response.json();
        setApiStatus(response.ok && payload.status === "ok" && payload.configured ? "online" : "offline");
      })
      .catch(() => setApiStatus("offline"));
  }, []);

  async function googleSignIn() {
    if (!supabase) return;
    setBusy(true);
    const { error } = await supabase.auth.signInWithOAuth({
      provider: "google",
      options: { redirectTo: window.location.origin + "/auth/callback?next=%2Faccount" },
    });
    if (error) { setMessage(error.message); setBusy(false); }
  }

  async function resendOtp() {
    if (!supabase) return;
    setBusy(true);
    const { error } = await supabase.auth.resend({ type: "signup", email: email.trim() });
    setMessage(error?.message ?? "A new verification code has been sent.");
    setBusy(false);
  }

  async function recoverPassword() {
    if (!supabase || !email.trim()) { setMessage("Enter your email address first."); return; }
    setBusy(true);
    const { error } = await supabase.auth.resetPasswordForEmail(email.trim(), {
      redirectTo: window.location.origin + "/auth/reset-password",
    });
    setMessage(error?.message ?? "If the account exists, a password reset email has been sent.");
    setBusy(false);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!supabase) { setMessage("Account service is not configured yet."); return; }
    setBusy(true);
    setMessage("");
    try {
      if (mode === "signup") {
        const { error } = await supabase.auth.signUp({
          email: email.trim(),
          password,
          options: { emailRedirectTo: `${window.location.origin}/auth/callback` },
        });
        if (error) throw error;
        setNeedsOtp(true);
        setMessage("Check your email for the verification code, then enter it below.");
      } else {
        const { error } = await supabase.auth.signInWithPassword({ email: email.trim(), password });
        if (error) throw error;
        setMessage("Signed in successfully.");
      }
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Authentication failed.");
    } finally {
      setBusy(false);
    }
  }

  async function verifyEmail(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!supabase) return;
    setBusy(true);
    setMessage("");
    try {
      const { error } = await supabase.auth.verifyOtp({
        email: email.trim(),
        token: otp.trim(),
        type: "signup",
      });
      if (error) throw error;
      setNeedsOtp(false);
      setMessage("Email verified. Your Vinz account is ready.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Verification failed.");
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    if (!supabase) return;
    const { error } = await supabase.auth.signOut();
    setMessage(error?.message ?? "Signed out.");
  }

  return (
    <main className="shell">
      <nav className="nav">
        <a className="brand" href="/" aria-label="Vinz home"><span className="brand-mark">V</span><span>vinz</span></a>
        <span className="nav-note">MUSIC · COMMUNITY · YOU</span>
      </nav>
      <section className="hero">
        <div className="eyebrow"><span className="pulse" /> YOUR MUSIC WORLD</div>
        <h1>Music feels better<br /><span>together.</span></h1>
        <p className="intro">One account for your music, your people, and everything you discover along the way.</p>
        <div className="status-row">
          <span className={apiStatus === "online" ? "status-dot good" : "status-dot"} />
          <span>Vinz API {apiStatus === "checking" ? "checking" : apiStatus === "online" ? "connected" : "not connected"}</span>
        </div>
      </section>

      <section className="auth-card" aria-label="Vinz account">
        {user ? (
          <div className="signed-in">
            <div className="success-icon">✓</div>
            <p className="eyebrow">ACCOUNT CONNECTED</p>
            <h2>Welcome to Vinz.</h2>
            <p className="muted">{user.email}</p>
            <a className="primary account-entry" href="/account">Open account</a><button className="secondary-action" onClick={signOut}>Sign out</button>
          </div>
        ) : needsOtp ? (
          <form onSubmit={verifyEmail}>
            <p className="eyebrow">EMAIL VERIFICATION</p>
            <h2>Check your inbox.</h2>
            <p className="muted">Enter the verification code sent to {email}.</p>
            <label htmlFor="otp">Verification code</label>
            <input id="otp" inputMode="numeric" autoComplete="one-time-code" value={otp} onChange={e => setOtp(e.target.value)} required maxLength={8} />
            <button className="primary" disabled={busy}>{busy ? "Verifying…" : "Verify email"}</button><button type="button" className="text-button" disabled={busy} onClick={resendOtp}>Resend verification code</button>
            <button type="button" className="text-button" onClick={() => { setNeedsOtp(false); setMessage(""); }}>Back</button>
          </form>
        ) : (
          <>
            <div className="tabs">
              <button className={mode === "signup" ? "tab active" : "tab"} onClick={() => { setMode("signup"); setMessage(""); }}>Create account</button>
              <button className={mode === "login" ? "tab active" : "tab"} onClick={() => { setMode("login"); setMessage(""); }}>Sign in</button>
            </div>
            <form onSubmit={submit}>
              <h2>{mode === "signup" ? "Join Vinz." : "Welcome back."}</h2>
              <p className="muted">{mode === "signup" ? "Create your account and verify your email to get started." : "Sign in with your Vinz account."}</p>
              <label htmlFor="email">Email address</label>
              <input id="email" type="email" autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} required />
              <label htmlFor="password">Password</label>
              <input id="password" type="password" autoComplete={mode === "signup" ? "new-password" : "current-password"} value={password} onChange={e => setPassword(e.target.value)} minLength={8} required />
              <button className="primary" disabled={busy}>{busy ? "Please wait…" : mode === "signup" ? "Create account" : "Sign in"}</button>{mode === "login" && <button type="button" className="text-button" onClick={recoverPassword}>Forgot password?</button>}<div className="auth-divider"><span>OR</span></div><button type="button" className="google-button" disabled={busy} onClick={googleSignIn}><span className="google-mark">G</span> Continue with Google</button>
            </form>
          </>
        )}
        {message && <p className="message" role="status">{message}</p>}
        {!supabase && <p className="message warning">Add the Supabase public URL and publishable key to the Vercel project environment.</p>}
        <p className="legal">By continuing, you agree to use Vinz responsibly and protect your account credentials.</p>
      </section>
      <footer><span>© {new Date().getFullYear()} Vinz</span><span>Built for the love of music.</span></footer>
    </main>
  );
}
