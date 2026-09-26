# JobTracker

JobTracker is an offline Android app for planning and tracking jobs. Keep upcoming work in a reorderable priority list, record job details and inventory, and save an unfinished setup as a draft.

## What it does

- Track planned, active, and completed jobs, with photos and costs.
- Save up to three drafts and open them from the Drafts button on Home.
- Take photos inside the app or choose existing pictures.
- Restore deleted jobs and drafts from the Recycle bin at the bottom of Settings.
- Share a job PDF or export costs separately.
- Add job dates to your calendar for review before saving.
- Choose dark or light mode in Settings.

## Install

Download the latest APK from [GitHub Releases](https://github.com/evan6509/JobTracker/releases) and install it on an Android 7.0 or newer device. The published app checks silently for updates when opened; failed checks wait until the next opening. You can also use **Home menu → Check for updates**. Checking and downloading updates requires internet access; your jobs remain available offline.

To build a development copy, run:

```sh
./gradlew :app:assembleLocalDebug
```

Install `app/build/outputs/apk/local/debug/app-local-debug.apk`. This **Job Tracker Local** app has separate storage and can be installed alongside the published app. It does not receive published updates.

## Share and sync

**QR sharing** creates a one-time text copy of a job. It includes job and client details, inventory, and worker assignments, but excludes photos and private costs. Anyone who scans the code can read its contents; codes do not expire. Later edits do not sync to the imported copy.

**Sync phones** lets each phone choose which jobs and drafts to send, and which details to include for each one. Costs are excluded unless you select them. On both phones, open **Sync phones**, choose what to share, allow nearby access, and compare the six-digit codes before confirming. Keep both phones on the sync screen until they show **Sync complete**. Update both phones to version 1.2.7 or newer before using this selective sync; older versions use a different format. No internet connection or account is needed.

Deleted jobs and drafts stay in the Recycle bin until restored or deleted forever. Their photos and costs are kept for restoration, and bin contents are not synced. Restoring an active job returns it to Planned. The optional **Skip confirmation for long swipes** setting moves a job or draft straight to the bin when swiped more than 70% to the left; shorter swipes still ask first.
