# JobTracker

An offline Android app for organizing current, planned, and completed jobs. The home screen keeps active and planned jobs in a drag-reorderable priority list. Setup has seven steps and saves an unfinished draft so it can be resumed.

## Build and install

Open this directory in Android Studio, or run `./gradlew :app:assembleDebug`. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk` and can be installed on an Android 7.0+ device.

## Job sharing

Job sharing uses an offline, text-only QR snapshot. It includes client names and numbers, site address, schedule, work details, and outside-worker assignments. It **does not include photos**. A job whose text exceeds the QR size limit cannot generate a code until some text is shortened. No account or internet connection is required.

Anyone who can scan a displayed code can read the data. Codes do not expire and cannot be revoked. Imports are separate planned copies; later edits to the original do not sync. If the same snapshot is scanned again, JobTracker warns before allowing another copy.

Reference photos are copied into the app's private storage. Removing a photo from a job deletes only that app-owned copy, not the original on the device. Calendar events are opened for review in a calendar app and are not automatically updated after job edits.
