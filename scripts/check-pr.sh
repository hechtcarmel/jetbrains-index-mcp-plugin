#!/usr/bin/env bash
# check-pr.sh — pre-push validation against CONTRIBUTING.md rules.
# Run before every push. Exits non-zero if any check fails.
set -euo pipefail

PASS=0
FAIL=0

ok()   { echo "  ✓ $1"; PASS=$((PASS+1)); }
fail() { echo "  ✗ $1"; FAIL=$((FAIL+1)); }
hdr()  { echo ""; echo "── $1"; }

# ── 1. CHANGELOG ────────────────────────────────────────────────────────────
hdr "CHANGELOG.md"

if grep -q "^## \[Unreleased\]" CHANGELOG.md; then
    ok "## [Unreleased] section present"
else
    fail "Missing ## [Unreleased] section — add one for user-visible changes"
fi
if git diff "${UPSTREAM_BASE:-HEAD~1}" HEAD -- CHANGELOG.md 2>/dev/null | grep -qE '"^\+## \[[0-9]"'; then
    fail "New versioned release entry added — only the maintainer creates ## [x.y.z] sections"
else
    ok "No versioned release entries added by contributor"
fi

# ── 2. Forbidden files ───────────────────────────────────────────────────────
hdr "Forbidden files"

UPSTREAM_BASE=$(git merge-base HEAD upstream/main 2>/dev/null || git merge-base HEAD origin/main 2>/dev/null || echo "")
if [ -n "$UPSTREAM_BASE" ]; then
    CHANGED=$(git diff --name-only "$UPSTREAM_BASE" HEAD)
    if echo "$CHANGED" | grep -q "^\.idea/gradle\.xml$"; then
        fail ".idea/gradle.xml is included — contains local JDK path, must not be in PRs"
    else
        ok ".idea/gradle.xml not included"
    fi
    if echo "$CHANGED" | grep -q "^scripts/build-install\.sh$"; then
        fail "scripts/build-install.sh is included — local helper, must not be in PRs"
    else
        ok "scripts/build-install.sh not included"
    fi
    if echo "$CHANGED" | grep -qE "^docs/pr-.+\.md$"; then
        fail "docs/pr-*.md file included — rename to remove pr- prefix before submitting"
    else
        ok "No docs/pr-*.md files"
    fi
else
    echo "  ? Could not determine upstream base — skipping forbidden-files check"
fi

# ── 3. Deprecated / internal API ─────────────────────────────────────────────
hdr "API compliance"

if git diff "${UPSTREAM_BASE:-HEAD~1}" HEAD -- src/main/ 2>/dev/null | grep -q "NON_MODAL\b"; then
    fail "ModalityState.NON_MODAL found — use ModalityState.nonModal()"
else
    ok "No deprecated ModalityState.NON_MODAL"
fi

if git diff "${UPSTREAM_BASE:-HEAD~1}" HEAD -- src/main/ 2>/dev/null | grep -q "getDeclaredField\|isAccessible = true"; then
    fail "Reflection on private fields detected — likely internal API usage; use public builder APIs"
else
    ok "No reflection on private fields"
fi

# ── 4. ToolNames.ALL sorted ──────────────────────────────────────────────────
hdr "ToolNames.ALL sort order"

TOOLNAMES="src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/constants/ToolNames.kt"
if [ -f "$TOOLNAMES" ]; then
    python3 - <<'PYEOF'
import re, sys
content = open("src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/constants/ToolNames.kt").read()
vals = {m.group(1): m.group(2) for m in re.finditer(r'const val (\w+) = "(ide_\w+)"', content)}
m = re.search(r'val ALL.*?=.*?listOf\((.*?)\)', content, re.DOTALL)
if m:
    names = [n.strip() for n in m.group(1).split(',') if n.strip()]
    resolved = [vals.get(n, '???' + n) for n in names]
    if resolved == sorted(resolved):
        print("  ✓ ToolNames.ALL is sorted")
        sys.exit(0)
    else:
        for i, (a, b) in enumerate(zip(resolved, sorted(resolved))):
            if a != b:
                print(f"  ✗ ToolNames.ALL out of order at position {i}: has '{a}', expected '{b}'")
                break
        sys.exit(1)
PYEOF
    [ $? -eq 0 ] && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
else
    echo "  ? ToolNames.kt not found — skipping sort check"
fi

# ── 5. New tools in disabledTools ────────────────────────────────────────────
hdr "New tools disabled by default"

if [ -n "$UPSTREAM_BASE" ]; then
    NEW_TOOLS=$(git diff "$UPSTREAM_BASE" HEAD -- src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/constants/ToolNames.kt 2>/dev/null \
        | grep '^+.*const val.*= "ide_' | grep -oE '"ide_[^"]+"' | tr -d '"' || true)
    DISABLED=$(grep -oE '"ide_[^"]+"' src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/settings/McpSettings.kt | tr -d '"' || true)
    for tool in $NEW_TOOLS; do
        if echo "$DISABLED" | grep -q "^${tool}$"; then
            ok "$tool is in disabledTools"
        else
            fail "$tool is NOT in McpSettings.disabledTools — all new tools must be opt-in"
        fi
    done
    [ -z "$NEW_TOOLS" ] && ok "No new tools added (or none detected)"
fi

# ── 6. Unit tests ────────────────────────────────────────────────────────────
hdr "Unit tests"

echo "  Running ./gradlew test --tests \"*UnitTest*\" ..."
if ./gradlew test --tests "*UnitTest*" 2>&1 | grep -q "BUILD SUCCESSFUL"; then
    ok "Unit tests pass"
else
    fail "Unit tests FAILED — fix before pushing"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "────────────────────────────────"
echo "  Passed: $PASS  Failed: $FAIL"
echo "────────────────────────────────"
if [ "$FAIL" -gt 0 ]; then
    echo "Fix the failures above before pushing. See CONTRIBUTING.md for details."
    exit 1
else
    echo "All checks passed."
fi
