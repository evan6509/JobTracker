#!/usr/bin/env python3
"""Select the next patch version from published releases, independent of CI runs."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path


TAG = re.compile(r"v(\d+)\.(\d+)\.(\d+)")
CODE = re.compile(r"<!-- jobtracker-version-code: (\d+) -->")


def version_code(release):
    match = CODE.search(release.get("body") or "")
    if match:
        code = int(match[1])
    else:
        version = tuple(map(int, TAG.fullmatch(release["tag_name"]).groups()))
        # Published 1.2.x APKs used patch + 5 (v1.2.7 is code 12).
        # New releases record the code in a hidden release-note marker.
        if "jobtracker-version-code" in (release.get("body") or "") or version[:2] != (1, 2):
            raise ValueError(f"Missing valid Android version code for {release['tag_name']}")
        code = version[2] + 5
    if not 1 < code <= 2_100_000_000:
        raise ValueError("Android version code is out of range")
    return code


def select_version(releases, commit, tag_commits):
    stable = [r for r in releases if not r.get("draft") and not r.get("prerelease")
              and TAG.fullmatch(r["tag_name"])]
    if not stable:
        raise ValueError("No published version found; refusing to reset release numbering")
    stable.sort(key=lambda r: tuple(map(int, TAG.fullmatch(r["tag_name"]).groups())))
    # A rerun of an already published commit must not create another release.
    existing = [r for r in stable if tag_commits.get(r["tag_name"]) == commit]
    if existing:
        release = existing[-1]
        return release["tag_name"], version_code(release)
    major, minor, patch = map(int, TAG.fullmatch(stable[-1]["tag_name"]).groups())
    # Historic 1.1.x releases precede the known 1.2.x code sequence.
    known = [r for r in stable if CODE.search(r.get("body") or "")
             or tuple(map(int, TAG.fullmatch(r["tag_name"]).groups())) >= (1, 2, 0)]
    code = max(version_code(r) for r in known) + 1
    if code > 2_100_000_000:
        raise ValueError("Android version code is out of range")
    return f"v{major}.{minor}.{patch + 1}", code


def main():
    pages = json.loads(Path(sys.argv[1]).read_text())
    releases = [r for page in pages for r in page]
    tag_commits = {}
    for release in releases:
        tag = release["tag_name"]
        if not release.get("draft") and not release.get("prerelease") and TAG.fullmatch(tag):
            result = subprocess.run(["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"],
                                    capture_output=True, text=True)
            if result.returncode != 0:
                raise ValueError(f"Cannot resolve published tag {tag}; fetch full history and tags first")
            tag_commits[tag] = result.stdout.strip()
    tag, code = select_version(releases, os.environ["GITHUB_SHA"], tag_commits)
    with open(os.environ["GITHUB_ENV"], "a") as env:
        env.write(f"JOBTRACKER_VERSION_NAME={tag[1:]}\nJOBTRACKER_VERSION_CODE={code}\n")
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"tag={tag}\ncode={code}\n")
    print(f"Release version: {tag} (code {code})")


if __name__ == "__main__":
    main()
