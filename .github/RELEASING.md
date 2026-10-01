# Choosing a release version

Development commits stay on `codex/development`. Push that branch and review its pull request into `main`; merging publishes the next release after GitHub checks pass.

By default, the next release increments the latest published patch version: `1.2.7` becomes `1.2.8`. Branch and PR checks do not consume version numbers.

To choose a version, tell the agent **“Make the next release 2.0.0”**, or edit `.github/release-version.json`:

```json
{
  "next_version": "2.0.0"
}
```

Commit this setting on the development branch so it is included in the PR being reviewed. Choosing a number does not publish it; publication still happens when the PR is merged into `main`.

- Use `null` for automatic numbering.
- A new requested version must be higher than the latest published version.
- Once the requested version is published, the request is consumed. Future releases automatically continue with the next patch, such as `2.0.1`, even if the old setting remains in the file.
- Android's internal version code increases by one independently of the chosen version name, so major version changes remain installable updates.
- The Local app remains `0.0.0`.
