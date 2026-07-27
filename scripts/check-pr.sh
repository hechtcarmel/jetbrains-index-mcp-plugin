#!/usr/bin/env bash
# check-pr.sh — pre-push validation against CONTRIBUTING.md rules.
# Run before every push. Exits non-zero if any check fails.
set -euo pipefail

# All paths below are repo-root-relative; run from anywhere.
cd "$(dirname "${BASH_SOURCE[0]}")/.." 2>/dev/null || cd "$(git rev-parse --show-toplevel)"

# Computed once, before any check uses it. An exported UPSTREAM_BASE overrides detection.
UPSTREAM_BASE=${UPSTREAM_BASE:-$(git merge-base HEAD upstream/main 2>/dev/null || git merge-base HEAD origin/main 2>/dev/null || echo "")}

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
if git diff "${UPSTREAM_BASE:-HEAD~1}" HEAD -- CHANGELOG.md 2>/dev/null | grep -qE '^\+## \[[0-9]'; then
    fail "New versioned release entry added — only the maintainer creates ## [x.y.z] sections"
else
    ok "No versioned release entries added by contributor"
fi

# ── 2. Forbidden files ───────────────────────────────────────────────────────
hdr "Forbidden files"

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

# Only ADDED lines count: a PR that removes a banned usage must not fail the gate.
API_ADDED=$(git diff "${UPSTREAM_BASE:-HEAD~1}" HEAD -- src/main/ 2>/dev/null | grep '^+' | grep -v '^+++' || true)

if echo "$API_ADDED" | grep -qE 'NON_MODAL\b'; then
    fail "ModalityState.NON_MODAL added — use ModalityState.nonModal()"
else
    ok "No deprecated ModalityState.NON_MODAL"
fi

if echo "$API_ADDED" | grep -qE 'ModalityState\.ANY\b'; then
    fail "ModalityState.ANY added — use ModalityState.any()"
else
    ok "No deprecated ModalityState.ANY"
fi

if echo "$API_ADDED" | grep -qE 'getDeclaredField|isAccessible = true'; then
    fail "Reflection on private fields added — likely internal API usage; use public builder APIs"
else
    ok "No reflection on private fields"
fi

# ── 4. ToolNames.ALL sorted ──────────────────────────────────────────────────
hdr "ToolNames.ALL sort order"

TOOLNAMES="src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/constants/ToolNames.kt"
if [ -f "$TOOLNAMES" ]; then
    if python3 - <<'PYEOF'
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
else:
    print("  ✗ Could not locate 'val ALL = listOf(...)' in ToolNames.kt — the sort-check regex needs updating")
    sys.exit(1)
PYEOF
    then
        PASS=$((PASS+1))
    else
        FAIL=$((FAIL+1))
    fi
else
    echo "  ? ToolNames.kt not found — skipping sort check"
fi

# ── 5. New tools in disabledTools ────────────────────────────────────────────
hdr "New tools disabled by default"

if [ -n "$UPSTREAM_BASE" ]; then
    NEW_TOOLS=$(git diff "$UPSTREAM_BASE" HEAD -- src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/constants/ToolNames.kt 2>/dev/null \
        | grep '^+.*const val.*= "ide_' | grep -oE '"ide_[^"]+"' | tr -d '"' || true)
    # A name already present in the base revision is not new. Without this, any cosmetic edit to
    # an existing constant line (reindent, added comment, renamed Kotlin identifier, moved group)
    # re-emits it as an addition and the schema-version gate below would demand a settings
    # migration for a no-op change.
    BASE_TOOLS=$(git show "$UPSTREAM_BASE:$TOOLNAMES" 2>/dev/null | grep -oE '"ide_[^"]+"' | tr -d '"' | sort -u || true)
    NEW_TOOLS=$(comm -23 <(printf '%s\n' $NEW_TOOLS | sort -u) <(printf '%s\n' $BASE_TOOLS | sort -u) || true)
    # Resolve disabled tools from McpSettings.kt — supports both literal "ide_..." strings
    # and ToolNames.CONSTANT references (the current convention).
    DISABLED=$(python3 - <<'PYEOF'
import re
tn_path = "src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/constants/ToolNames.kt"
ms_path = "src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/settings/McpSettings.kt"
tn = open(tn_path).read()
ms = open(ms_path).read()
vals = {m.group(1): m.group(2) for m in re.finditer(r'const val (\w+) = "(ide_\w+)"', tn)}
seen = set()
for m in re.finditer(r'"(ide_\w+)"', ms):
    seen.add(m.group(1))
for m in re.finditer(r'ToolNames\.(\w+)', ms):
    v = vals.get(m.group(1))
    if v:
        seen.add(v)
for t in sorted(seen):
    print(t)
PYEOF
    )
    for tool in $NEW_TOOLS; do
        if echo "$DISABLED" | grep -q "^${tool}$"; then
            ok "$tool is in disabledTools"
        else
            fail "$tool is NOT in McpSettings.disabledTools — all new tools must be opt-in"
        fi
    done

    # Six-doc-location consistency (CONTRIBUTING.md § Quick consistency check). These are the five
    # grep-able doc files. The sixth location — ToolNames.kt / McpSettings.kt / ToolRegistry.kt —
    # is only partly covered: check 4 verifies ALL is sorted but not that the tool is in it, and
    # nothing here checks ToolRegistry registration. ToolExecutionIntegrationTest catches both.
    DOC_LOCATIONS="README.md USAGE.md CLAUDE.md src/main/resources/skill/ide-index-mcp/SKILL.md src/main/resources/skill/ide-index-mcp/references/tools-reference.md"
    for tool in $NEW_TOOLS; do
        MISSING_DOCS=""
        for doc in $DOC_LOCATIONS; do
            grep -qw "$tool" "$doc" 2>/dev/null || MISSING_DOCS="$MISSING_DOCS $doc"
        done
        if [ -z "$MISSING_DOCS" ]; then
            ok "$tool present in all doc locations"
        else
            fail "$tool missing from:$MISSING_DOCS"
        fi
    done

    # A new tool is opt-in, so ToolSettingsDefaults.CURRENT_SCHEMA_VERSION (McpSettings.kt) must
    # be bumped with a migration entry, or existing users' persisted settings ship it enabled.
    if [ -n "$NEW_TOOLS" ]; then
        MCPSETTINGS="src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/settings/McpSettings.kt"
        if git diff "$UPSTREAM_BASE" HEAD -- "$MCPSETTINGS" 2>/dev/null | grep '^+' | grep -v '^+++' | grep -q 'CURRENT_SCHEMA_VERSION'; then
            ok "CURRENT_SCHEMA_VERSION touched for new tool(s)"
        else
            fail "New tool(s) added but CURRENT_SCHEMA_VERSION in McpSettings.kt is untouched — bump it and add a DEFAULT_DISABLED_TOOL_MIGRATIONS entry"
        fi
    fi
    [ -z "$NEW_TOOLS" ] && ok "No new tools added (or none detected)"
fi

# ── 6. Code correctness — proxy safety ───────────────────────────────────────
hdr "Reflection proxy safety"

PROXY_FILES=$(grep -rln "Proxy.newProxyInstance" src/main/ 2>/dev/null || true)
if [ -n "$PROXY_FILES" ]; then
    PROXY_UNSAFE=""
    for pf in $PROXY_FILES; do
        if ! grep -q '"equals"' "$pf" 2>/dev/null; then
            PROXY_UNSAFE="$PROXY_UNSAFE  $pf"$'\n'
        fi
    done
    if [ -n "$PROXY_UNSAFE" ]; then
        fail "Proxy.newProxyInstance without equals/hashCode/toString handling:"
        printf '%s' "$PROXY_UNSAFE"
    else
        ok "All proxies handle equals/hashCode/toString"
    fi
else
    ok "No Proxy.newProxyInstance calls found"
fi

# ── 7. Code correctness — silent exception swallowing ────────────────────────
hdr "Exception handling"

if [ -n "$UPSTREAM_BASE" ]; then
    SILENT_CATCH_NULL=$(git diff "$UPSTREAM_BASE" HEAD -- src/main/ 2>/dev/null | grep -c '^\+.*catch.*(_:.*Exception).*null' || true)
    SILENT_CATCH_NULL=${SILENT_CATCH_NULL:-0}
    if [ "$SILENT_CATCH_NULL" -gt 0 ] 2>/dev/null; then
        fail "$SILENT_CATCH_NULL new catch blocks return null — verify each distinguishes 'unavailable' from 'broken' (CONTRIBUTING.md § Error handling)"
    else
        ok "No new catch-and-return-null patterns in diff"
    fi
fi

# ── 8. Code correctness — test skip honesty ──────────────────────────────────
hdr "Test skip honesty"

# Whole-tree, not diff-only: a diff-scoped check permanently grandfathers existing violations.
#
# Two rule sets, because the two shapes have different false-positive profiles:
#
#   A. A capability guard (`if (!javaAvailable) return`) is dishonest wherever it appears — in a
#      helper it makes the helper a silent no-op and the caller's assertions run against an
#      un-set-up fixture. Checked in every function.
#
#   B. An emptiness/null guard or an elvis bare return is only dishonest inside a test method,
#      where it reports PASSED having asserted nothing. In a helper it is ordinary Kotlin —
#      `setFieldIfPresent` in GetDiagnosticsToolBehaviorTest is a legitimate `?: return`.
#      Scoped to `fun test*` bodies, tracking the innermost enclosing `fun` so a local function
#      declared inside a test method is treated as the helper it is.
#
# A bare `return` is the tell in both cases: `return someValue` is control flow, `return` alone
# after a guard is a skip. `if (expected == actual) return` survives on purpose — that is an
# early exit on a satisfied assertion, not an unmet precondition.
if SKIP_REPORT=$(python3 - <<'PYEOF'
import re, sys
from pathlib import Path

CAPABILITY = r'(available|capabilit|supported|enabled)'
EMPTY_OR_NULL = r'(isEmpty\(\)|isBlank\(\)|isNullOrEmpty\(\)|isNullOrBlank\(\)|==\s*null|!=\s*null)'
BARE_RETURN = r'return(@\w+)?\s*(//.*)?$'

FUN_DECL = re.compile(r'\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*[(<]')
CAPABILITY_GUARD = re.compile(r'if\s*\(.*' + CAPABILITY + r'.*\)\s*' + BARE_RETURN, re.IGNORECASE)
EMPTY_GUARD = re.compile(r'if\s*\(.*' + EMPTY_OR_NULL + r'.*\)\s*' + BARE_RETURN)
ELVIS_GUARD = re.compile(r'\?:\s*' + BARE_RETURN)
STANDALONE_RETURN = re.compile(r'^\s*' + BARE_RETURN)
CONDITION = re.compile(CAPABILITY + '|' + EMPTY_OR_NULL, re.IGNORECASE)

offenders = []
for path in sorted(Path('src/test').rglob('*.kt')):
    enclosing = ''
    previous = ''
    for lineno, line in enumerate(path.read_text().splitlines(), start=1):
        code = line.split('//')[0]
        decl = FUN_DECL.search(code)
        if decl:
            enclosing = decl.group(1)
        in_test = enclosing.startswith('test')
        hit = CAPABILITY_GUARD.search(line)
        if not hit and in_test:
            hit = (EMPTY_GUARD.search(line) or ELVIS_GUARD.search(line)
                   # `if (roots.isEmpty())` on one line, `return` on the next.
                   or (STANDALONE_RETURN.search(line) and CONDITION.search(previous)))
        if hit:
            offenders.append(f'{path}:{lineno}:{line.strip()}')
        if code.strip():
            previous = code
for o in offenders:
    print(o)
sys.exit(1 if offenders else 0)
PYEOF
); then
    ok "No early-return test skips"
else
    fail "Early-return test skips — use Assume.assumeTrue() instead (CONTRIBUTING.md § Test honesty)"
    printf '%s\n' "$SKIP_REPORT" | head -10 | sed 's/^/      /'
fi

# ── 9. Plugin-detector impersonation ─────────────────────────────────────────
hdr "Test tree hygiene"

# A test class sitting at a PluginDetector fallback FQN makes that detector report its plugin
# as available for the entire test fork (the result is cached `by lazy` on an object). See
# src/test/kotlin/.../testutil/README.md and PluginDetectorLeakUnitTest.
# The fallback FQNs are read from production so this check cannot drift.
LEAKS=0
while read -r FQN; do
    [ -z "$FQN" ] && continue
    CANDIDATE="src/test/kotlin/$(echo "$FQN" | tr '.' '/').kt"
    if [ -f "$CANDIDATE" ]; then
        fail "$CANDIDATE sits at PluginDetector fallback FQN '$FQN' — it will make that plugin appear available in every test. Use a duck-typed fake in the test's own package."
        LEAKS=$((LEAKS+1))
    fi
done <<EOF
$(grep -oE 'fallbackClass = "[^"]+"' src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/util/PluginDetectors.kt 2>/dev/null | sed 's/fallbackClass = "//; s/"//')
EOF
if [ "$LEAKS" -eq 0 ]; then
    ok "No test classes at PluginDetector fallback FQNs"
fi

# PluginManager.findEnabledPlugin was rejected in JetBrains Marketplace review; PluginDetector
# must keep using PluginManagerCore.isLoaded/isDisabled. Previously asserted by a test that read
# the source file off disk — a lint rule belongs here, not in the test suite.
if grep -qE '^import com\.intellij\.ide\.plugins\.PluginManager$|findEnabledPlugin' \
    src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/util/PluginDetector.kt 2>/dev/null; then
    fail "PluginDetector uses PluginManager.findEnabledPlugin — rejected in Marketplace review. Use PluginManagerCore.isLoaded/isDisabled."
else
    ok "PluginDetector avoids the rejected PluginManager API"
fi

# ── 10. Tests ─────────────────────────────────────────────────────────────────
hdr "Tests"

# Exit code, not a grep for BUILD SUCCESSFUL: Gradle prints that when :test is UP-TO-DATE, so the
# old check passed having executed nothing.
#
# --rerun-tasks, not cleanTest: with org.gradle.caching=true, cleanTest alone still resolves to
# ":test FROM-CACHE" and the gate reports "All tests pass" in 3s having run nothing. Input-keyed
# caching means a real code change does invalidate it, but the gate should not be blind to flakes
# or print a claim it did not verify.
# Gradle 9 needs Java 17+ to launch; fail with an actionable message instead of letting the
# Gradle bootstrap die cryptically on an old default JDK (this machine defaults to JDK 8).
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAVA_MAJOR=$("$JAVA_BIN" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | sed -E 's/^1\.//; s/[^0-9].*$//' || true)
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 17 ]; then
    fail "Gradle needs Java 17+ but '$JAVA_BIN' is version ${JAVA_MAJOR:-unknown} — set JAVA_HOME to a JDK 17+ (e.g. export JAVA_HOME=\$(/usr/libexec/java_home -v 21) for Zulu 21) and re-run"
else
    echo "  Running ./gradlew test --rerun-tasks ..."
    if ./gradlew test --rerun-tasks; then
        ok "All tests pass"
    else
        fail "Tests FAILED — fix before pushing"
    fi
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
