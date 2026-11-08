# Vinz CI workflows and provider operations

The repository keeps the Android app, Cloudflare Worker and Supabase Edge Function on separate validation paths. All workflows use read-only permissions by default. Deployments are explicit, scoped to staging and never run from an ordinary pull request.

## Workflows

| Workflow | Trigger | What it does |
|---|---|---|
| `PR checks` | Pull requests | Existing Android wrapper validation, scripts, lint, unit tests and debug APK build |
| `Main build` | Push to `main` | Existing Android tests, lint, APK build and rolling development prerelease |
| `Icons & shapes regression` | Pull requests | Existing Android emulator UI regression test |
| `CodeQL` | PR, push, weekly | Existing Kotlin/Java security analysis |
| `Platform backend checks` | Backend/workflow changes, manual | Node 22 syntax checks, Cloudflare Worker tests, Supabase migration filename/order checks and YAML/patch hygiene |
| `Connected provider health` | Weekly Monday, manual | Non-mutating authentication checks for Cloudflare, Supabase, Linear and PostHog |
| `Deploy Cloudflare staging API` | Manual only | Re-runs Worker tests and uploads only `staging`; requires the exact confirmation phrase |
| `Vinz platform deployment` | Manual only | Deploys staging or production Worker after tests; production requires `deploy-production` and a protected environment |
| `Vinz web checks and Cloudflare deployment` | PR, main push, manual | Builds the Next.js portal with vinext for Cloudflare Workers; production deploy is manual and targets `home.vinzmusic.workers.dev` |
| `Deploy Vinz Supabase Edge Function` | Manual only | Deploys only `vinz-discovery` after the exact confirmation phrase; does not push migrations |
| Dependabot | Weekly Monday | Gradle, Cloudflare Worker npm dependencies and pinned GitHub Actions updates |

A skipped provider check means its GitHub secret or variable has not been configured. A failed check means the credential is present but the provider rejected the request or could not be reached. Tokens are never printed.

## GitHub Actions secrets and variables

Add these under **Repository → Settings → Secrets and variables → Actions**. Use **Secrets** for credentials and **Variables** for non-secret identifiers/configuration. Never paste secret values into a chat or commit them to the repository.

### Provider health checks

| Provider | Secret | Repository variable |
|---|---|---|
| Cloudflare | `CLOUDFLARE_API_TOKEN` | `CLOUDFLARE_ACCOUNT_ID` |
| Supabase | `SUPABASE_ACCESS_TOKEN` | `SUPABASE_PROJECT_REF` |
| Linear | `LINEAR_API_KEY` | — |
| PostHog | `POSTHOG_PERSONAL_API_KEY` | `POSTHOG_HOST` (for example `https://us.i.posthog.com`), `POSTHOG_PROJECT_KEY` (public ingestion key) |

The health workflow checks each provider independently. Configure only the integrations Vinz is authorized to use; unconfigured providers are reported as skipped.

### Cloudflare Worker deployments

The web frontend is hosted on Cloudflare Workers at `https://home.vinzmusic.workers.dev`; the API is a separate Worker at `https://api.vinzmusic.workers.dev`. Web builds use `npm run check:vinext` and `npm run build:vinext`; only the dedicated Cloudflare web workflow deploys the `home` Worker, and it requires the explicit `deploy-home-production` confirmation. No Vercel credentials or deployment integration are used.



The staging and production deploy workflows use `CLOUDFLARE_ACCOUNT_ID` and the `POSTHOG_PROJECT_KEY` repository variable (optional for telemetry). The Worker URL, publishable Supabase key, PostHog host, R2 bucket, KV namespace and compatibility date are maintained in `platform/cloudflare/wrangler.jsonc`, which CI validates and the deployment workflow reads as its source of truth. The deploy injects the public PostHog project ingestion key at deploy time; it is not committed to source.

It reuses `CLOUDFLARE_API_TOKEN` from the provider health section. The Cloudflare token must be scoped to the Vinz account and have permission to edit Workers scripts. The workflow binds the existing `vinz-backups` R2 bucket and `VINZ_CATALOG_CACHE` KV namespace, then deploys only `staging`.

The production deploy requires `confirm_production=deploy-production`. Create GitHub Actions environments named `staging` and `production`; require reviewers on `production` under **Settings → Environments**. The workflow does not create routes, change DNS, or deploy Supabase migrations. The committed Wrangler config contains only public configuration and resource identifiers; never put service-role keys, access tokens, SMTP credentials or signing keys in it.

## Safe operating rules

1. PR and scheduled checks never deploy or mutate production resources.
2. Cloudflare staging deployment is manual and requires typing `deploy-staging`; production requires `deploy-production` and a protected environment.
3. Supabase migrations are validated for naming/order in CI but are not auto-applied to the live database. The Edge Function deploy is separate and manual.
4. Dependabot proposes dependency updates as pull requests; it does not silently upgrade production.
5. Review major Android Gradle Plugin, Kotlin, Gradle, Node and GitHub Action updates before merging.
6. Keep provider tokens least-privileged and rotate/revoke them if exposed.
7. Keep the production Worker and database migrations behind a separately reviewed release step.

## How to run

- Run **Platform backend checks** from the Actions tab after backend changes.
- Run **Connected provider health** after adding or rotating provider credentials.
- Run **Deploy Cloudflare staging API** only when staging should receive the current reviewed branch source; enter `deploy-staging` when prompted.
- Run **Vinz platform deployment** to deploy a Worker; select staging or production and confirm production explicitly.
- Run **Deploy Vinz Supabase Edge Function** to publish the reviewed `vinz-discovery` source; enter `deploy-vinz-discovery` when prompted.
- Dependabot opens weekly update PRs automatically; use the existing PR checks before merging.
