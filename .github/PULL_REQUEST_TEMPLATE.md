## Summary

<!-- What does this PR change and why? -->

## Checklist

Every PR must comply with [CONTRIBUTING.md](../CONTRIBUTING.md) — read it first.

- [ ] `CHANGELOG.md` entry added under `[Unreleased]` only (no `## [x.y.z]` version sections)
- [ ] `./scripts/check-pr.sh` passes
- [ ] `./gradlew test` passes (full suite, not just `-Ptier=unit`)
- [ ] New tools (if any): registered in `ToolNames`, `ToolRegistry`, and `McpSettings` (opt-in defaults), documented in all six doc locations, and the golden tool manifest regenerated with its diff reviewed
- [ ] No forbidden files (`.idea/gradle.xml`, `scripts/build-install.sh`, `docs/pr-*.md`)
