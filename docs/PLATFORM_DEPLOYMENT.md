# Vinz platform deployment

## Production architecture

- **Cloudflare Workers**: Vinz web frontend at `https://home.vinzmusic.workers.dev` (Worker name `home`, account subdomain `vinzmusic`).
- **Cloudflare Workers**: Vinz API at `https://api.vinzmusic.workers.dev`. The web frontend and API are separate Workers.
- **Supabase**: Auth, OTP, Postgres, RLS, and the `vinz-discovery` Edge Function.
- **Cloudflare R2**: private backup objects in `vinz-backups`.
- **Cloudflare KV**: catalog cache.
- **PostHog**: operational error telemetry only; the API intentionally excludes user identifiers, emails, tokens, lyrics, and music queries.

Vinz uses Cloudflare Workers for both web and API hosting. Vercel is not a build, preview, rollback, or production dependency; do not configure or rely on a Vercel project for Vinz.

## Web build and deploy

The Next.js web app remains in `web/` and is built and deployed exclusively to Cloudflare Workers using vinext.

- `web/vite.config.ts`: vinext + Cloudflare Vite plugin.
- `web/cloudflare.config.ts`: Worker name is explicitly `home`; `workersDev: true` enables its `workers.dev` route.
- `web/package.json`: `dev:vinext`, `check:vinext`, `build:vinext`, `start:vinext`, and `deploy:cloudflare`.
- `.github/workflows/cloudflare-web.yml`: pull-request and main-branch build checks. Production deployment is manual and requires the exact confirmation string `deploy-home-production` in the workflow dispatch input. It deploys only the `home` Worker.

The expected frontend URL is exactly:

`https://home.vinzmusic.workers.dev`

The API URL remains:

`https://api.vinzmusic.workers.dev`

## GitHub Actions configuration

Repository/environment variables:
Cloudflare account ID is pinned in `web/cloudflare.config.ts` to the verified Vinz account; it is not a GitHub variable.
- `NEXT_PUBLIC_SUPABASE_URL` — optional override; defaults to `https://iszghebmetrbkfhnnoqq.supabase.co` in the workflow.
- `NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY` — optional GitHub Actions variable override. If unset, the workflow uses the verified Supabase publishable key for project `iszghebmetrbkfhnnoqq` (this key is designed for public clients; RLS remains the data-access boundary).
- `NEXT_PUBLIC_VINZ_API_URL` — optional override; defaults to `https://api.vinzmusic.workers.dev` in the workflow.

GitHub environment secret (still required before the production deploy can run):
- `CLOUDFLARE_API_TOKEN` — scoped to deploy Workers scripts and read required account metadata. Never put it in repository variables or source files. The Cloudflare CLI also receives `CF_SEND_TELEMETRY=false` in CI.

Set a GitHub `production` environment and require a reviewer if available. The workflow does not deploy on PR pushes or ordinary pushes to `main`; it only deploys after an explicit workflow dispatch confirmation.

## Supabase Auth URLs

Before enabling sign-in on the new host, update Supabase Auth URL configuration:

- Site URL: `https://home.vinzmusic.workers.dev`
- Additional Redirect URL: `https://home.vinzmusic.workers.dev/auth/callback`

Retain any other callback URLs only while they are intentionally used for development or rollback. Google OAuth's authorized redirect URI remains the Supabase Auth callback URL, not the Workers hostname.

## Verification checklist

- [ ] Cloudflare build and vinext compatibility check pass.
- [ ] Worker deploy creates `home.vinzmusic.workers.dev` and serves the app.
- [ ] `/auth/callback` completes OTP and Google auth redirects.
- [ ] Signup, email OTP, resend, password recovery and Google sign-in work.
- [ ] Profile routes, username availability RPC and avatar storage work.
- [ ] Web-to-API calls use `api.vinzmusic.workers.dev`.
- [ ] Android local playback remains independent of cloud account sign-in.
- [ ] No production deployment until PR review and Supabase redirect allowlist are complete.
- [ ] No Vercel project, Git integration, environment variables, preview URL, or production URL is required for Vinz.

## Existing API deployment

- `.github/workflows/vinz-platform-deploy.yml`: manual staging/production Worker deployment with tests and post-deploy health check.
- `.github/workflows/cloudflare-staging-deploy.yml`: staging API only.
- Existing platform checks and provider health workflows remain separate.

Production API deployment requires `confirm_production=deploy-production`. Supabase migrations are not automatically applied by a web or Worker deployment; database migrations require their own reviewed migration release.
