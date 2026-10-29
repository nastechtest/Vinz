#!/usr/bin/env bash
# Applies the GitHub settings the pipeline depends on but that cannot live in the repository:
#   - a branch ruleset protecting main and stable (PR required, checks required, no force-push, no deletion)
#   - a tag ruleset making v* tags immutable
#   - the "stable-production" environment with required reviewers
#
# Requires the GitHub CLI authenticated as a repository admin:
#   gh auth login && ./scripts/apply-github-settings.sh --reviewer your-github-username
#
# Safe to re-run: rulesets are matched by name and updated in place. Use --dry-run to print the payloads
# without touching anything.
set -euo pipefail

dry_run=false
repo=''

usage() {
  cat <<EOF
usage: scripts/apply-github-settings.sh [--repo owner/name] [--reviewer <username>] [--dry-run]

  --repo       repository to configure (default: the current clone's origin)
  --reviewer   GitHub user who must approve stable releases; repeatable
  --dry-run    print the payloads, change nothing
EOF
}

reviewers=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) repo=${2:?}; shift 2 ;;
    --reviewer) reviewers+=("${2:?}"); shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

die() { printf '::error::%s\n' "$*" >&2; exit 1; }
say() { printf '%s\n' "$*"; }

if [[ -z $repo ]]; then
  repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null) ||
    die "could not determine the repository; pass --repo owner/name"
fi
say "Repository: $repo"

BRANCH_RULESET_NAME='protect-main-and-stable'
TAG_RULESET_NAME='immutable-release-tags'
ENVIRONMENT='stable-production'

branch_ruleset=$(cat <<'JSON'
{
  "name": "protect-main-and-stable",
  "target": "branch",
  "enforcement": "active",
  "conditions": {
    "ref_name": {
      "include": ["refs/heads/main", "refs/heads/stable"],
      "exclude": []
    }
  },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 1,
        "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": true,
        "require_last_push_approval": true,
        "required_review_thread_resolution": true
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": true,
        "required_status_checks": [
          { "context": "Validate Gradle wrapper" },
          { "context": "Lint" },
          { "context": "CI scripts and workflows" },
          { "context": "Unit tests and debug build" },
          { "context": "Analyze (java-kotlin)" }
        ]
      }
    }
  ]
}
JSON
)

tag_ruleset=$(cat <<'JSON'
{
  "name": "immutable-release-tags",
  "target": "tag",
  "enforcement": "active",
  "conditions": {
    "ref_name": {
      "include": ["refs/tags/v*"],
      "exclude": []
    }
  },
  "rules": [
    { "type": "deletion" },
    { "type": "update" }
  ]
}
JSON
)

if [[ $dry_run == true ]]; then
  say ""
  say "=== ruleset: $BRANCH_RULESET_NAME ==="
  printf '%s\n' "$branch_ruleset"
  say ""
  say "=== ruleset: $TAG_RULESET_NAME ==="
  printf '%s\n' "$tag_ruleset"
  say ""
  say "=== environment: $ENVIRONMENT ==="
  say "required reviewers: ${reviewers[*]:-<none given>}"
  say "deployment branch policy: stable only"
  exit 0
fi

command -v gh >/dev/null || die "the GitHub CLI (gh) is required"
is_admin=$(gh api "repos/$repo" --jq '.permissions.admin' 2>/dev/null || echo false)
[[ $is_admin == true ]] || die "this token is not a repository admin; rulesets and environments cannot be configured"

upsert_ruleset() { # <name> <json>
  local name=$1 payload=$2 existing_id
  existing_id=$(gh api "repos/$repo/rulesets" --jq ".[] | select(.name == \"$name\") | .id" | head -1)
  if [[ -n $existing_id ]]; then
    printf '%s' "$payload" | gh api --method PUT "repos/$repo/rulesets/$existing_id" --input - >/dev/null
    say "updated ruleset: $name (id $existing_id)"
  else
    printf '%s' "$payload" | gh api --method POST "repos/$repo/rulesets" --input - >/dev/null
    say "created ruleset: $name"
  fi
}

upsert_ruleset "$BRANCH_RULESET_NAME" "$branch_ruleset"
upsert_ruleset "$TAG_RULESET_NAME" "$tag_ruleset"

# The environment is what makes a stable release wait for a human. Without required reviewers, the
# stable-release workflow would publish immediately, so this is not optional.
reviewer_json='[]'
if [[ ${#reviewers[@]} -gt 0 ]]; then
  ids=()
  for login in "${reviewers[@]}"; do
    id=$(gh api "users/$login" --jq .id 2>/dev/null) || die "unknown GitHub user: $login"
    ids+=("{\"type\":\"User\",\"id\":$id}")
  done
  reviewer_json="[$(IFS=,; echo "${ids[*]}")]"
fi

printf '%s' "{
  \"reviewers\": $reviewer_json,
  \"deployment_branch_policy\": { \"protected_branches\": false, \"custom_branch_policies\": true }
}" | gh api --method PUT "repos/$repo/environments/$ENVIRONMENT" --input - >/dev/null
say "configured environment: $ENVIRONMENT (reviewers: ${reviewers[*]:-none yet})"

if gh api --method POST "repos/$repo/environments/$ENVIRONMENT/deployment-branch-policies" \
     -f name=stable -f type=branch >/dev/null 2>&1; then
  say "deployment branch policy: stable only"
else
  say "deployment branch policy: already present (or unchanged)"
fi

if [[ ${#reviewers[@]} -eq 0 ]]; then
  say ""
  say "NO REVIEWERS WERE SET. Re-run with --reviewer <username>, or stable releases will not require approval."
fi

say ""
say "Done. Verify in Settings -> Rules and Settings -> Environments."
