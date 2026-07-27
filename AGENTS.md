# Agent Instructions

All agent-facing guidance for this repository lives in [CLAUDE.md](CLAUDE.md)
(architecture, tool catalog, test tiers, threading rules) and
[CONTRIBUTING.md](CONTRIBUTING.md) (the authoritative PR checklist).
Read both before making changes. This file only lists the essentials that are
easy to get wrong:

- **JDK 21 is required** to run Gradle.
- Build and test:
  - `./gradlew test` — full suite (~40s), required before pushing
  - `./gradlew test -Ptier=unit` — fast headless tier only
  - `./gradlew test -Ptier=platform` — IntelliJ Platform fixture tier only
- Run `./scripts/check-pr.sh` before pushing — it catches the common mistakes.
- **New MCP tool = six doc locations + golden manifest**: register it in
  `ToolNames` and `ToolRegistry`, update all six doc locations (`README.md`,
  `USAGE.md`, `CLAUDE.md`, `SKILL.md`, `tools-reference.md`, `ToolNames.ALL`
  sorted), then regenerate the golden tool manifest and review its diff (see
  the Testing section of CLAUDE.md).
