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
    supabase.auth.getSession().then(({ data }) => {
      if (alive) setUser(data.session?.user ?? null);
    });
    const { data: listener } = supabase.auth.onAuthStateChange((_event, session) => {
      if (alive) setUser(session?.user ?? null);
    });
    return () => {
      alive = false;
      listener.subscription.unsubscribe();
    };
  }, [supabase]);

  useEffect(() => {
    const api = process.env.NEXT_PUBLIC_VINZ_API_URL;
    if (!api) {
      setApiStatus("offline");
      return;
    }
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
    if (error) {
      setMessage(error.message);
      setBusy(false);
    }
  }

  async function resendOtp() {
    if (!supabase) return;
    setBusy(true);
    const { error } = await supabase.auth.resend({ type: "signup", email: email.trim() });
    setMessage(error?.message ?? "A new verification code has been sent.");
    setBusy(false);
  }

  async function recoverPassword() {
    if (!supabase || !email.trim()) {
      setMessage("Enter your email address first.");
      return;
    }
    setBusy(true);
    const { error } = await supabase.auth.resetPasswordForEmail(email.trim(), {
      redirectTo: window.location.origin + "/auth/callback?next=%2Fauth%2Freset-password",
    });
    setMessage(error?.message ?? "If the account exists, a password reset email has been sent.");
    setBusy(false);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!supabase) {
      setMessage("Account service is not configured yet.");
      return;
    }
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
      const { error } = await supabase.auth.verifyOtp({ email: email.trim(), token: otp.trim(), type: "signup" });
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
    <main className="welcome-shell">
      <nav className="welcome-nav" aria-label="Primary navigation">
        <a className="brand" href="/" aria-label="Vinz home">
          <img className="brand-logo" src="/vinz-logo.png" alt="" width="42" height="42" />
          <span>vinz</span>
        </a>
        <div className="nav-links">
          <a href="#why-vinz">Discover</a>
          <a href={user ? "/account" : "#account"}>{user ? "Account" : "Sign in"}</a>
          <a className="nav-join" href={user ? "/account" : "#account"}>{user ? "Open account" : "Join Vinz"} <span aria-hidden="true">↗</span></a>
        </div>
      </nav>

      <section className="welcome-hero" aria-labelledby="welcome-title">
        <div className="hero-copy">
          <p className="eyebrow"><span className="pulse" /> MUSIC, MADE PERSONAL</p>
          <h1 id="welcome-title">Turn it up.<br /><span>Find your people.</span></h1>
          <p className="hero-intro">Vinz is the space for every track, every mood, and every person who gets it. Bring your music world together.</p>
          <div className="hero-actions">
            <a className="hero-cta" href="#account">Create your free account <span aria-hidden="true">→</span></a>
            <a className="quiet-link" href="#why-vinz">Explore Vinz <span aria-hidden="true">↓</span></a>
          </div>
          <div className="status-row" aria-live="polite">
            <span className={apiStatus === "online" ? "status-dot good" : "status-dot"} />
            <span>Vinz network {apiStatus === "checking" ? "checking" : apiStatus === "online" ? "online" : "not connected"}</span>
          </div>
        </div>
        <div className="hero-art" aria-label="A Vinz listener immersed in music">
          <div className="art-orb orb-purple" />
          <div className="art-orb orb-green" />
          <div className="hero-art-frame">
            <img src="/artwork/vinz-listening-together.jpg" alt="Listener wearing headphones in a vibrant music setting" />
          </div>
          <div className="now-playing" aria-label="Now playing card">
            <div className="album-bars" aria-hidden="true"><i /><i /><i /><i /><i /></div>
            <div><small>YOUR SOUND, YOUR SPACE</small><strong>Always in the mix</strong></div>
            <span aria-hidden="true">↗</span>
          </div>
        </div>
      </section>

      <section className="moment-strip" aria-label="Vinz highlights">
        <p>ONE APP. <span>ENDLESS ENERGY.</span></p>
        <div className="strip-rule" />
        <p>BUILT FOR THE <span>GROUP CHAT.</span></p>
      </section>

      <section className="experience-section" id="why-vinz" aria-labelledby="experience-title">
        <div className="experience-art">
          <img src="/artwork/vinz-headphones-collage.jpg" alt="Headphones and vinyl-inspired music artwork" />
          <div className="art-label"><span>01</span><p>Every mood<br />has a soundtrack.</p></div>
        </div>
        <div className="experience-copy">
          <p className="eyebrow">YOUR MUSIC WORLD</p>
          <h2 id="experience-title">More than<br /><span>a listening app.</span></h2>
          <p>Build your profile, share the songs you cannot stop playing, and discover what your people are into. Vinz keeps the community close and your choices yours.</p>
          <ul className="feature-list">
            <li><span aria-hidden="true">✦</span> A profile that sounds like you</li>
            <li><span aria-hidden="true">✦</span> Optional, read-only music imports</li>
            <li><span aria-hidden="true">✦</span> Your privacy and preferences in one place</li>
          </ul>
        </div>
      </section>

      <section className="account-section" id="account" aria-labelledby="account-title">
        <div className="account-pitch">
          <p className="eyebrow">MAKE IT OFFICIAL</p>
          <h2 id="account-title">Your next favourite<br /><span>thing starts here.</span></h2>
          <p>Create a Vinz account to shape your profile and connect supported services only when you choose. Already have an account? Pick up right where you left off.</p>
          <div className="account-art-mini" aria-hidden="true"><img src="/artwork/vinz-headphones-music.jpg" alt="" /></div>
          <p className="privacy-note">YouTube connections are optional and read-only for supported playlist and library metadata imports.</p>
        </div>

        <section className="auth-card" aria-label="Vinz account sign in and registration">
          <div className="auth-brand-lockup">
            <img src="/vinz-logo.png" alt="Vinz" width="54" height="54" />
            <div><span>VINZ ACCOUNT</span><small>Everything you love, in sync.</small></div>
            <div className="brand-equalizer compact" aria-hidden="true"><i /><i /><i /><i /><i /></div>
          </div>
          {user ? (
            <div className="signed-in">
              <div className="success-icon">✓</div>
              <p className="eyebrow">YOU&apos;RE IN THE MIX</p>
              <h3>Welcome back.</h3>
              <p className="muted">{user.email}</p>
              <a className="primary account-entry" href="/account">Open account</a>
              <button className="secondary-action" onClick={signOut}>Sign out</button>
            </div>
          ) : needsOtp ? (
            <form onSubmit={verifyEmail}>
              <p className="eyebrow">EMAIL VERIFICATION</p>
              <h3>Check your inbox.</h3>
              <p className="muted">Enter the verification code sent to {email}.</p>
              <label htmlFor="otp">Verification code</label>
              <input id="otp" inputMode="numeric" autoComplete="one-time-code" value={otp} onChange={e => setOtp(e.target.value)} required maxLength={8} />
              <button className="primary" disabled={busy}>{busy ? "Verifying…" : "Verify email"}</button>
              <button type="button" className="text-button" disabled={busy} onClick={resendOtp}>Resend verification code</button>
              <button type="button" className="text-button" onClick={() => { setNeedsOtp(false); setMessage(""); }}>Back</button>
            </form>
          ) : (
            <>
              <div className="tabs" role="tablist" aria-label="Account action">
                <button type="button" role="tab" aria-selected={mode === "signup"} className={mode === "signup" ? "tab active" : "tab"} onClick={() => { setMode("signup"); setMessage(""); }}>Create account</button>
                <button type="button" role="tab" aria-selected={mode === "login"} className={mode === "login" ? "tab active" : "tab"} onClick={() => { setMode("login"); setMessage(""); }}>Sign in</button>
              </div>
              <form onSubmit={submit}>
                <h3>{mode === "signup" ? "Join the good noise." : "Welcome back."}</h3>
                <p className="muted">{mode === "signup" ? "Create your account and verify your email to get started." : "Sign in with your Vinz account."}</p>
                <label htmlFor="email">Email address</label>
                <input id="email" type="email" autoComplete="email" value={email} onChange={e => setEmail(e.target.value)} required />
                <label htmlFor="password">Password</label>
                <input id="password" type="password" autoComplete={mode === "signup" ? "new-password" : "current-password"} value={password} onChange={e => setPassword(e.target.value)} minLength={8} required />
                <button className="primary" disabled={busy}>{busy ? "Please wait…" : mode === "signup" ? "Create account" : "Sign in"}</button>
                {mode === "login" && <button type="button" className="text-button" onClick={recoverPassword}>Forgot password?</button>}
                <div className="auth-divider"><span>OR CONTINUE WITH</span></div>
                <button type="button" className="google-button" disabled={busy} onClick={googleSignIn}><span className="google-mark">G</span> Google</button>
              </form>
            </>
          )}
          {message && <p className="message" role="status">{message}</p>}
          {!supabase && <p className="message warning">Vinz account services are temporarily unavailable. Please try again later.</p>}
          <p className="legal">By continuing, you agree to use Vinz responsibly and protect your account credentials.</p>
        </section>
      </section>

      <footer className="welcome-footer">
        <a className="brand" href="/" aria-label="Vinz home"><img className="brand-logo" src="/vinz-logo.png" alt="" width="34" height="34" /><span>vinz</span></a>
        <span>© {new Date().getFullYear()} Vinz. Built for the love of music.</span>
        <div><a href="/privacy">Privacy</a><a href="/terms">Terms</a><a href="mailto:nastechplatforms@gmail.com">Contact</a></div>
      </footer>
    </main>
  );
}
