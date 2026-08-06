#!/bin/bash
# IDE Index MCP Server — EDT Freeze Watchdog
# Installed by the IDE Index MCP plugin. Do not edit — reinstall to update.
#
# Detects frozen IntelliJ EDT (Event Dispatch Thread) via jstack and
# auto-restarts the IDE. The MCP HTTP server stays responsive when the
# EDT is frozen, so HTTP health checks cannot detect UI freezes — only
# jstack thread analysis can.
#
# Run via crontab every 30 seconds:
#   * * * * * "/path/to/watchdog.sh"
#   * * * * * sleep 30 && "/path/to/watchdog.sh"

JSTACK="__JSTACK_PATH__"
IDE_LAUNCH="__IDE_LAUNCH_CMD__"
IDE_PID_PATTERN="__IDE_PID_PATTERN__"
FAIL_THRESHOLD=2
WATCHDOG_DIR="$HOME/.ide-index-mcp/watchdog"
STATE_FILE="$WATCHDOG_DIR/failures"
LOG_FILE="$WATCHDOG_DIR/watchdog.log"

mkdir -p "$WATCHDOG_DIR"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S'): $1" >> "$LOG_FILE"; }

read_failures() { cat "$STATE_FILE" 2>/dev/null || echo 0; }
write_failures() { echo "$1" > "$STATE_FILE"; }

# Trim log to last 1000 lines periodically
if [ -f "$LOG_FILE" ] && [ "$(wc -l < "$LOG_FILE")" -gt 2000 ]; then
    tail -1000 "$LOG_FILE" > "$LOG_FILE.tmp" && mv "$LOG_FILE.tmp" "$LOG_FILE"
fi

# Find IDE process
IDE_PID=$(pgrep -f "$IDE_PID_PATTERN" | head -1)
if [ -z "$IDE_PID" ]; then
    write_failures 0
    exit 0
fi

# Verify jstack exists
if [ ! -x "$JSTACK" ]; then
    exit 0
fi

# jstack the process — 10 second timeout
EDT_DUMP=$(timeout 10 "$JSTACK" "$IDE_PID" 2>/dev/null | sed -n '/AWT-EventQueue/,/^$/p')

if [ -z "$EDT_DUMP" ]; then
    # jstack failed or EDT not found — process may be starting up
    exit 0
fi

# Check if EDT is in normal idle state (waiting for next event to process)
if echo "$EDT_DUMP" | grep -q "EventQueue.getNextEvent\|NonBlockingFlushQueue"; then
    FAILURES=$(read_failures)
    if [ "$FAILURES" -gt 0 ]; then
        log "Recovered after $FAILURES failure(s)"
        write_failures 0
    fi
    exit 0
fi

# Check EDT thread state — only flag WAITING or BLOCKED, not RUNNABLE
EDT_STATE=$(echo "$EDT_DUMP" | head -1)
if ! echo "$EDT_STATE" | grep -q "WAITING\|BLOCKED\|parking\|waiting on"; then
    # EDT is RUNNABLE — it's doing work, not frozen
    write_failures 0
    exit 0
fi

# EDT is in WAITING/BLOCKED outside the normal idle loop — frozen
FAILURES=$(($(read_failures) + 1))
TOP_FRAME=$(echo "$EDT_DUMP" | grep "at " | head -1 | sed 's/^[ 	]*//')
write_failures "$FAILURES"
log "EDT frozen: $TOP_FRAME ($FAILURES/$FAIL_THRESHOLD)"

if [ "$FAILURES" -ge "$FAIL_THRESHOLD" ]; then
    log "RESTARTING IDE — EDT frozen for $FAIL_THRESHOLD consecutive checks"
    kill -9 "$IDE_PID" 2>/dev/null
    sleep 3
    eval "$IDE_LAUNCH" &
    disown 2>/dev/null
    log "IDE relaunched"
    write_failures 0
fi
