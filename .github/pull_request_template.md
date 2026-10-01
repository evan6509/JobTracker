## Changes

Describe the problem and resulting behavior.

## Validation

List checks actually performed and any verification gaps, including Pixel testing with `localDebug` when relevant.

## Review context

- Check saved-job, draft, photo, cost, and sync compatibility when affected.
- Keep Local app storage separate from the published app; release assets must contain only the numbered published APK.
- Review the latest revision after fixes and confirm GitHub checks pass before merging.

Merging into `main` publishes a release after successful GitHub checks. Merge only when publication is explicitly authorized.
