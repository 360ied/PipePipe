#!/usr/bin/env python3
"""
Git 50/72 Commit Wrapper with AGENTS.md body enforcement.

Wraps `git commit` by automatically formatting and validating commit messages:
  - Subject line: <= 50 characters, concise and imperative, no trailing period.
    (Merge commits are exempt from the 50-character subject limit).
  - Line 2: Exactly one blank line between subject and body.
  - Body: Wrapped to <= 72 characters per line (with hanging indents for lists,
    verbatim code blocks, URLs, markdown tables, and Git trailers).
  - Body format: MUST contain the three labeled sections below, labels exact,
    in this order, as plain line starts (no Markdown bullets/headings):

      Context & Motivation: <why this change is needed>
      Technical Approach: <how it is implemented>
      Edge Cases & Impact: <boundary conditions, blast radius>

    Merge/revert/fixup commits are exempt; --no-body-check bypasses the check.

Errors are instructions: on failure, stderr names the exact problem, prints the
required template, and points to AGENTS.md. Fix the message and retry.

Exit codes: 0 = committed/passed, 1 = fixable error (correct the problem and
retry), 2 = usage/environment error (do not retry, report).

Message input takes exactly one of two shapes (mixing them is rejected):
  - Whole-message mode: -m "<subject>\n\n<body>" (repeatable; chunks join
    with a blank line). A lone positional argument counts as -m.
  - Split mode: -s "<subject>" -b "<body>".

Usage (stage first with `git add` — the wrapper commits only staged changes):
  python3 scripts/commit.py -m "docs: update readme\n\nDetailed explanation..."
  python3 scripts/commit.py -s "docs: update readme" -b "Detailed explanation..."
  python3 scripts/commit.py -m "docs: update readme" -m "Body paragraph 1..." -m "Body paragraph 2..."
  python3 scripts/commit.py --check -m "..."
"""

import os
import re
import subprocess
import sys
import textwrap
from typing import List, Optional, Tuple

MAX_SUBJECT = 50
MAX_BODY = 72

# Tripartite body enforcement (AGENTS.md "Commit Messages & Detailed
# Descriptions"). The body must contain the three labeled sections, labels
# exact, in this order, as plain line starts (no Markdown decoration).
BODY_LABELS = ("Context & Motivation", "Technical Approach", "Edge Cases & Impact")
BODY_TEMPLATE = "\n\n".join(
    [
        "Context & Motivation: <why this change is needed>",
        "Technical Approach: <how it is implemented>",
        "Edge Cases & Impact: <boundary conditions, blast radius>",
    ]
)
AGENTS_POINTER = (
    "See AGENTS.md (Commit Messages & Detailed Descriptions) "
    "for the full commit style rules."
)
# Merge/revert/fixup commits are exempt from body style checks.
RE_EXEMPT_SUBJECT = re.compile(r'^(Merge |Revert "|fixup! |squash! )')
# Merge commit subject patterns exempt from MAX_SUBJECT length limit.
RE_MERGE_SUBJECT = re.compile(
    r'^(?:Merge\b|chore(?:\([^)]+\))?:\s*merge\b|merge:\s*|chore\(merge\):)',
    re.IGNORECASE,
)

# Regex patterns
RE_GIT_TRAILER = re.compile(
    r"^(Signed-off-by|Co-authored-by|Acked-by|Reviewed-by|Tested-by|"
    r"Reported-by|Suggested-by|Fixes|Closes|Resolves|Refs|See-also|"
    r"Change-Id|Bug|CC|Cc):\s+.+$",
    re.IGNORECASE,
)
RE_URL = re.compile(r"^(?:https?|ftp|file)://\S+$")
RE_CONTAINS_LONG_URL = re.compile(r"https?://\S{40,}")
RE_BULLET = re.compile(r"^(\s*)([-*+•]|\d+[.)]|\([0-9a-zA-Z]+\))\s+(.*)$")
RE_CODE_FENCE = re.compile(r"^\s*```")
RE_DIFF_HEADER = re.compile(r"^(?:diff --git|index [0-9a-f]+\.\.[0-9a-f]+|--- |\+\+\+ |@@ )")
RE_MARKDOWN_TABLE = re.compile(r"^\s*\|.*\|\s*$")


def is_verbatim(line: str) -> bool:
    """Check if a line should be preserved without line wrapping."""
    stripped = line.strip()
    if not stripped:
        return True
    if RE_CODE_FENCE.match(line):
        return True
    if RE_GIT_TRAILER.match(stripped):
        return True
    if RE_URL.match(stripped) or RE_CONTAINS_LONG_URL.search(stripped):
        return True
    if RE_DIFF_HEADER.match(stripped):
        return True
    if RE_MARKDOWN_TABLE.match(stripped):
        return True
    if line.startswith("    ") or line.startswith("\t"):
        return True
    return False


def wrap_bullet_item(indent: str, marker: str, content: str) -> List[str]:
    """Wrap a bullet list item with hanging indentation."""
    first_line_prefix = f"{indent}{marker} "
    subsequent_indent = " " * len(first_line_prefix)
    words = content.split()
    if not words:
        return [first_line_prefix.rstrip()]

    wrapped = textwrap.wrap(
        " ".join(words),
        width=MAX_BODY,
        initial_indent=first_line_prefix,
        subsequent_indent=subsequent_indent,
        break_long_words=False,
        break_on_hyphens=False,
    )
    return wrapped if wrapped else [first_line_prefix + content]


def wrap_paragraph(text: str) -> List[str]:
    """Wrap a continuous text paragraph to 72 chars width."""
    words = text.split()
    if not words:
        return []
    return textwrap.wrap(
        " ".join(words),
        width=MAX_BODY,
        break_long_words=False,
        break_on_hyphens=False,
    )


def is_git_merge_in_progress() -> bool:
    """Check if Git is currently in the middle of a merge (MERGE_HEAD exists)."""
    try:
        res = subprocess.run(
            ["git", "rev-parse", "-q", "--verify", "MERGE_HEAD"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        return res.returncode == 0
    except Exception:
        return False


def is_merge_commit(subject: str) -> bool:
    """Determine if a commit is a merge commit by subject pattern or git state."""
    if RE_MERGE_SUBJECT.match(subject):
        return True
    return is_git_merge_in_progress()


def format_commit_message(
    raw_text: str, is_merge: Optional[bool] = None
) -> Tuple[Optional[str], Optional[str]]:
    """
    Format and validate raw commit message to 50/72 rule.
    Merge commits are exempt from the MAX_SUBJECT (50 char) limit.
    Returns (formatted_message, error_string).
    """
    lines = raw_text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    # Strip comments starting with '#'
    lines = [l for l in lines if not l.startswith("#")]
    while lines and not lines[-1].strip():
        lines.pop()

    if not lines:
        return None, "Commit message is empty."

    # 1. Subject extraction
    subject_idx = 0
    while subject_idx < len(lines) and not lines[subject_idx].strip():
        subject_idx += 1

    if subject_idx >= len(lines):
        return None, "Commit message is empty."

    raw_subject = lines[subject_idx].strip()
    cleaned_subject = re.sub(r"\.+$", "", raw_subject).strip()

    if len(cleaned_subject) > MAX_SUBJECT:
        if is_merge is None:
            is_merge = is_merge_commit(cleaned_subject)
        if not is_merge:
            return None, (
                f"Subject line exceeds {MAX_SUBJECT} characters ({len(cleaned_subject)} chars):\n"
                f"  > {cleaned_subject}"
            )

    body_raw_lines = lines[subject_idx + 1 :]
    while body_raw_lines and not body_raw_lines[0].strip():
        body_raw_lines.pop(0)

    if not body_raw_lines:
        return cleaned_subject, None

    # 2. Body formatting
    formatted_body_blocks: List[List[str]] = []
    current_paragraph: List[str] = []

    def flush_paragraph():
        nonlocal current_paragraph
        if current_paragraph:
            wrapped = wrap_paragraph(" ".join(current_paragraph))
            if wrapped:
                formatted_body_blocks.append(wrapped)
            current_paragraph = []

    i = 0
    while i < len(body_raw_lines):
        line = body_raw_lines[i]
        stripped = line.strip()

        # Code block fences
        if RE_CODE_FENCE.match(line):
            flush_paragraph()
            code_block_lines = [line]
            i += 1
            while i < len(body_raw_lines):
                c_line = body_raw_lines[i]
                code_block_lines.append(c_line)
                i += 1
                if RE_CODE_FENCE.match(c_line):
                    break
            formatted_body_blocks.append(code_block_lines)
            continue

        # Blank line separates paragraphs/blocks
        if not stripped:
            flush_paragraph()
            i += 1
            continue

        # Bullet points
        bullet_match = RE_BULLET.match(line)
        if bullet_match:
            flush_paragraph()
            indent, marker, content = bullet_match.groups()
            bullet_lines_content = [content]
            i += 1
            while i < len(body_raw_lines):
                next_line = body_raw_lines[i]
                next_stripped = next_line.strip()
                if not next_stripped:
                    break
                if RE_BULLET.match(next_line) or is_verbatim(next_line):
                    break
                if next_line.startswith("  ") or not next_line.startswith("-"):
                    bullet_lines_content.append(next_stripped)
                    i += 1
                else:
                    break
            full_bullet_content = " ".join(bullet_lines_content)
            wrapped_bullet = wrap_bullet_item(indent, marker, full_bullet_content)
            formatted_body_blocks.append(wrapped_bullet)
            continue

        # Git trailers
        if RE_GIT_TRAILER.match(stripped):
            flush_paragraph()
            trailers = [stripped]
            i += 1
            while i < len(body_raw_lines):
                next_stripped = body_raw_lines[i].strip()
                if RE_GIT_TRAILER.match(next_stripped):
                    trailers.append(next_stripped)
                    i += 1
                else:
                    break
            formatted_body_blocks.append(trailers)
            continue

        # Markdown tables
        if RE_MARKDOWN_TABLE.match(stripped):
            flush_paragraph()
            table_lines = [line]
            i += 1
            while i < len(body_raw_lines):
                next_line = body_raw_lines[i]
                if RE_MARKDOWN_TABLE.match(next_line.strip()):
                    table_lines.append(next_line)
                    i += 1
                else:
                    break
            formatted_body_blocks.append(table_lines)
            continue

        # 4-space indented blocks
        if line.startswith("    ") or line.startswith("\t"):
            flush_paragraph()
            indented_lines = [line]
            i += 1
            while i < len(body_raw_lines):
                next_line = body_raw_lines[i]
                if next_line.startswith("    ") or next_line.startswith("\t"):
                    indented_lines.append(next_line)
                    i += 1
                else:
                    break
            formatted_body_blocks.append(indented_lines)
            continue

        # Other verbatim lines (URLs, diff headers)
        if is_verbatim(line):
            flush_paragraph()
            formatted_body_blocks.append([line])
            i += 1
            continue

        # Standard paragraph line
        current_paragraph.append(stripped)
        i += 1

    flush_paragraph()

    # Combine blocks with proper single blank lines
    result_lines = [cleaned_subject, ""]
    for block_idx, block in enumerate(formatted_body_blocks):
        for l in block:
            result_lines.append(l)
        if block_idx < len(formatted_body_blocks) - 1:
            is_curr_bullet = len(block) > 0 and RE_BULLET.match(block[0])
            next_block = formatted_body_blocks[block_idx + 1]
            is_next_bullet = len(next_block) > 0 and RE_BULLET.match(next_block[0])

            if is_curr_bullet and is_next_bullet:
                pass  # Keep list items adjacent
            else:
                result_lines.append("")

    formatted_msg = "\n".join(result_lines)
    return formatted_msg, None


def validate_tripartite_body(subject: str, body: Optional[str]) -> Optional[str]:
    """
    Enforce the AGENTS.md tripartite commit body format.

    Returns None when the body is compliant (or the commit is exempt),
    otherwise returns a full error message (tagged, actionable, ending with
    the AGENTS.md pointer) for the caller to print.
    """
    if RE_EXEMPT_SUBJECT.match(subject):
        return None

    if body is None or not body.strip():
        return (
            "[body-missing] Commit message has no body. AGENTS.md requires a "
            "descriptive body with three labeled sections. Required format "
            "(labels exact, in this order):\n\n"
            f"{BODY_TEMPLATE}\n\n"
            f"{AGENTS_POINTER}"
        )

    lines = body.split("\n")
    first_idx = {}
    duplicated = []
    for label in BODY_LABELS:
        prefix = label + ":"
        idxs = [i for i, l in enumerate(lines) if l.startswith(prefix)]
        if idxs:
            first_idx[label] = idxs[0]
            if len(idxs) > 1:
                duplicated.append(label)

    missing = [label for label in BODY_LABELS if label not in first_idx]
    # A label whose text occurs mid-line was written but folded into the
    # previous paragraph (sections not separated by a blank line). That is
    # a layout problem, not an absent section: say so precisely.
    folded = [
        label
        for label in missing
        if any((label + ":") in line for line in lines)
    ]
    missing = [label for label in missing if label not in folded]
    present_order = [first_idx[label] for label in BODY_LABELS if label in first_idx]
    out_of_order = present_order != sorted(present_order)

    if not missing and not folded and not duplicated and not out_of_order:
        return None

    problems = []
    if folded:
        problems.append(
            "section(s) run together with the previous paragraph "
            "(separate sections with a blank line): "
            + ", ".join(f'"{label}:"' for label in folded)
        )
    if missing:
        problems.append(
            "missing label(s): " + ", ".join(f'"{label}:"' for label in missing)
        )
    if out_of_order:
        problems.append(
            "labels out of order (expected: " + ", ".join(BODY_LABELS) + ")"
        )
    if duplicated:
        problems.append(
            "duplicated label(s): " + ", ".join(f'"{label}:"' for label in duplicated)
        )
    return (
        "[body-structure] Commit body does not match the required "
        "three-section format.\nProblems:\n  - "
        + "\n  - ".join(problems)
        + "\n\nRequired format (labels exact, in this order):\n\n"
        f"{BODY_TEMPLATE}\n\n"
        f"{AGENTS_POINTER}"
    )


def main() -> int:
    args = sys.argv[1:]
    if not args and sys.stdin.isatty():
        print(
            "Usage: python3 scripts/commit.py -m \"<subject>\\n\\n<body>\" [extra git args...]",
            file=sys.stderr,
        )
        return 2

    messages: List[str] = []
    subject: Optional[str] = None
    body: Optional[str] = None
    check_only = False
    no_body_check = False
    forwarded_git_args: List[str] = []

    i = 0
    while i < len(args):
        arg = args[i]
        if arg in ("-m", "--message"):
            if i + 1 < len(args):
                messages.append(args[i + 1])
                i += 2
            else:
                print("Error: -m requires a message argument.", file=sys.stderr)
                return 2
        elif arg in ("-s", "--subject"):
            if i + 1 < len(args):
                subject = args[i + 1]
                i += 2
            else:
                print("Error: -s requires a subject argument.", file=sys.stderr)
                return 2
        elif arg in ("-b", "--body"):
            if i + 1 < len(args):
                body = args[i + 1]
                i += 2
            else:
                print("Error: -b requires a body argument.", file=sys.stderr)
                return 2
        elif arg in ("--check", "--lint"):
            check_only = True
            i += 1
        elif arg == "--no-body-check":
            no_body_check = True
            i += 1
        elif arg in ("-h", "--help"):
            print(__doc__)
            return 0
        elif not arg.startswith("-") and not messages and not subject:
            # Positional message string
            messages.append(arg)
            i += 1
        else:
            forwarded_git_args.append(arg)
            i += 1

    # Message-input modes are mutually exclusive: -m/--message (and a lone
    # positional argument, which counts as -m) already carries the full
    # message, so combining it with -s/--subject or -b/--body would silently
    # drop one side. Likewise -b without -s has no subject to attach to.
    # Fail fast with instructions instead of committing a body-less message.
    if messages and (subject is not None or body is not None):
        print(
            "Error: [mixed-message-flags] -m/--message cannot be combined "
            "with -s/--subject or -b/--body. -m already carries the full "
            "message (subject + body); use either -m \"...\" [-m \"...\"...] "
            "for whole-message mode or -s \"<subject>\" -b \"<body>\" for "
            "split mode, not both.",
            file=sys.stderr,
        )
        return 2
    if body is not None and subject is None:
        print(
            "Error: [body-without-subject] -b/--body requires "
            "-s/--subject: there is no subject to attach the body to. "
            "Use -s \"<subject>\" -b \"<body>\", or pass the full message "
            "via -m \"<subject>\\n\\n<body>\".",
            file=sys.stderr,
        )
        return 2

    # Build raw message
    if subject is not None:
        raw_text = subject
        if body:
            raw_text += "\n\n" + body
    elif messages:
        raw_text = "\n\n".join(messages)
    elif not sys.stdin.isatty():
        raw_text = sys.stdin.read()
    else:
        print("Error: No commit message provided.", file=sys.stderr)
        return 2

    # Format and validate
    formatted_msg, err = format_commit_message(raw_text)
    if err:
        print(f"Error: {err}", file=sys.stderr)
        return 2

    subject_line, _, body_text = formatted_msg.partition("\n\n")
    body_text = body_text if body_text.strip() else None

    if not no_body_check:
        body_err = validate_tripartite_body(subject_line, body_text)
        if body_err:
            print(f"Error: {body_err}", file=sys.stderr)
            return 1

    if check_only:
        print("OK: Commit message complies with the 50/72 rule and the AGENTS.md body format.")
        return 0

    # Pre-flight: plain `git commit` only records staged changes. Fail early
    # with instructions instead of passing git's raw "nothing to commit" through.
    # Skipped when the commit is fed by other means (a forwarded pathspec, which
    # `git commit <path>` records directly, or auto-staging flags).
    auto_fed = {"--amend", "-a", "--all"}
    if not any(
        a in auto_fed or not a.startswith("-") for a in forwarded_git_args
    ):
        staged = subprocess.run(
            ["git", "diff", "--cached", "--quiet"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if staged.returncode == 0:
            print(
                "Error: [nothing-staged] No staged changes to commit. "
                "The wrapper runs plain `git commit`, which only records "
                "what is staged. Stage your changes (`git add <paths>`, "
                "including new files), then retry the same command.",
                file=sys.stderr,
            )
            return 1

    # Execute git commit
    git_cmd = ["git", "commit", "-m", formatted_msg] + forwarded_git_args
    proc = subprocess.run(git_cmd)
    return proc.returncode


if __name__ == "__main__":
    sys.exit(main())
