"use client";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import type { User } from "@supabase/supabase-js";
import { vinzClient } from "@/lib/vinz";
const links = [["/account","Overview"],["/account/profile","Profile"],["/account/security","Security"],["/account/connections","Connections"],["/account/preferences","Preferences"]] as const;
export default function AccountShell({ children }: { children: React.ReactNode }) {
 const [user,setUser]=useState<User|null>(null); const [ready,setReady]=useState(false); const router=useRouter(); const path=usePathname();
 useEffect(()=>{ const client=vinzClient(); if(!client){setReady(true);return;} let active=true;
  client.auth.getSession().then(({data})=>{if(!active)return;setUser(data.session?.user??null);setReady(true);if(!data.session)router.replace("/");});
  const {data}=client.auth.onAuthStateChange((_event,session)=>{if(!active)return;setUser(session?.user??null);if(!session)router.replace("/");});
  return ()=>{active=false;data.subscription.unsubscribe();};
 },[router]);
 return <main className="account-shell"><header className="account-topbar"><Link className="brand" href="/"><span className="brand-mark">V</span><span>vinz</span></Link><div className="account-top-user">{user?.email??"Your account"}</div></header><div className="account-frame"><aside className="account-sidebar"><p className="eyebrow">VINZ IDENTITY</p><h1>Account</h1><nav aria-label="Account navigation">{links.map(([href,label])=><Link key={href} href={href} className={path===href?"account-nav-link active":"account-nav-link"}>{label}</Link>)}</nav><Link className="account-back-link" href="/">← Back to Vinz</Link></aside><section className="account-content">{!ready?<p className="muted">Loading your secure account…</p>:user?children:<p className="muted">Redirecting to sign in…</p>}</section></div></main>;
}
