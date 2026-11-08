import { bindings, defineConfig, defineWorker } from "cf/config";

export default defineConfig({
  accountId: "6ac32c2af3e1d65a98b3733b29bee4ea",
  worker: defineWorker({
    // The workers.dev hostname is derived from this exact Worker name.
    // Account subdomain: vinzmusic => https://home.vinzmusic.workers.dev
    name: "home",
    entrypoint: "vinext/server/fetch-handler",
    compatibilityDate: "2026-11-08",
    compatibilityFlags: ["nodejs_compat"],
    workersDev: true,
    previewUrls: true,
    assets: {
      htmlHandling: "none",
      notFoundHandling: "none",
      runWorkerFirst: ["/*", "!/_next/static/*", "!/*/_next/static/*"],
    },
    env: {
      ASSETS: bindings.assets(),
    },
    observability: {
      enabled: true,
      headSamplingRate: 1,
      logs: { enabled: true, invocationLogs: true },
      traces: { enabled: true, headSamplingRate: 0.1 },
    },
  }),
});
