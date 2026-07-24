#!/bin/bash
# Tests for intellij-first.sh hook
# Run: bash tests/hook-tests.sh
#
# Each test sends a JSON payload to the hook via stdin (simulating Claude Code's
# PreToolUse event) and checks the exit code:
#   0 = allowed
#   2 = blocked
#
# Usage: assert_blocked "test name" "command string"
#        assert_allowed "test name" "command string"

HOOK="$(dirname "$0")/../docs/intellij-first.sh"
if [ ! -f "$HOOK" ]; then
  # Fall back to the installed hook for development
  HOOK="$HOME/.claude/hooks/intellij-first.sh"
fi

PASS=0
FAIL=0

assert_blocked() {
  local name="$1"
  local cmd="$2"
  local json="{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":$(echo "$cmd" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().strip()))')}}"
  local output
  output=$(echo "$json" | bash "$HOOK" 2>&1)
  local rc=$?
  if [ $rc -eq 2 ]; then
    PASS=$((PASS + 1))
  else
    FAIL=$((FAIL + 1))
    echo "FAIL (expected BLOCK): $name"
    echo "  command: $cmd"
    echo "  exit: $rc"
    echo "  output: $output"
  fi
}

assert_allowed() {
  local name="$1"
  local cmd="$2"
  local json="{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":$(echo "$cmd" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().strip()))')}}"
  local output
  output=$(echo "$json" | bash "$HOOK" 2>&1)
  local rc=$?
  if [ $rc -eq 0 ]; then
    PASS=$((PASS + 1))
  else
    FAIL=$((FAIL + 1))
    echo "FAIL (expected ALLOW): $name"
    echo "  command: $cmd"
    echo "  exit: $rc"
    echo "  output: $output"
  fi
}

echo "=== Pattern 1: grep -r on source files ==="
assert_blocked "grep -rn on java"            'grep -rn "pattern" --include="*.java" src/'
assert_blocked "grep -rl on kotlin"           'grep -rl "pattern" --include="*.kt" src/'
assert_blocked "grep -r on src/main"          'grep -r "something" src/main/'
assert_blocked "grep -r on src/test"          'grep -r "something" src/test/'
assert_allowed "grep without -r"              'grep "pattern" file.txt'
assert_allowed "grep -r on non-source"        'grep -r "pattern" docs/'
assert_allowed "grep on single file"          'grep "pattern" src/main/java/Foo.java'

echo ""
echo "=== Pattern 2: mvn piped to grep ==="
assert_blocked "mvn compile | grep"           'mvn compile | grep ERROR'
assert_blocked "mvn package | awk"            'mvn package | awk "/ERROR/"'
assert_allowed "mvn compile alone"            'mvn compile'
assert_allowed "mvn test (not matched)"        'mvn test | grep FAIL'

echo ""
echo "=== Pattern 3: find + grep on source files ==="
assert_blocked "find java + grep"             'find . -name "*.java" | xargs grep "pattern"'
assert_blocked "find kt + grep"               'find . -name "*.kt" | grep -l "pattern"'
assert_allowed "find without grep"            'find . -name "*.java" -type f'
assert_allowed "find non-source + grep"       'find . -name "*.md" | xargs grep "pattern"'

echo ""
echo "=== Pattern 4: sed -i on source files ==="
assert_blocked "sed -i on java"               'sed -i "s/old/new/g" src/Foo.java'
assert_blocked "sed -ie on kotlin"            'sed -ie "s/old/new/" src/Bar.kt'
assert_allowed "sed without -i"               'sed "s/old/new/" src/Foo.java'
assert_allowed "sed -i on non-source"         'sed -i "s/old/new/" config.yml'

echo ""
echo "=== Pattern 5: python3 /tmp/ scripts (hook bypass) ==="
assert_blocked "python3 /tmp/fix.py"          'python3 /tmp/fix-resolver.py'
assert_blocked "python3 /tmp/script.py"       'python3 /tmp/script.py'
assert_blocked "python3 /tmp/edit.py"         'python3 /tmp/edit-member.py'

echo ""
echo "=== Pattern 5: false positives that should be ALLOWED ==="
assert_allowed "gh pr create mentioning tmp"  'gh pr create --title "fix" --body "block python3 /tmp/ bypass"'
assert_allowed "echo mentioning tmp"          'echo "the hook blocks python3 /tmp/ scripts"'
assert_allowed "git commit with tmp in msg"   'git commit -m "fix: block python3 /tmp/ bypass"'
assert_allowed "cat a doc mentioning tmp"     'cat docs/hooks.md'
assert_allowed "grep for tmp pattern"         'grep "python3 /tmp/" docs/hooks.md'

echo ""
echo "=== Pattern 5: chained commands with tmp scripts ==="
assert_blocked "cmd && python3 /tmp/"         'echo "setup" && python3 /tmp/fix.py'
assert_blocked "cmd ; python3 /tmp/"          'cd /project ; python3 /tmp/edit.py'
assert_blocked "pipe to python3 /tmp/"        'cat data | python3 /tmp/process.py'

echo ""
echo "=== Pattern 5b: python3 -c inline file manipulation ==="
assert_blocked "python3 inline open java"     'python3 -c "open(\"Foo.java\",\"w\").write(\"x\")"'
assert_blocked "python3 inline re.sub kt"     'python3 -c "import re; re.sub(\"x\",\"y\",open(\"Bar.kt\").read())"'
assert_allowed "python3 -c no file ext"       'python3 -c "print(42)"'
assert_allowed "python3 -c with .py import"   'python3 -c "import json; print(json.dumps({}))"'

echo ""
echo "=== Pattern 6: mv on source files ==="
assert_blocked "mv java file"                 'mv src/Old.java src/New.java'
assert_blocked "mv kotlin file"               'mv src/Old.kt src/New.kt'
assert_allowed "mv java to /tmp/"             'mv src/Backup.java /tmp/'
assert_allowed "mv non-source"                'mv old.yml new.yml'

echo ""
echo "=== Pattern 7: cp on source files ==="
assert_blocked "cp java file"                 'cp src/Foo.java src/FooCopy.java'
assert_allowed "cp non-source"                'cp config.yml config.bak'

echo ""
echo "=== Pattern 8: rm on source files (warn, don't block) ==="
assert_allowed "rm java file (warn only)"     'rm src/Foo.java'
assert_allowed "rm kotlin file (warn only)"   'rm src/Bar.kt'

echo ""
echo "=== Safe commands that should never be blocked ==="
assert_allowed "git status"                   'git status'
assert_allowed "git log"                      'git log --oneline -5'
assert_allowed "git diff"                     'git diff HEAD~1'
assert_allowed "ls"                           'ls -la src/'
assert_allowed "cat a java file"              'cat src/Foo.java'
assert_allowed "pwd"                          'pwd'
assert_allowed "gradlew test"                 './gradlew test --tests "*UnitTest*"'
assert_allowed "gh pr list"                   'gh pr list --repo owner/repo'
assert_allowed "python3 version"              'python3 --version'
assert_allowed "python3 json one-liner"       "python3 -c 'import json; print(json.dumps({\"a\": 1}))'"

echo ""
echo "════════════════════════════"
echo "  Passed: $PASS  Failed: $FAIL"
echo "════════════════════════════"

if [ $FAIL -gt 0 ]; then
  exit 1
fi
