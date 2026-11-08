"use client";

import { useEffect, useState } from "react";
import { vinzApi, vinzClient } from "@/lib/vinz";

type YouTubeStatus = {
  connected: boolean;
  channel?: { id: string | null; title: string } | null;
  connected_at?: string | null;
};
type ImportResult = {
  imported_count: number;
  playlist_count: number;
  playlists: Array<{ id: string; title: string }>;
};

export default function Connections() {
  const [googleLinked, setGoogleLinked] = useState(false);
  const [youtube, setYoutube] = useState<YouTubeStatus>({ connected: false });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [importResult, setImportResult] = useState<ImportResult | null>(null);

  async function refresh() {
    const client = vinzClient();
    if (!client) {
      setMessage("Vinz account service is unavailable.");
      return;
    }
    const [{ data }, status] = await Promise.all([
      client.auth.getUser(),
      vinzApi("/v1/integrations/youtube/status").catch((error) => {
        setMessage(error instanceof Error ? error.message : "YouTube connection status is unavailable.");
        return null;
      }),
    ]);
    setGoogleLinked(Boolean(data.user?.identities?.some((identity) => identity.provider === "google")));
    if (status) setYoutube(status as YouTubeStatus);
  }

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const result = params.get("youtube");
    if (result === "connected") setMessage("YouTube Music is connected to your Vinz account.");
    else if (result && result !== "cancelled") setMessage("YouTube connection could not be completed (" + result.replace(/[^a-z_]/g, "") + ").");
    void refresh();
  }, []);

  async function linkGoogle() {
    const client = vinzClient();
    if (!client) return;
    setBusy(true);
    setMessage("");
    const { error } = await client.auth.linkIdentity({
      provider: "google",
      options: { redirectTo: window.location.origin + "/auth/callback?next=%2Faccount%2Fconnections" },
    });
    if (error) {
      setMessage(error.message);
      setBusy(false);
    }
  }

  async function connectYouTube() {
    setBusy(true);
    setMessage("");
    try {
      const result = await vinzApi("/v1/integrations/youtube/connect");
      if (typeof result.authorization_url !== "string" || !result.authorization_url.startsWith("https://accounts.google.com/")) {
        throw new Error("Vinz returned an invalid Google authorization link.");
      }
      window.location.assign(result.authorization_url);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "YouTube connection could not start.");
      setBusy(false);
    }
  }

  async function importYouTube() {
    setBusy(true);
    setMessage("");
    setImportResult(null);
    try {
      const result = await vinzApi("/v1/integrations/youtube/import", { method: "POST" }) as ImportResult;
      setImportResult(result);
      setMessage(result.imported_count
        ? `Imported ${result.imported_count} videos from ${result.playlist_count} YouTube playlists.`
        : "Your accessible YouTube playlists did not contain any importable videos.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "YouTube Music import failed.");
    } finally {
      setBusy(false);
    }
  }

  async function disconnectYouTube() {
    setBusy(true);
    setMessage("");
    try {
      await vinzApi("/v1/integrations/youtube/connection", { method: "DELETE" });
      setYoutube({ connected: false });
      setImportResult(null);
      setMessage("YouTube has been disconnected. Previously imported metadata remains in your Vinz account.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "YouTube could not be disconnected.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="page-heading">
        <p className="eyebrow">CONNECTED SERVICES</p>
        <h2>Connections</h2>
        <p className="muted">Vinz is your primary identity. Connect music services from your account settings without creating another Vinz account.</p>
      </div>
      <article className="account-card connection-card">
        <div>
          <p className="eyebrow">SIGN-IN IDENTITY</p>
          <h3>Google</h3>
          <p className="muted">Link Google to your existing Vinz account. This identity can also help preselect the Google account used for YouTube Music import.</p>
        </div>
        <span className={googleLinked ? "connection-state good" : "connection-state"}>{googleLinked ? "Linked" : "Not linked"}</span>
        <button className="secondary-action" disabled={busy || googleLinked} onClick={linkGoogle}>
          {googleLinked ? "Google linked" : "Link Google identity"}
        </button>
      </article>
      <article className="account-card connection-card">
        <div>
          <p className="eyebrow">MUSIC IMPORT</p>
          <h3>YouTube Music</h3>
          <p className="muted">Authorize read-only access through Google's secure consent screen. Vinz never asks you to enter a YouTube email address or password.</p>
          <span className={youtube.connected ? "connection-state good" : "connection-state"}>
            {youtube.connected ? "Connected" : "Not connected"}
          </span>
          {youtube.connected && youtube.channel?.title && <p className="muted">Connected channel: {youtube.channel.title}</p>}
        </div>
        {!youtube.connected ? (
          <button className="primary" disabled={busy} onClick={connectYouTube}>
            {busy ? "Connecting…" : "Connect YouTube Music"}
          </button>
        ) : (
          <>
            <button className="primary" disabled={busy} onClick={importYouTube}>
              {busy ? "Importing…" : "Import YouTube Music"}
            </button>
            <button className="secondary-action" disabled={busy} onClick={disconnectYouTube}>Disconnect YouTube</button>
          </>
        )}
        <p className="legal">Vinz imports accessible playlists and video metadata only. The official YouTube Data API does not expose every YouTube Music feature or complete private listening history. Audio is not copied or rehosted.</p>
        {importResult && <div className="import-results" aria-live="polite">
          <strong>Latest import</strong>
          <p>{importResult.imported_count} videos · {importResult.playlist_count} playlists</p>
          <ul>{importResult.playlists.map((playlist) => <li key={playlist.id}>{playlist.title}</li>)}</ul>
        </div>}
      </article>
      {message && <p className="message" role="status">{message}</p>}
    </>
  );
}
