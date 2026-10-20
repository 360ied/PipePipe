---
name: pipepipe-merge
description: >-
  Merge a feature/bugfix branch into main for the PipePipe client:
  pre-merge verification with scripts/check.sh, a fast-forward for
  single-commit branches, otherwise a non-fast-forward merge commit
  formatted via commit.py, and a post-merge status report. CRITICAL: ONLY
  use when the user EXPLICITLY asks to merge a branch into main. NEVER
  invoke this skill autonomously or as part of task completion.
---

# PipePipe: Merge a Branch into Main

> [!CAUTION]
> **DO NOT RUN AUTONOMOUSLY.** Merging into `main` must be **explicitly requested by the user** (e.g., "merge this branch", "land into main"). Agents must NEVER autonomously merge into `main` upon completing a feature, bugfix, or test suite. Task completion ends when commits and verification are done inside the dedicated worktree.

Most merges into `main` are **non-fast-forward merge commits** created
with plain `git merge --no-ff` — never squash, never rebase, never
force-push (AGENTS.md "Forward-Only History"). Exception: a branch with
exactly one commit is fast-forwarded when possible (`git merge
--ff-only`) and gets no merge commit. A merge commit's message is
formatted through `scripts/commit.py` so it follows the 50/72 rule and
the tripartite body convention.

## 1. Pre-flight

- Identify the branch: either the current worktree's branch (`git branch --show-current`),
  or the branch named by the user. It must not be `main`.
- Confirm the root repository is clean — the merge runs there, so run
  `git status --porcelain` in the root repository, not the feature worktree.
  Refuse to merge with uncommitted changes (or stash only if the user asks).
- Review what is being merged: `git log main..<branch> --oneline`. If the
  log contains anything beyond what the user described, flag it before
  proceeding.

## 2. Verify the feature branch

Run the pre-completion check **inside the dedicated worktree** before merging (from the repository root):

```bash
(cd ".worktrees/<branch>" && ./scripts/check.sh)
```

If it fails, stop. The branch must pass before it lands on `main`.
(Do not merge first and "fix forward" — every commit on `main` must leave
the tree working.)

## 3. Update main

In the root repository (which stays checked out on `main`):

```bash
git fetch origin
git merge --ff-only origin/main
```

If the root repository is not on main, check it out first (`git checkout main`).
`--ff-only` guarantees main is only fast-forwarded to origin — no local
merge commits, no rebases.

## 4. Merge

Count the branch's commits: `git rev-list --count main..<branch>`.

### Fast-forward (single-commit branches)

If the count is exactly **1**, land the branch as a fast-forward — no
merge commit, no wrapper:

```bash
git merge --ff-only <branch>
```

If `--ff-only` fails (main has moved since the branch diverged), fall
back to the non-fast-forward flow below. The single commit's own
tripartite body already documents the change; duplicating it in a merge
message is not warranted.

### Non-fast-forward (everything else)

Stage the merge without committing, so the message goes through the
wrapper:

```bash
git merge --no-ff --no-commit <branch>
```

If conflicts appear, resolve them, `git add` the results, and proceed to the wrapper commit below — never `git merge --continue`, which would commit with the default message and bypass the wrapper.
If the user wants out instead: `git merge --abort`.

Then create the merge commit via the wrapper (this runs `git commit` with
the formatted message; `MERGE_HEAD` present makes it a merge commit):

```bash
python3 scripts/commit.py -m "<subject>

Context & Motivation: <why this branch is landing on main>

Technical Approach: <summary of the branch's commits and how they fit together>

Edge Cases & Impact: <how it was verified, e.g. check.sh, unit tests, manual device test>"
```

Message rules:

- **Subject**: `chore: merge branch '<branch>'`. Merge commits are
  exempt from the 50-char limit in `scripts/commit.py`, so use the full
  branch name directly.
- **Body**: full tripartite format (labels exact, in that order). Because
  the subject does not start with `Merge `, `commit.py` enforces the body
  check — do not use a bare `Merge branch '...'` subject, and do not pass
  `--no-body-check`.
- Distill the branch's individual commit bodies into the merge message; do
  not paste them verbatim.

## 5. Verify and report — nothing automatic after this

- `git log -1` — for the non-fast-forward flow, confirm the merge commit
  has two parents and the tripartite body; for a fast-forward, confirm
  `git log -1` is the branch's single commit.
- `git status` — clean tree, nothing left staged.

Do **not** push or delete branches on your own. Instead, report success and
offer the commands for the user to run (or ask before running them):

```bash
./scripts/worktree.py remove <branch>  # remove worktree if one was used
git push origin main
git branch -d <branch>                 # local cleanup
git push origin --delete <branch>      # remote cleanup, if pushed
```

## Notes

- Merging `main` into a feature branch mid-work is allowed (plain
  `git merge --no-ff main` from the feature branch) but is not this
  skill's job — this skill is only for landing a branch on `main`.
- The AGENTS.md exception permitting direct edits on the current branch
  applies only to `AGENTS.md` itself; everything else goes through a
  dedicated branch and this merge flow.
