---
name: pipepipe-sensitive-redact
description: >-
  Scan unpushed commits for sensitive data per AGENTS.md and redact on
  explicit request: read-only grep audit by default, then either a
  forward redaction commit or, only with explicit user override, a
  history rewrite with backup, tree-filter, verification, and ref purge.
  CRITICAL: NEVER rewrite history autonomously; redaction requires an
  explicit user choice of method and scope.
---

# PipePipe: Sensitive-Data Scan and History Redaction

> [!CAUTION]
> **Scanning is read-only and safe. Redaction is destructive — DO NOT RUN
> IT AUTONOMOUSLY.** Redact only when the user explicitly asks (e.g.,
> "erase these hits from history"). Always confirm the **method**
> (forward commit vs history rewrite) and the **scope** before touching
> anything. History rewrite violates the AGENTS.md forward-only rule and
> is allowed solely as an explicit user override. Never push; report and
> wait for review.

This skill covers the full loop: audit every unpushed commit against the
AGENTS.md public-repo criteria, report clean/hit/borderline, then apply
the user-chosen redaction and prove the old strings are gone from every
rewritten commit. The reference example is the phone-homes audit
(`../../../docs/phone-homes/README.md`).

## 1. Scope the unpushed range (read-only)

The root repository stays checked out on `main`. Do not switch branches.

```bash
git status --short
git branch -vv
git worktree list
git stash list
git log origin/main..HEAD --oneline --decorate
git diff --name-only origin/main..HEAD
git log --all --not --remotes --oneline --decorate
```

There should be no other unpushed refs, no stash entries, and no
untracked files relevant to the audit. Record the commit list and the
touched files; everything below operates on `origin/main..HEAD`.

## 2. Scan for sensitive data (read-only)

Criteria (AGENTS.md public-repo rule): secrets, credentials, API tokens,
personal data, physical hardware identifiers (device serials, MAC
addresses, private IPs), and local machine paths.

Run each pattern against the unified diff with zero context, then repeat
against the post-image files and the commit messages:

```bash
git diff origin/main..HEAD --unified=0 | grep -En '/home/|file:///|/Users/|C:\\' || true
git diff origin/main..HEAD --unified=0 | grep -En '192\.168\.|10\.[0-9]+\.[0-9]+\.[0-9]+|172\.(1[6-9]|2[0-9]|3[0-1])\.|127\.0\.0\.1' || true
git diff origin/main..HEAD --unified=0 | grep -En '([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}' || true
git diff origin/main..HEAD --unified=0 | grep -Eni 'password|passwd|secret|api[_-]?key|auth[_-]?token|bearer |AKIA|ghp_|gho_|github_pat_|xox[bap]-|-----BEGIN .*PRIVATE KEY' || true
git diff origin/main..HEAD --unified=0 | grep -En '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' || true
git diff origin/main..HEAD --unified=0 | grep -Eni 'serial.?number|serialNo|IMEI|IMSI|android_id|Build\.SERIAL|Build\.getSerial' || true
git log origin/main..HEAD --format='%H %an <%ae> %s%n%b%n---' | cat
```

Then check the post-image files, added-line entropy, and leak surfaces:

```bash
for f in $(git diff --name-only origin/main..HEAD); do
  grep -En '/home/|file:///|BEGIN .*PRIVATE KEY|AKIA|ghp_' "$f" || true
done
git diff origin/main..HEAD --unified=0 | grep '^+' | grep -v '^+++' | grep -Eo '[A-Za-z0-9_+/=-]{30,}' | sort -u
git diff origin/main..HEAD --unified=0 | grep '^+' | grep -Eo 'https?://[^ )"]+' | sort -u
git diff origin/main..HEAD | grep -i "$USER\|$(hostname -s)" || true  # expand locally; never hardcode names
```

Classify the findings:

- **Clean**: no hits in any category.
- **Hit**: any secret, token, private key, email, serial, MAC, private
  IP, or machine-specific path — must be redacted.
- **Borderline**: instance-specific but not categorically banned
  (exact incident timestamps, PID, `versionCode`, `Process-Runtime`,
  OS thread IDs, lock-address hashes, unrelated third-party package
  names). List each item with the file and line, explain why it is not
  a hard violation, and let the user decide the scope.

Report the verdict before any redaction.

## 3. Redact (explicit user choice only)

Ask the user for **method** and **scope** (use the question tool; do not
proceed on assumptions):

- Method: **forward redaction commit (default, AGENTS-compliant)** vs
  **history rewrite (explicit override only)**.
- Scope: **full anonymization** (generalize timestamps, PID,
  `versionCode`, runtime, thread IDs, lock hashes; drop unrelated
  third-party names) vs narrower options (third-party plus IDs only,
  timestamps only).

Reference redaction shape (adapt to the actual file; keep technical
meaning, drop instance identity):

| Before | After |
|---|---|
| Exact incident clock | Fuzzy date (`In October 2026,`) |
| Exact dropbox print timestamp | Placeholder (`<timestamp>`) |
| Logcat window clocks (`main`/`system` start times) | Drop the clocks, keep "rotated past the window" |
| `versionCode` / `PID` | Drop both, keep public `version` |
| `Process-Runtime: <exact>` + exact duration | `Process-Runtime: <redacted>`, `shortly before` |
| Unrelated third-party package names | `unrelated third-party apps` |
| `tid=N` / `held by thread N` | Role names (`main thread`, `capture thread`) |
| Lock hashes (`<0x…>`) | Distinct placeholders (`<lock-A>`, `<lock-E>`) |
| Exact runtime phrasing | `The short process runtime` |

### 3a. Forward redaction (default)

Edit the files (docs-only and skill edits go directly on `main` per the
AGENTS.md documentation exception; code edits go through a dedicated
worktree via `scripts/worktree.py`), stage with `git add`, and commit via
the wrapper (see `../../../scripts/commit.py`) — never rewrite. Verify
per §4, report, and stop without pushing.

### 3b. History rewrite (explicit override only)

The user has acknowledged the forward-only violation. Work in the root
repository (this is a `main`-history operation, not feature work):

1. Back up: `git branch backup/main-pre-redact-<yyyymmdd> main`.
2. Write a temp redaction script outside the repo that applies the
   scope table to the affected file only (plain `str.replace` pairs;
   warn on any pattern not found). Test it on a copy first and inspect
   `git diff` for grammar damage.
3. Rewrite the unpushed range, preserving merges:

   ```bash
   export FILTER_BRANCH_SQUELCH_WARNING=1
   git filter-branch --force --tree-filter 'python3 <tmp>/redact_readme.py' origin/main..HEAD
   ```

4. Verify per §4. If anything is wrong, restore from the backup branch.
5. Only after verification passes, purge the old objects:

   ```bash
   git update-ref -d refs/original/refs/heads/main
   git branch -D backup/main-pre-redact-<yyyymmdd>
   git reflog expire --expire=now --all
   git gc --prune=now
   ```

   After this point recovery is impossible — confirm the user
   understands before purging.

## 4. Verify and report — nothing automatic after this

```bash
git rev-list origin/main..HEAD | while read c; do
  git show "$c:<file>" | grep -En '<each-removed-literal>' && echo "HIT in $c" || echo "clean $c"
done
git diff origin/main..HEAD --unified=0 | grep -En '<each-removed-literal>' && echo HITS || echo CLEAN
git diff <backup-or-origin>..HEAD --stat
git diff <backup-or-origin>..HEAD -- PipePipeClient/ PipePipeExtractor/   # must be empty for a docs-only redaction
git status --short
git log origin/main..HEAD --oneline --decorate
```

Report: method used (noting any AGENTS.md exception), scope table
applied, per-commit clean proof, code-tree equivalence
(`PipePipeClient/`/`PipePipeExtractor/` empty diff when docs-only), new
hashes, confirmation the backup and `refs/original` refs were purged
(rewrite flow), and that nothing was pushed. Offer next steps
(`git push origin main`) without running them.

## Notes

- This skill never covers pushing, merging into `main`, or worktree
  deletion — those are separate skills and separate explicit requests.
- Keep incident docs technically complete (lock-order proof, call paths,
  fix shape) while removing instance identity; when in doubt, generalize
  rather than delete.
