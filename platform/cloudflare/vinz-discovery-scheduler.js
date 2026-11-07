/**
 * Vinz discovery scheduler and queue consumer.
 * This Worker has no user-facing routes and no Supabase service-role key.
 * A private shared secret is stored as a Worker secret and verified by a
 * service-role-only Supabase RPC that reads the matching value from Vault.
 */
export default {
  async scheduled(_controller, env) {
    if (!env.DISCOVERY_QUEUE) throw new Error("DISCOVERY_QUEUE binding missing");
    await env.DISCOVERY_QUEUE.send({
      kind: "refresh_artist_releases",
      scheduled_at: new Date().toISOString(),
    });
  },

  async queue(batch, env) {
    for (const message of batch.messages) {
      try {
        if (!env.DISCOVERY_FUNCTION_URL || !env.DISCOVERY_CRON_SECRET) {
          throw new Error("Discovery scheduler is not configured");
        }
        const response = await fetch(env.DISCOVERY_FUNCTION_URL, {
          method: "POST",
          headers: {
            "content-type": "application/json",
            "x-vinz-cron-secret": env.DISCOVERY_CRON_SECRET,
          },
          body: JSON.stringify({ kind: message.body?.kind || "refresh_artist_releases" }),
          signal: AbortSignal.timeout(90000),
        });
        if (response.ok) message.ack();
        else message.retry({ delaySeconds: response.status === 401 ? 300 : 60 });
      } catch {
        message.retry({ delaySeconds: 60 });
      }
    }
  },
};
