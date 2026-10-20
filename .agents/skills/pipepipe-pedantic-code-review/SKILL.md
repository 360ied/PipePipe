---
name: pipepipe-pedantic-code-review
description: >-
  Execute an adversarial pedantic code review of changes, diffs, branches, or
  files in the PipePipe repository: launch one or more independent reviewer
  subagents to identify issues, then provide a critical assessment of the
  findings. Use whenever the user asks for a "pedantic code review",
  "pedantic review", "parallel pedantic review", "parallelized code review",
  or exhaustive line-by-line inspection of code.
---

# PipePipe: Pedantic Code Review

Launch independent reviewer subagent(s) to conduct an exhaustive,
line-by-line review of changes, followed by an adversarial critical
assessment by the parent agent. The parent evaluates the change size and
chooses one reviewer or a single batched fan-out of slice reviewers.

## 1. Identify Review Target

Identify the target directory and scope:
- **Default scope**: Everything the active worktree touches against its fork-point from `main` (all branch commits since `git merge-base main HEAD`, staged and unstaged modifications, and any untracked files). Identify the active worktree path (e.g., `.worktrees/<branch-name>`). NEVER diff directly against `main` tip — `main` is a moving target and that produces "phantom deletions" for upstream changes made after the worktree branched.
- **Narrowed scope**: A specific file, diff, or commit range explicitly requested by the user.

## 2. Enumerate Changes and Size the Review

Before spawning reviewers, compute the changed-file set inside the worktree:

```bash
git diff main...HEAD --name-only
git status --porcelain
git diff main...HEAD --stat
```
(`main...HEAD` three-dot form diffs against the fork-point, i.e. `git merge-base main HEAD`.)

Evaluate the correct number of reviewers from the result:

- **Single reviewer**: change is ≤2 files or ≤200 diff lines — parallel overhead is not worth it. Run the §3 single-reviewer prompt.
- **Fan-out**: larger changes — partition into 2–6 slices of roughly equal size (~3–5 files or ~400 diff lines each). Never exceed 8 slices unless the user approves; overlapping caller inspection already multiplies I/O.
  - Group co-located files (same subsystem: `PipePipeClient/app/`, `PipePipeExtractor/`, build scripts) so caller context stays local to a slice.
  - Exclude generated/binary output (e.g., `build/`, `*.apk`); include untracked source files.

## 3. Launch Reviewer Subagent(s)

No builds, lint, or tests mid-flight. Each task is self-contained
(slice file list, worktree path, read-only instructions).

**Single reviewer** (replacing `<target-description-and-path>` with the
concrete worktree path and review scope):

```text
Perform an exhaustive, line-by-line pedantic code review of <target-description-and-path>.

Instructions:
- Inspect enclosing files and callers rather than viewing diff hunks in isolation.
- "See something, say something": if you stumble upon pre-existing defects, latent bugs, or hazards in surrounding code (even if not caused by the current changes), flag them as incidental findings.
- Check both committed changes (`git diff main...HEAD`) and untracked/modified working tree files.
- For extractor or playback changes, verify behavior at the extractor/app boundary (service, cache, and UI callers) rather than in isolation.
- This is a strictly read-only review: do not edit files, stage commits, or attempt fixes; your sole purpose is to identify and report issues.
- Do not fabricate issues or report false positives. If no issues exist within a category, explicitly state that none were identified.

Report format:
- Group findings into two tiers:
  - [DEFECT]: Functional, behavioral, or safety issues (e.g., correctness, race conditions, resource leaks, error handling, extractor/app contract breaks).
  - [PEDANTIC]: Craftsmanship, standards, and stylistic issues (e.g., naming precision, conventions, visibility, dead code, documentation, micro-hygiene).
- Identify each issue with clickable markdown links in [`<file>:<line>`](file://<absolute-path>#L<line>) format, exact line numbers, and a clear description of the problem (note if incidental/pre-existing).
- Send your complete report back to the caller.
```

**Fan-out** (one `task` call with a single `tasks[]` batch — one
`reviewer` per slice, all slices in the same batch; shared contract
across slices: the report format below). Per-slice prompt (replace
bracketed placeholders):

```text
Perform an exhaustive, line-by-line pedantic code review of the following
slice of <target-description> in worktree <worktree-path>. Your slice owns
these files: <slice-file-list> (full diff: `git diff main...HEAD -- <slice-files>`).

Instructions:
- Your slice owns the files above, but inspect enclosing files and callers
  regardless of slice ownership rather than viewing diff hunks in isolation.
  Other reviewers cover other slices; flag what you see even if the
  counterpart lives outside your slice, and note the cross-slice reference.
- "See something, say something": if you stumble upon pre-existing defects,
  latent bugs, or hazards in surrounding code (even if not caused by the
  current changes), flag them as incidental findings.
- Check both committed changes (`git diff main...HEAD -- <slice-files>`) and
  untracked/modified working tree files in your slice.
- For extractor or playback changes, verify behavior at the extractor/app boundary (service, cache, and UI callers) rather than in isolation.
- This is a strictly read-only review: do not edit files, stage commits, or
  attempt fixes; your sole purpose is to identify and report issues.
- Do not fabricate issues or report false positives. If no issues exist
  within a category, explicitly state that none were identified.

Report format:
- Group findings into two tiers:
  - [DEFECT]: Functional, behavioral, or safety issues (e.g., correctness,
    race conditions, resource leaks, error handling, extractor/app contract breaks).
  - [PEDANTIC]: Craftsmanship, standards, and stylistic issues (e.g., naming
    precision, conventions, visibility, dead code, documentation,
    micro-hygiene).
- Identify each issue with clickable markdown links in
  [`<file>:<line>`](file://<absolute-path>#L<line>) format, exact line
  numbers, and a clear description of the problem (note if
  incidental/pre-existing or cross-slice).
- Send your complete report back to the caller.
```

## 4. Deduplicate, Assess & Report

Do not merely relay or concatenate the subagent report(s). Overlapping
caller inspection means slice reports will state the same defect twice —
merge them:

1. **Deduplicate** (fan-out only): collapse identical or overlapping findings (same file:line, same root cause) into one entry; record which slices reported it as corroboration, not as separate issues.
2. **Evaluate Findings**:
   - **Concurred**: Validate legitimate defects and pedantic issues that warrant remediation.
   - **Contested / False Positives**: Provide technical rebuttals and rationale for findings that misinterpret design intent, reflect false positives, or involve intentional trade-offs.
   - **Incidental / Pre-existing**: Note whether any valid findings were pre-existing vs. introduced by the current worktree changes.
3. **Present to User**: Deliver the findings (with slice attribution for fan-out) plus your critical assessment, identifying actionable next steps.
