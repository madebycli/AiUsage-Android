# AI Context Route

```yaml
schema_version: 1
context_repo: https://github.com/madebycli/master-context
project_id: aiusage-android
source_repo: https://github.com/madebycli/AiUsage-Android
context_root: projects/aiusage-android/
entrypoint: projects/aiusage-android/INDEX.md
```

This route is newly established for this repository. Validate it against
`REGISTRY.yaml`. Read the entrypoint and `handoffs/CURRENT.md` first. Follow
only task-relevant links within this project. Do not search sibling projects.
If validation fails, stop context loading and report the exact mismatch.
Private durable context belongs in master-context; public documentation belongs
in this source repository.

## Release-Regel

Stabile Releases entstehen nur aus `main` (Workflow `release.yml`, Tag `v<versionName>`).
Feature-Branches bauen und testen nur über `android.yml`. Eine Version wird erst mit
einer erhöhten `versionName` in `app/build.gradle.kts` veröffentlicht.
Signierschlüssel liegen nur als GitHub-Secrets und lokal außerhalb des Repos.
