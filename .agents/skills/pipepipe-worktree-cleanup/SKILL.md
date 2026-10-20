---
name: pipepipe-worktree-cleanup
description: >-
  Safely clean up and teardown Git worktrees for the PipePipe repository
  via scripts/worktree.py cleanup: preview with --dry-run, remove only
  clean worktrees merged into main by default (deleting their merged
  local branches too), opt in to unmerged or dirty
  removal with explicit flags. CRITICAL: ONLY use when the user EXPLICITLY
  asks to clean up or remove worktrees. NEVER invoke this skill autonomously
  or as part of task completion.
---

# PipePipe: Worktree Cleanup

This skill covers safe teardown and cleanup of Git worktrees in the PipePipe
repository via the non-interactive `cleanup` subcommand.

> [!CAUTION]
> **DO NOT RUN AUTONOMOUSLY.** Worktree teardown must be **explicitly requested by the user** (e.g., "clean up worktrees", "remove the worktree for branch X"). Agents must NEVER autonomously delete worktrees upon completing a feature, bugfix, or test suite. Task completion ends when commits and verification (`./scripts/check.sh`) are done inside the dedicated worktree; always leave the branch and worktree intact, report completion, and wait for review.

Worktrees allow isolated development on dedicated branches without touching
the primary repository tree (which stays permanently checked out on `main`).
Worktree removal also deletes the worktree's local branch when it is fully merged
into `main` (unless `--keep-branch` or protected). The primary root
worktree (`main`) must **never** be removed.

## 1. Preview (always run first)

```bash
./scripts/worktree.py cleanup --dry-run
```

This inventories all secondary worktrees (typically `.worktrees/<branch-name>`)
and classifies each as merged/unmerged and clean/dirty without deleting anything.
The preview also predicts branch deletion. If no secondary worktrees exist it reports that only the root worktree exists.
Include the preview output in your report to the user.

## 2. Remove (flag-driven, non-interactive)

There is no interactive prompt. Destructive scope is controlled entirely by
explicit flags (`-n` is short for `--dry-run`, `-f` for `--force`):

```bash
./scripts/worktree.py cleanup                                  # clean and merged only (default; safe w.r.t. tracked work)
./scripts/worktree.py cleanup --include-unmerged               # also remove clean worktrees with unmerged commits
./scripts/worktree.py cleanup --force                          # also remove dirty-but-merged worktrees
./scripts/worktree.py cleanup --include-unmerged --force       # remove dirty AND unmerged (requires both flags)
./scripts/worktree.py cleanup --keep-branch                    # remove worktrees but preserve all branches
./scripts/worktree.py cleanup --dry-run <branch-or-path>...    # preview specific worktrees
./scripts/worktree.py cleanup <branch-or-path>...              # target specific worktrees
```

Rules:

- Default removes only **clean worktrees whose branch is fully merged** into local `main` (`git merge-base --is-ancestor <branch> main`). Safe w.r.t. tracked commits and changes; ignored build artifacts inside the worktree directory are discarded with it.
- A removed worktree's **local branch is deleted as well when fully merged** (safe `git branch -d`, which refuses otherwise). `main`/`master` (plus internal `HEAD`), detached checkouts, and unmerged branches are never deleted; pass `--keep-branch` to preserve every branch.
- Dirty means staged, unstaged, or untracked (`??`) changes; ignored files do not count.
- Detached-HEAD worktrees have no branch to test, so they count as unmerged: clean detached needs `--include-unmerged`, dirty detached needs both flags.
- A dirty AND unmerged worktree requires **both** `--include-unmerged` and `--force`.
- An explicitly named worktree that is skipped under the given flags exits non-zero (except in `--dry-run`, which only previews and exits zero); bulk mode preserves skipped worktrees and exits zero unless a removal itself fails.
- Locked worktrees are always preserved (unlock first). The worktree you are currently inside is never removed.
- `remove` applies the same branch rule: the worktree's branch is deleted when fully merged unless `--keep-branch` is given (aliases: `rm`; `-f` for `--force`).
- Aliases from the usage text hold throughout: `clean` for `cleanup`, `ls` for `list`.
- The command prunes worktree metadata and sweeps leftover empty directories under `.worktrees/` automatically.

## 3. Verify and report

```bash
./scripts/worktree.py list
git status
git branch
```

Report which worktrees were removed, which branches were deleted vs preserved (and why); a branch is gone only when it was fully merged.
