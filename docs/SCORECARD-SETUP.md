# OpenSSF Scorecard Setup

The `scorecard.yml` workflow runs the [OpenSSF Scorecard](https://github.com/ossf/scorecard)
weekly and on every push to `master`. Several checks — most importantly **Branch-Protection** —
require an admin-scoped token to read settings the default `GITHUB_TOKEN` cannot see:

- `DismissStaleReviews`
- `EnforceAdmins`
- `RequireLastPushApproval`
- `RequiresStatusChecks`
- `UpToDateBeforeMerge`

Without an admin token, those settings are silently treated as missing, capping the
Branch-Protection score at Tier 1 (3/10) regardless of actual branch protection.

## One-time PAT setup

1. Go to <https://github.com/settings/tokens?type=beta> and click **Generate new token**.
2. **Resource owner**: your account (the repo owner).
3. **Repository access**: *Only select repositories* → pick `agentbridge`.
4. **Repository permissions**:
   - `Administration` → **Read-only**
   - `Contents` → **Read-only**
   - `Metadata` → **Read-only** (auto-selected)
5. **Expiration**: 1 year (set a calendar reminder to rotate).
6. Generate and copy the token.

## Add as a repo secret

1. <https://github.com/catatafishen/agentbridge/settings/secrets/actions/new>
2. **Name**: `SCORECARD_READ_TOKEN`
3. **Value**: paste the PAT.
4. Save.

The workflow will pick it up automatically on the next run. If the secret is absent, the
workflow falls back to `GITHUB_TOKEN` (with the existing reduced-score behaviour).

## Expected score after setup

With our current branch protection on `master`:

| Tier | Requirement                                  | Status |
|------|----------------------------------------------|--------|
| 1    | Prevent force push, prevent deletion         | ✅     |
| 2    | ≥1 reviewer, PR required, up-to-date, last-push approval | ✅ |
| 3    | ≥1 status check                              | ✅     |
| 4    | ≥2 reviewers, code-owner review              | ✅     |
| 5    | Dismiss stale reviews, **include admin**     | ⚠️ partial |

`enforce_admins` is intentionally **disabled** so the project owner can bypass review when
needed (e.g. emergency security patch, scorecard-only changes). This caps the score at
**9/10** — the missing point is by design.

## Verifying

Run the workflow manually after adding the secret:

```
gh workflow run scorecard.yml
```

Then check the SARIF output in the **Security → Code scanning** tab or the workflow logs:

```
gh run list --workflow=scorecard.yml --limit 1
gh run view <run-id> --log | grep -A2 'Branch-Protection'
```
