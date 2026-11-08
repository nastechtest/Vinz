import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Vinz — Your music, your community",
  description: "Sign in to your Vinz account and connect your music world.",
  applicationName: "Vinz",
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
