import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Privacy Policy — Vinz",
  description: "How Nastech Research handles information in Vinz.",
  robots: { index: true, follow: true },
};

export default function PrivacyPolicy() {
  return <main className="legal-page"><a href="/">← Back to Vinz</a><p className="eyebrow">NASTECH RESEARCH · VINZ</p><h1>Privacy Policy</h1><p className="muted">Effective date: 8 November 2026</p>
    <p>Nastech Research ("we", "us") operates Vinz, a music and community application. This policy explains what information Vinz handles, why it is used, and the choices available to you.</p>
    <h2>1. Information we handle</h2><ul>
      <li><strong>Account information:</strong> email address and authentication identifiers supplied by Supabase or Google when you create or access a Vinz account.</li>
      <li><strong>Profile information:</strong> username, display name, profile image and preferences that you choose to provide.</li>
      <li><strong>Connected YouTube data:</strong> if you choose to connect YouTube, Vinz requests read-only access and may retrieve YouTube Music-related playlist and library metadata for the import features you request. Vinz does not download or copy audio files.</li>
      <li><strong>Technical information:</strong> limited request, diagnostic and security information needed to operate, protect and troubleshoot the service.</li>
    </ul>
    <h2>2. How we use information</h2><p>We use information to create and secure your account, display your profile, provide requested music-import features, maintain service reliability, prevent abuse and respond to support requests. We do not sell personal information or use Google user data for advertising.</p>
    <h2>3. Google API and YouTube data</h2><p>When you choose to connect a Google account, Google authentication and YouTube API access are handled through Google's OAuth process. Vinz uses only the permissions shown on Google's consent screen and only to provide the feature you request. You can disconnect YouTube in Vinz Account → Connections. Disconnecting stops future access; you can also revoke Vinz's access in your Google Account security settings.</p>
    <p>Google user data is not sold, used for targeted advertising, or shared except as needed to provide the feature you requested, comply with law, protect the service, or as described in this policy. Any use and transfer of information received from Google APIs adheres to the Google API Services User Data Policy, including its Limited Use requirements.</p>
    <h2>4. Storage and service providers</h2><p>Vinz uses Supabase for account and application data and Cloudflare for application hosting and API delivery. These providers process information on our behalf to operate the service. OAuth credentials and provider tokens are protected using server-side security controls; access is restricted to the account owner and the services needed to perform the requested connection.</p>
    <h2>5. Sharing</h2><p>We do not sell or rent personal information. We may disclose the minimum information necessary to our infrastructure providers, when you direct us to connect a third-party service, when required by law, or when reasonably necessary to protect users and the service.</p>
    <h2>6. Retention and deletion</h2><p>We retain account and connected-service information while needed to provide Vinz, meet legal obligations, resolve disputes and protect the service. You can disconnect a connected provider in Account → Connections and request account/data deletion by emailing us from your account email. We will verify the request and delete or de-identify information we are not required to retain. Some limited backups or security records may persist until their normal rotation or legal retention period.</p>
    <h2>7. Security</h2><p>We use reasonable technical and organisational safeguards. No internet service can guarantee absolute security. Protect your credentials and contact us promptly if you believe your account has been compromised.</p>
    <h2>8. Children and eligibility</h2><p>Vinz is not designed for children who are below the minimum age permitted to use the service under the laws that apply to them. If you believe a child has provided personal information, contact us so we can review the request.</p>
    <h2>9. Your choices and rights</h2><p>Depending on your location, you may have rights to access, correct, delete or restrict certain personal information. You may also revoke Google access through your Google Account. Contact us to exercise applicable rights.</p>
    <h2>10. Changes and contact</h2><p>We may update this policy as Vinz changes. We will publish the current version on this page and update the effective date. Questions or privacy requests: <a href="mailto:nastechplatforms@gmail.com">nastechplatforms@gmail.com</a>.</p>
    <footer>© 2026 Nastech Research · <a href="/terms">Terms of Service</a> · <a href="mailto:nastechplatforms@gmail.com">Contact</a></footer>
  </main>;
}
