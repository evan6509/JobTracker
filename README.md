# JobTracker

An offline Android app for organizing current, planned, and completed jobs. The home screen keeps active and planned jobs in a drag-reorderable priority list. Setup has eight steps, including an optional inventory list, and saves an unfinished draft so it can be resumed. Dark mode is the default; use the switch on Home for light mode.

## Build and install

Open this directory in Android Studio, or run `./gradlew :app:assembleDebug`. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk` and can be installed on an Android 7.0+ device.

GitHub Actions builds, tests, and lints every push and pull request. Download the `JobTracker-debug-apk` artifact from a successful `main` push to install that build. Pushing a version tag such as `v1.1.1` also creates a GitHub Release with the APK attached. Pull requests do not publish installable artifacts.

The `main` and tagged APKs use the same debug signing key as the local build, so they can update the installed app. The key is stored as a GitHub Actions secret and is unavailable to pull requests. Keep a backup of the signing key; a different key cannot update existing installs.

## Job sharing

Job sharing uses an offline, text-only QR snapshot. It includes client names and numbers, site address, start/end dates, work details, inventory, and outside-worker assignments. It **does not include photos or private costs**. A job whose text exceeds the QR size limit cannot generate a code until some text is shortened. No account or internet connection is required.

Anyone who can scan a displayed code can read the data. Codes do not expire and cannot be revoked. Imports are separate planned copies; later edits to the original do not sync. If the same snapshot is scanned again, JobTracker warns before allowing another copy.

Reference photos are copied into the app's private storage. Removing a photo from a job deletes only that app-owned copy, not the original on the device. Calendar events are opened for review in a calendar app and are not automatically updated after job edits.

The job detail screen can share a job PDF through Android's share sheet, including by a messaging app that accepts PDFs. That PDF includes the job details, inventory, and locally available reference photos. The separate Costs screen stores per-job cost entries and can export its own costs PDF. Costs are never included in the job PDF or QR code. Dates are shown as Month Day, Year and selected with a calendar picker. The calendar action opens an all-day event for dates without a start time, or a timed event when a start time is set; for a single timed day it defaults to one hour, and the user can adjust the event before saving.
