# JobTracker

An offline Android app for organizing current, planned, and completed jobs. The home screen keeps active and planned jobs in a drag-reorderable priority list. Setup has eight steps, including an optional inventory list, and saves an unfinished draft so it can be resumed. Dark mode is the default; use the switch in Settings for light mode.

## Build and install

For work in progress, select the `localDebug` build variant in Android Studio or run `./gradlew :app:assembleLocalDebug`. Install `app/build/outputs/apk/local/debug/app-local-debug.apk` on an Android 7.0+ device. It appears as **Job Tracker Local**, uses version `0.0.0`, and has separate storage from the published app. It can stay installed beside a published release and has no GitHub update option. Rebuild and reinstall it from the Mac as features change; GitHub Actions does not build or publish this variant.

When starting another Codex chat for the same unpublished batch, choose this project's **Local** checkout. A separate worktree is an isolated copy; its later edits do not appear in the Local checkout until they are handed off or integrated. Local commits are fine and do not create GitHub releases.

GitHub Actions builds the `publishedDebug` variant and publishes its APK. Install numbered updates to the published app from its GitHub Releases. Both variants' source code is stored in this repository, so the `0.0.0` local build configuration is included when code is pushed; the local APK is never attached to a release.

GitHub Actions builds, tests, and lints every push and pull request. Every successful push to `main` gets a new numbered GitHub Release with the installable APK. The workflow assigns an increasing published app version and version code from its run number, creates the matching tag, and generates release notes from the changes. Other workflow runs can leave gaps in the release numbers. A manually built `publishedDebug` APK uses the fallback version in `app/build.gradle.kts`; set `JOBTRACKER_VERSION_NAME` and `JOBTRACKER_VERSION_CODE` when building it locally if it needs to update a newer installed release. Pull requests do not publish releases.

The published APKs use the same debug signing key as a locally built `publishedDebug` APK, so they can update the installed published app. The key is stored as a GitHub Actions secret and is unavailable to pull requests. Keep a backup of the signing key; a different key cannot update existing installs. The `localDebug` app has a different package ID, so published updates cannot replace it.

On Home, open the menu and tap **Check for updates** to compare the installed version with the latest published GitHub Release. If a newer release has a `JobTracker-vX.Y.Z-debug.apk` asset, tap **Download APK** and then the completed download notification to install it. Android may ask you to allow installs from JobTracker. Checking requires an internet connection; jobs remain available offline.

## Job sharing

Job sharing uses an offline, text-only QR snapshot. It includes client names and numbers, site address, start/end dates, work details, inventory, and outside-worker assignments. It **does not include photos or private costs**. A job whose text exceeds the QR size limit cannot generate a code until some text is shortened. No account or internet connection is required.

Anyone who can scan a displayed code can read the data. Codes do not expire and cannot be revoked. Imports are separate planned copies; later edits to the original do not sync. If the same snapshot is scanned again, JobTracker warns before allowing another copy.

## Sync phones

Install the same current version of JobTracker on both Android phones. Keep the phones near each other, turn on Wi-Fi and Location, and open **Sync phones** on both. Allow the requested nearby access, tap **Search again** if needed, and select the other phone on one device. Accept the Android Wi-Fi Direct connection prompt. Compare the six-digit code shown in JobTracker and tap **Codes match** on both phones.

Each phone sends its saved jobs, reference photos, private costs, and unfinished draft directly to the other. This works without an internet connection, router, account, or hosted server. The phones must stay on the sync screen until both show **Sync complete**. Newer edits to the same job win; if each phone has a different unfinished draft, each keeps its own draft. Previously exported PDFs and calendar events are not updated. QR sharing remains a one-time copy and does not include photos or costs.

Reference photos are copied into the app's private storage. Removing a photo from a job deletes only that app-owned copy, not the original on the device. Calendar events are opened for review in a calendar app and are not automatically updated after job edits.

The job detail screen can share a job PDF through Android's share sheet, including by a messaging app that accepts PDFs. That PDF includes the job details, inventory, and locally available reference photos. The separate Costs screen stores per-job cost entries and can export its own costs PDF. Costs are never included in the job PDF or QR code. Dates are shown as Month Day, Year and selected with a calendar picker. The calendar action opens an all-day event for dates without a start time, or a timed event when a start time is set; for a single timed day it defaults to one hour, and the user can adjust the event before saving.
