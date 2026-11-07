# Vinz platform deployment

## Architecture

- **Vercel**: Next.js web account portal at `web/`.
- **Supabase**: Auth, Postgres, RLS, and the `vinz-discovery` Edge Function.
- **Cloudflare Workers**: Vinz API. Staging is `vinz-api-staging`; production is `vinz`.
- **Cloudflare R2**: private backup objects in `vinz-backups`.
- **Cloudflare KV**: catalog cache.
- **PostHog**: operational error telemetry only; the Worker intentionally excludes user identifiers, emails, tokens, lyrics, and music queries.

## Deploy workflows

- `.github/workflows/vercel-web.yml`: web dependency/build validation. Vercel Git integration creates deployments for connected branches.
- `.github/workflows/vinz-platform-deploy.yml`: manual staging/production Worker deployment with tests and post-deploy health check.
- Existing platform checks and provider health workflows remain separate.
- Production Worker deploy requires `confirm_production=deploy-production`.
- Supabase database migrations are not auto-applied by a web or Worker deployment. They are already present in the connected project; future migrations need their own reviewed migration release.

## GitHub Actions configuration

Secrets:
- `CLOUDFLARE_API_TOKEN` — Workers edit/deploy permission for the account.
- `POSTHOG_PROJECT_KEY` — PostHog ingestion key; distinct from the personal API token.
- Existing provider-health credentials as documented in `docs/CI_WORKFLOWS.md`.

Variables:
- `CLOUDFLARE_ACCOUNT_ID`
- `NEXT_PUBLIC_SUPABASE_URL`
- `NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY`
- `NEXT_PUBLIC_VINZ_API_URL`

Set GitHub environment `staging` and `production`; put Cloudflare credentials in both environments. Production should require an environment reviewer.

## Vercel project

Project: `vinz-web`, root directory `web`, framework Next.js. The project is connected to `nastechtest/Vinz` and has the public Supabase URL, publishable key, and Worker API URL configured in Vercel environments.

Canonical web URL: https://vinz-web-nastechresearch.vercel.app

The callback route is `/auth/callback`. Add these Supabase Auth URL settings:
- Site URL: `https://vinz-web-nastechresearch.vercel.app`
- Additional Redirect URL: `https://vinz-web-nastechresearch.vercel.app/auth/callback`
- For preview testing, add only the exact trusted Vercel preview domains required.

## Verification

The API smoke test requires `/health` to report `configured=true` and `backup_storage=true`. Browser auth and authenticated API operations require the Supabase email/OTP settings and allowed-origin policy to be tested against the deployed URL. Never expose service-role keys to the browser.
