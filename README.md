# JobTracker

JobTracker is an offline Android app for organizing jobs, from planning through completion.

## What it does

- Plan upcoming work, prioritize jobs, and track progress through completion.
- Keep client details, materials with optional prices, worker assignments, and photos together for each job.
- Save unfinished job entries as drafts and review completed work in History.
- Export job PDFs with your choice of details and prices, and add job dates to your calendar.
- Share jobs by QR code or sync them directly between phones.

## Install

Download the latest APK from [GitHub Releases](https://github.com/evan6509/JobTracker/releases) and install it on an Android 7.0 or newer device. The published app checks silently for updates when opened; failed checks wait until the next opening. You can also use **Home menu → Check for updates**. Checking and downloading updates requires internet access; your jobs remain available offline.

To build a development copy, run:

```sh
./gradlew :app:assembleLocalDebug
```

Install `app/build/outputs/apk/local/debug/app-local-debug.apk`. This **Job Tracker Local** app has separate storage and can be installed alongside the published app. It does not receive published updates.

## Share and sync

**QR sharing** creates a one-time text copy of a job. It includes job and client details, materials, and worker assignments. Photos are excluded, and material prices are off unless you choose to include them. Anyone who scans the code can read its contents; codes do not expire. Later edits do not sync to the imported copy.

**Sync phones** lets each phone choose which jobs and drafts to send, and which details to include for each one. Material prices are excluded unless you select them for that job or draft. On both phones, open **Sync phones**, choose what to share, allow nearby access, and compare the six-digit codes before confirming. Keep both phones on the sync screen until they show **Sync complete**. Both phones must use a version that supports materials with optional prices; older versions use a different sync format. No internet connection or account is needed.

Deleted jobs and drafts stay in the Recycle bin until restored or deleted forever. Their photos, materials, and prices are kept for restoration, and bin contents are not synced. Restoring an active job returns it to Planning. The optional **Skip confirmation for long swipes** setting moves a job or draft straight to the bin when swiped more than 70% to the left; shorter swipes still ask first.

## Job site addresses

Start typing in **Street address** to see matching streets and addresses, then tap a suggestion to fill the field. Add a city for better matches. Manual address entry always remains available.

Suggestions are limited to your current country, using the mobile network’s country or the phone’s region setting when that is unavailable. If you allow approximate location while entering an address, nearby matches are shown first; the app still searches the whole country. You can scroll the suggestion list. Numbered addresses are checked with the phone’s address service first, with [Photon](https://github.com/komoot/photon) and OpenStreetMap data as a fallback. Matching house numbers appear before street-only results. If only a street is found, the suggestion keeps the number you typed and says that the house number is not confirmed. Add a city and state to distinguish streets with the same name.

Common street abbreviations, directions, and US highway names are recognized. If a numbered lookup finds nothing, the app tries the street separately and keeps your entered number with an unconfirmed label.

Address lookup requires internet. Search text is sent to the phone’s address service and/or Photon; when location is allowed, Photon also receives approximate coordinates to prioritize nearby results. Suggestions wait briefly until typing pauses, and repeated searches are cached. Coverage varies, and the public service may be unavailable or limit requests. No Google Maps key or billing account is required. Saved jobs and phone-to-phone sync still work offline.
