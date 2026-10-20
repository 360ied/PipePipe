#!/usr/bin/env python3
"""
Unit tests for scripts/commit.py.
"""

import io
import os
import subprocess
import sys
import tempfile
import unittest

from unittest.mock import patch, MagicMock
from scripts.commit import (
    format_commit_message,
    is_git_merge_in_progress,
    is_merge_commit,
    main,
    validate_tripartite_body,
    MAX_SUBJECT,
    MAX_BODY,
)

COMPLIANT_BODY = (
    "Context & Motivation: The old behavior leaked file descriptors on "
    "disconnect paths.\n\n"
    "Technical Approach: Close descriptors in a finally block within the "
    "shutdown handler.\n\n"
    "Edge Cases & Impact: Covers null sockets and double-close; no behavior "
    "change on success paths."
)


class TestTripartiteBody(unittest.TestCase):
    def test_compliant_golden_shape_passes(self):
        raw = "audio: fix rnnoise model teardown crash\n\n" + COMPLIANT_BODY
        formatted, err = format_commit_message(raw)
        self.assertIsNone(err)
        subject, _, body = formatted.partition("\n\n")
        self.assertIsNone(validate_tripartite_body(subject, body))

    def test_body_missing(self):
        err = validate_tripartite_body("docs: update readme", None)
        self.assertIsNotNone(err)
        self.assertIn("[body-missing]", err)
        self.assertIn("Context & Motivation:", err)
        self.assertIn("Technical Approach:", err)
        self.assertIn("Edge Cases & Impact:", err)

    def test_missing_label(self):
        body = (
            "Context & Motivation: Because things broke.\n"
            "Edge Cases & Impact: None expected."
        )
        err = validate_tripartite_body("docs: x", body)
        self.assertIsNotNone(err)
        self.assertIn("[body-structure]", err)
        self.assertIn('"Technical Approach:"', err)

    def test_run_together_sections_name_blank_line_remedy(self):
        raw = (
            "docs: x\n\n"
            "Context & Motivation: Because things broke.\n"
            "Technical Approach: Did it this way.\n"
            "Edge Cases & Impact: None expected."
        )
        formatted, err = format_commit_message(raw)
        self.assertIsNone(err)
        subject, _, body = formatted.partition("\n\n")
        err = validate_tripartite_body(subject, body)
        self.assertIsNotNone(err)
        self.assertIn("[body-structure]", err)
        self.assertIn("blank line", err)
        self.assertIn('"Technical Approach:"', err)
        self.assertNotIn("missing", err)

    def test_template_itself_passes_validation(self):
        from scripts.commit import BODY_TEMPLATE
        self.assertIsNone(validate_tripartite_body("docs: x", BODY_TEMPLATE))

    def test_labels_out_of_order(self):
        body = (
            "Technical Approach: Did it this way.\n"
            "Context & Motivation: Because things broke.\n"
            "Edge Cases & Impact: None expected."
        )
        err = validate_tripartite_body("docs: x", body)
        self.assertIsNotNone(err)
        self.assertIn("[body-structure]", err)
        self.assertIn("out of order", err)

    def test_misspelled_label(self):
        body = (
            "Context and Motivation: Wrong ampersand.\n"
            "Technical Approach: Did it this way.\n"
            "Edge Cases & Impact: None expected."
        )
        err = validate_tripartite_body("docs: x", body)
        self.assertIsNotNone(err)
        self.assertIn("[body-structure]", err)
        self.assertIn('"Context & Motivation:"', err)

    def test_duplicated_label(self):
        body = (
            "Context & Motivation: First reason.\n"
            "Context & Motivation: Second reason.\n"
            "Technical Approach: Did it this way.\n"
            "Edge Cases & Impact: None expected."
        )
        err = validate_tripartite_body("docs: x", body)
        self.assertIsNotNone(err)
        self.assertIn("[body-structure]", err)
        self.assertIn("duplicated", err)

    def test_markdown_variants_rejected(self):
        for label in (
            "- Context & Motivation:",
            "### Context & Motivation",
            "1. Context & Motivation:",
        ):
            body = (
                f"{label} Because things broke.\n"
                "Technical Approach: Did it this way.\n"
                "Edge Cases & Impact: None expected."
            )
            err = validate_tripartite_body("docs: x", body)
            self.assertIsNotNone(err, f"should reject: {label!r}")
            self.assertIn("[body-structure]", err)

    def test_leading_paragraph_allowed(self):
        body = (
            "A short summary line before the sections.\n\n" + COMPLIANT_BODY
        )
        self.assertIsNone(validate_tripartite_body("docs: x", body))

    def test_multiline_section_content_allowed(self):
        body = (
            "Context & Motivation: A longer explanation that wraps across\n"
            "multiple lines before the next label starts.\n"
            "Technical Approach: Did it this way.\n"
            "Edge Cases & Impact: None expected."
        )
        self.assertIsNone(validate_tripartite_body("docs: x", body))

    def test_merge_commit_exempt(self):
        self.assertIsNone(validate_tripartite_body("Merge branch 'feature'", None))

    def test_revert_commit_exempt(self):
        self.assertIsNone(
            validate_tripartite_body('Revert "app: drop legacy path"', None)
        )

    def test_fixup_commit_exempt(self):
        self.assertIsNone(validate_tripartite_body("fixup! app: drop legacy path", None))

    def test_error_ends_with_agents_pointer(self):
        for err in (
            validate_tripartite_body("docs: x", None),
            validate_tripartite_body("docs: x", "No labels here."),
        ):
            self.assertIsNotNone(err)
            self.assertTrue(err.endswith("for the full commit style rules."), err)

    def test_subject_style_not_enforced(self):
        # Scope prefix and imperative mood are NOT checked (plan §4.3).
        body = COMPLIANT_BODY.replace("Context & Motivation:", "Context & Motivation:")
        for subject in ("fix: update .gitignore", "app: Added setting", "no scope here"):
            self.assertIsNone(validate_tripartite_body(subject, body))


class TestCommitFormatter(unittest.TestCase):
    def test_single_line_subject(self):
        raw = "docs: update readme"
        formatted, err = format_commit_message(raw)
        self.assertIsNone(err)
        self.assertEqual(formatted, "docs: update readme")

    def test_trailing_period_stripped(self):
        raw = "docs: update readme."
        formatted, err = format_commit_message(raw)
        self.assertIsNone(err)
        self.assertEqual(formatted, "docs: update readme")

    def test_subject_too_long_returns_error(self):
        raw = "docs: this subject line is way longer than fifty characters limit in git rule"
        formatted, err = format_commit_message(raw)
        self.assertIsNotNone(err)
        self.assertIn("exceeds 50 characters", err)

    def test_merge_commit_subject_over_50_chars_allowed(self):
        subjects = [
            "chore: merge branch 'feature/adaptive-voice-leveler-and-noise-suppression'",
            "Merge branch 'bugfix/extractor-retry-loop' into main",
            "Merge remote-tracking branch 'origin/feature/adaptive-voice-leveler'",
            "merge: branch 'feature/adaptive-voice-leveler-and-noise-suppression'",
            "chore(merge): branch 'feature/adaptive-voice-leveler-and-noise-suppression'",
        ]
        for subject in subjects:
            raw = f"{subject}\n\n{COMPLIANT_BODY}"
            formatted, err = format_commit_message(raw)
            self.assertIsNone(err, f"Expected no error for merge subject: {subject}")
            self.assertTrue(formatted.startswith(subject))

    def test_non_merge_commit_subject_over_50_chars_rejected(self):
        long_subjects = [
            "docs: this subject line is way longer than fifty characters limit in git rule",
            "audio: merge two audio streams in mixer and handle buffer underruns",
            "feat: implement a brand new feature that has a really long commit subject",
        ]
        for subject in long_subjects:
            raw = f"{subject}\n\n{COMPLIANT_BODY}"
            formatted, err = format_commit_message(raw)
            self.assertIsNotNone(err, f"Expected error for non-merge subject: {subject}")
            self.assertIn("exceeds 50 characters", err)

    def test_explicit_is_merge_override(self):
        # Force is_merge=True on non-merge prefix
        raw = "app: very long subject line that exceeds fifty characters limit\n\n" + COMPLIANT_BODY
        formatted, err = format_commit_message(raw, is_merge=True)
        self.assertIsNone(err)

        # Force is_merge=False on merge prefix
        raw_merge = "chore: merge branch 'feature/very-long-branch-name-exceeding-fifty-chars'\n\n" + COMPLIANT_BODY
        formatted, err = format_commit_message(raw_merge, is_merge=False)
        self.assertIsNotNone(err)
        self.assertIn("exceeds 50 characters", err)

    @patch("scripts.commit.is_git_merge_in_progress", return_value=True)
    def test_git_merge_in_progress_allows_long_subject(self, mock_is_git_merge):
        raw = "app: ongoing merge with extra long subject line that exceeds fifty characters\n\n" + COMPLIANT_BODY
        formatted, err = format_commit_message(raw)
        self.assertIsNone(err)

    def test_paragraph_wrapping_at_72(self):
        raw = """docs: update installation notes

This is a long paragraph that is going to be well over seventy-two characters in a single line and should be cleanly wrapped across multiple lines without splitting any individual words inappropriately."""
        formatted, err = format_commit_message(raw)
        self.assertIsNone(err)
        lines = formatted.split("\n")
        self.assertEqual(lines[0], "docs: update installation notes")
        self.assertEqual(lines[1], "")
        for line in lines[2:]:
            self.assertLessEqual(len(line), 72)

    def test_bullet_points_with_hanging_indent(self):
        raw = """feat: add cool features

Here is what changed:
- First item: This is a relatively long bullet item that should wrap onto a second line with a nice hanging indent matching the start of the bullet text.
- Second item: Short text.
* Third item (asterisk): Another bullet point that contains enough text to require wrapping onto another line cleanly.
1. Numbered item: Explaining the first step in the process with enough detail to wrap around to the next line."""
        formatted, err = format_commit_message(raw)
        self.assertIsNone(err)
        lines = formatted.split("\n")
        for line in lines:
            self.assertLessEqual(len(line), 72)

        bullet_idx = [i for i, l in enumerate(lines) if l.startswith("- First item:")][0]
        continuation_line = lines[bullet_idx + 1]
        self.assertTrue(continuation_line.startswith("  "), f"Expected 2-space indent, got: {continuation_line}")

        num_idx = [i for i, l in enumerate(lines) if l.startswith("1. Numbered item:")][0]
        num_continuation = lines[num_idx + 1]
        self.assertTrue(num_continuation.startswith("   "), f"Expected 3-space indent, got: {num_continuation}")

    def test_code_fence_preservation(self):
        code_block = "```bash\nadb shell am start -n ca.bualy.pipepipefork/org.schabi.newpipe.MainActivity --extra-long-flag-that-must-not-be-wrapped-at-seventy-two-characters\n```"
        raw = f"""feat: add adb instructions

Run the following command to test:

{code_block}

End of message."""
        formatted, err = format_commit_message(raw)
        self.assertIsNone(err)
        self.assertIn(code_block, formatted)

    def test_long_url_preservation(self):
        url = "https://github.com/bualy/pipepipe/commit/903e87d5ed51dc72fde8033b623ab3da7131fe?diff=split&context=10#diff-longanchor"
        raw = f"""docs: link to upstream commit

Reference URL:
{url}

Thanks."""
        formatted, err = format_commit_message(raw)
        self.assertIsNone(err)
        self.assertIn(url, formatted)

    def test_git_trailers_preservation(self):
        raw = """core: improve connection handling

Refactor TCP reconnection logic when connection drops.

Signed-off-by: Developer Name <developer@example.com>
Co-authored-by: Another Dev <another@example.com>
Fixes: #1234"""
        formatted, err = format_commit_message(raw)
        self.assertIsNone(err)
        self.assertIn("Signed-off-by: Developer Name <developer@example.com>", formatted)
        self.assertIn("Co-authored-by: Another Dev <another@example.com>", formatted)
        self.assertIn("Fixes: #1234", formatted)

    def test_markdown_table_preservation(self):
        table = "| Column 1 | Column 2 |\n|---|---|\n| Value A | Value B |"
        raw = f"""docs: add table summary

Overview:

{table}"""
        formatted, err = format_commit_message(raw)
        self.assertIsNone(err)
        self.assertIn(table, formatted)

    def test_comment_stripping(self):
        raw = """docs: update AGENTS.md

# Please enter the commit message for your changes. Lines starting
# with '#' will be ignored.
Actual commit body explanation."""
        formatted, err = format_commit_message(raw)
        self.assertIsNone(err)
        self.assertNotIn("Please enter the commit message", formatted)
        self.assertIn("Actual commit body explanation.", formatted)

    def test_empty_message_returns_error(self):
        formatted, err = format_commit_message("")
        self.assertIsNotNone(err)


class TestCLIIntegration(unittest.TestCase):
    def run_cli(self, args: list, input_data: str = None) -> subprocess.CompletedProcess:
        cmd = [sys.executable, "scripts/commit.py"] + args
        return subprocess.run(
            cmd,
            input=input_data,
            capture_output=True,
            text=True,
        )

    @patch("subprocess.run")
    def test_cli_execution_with_message_flag(self, mock_run):
        diff_proc = MagicMock()
        diff_proc.returncode = 1  # `git diff --cached --quiet`: staged changes present
        commit_proc = MagicMock()
        commit_proc.returncode = 0
        mock_run.side_effect = [diff_proc, commit_proc]

        with patch("sys.argv", ["commit.py", "-m", "docs: test subject\n\n" + COMPLIANT_BODY]):
            ret = main()
            self.assertEqual(ret, 0)
            self.assertEqual(mock_run.call_count, 2)
            cmd_args = mock_run.call_args[0][0]

    @patch("subprocess.run")
    def test_cli_multiple_message_flags(self, mock_run):
        diff_proc = MagicMock()
        diff_proc.returncode = 1  # `git diff --cached --quiet`: staged changes present
        commit_proc = MagicMock()
        commit_proc.returncode = 0
        mock_run.side_effect = [diff_proc, commit_proc]

        with patch("sys.argv", ["commit.py", "-m", "docs: multi message", "-m", COMPLIANT_BODY]):
            ret = main()
            self.assertEqual(ret, 0)
            self.assertEqual(mock_run.call_count, 2)
            cmd_args = mock_run.call_args[0][0]
            expected, _ = format_commit_message("docs: multi message\n\n" + COMPLIANT_BODY)
            self.assertEqual(cmd_args[3], expected)

    @patch("subprocess.run")
    def test_cli_subject_and_body_flags(self, mock_run):
        diff_proc = MagicMock()
        diff_proc.returncode = 1  # `git diff --cached --quiet`: staged changes present
        commit_proc = MagicMock()
        commit_proc.returncode = 0
        mock_run.side_effect = [diff_proc, commit_proc]

        with patch("sys.argv", ["commit.py", "-s", "docs: test flags", "-b", COMPLIANT_BODY]):
            ret = main()
            self.assertEqual(ret, 0)
            self.assertEqual(mock_run.call_count, 2)
            cmd_args = mock_run.call_args[0][0]
            expected, _ = format_commit_message("docs: test flags\n\n" + COMPLIANT_BODY)
            self.assertEqual(cmd_args[3], expected)

    def test_cli_check_flag_valid(self):
        proc = self.run_cli(["--check", "-m", "docs: valid subject\n\n" + COMPLIANT_BODY])
        self.assertEqual(proc.returncode, 0)
        self.assertIn("OK:", proc.stdout)

    def test_cli_check_flag_subject_too_long_is_usage_exit_code(self):
        proc = self.run_cli(["--check", "-m", "docs: " + ("x" * 50)])
        self.assertEqual(proc.returncode, 2)
        self.assertIn("exceeds 50 characters", proc.stderr)

    def test_cli_check_flag_body_missing_fails(self):
        proc = self.run_cli(["--check", "-m", "docs: valid subject"])
        self.assertEqual(proc.returncode, 1)
        self.assertIn("[body-missing]", proc.stderr)
        self.assertIn("AGENTS.md", proc.stderr)

    def test_cli_message_and_body_flags_rejected(self):
        # -m already carries the full message: combining it with -b must
        # fail fast (exit 2) instead of silently dropping the body.
        proc = self.run_cli(["--check", "-m", "docs: subject", "-b", COMPLIANT_BODY])
        self.assertEqual(proc.returncode, 2)
        self.assertIn("[mixed-message-flags]", proc.stderr)
        self.assertIn("-s", proc.stderr)

    def test_cli_message_and_subject_flags_rejected(self):
        proc = self.run_cli(["--check", "-m", "docs: subject", "-s", "docs: subject"])
        self.assertEqual(proc.returncode, 2)
        self.assertIn("[mixed-message-flags]", proc.stderr)

    def test_cli_body_without_subject_rejected(self):
        # -b alone has no subject to attach to: reject with instructions
        # instead of falling through to "No commit message provided".
        proc = self.run_cli(["--check", "-b", COMPLIANT_BODY])
        self.assertEqual(proc.returncode, 2)
        self.assertIn("[body-without-subject]", proc.stderr)
        self.assertIn("-s", proc.stderr)

    def test_cli_no_body_check_bypasses(self):
        proc = self.run_cli(["--check", "--no-body-check", "-m", "docs: valid subject"])
        self.assertEqual(proc.returncode, 0)
        self.assertIn("OK:", proc.stdout)

    def test_cli_check_merge_commit_long_subject_with_tripartite_body(self):
        raw = (
            "chore: merge branch 'feature/long-branch-name-exceeding-fifty-chars'\n\n"
            + COMPLIANT_BODY
        )
        proc = self.run_cli(["--check", "-m", raw])
        self.assertEqual(proc.returncode, 0, f"stderr: {proc.stderr}")
        self.assertIn("OK:", proc.stdout)

    def test_cli_check_bare_merge_commit_long_subject(self):
        raw = "Merge branch 'feature/long-branch-name-exceeding-fifty-chars' into main"
        proc = self.run_cli(["--check", "-m", raw])
        self.assertEqual(proc.returncode, 0, f"stderr: {proc.stderr}")
        self.assertIn("OK:", proc.stdout)

    def test_cli_check_merge_commit_missing_body_fails(self):
        raw = "chore: merge branch 'feature/long-branch-name-exceeding-fifty-chars'"
        proc = self.run_cli(["--check", "-m", raw])
        self.assertEqual(proc.returncode, 1)
        self.assertIn("[body-missing]", proc.stderr)

    @patch("subprocess.run")
    def test_cli_stdin(self, mock_run):
        diff_proc = MagicMock()
        diff_proc.returncode = 1  # `git diff --cached --quiet`: staged changes present
        commit_proc = MagicMock()
        commit_proc.returncode = 0
        mock_run.side_effect = [diff_proc, commit_proc]

        raw = "docs: from stdin\n\n" + COMPLIANT_BODY
        with patch("sys.argv", ["commit.py"]):
            with patch("sys.stdin.isatty", return_value=False):
                with patch("sys.stdin.read", return_value=raw):
                    ret = main()
                    self.assertEqual(ret, 0)
                    self.assertEqual(mock_run.call_count, 2)
                    cmd_args = mock_run.call_args[0][0]
                    expected, _ = format_commit_message(raw)
                    self.assertEqual(cmd_args[3], expected)

    @patch("subprocess.run")
    def test_cli_no_staged_changes_aborts_before_commit(self, mock_run):
        diff_proc = MagicMock()
        diff_proc.returncode = 0  # `git diff --cached --quiet`: nothing staged
        mock_run.return_value = diff_proc

        with patch("sys.argv", ["commit.py", "-m", "docs: test subject\n\n" + COMPLIANT_BODY]):
            with patch("sys.stderr", new_callable=io.StringIO) as stderr:
                ret = main()
                self.assertEqual(ret, 1)
                self.assertIn("[nothing-staged]", stderr.getvalue())
                mock_run.assert_called_once()
                diff_args = mock_run.call_args[0][0]
                self.assertEqual(diff_args[:3], ["git", "diff", "--cached"])


if __name__ == "__main__":
    unittest.main()
