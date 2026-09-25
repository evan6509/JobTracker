#!/usr/bin/env python3
"""Reject a local or incorrectly versioned APK before GitHub uploads it."""

import json
import os
import re
import sys
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"Invalid published APK: {message}")


if len(sys.argv) != 2:
    raise SystemExit("Usage: verify_published_apk.py OUTPUT_DIRECTORY")

output_dir = Path(sys.argv[1])
metadata = json.loads((output_dir / "output-metadata.json").read_text())
elements = metadata.get("elements", [])

if metadata.get("applicationId") != "com.evanchubbuck.jobtracker":
    fail("wrong application ID")
if metadata.get("variantName") != "publishedDebug":
    fail("wrong build variant")
if len(elements) != 1:
    fail("expected exactly one APK")

apk = elements[0]
version_name = apk.get("versionName")
version_code = apk.get("versionCode")
if apk.get("outputFile") != "app-published-debug.apk" or not (output_dir / "app-published-debug.apk").is_file():
    fail("published APK file is missing")
if not isinstance(version_name, str) or not re.fullmatch(r"\d+\.\d+\.\d+", version_name) or version_name == "0.0.0":
    fail("published version name is missing or local")
if not isinstance(version_code, int) or version_code <= 1:
    fail("published version code is missing or local")
if os.getenv("JOBTRACKER_VERSION_NAME") and version_name != os.environ["JOBTRACKER_VERSION_NAME"]:
    fail("version name differs from the GitHub release version")
if os.getenv("JOBTRACKER_VERSION_CODE") and version_code != int(os.environ["JOBTRACKER_VERSION_CODE"]):
    fail("version code differs from the GitHub release code")

print(f"Verified published APK: {version_name} (code {version_code})")
