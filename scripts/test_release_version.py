import json
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from release_version import main, select_version


def release(tag, **fields):
    return dict(tag_name=tag, draft=False, prerelease=False, body="") | fields


class ReleaseVersionTest(unittest.TestCase):
    def test_next_version_comes_from_published_releases(self):
        self.assertEqual(select_version([release("v1.2.7"), release("v1.2.6")], "new", {}),
                         ("v1.2.8", 13))

    def test_numeric_order_and_existing_gaps(self):
        self.assertEqual(select_version([release("v1.2.9"), release("v1.2.12")], "new", {}),
                         ("v1.2.13", 18))

    def test_unpublished_and_prerelease_versions_do_not_consume_numbers(self):
        releases = [release("v1.2.7"), dict(tag_name="v1.2.20", draft=True),
                    dict(tag_name="v1.2.30", prerelease=True), release("v1.2.100-beta")]
        self.assertEqual(select_version(releases, "new", {"v1.2.99": "other"}), ("v1.2.8", 13))

    def test_published_commit_rerun_reuses_its_original_version(self):
        releases = [release("v1.2.7"), release("v1.2.8")]
        self.assertEqual(select_version(releases, "old", {"v1.2.7": "old", "v1.2.8": "new"}),
                         ("v1.2.7", 12))

    def test_new_series_uses_recorded_android_code(self):
        releases = [release("v1.2.7"), dict(tag_name="v1.3.0", body="<!-- jobtracker-version-code: 13 -->")]
        self.assertEqual(select_version(releases, "new", {}), ("v1.3.1", 14))

    def test_code_cannot_regress_below_an_earlier_release(self):
        releases = [dict(tag_name="v1.2.8", body="<!-- jobtracker-version-code: 50 -->"),
                    dict(tag_name="v1.2.9", body="<!-- jobtracker-version-code: 14 -->")]
        self.assertEqual(select_version(releases, "new", {}), ("v1.2.10", 51))

    def test_empty_history_fails_instead_of_resetting(self):
        with self.assertRaises(ValueError):
            select_version([], "new", {})

    def test_unknown_android_code_fails_instead_of_guessing(self):
        with self.assertRaises(ValueError):
            select_version([release("v1.3.0")], "new", {})

    def test_malformed_code_fails_instead_of_using_legacy_rule(self):
        with self.assertRaises(ValueError):
            select_version([dict(tag_name="v1.2.8", body="<!-- jobtracker-version-code: invalid -->")], "new", {})

    def test_android_code_overflow_is_rejected(self):
        with self.assertRaises(ValueError):
            select_version([dict(tag_name="v1.3.0", body="<!-- jobtracker-version-code: 2100000000 -->")], "new", {})

    def test_requested_major_version_keeps_android_code_sequential(self):
        self.assertEqual(select_version([release("v1.2.7")], "new", {}, "2.0.0"), ("v2.0.0", 13))

    def test_requested_minor_or_patch_version_is_used_exactly(self):
        for target in ["1.3.0", "1.2.9"]:
            self.assertEqual(select_version([release("v1.2.7")], "new", {}, target), (f"v{target}", 13))

    def test_consumed_request_continues_automatically_after_publication(self):
        releases = [release("v1.2.7"), release("v2.0.0", body="<!-- jobtracker-version-code: 13 -->")]
        self.assertEqual(select_version(releases, "new", {}, "2.0.0"), ("v2.0.1", 14))
        releases.append(release("v2.0.1", body="<!-- jobtracker-version-code: 14 -->"))
        self.assertEqual(select_version(releases, "new", {}, "2.0.0"), ("v2.0.2", 15))

    def test_published_commit_rerun_does_not_apply_a_new_version_request(self):
        self.assertEqual(select_version([release("v1.2.7")], "old", {"v1.2.7": "old"}, "2.0.0"),
                         ("v1.2.7", 12))

    def test_lower_unpublished_version_request_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "must be higher"):
            select_version([release("v1.2.7")], "new", {}, "1.2.4")

    def test_invalid_and_local_version_requests_are_rejected(self):
        for target in ["0.0.0", "v2.0.0", "2.0", "2.0.0-beta", "02.0.0", "", 2]:
            with self.subTest(target=target), self.assertRaises(ValueError):
                select_version([release("v1.2.7")], "new", {}, target)

    def test_cli_reads_paginated_history_and_writes_workflow_outputs(self):
        self.run_cli([[release("v1.2.7")], [release("v1.2.6")]], expected_lookups=2)

    def test_cli_does_not_require_a_tag_for_an_unpublished_release(self):
        self.run_cli([[release("v1.2.7"), release("v1.2.8", draft=True),
                       release("v1.2.9", prerelease=True)]], expected_lookups=1)

    def test_cli_fails_when_a_published_tag_cannot_be_resolved(self):
        with self.assertRaisesRegex(ValueError, "Cannot resolve published tag"):
            self.run_cli([[release("v1.2.7")]], expected_lookups=1, git_result=1)

    def test_cli_applies_requested_version_from_config(self):
        self.run_cli([[release("v1.2.7")]], expected_lookups=1, requested="2.0.0", expected="2.0.0")

    def run_cli(self, pages, expected_lookups, git_result=0, requested=None, expected="1.2.8"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            history, env, output = root / "releases.json", root / "env", root / "output"
            history.write_text(json.dumps(pages))
            config = root / "release-version.json"
            config.write_text(json.dumps({"next_version": requested}))
            with patch("sys.argv", ["release_version.py", str(history), str(config)]), \
                    patch.dict(os.environ, GITHUB_SHA="new", GITHUB_ENV=str(env), GITHUB_OUTPUT=str(output)), \
                    patch("release_version.subprocess.run", return_value=SimpleNamespace(
                        returncode=git_result, stdout="old\n")) as lookup:
                main()
            self.assertEqual(lookup.call_count, expected_lookups)
            self.assertEqual(env.read_text(), f"JOBTRACKER_VERSION_NAME={expected}\nJOBTRACKER_VERSION_CODE=13\n")
            self.assertEqual(output.read_text(), f"tag=v{expected}\ncode=13\n")


if __name__ == "__main__":
    unittest.main()
