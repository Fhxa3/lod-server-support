#!/usr/bin/env bash
set -euo pipefail

# LSS Soak Test Orchestrator
# Usage: ./scripts/soak.sh <scenario>|all
#   scenario: fresh-backfill | warm-rejoin | dimension-trip | dirty-broadcast
#
# Runs a real dedicated server + headless client through a scripted timeline
# (scripts/soak-scenarios/<name>.json), collects jsonl snapshots from both
# sides into soak-results/<scenario>-<timestamp>/, then runs
# scripts/check_soak.py against them. Exit code = checker exit code.

SCENARIO="${1:-}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SELF="$PROJECT_ROOT/scripts/soak.sh"
SERVER_RUN_DIR="$PROJECT_ROOT/fabric/build/run/soak-server"
CLIENT_RUN_DIR="$PROJECT_ROOT/fabric/build/run/soak-client"
RESULTS_ROOT="$PROJECT_ROOT/soak-results"
WORLDS_DIR="$PROJECT_ROOT/soak-worlds"
SCENARIOS_DIR="$PROJECT_ROOT/scripts/soak-scenarios"
ALL_SCENARIOS=(fresh-backfill warm-rejoin dimension-trip dirty-broadcast)
LOG_PREFIX="soak"

source "$PROJECT_ROOT/scripts/lib/mc-run.sh"

usage() {
    echo "Usage: $0 <scenario>|all"
    echo "  scenarios: ${ALL_SCENARIOS[*]}"
}

if [[ -z "$SCENARIO" ]]; then
    usage
    exit 1
fi

# 'all' runs every scenario in spec order; set -e stops at the first failure
# and propagates the failing child's exit code.
if [[ "$SCENARIO" == "all" ]]; then
    for s in "${ALL_SCENARIOS[@]}"; do
        "$SELF" "$s"
    done
    echo "[soak] All scenarios passed"
    exit 0
fi

case "$SCENARIO" in
    fresh-backfill|warm-rejoin|dimension-trip|dirty-broadcast) ;;
    *)
        echo "[soak] ERROR: Unknown scenario '$SCENARIO'"
        usage
        exit 1
        ;;
esac

# Per-scenario knobs: number of client runs and expected end-to-end seconds.
# Kill switch budget = expected + 240s slack.
case "$SCENARIO" in
    fresh-backfill)  CLIENT_RUNS=1; EXPECTED_SECONDS=280 ;;
    warm-rejoin)     CLIENT_RUNS=2; EXPECTED_SECONDS=360 ;;
    dimension-trip)  CLIENT_RUNS=1; EXPECTED_SECONDS=420 ;;
    dirty-broadcast) CLIENT_RUNS=1; EXPECTED_SECONDS=240 ;;
esac
RUNTIME_BUDGET=$((EXPECTED_SECONDS + 240))
DEADLINE_EPOCH=0

SCENARIO_JSON="$SCENARIOS_DIR/$SCENARIO.json"
SCENARIO_CONFIG="$SCENARIOS_DIR/$SCENARIO-config.json"
for f in "$SCENARIO_JSON" "$SCENARIO_CONFIG"; do
    if [[ ! -f "$f" ]]; then
        echo "[soak] ERROR: Missing scenario file: $f"
        exit 1
    fi
done

trap mc_cleanup EXIT

# Hard ceiling on total scenario runtime, armed once the server is ready.
soak_check_deadline() {
    if [[ "$DEADLINE_EPOCH" -gt 0 ]] && (( $(date +%s) >= DEADLINE_EPOCH )); then
        echo "[soak] ERROR: Runtime exceeded ${RUNTIME_BUDGET}s budget (expected ~${EXPECTED_SECONDS}s + 240s slack), killing server and client"
        [[ -n "$SERVER_PID" ]] && kill "$SERVER_PID" 2>/dev/null || true
        [[ -n "$CLIENT_PID" ]] && kill "$CLIENT_PID" 2>/dev/null || true
        exit 1
    fi
}

soak_port_in_use() {
    if command -v ss >/dev/null 2>&1; then
        [[ -n "$(ss -ltn 2>/dev/null | awk '$4 ~ /:25565$/')" ]]
    else
        [[ -n "$(awk '$2 ~ /:63[Dd][Dd]$/' /proc/net/tcp /proc/net/tcp6 2>/dev/null)" ]]
    fi
}

echo "========================================="
echo " LSS Soak: scenario=$SCENARIO, client runs=$CLIENT_RUNS, budget=${RUNTIME_BUDGET}s"
echo "========================================="

# Step 1: Auto-run fresh-backfill first if a base world is required but missing
if [[ "$SCENARIO" != "fresh-backfill" && ! -d "$WORLDS_DIR/base/world" ]]; then
    echo "[soak] No base world at $WORLDS_DIR/base/world — running fresh-backfill first"
    "$SELF" fresh-backfill
fi

# Step 2: Pre-flight — validate the scenario timeline before anything boots
echo "[soak] Validating scenario..."
python3 "$PROJECT_ROOT/scripts/check_soak.py" --validate "$SCENARIO"

# Step 3: Build
echo "[soak] Building mod..."
cd "$PROJECT_ROOT"
./gradlew :fabric:build -x test -x runGameTest -x runClientGameTest --quiet

# Step 4: Prepare run + results directories
mkdir -p "$SERVER_RUN_DIR" "$CLIENT_RUN_DIR"
RUN_RESULTS_DIR="$RESULTS_ROOT/$SCENARIO-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$RUN_RESULTS_DIR"

# Step 5a: Stage world
echo "[soak] Staging world for scenario: $SCENARIO"
case "$SCENARIO" in
    fresh-backfill)
        rm -rf "$SERVER_RUN_DIR/world"
        rm -rf "$SERVER_RUN_DIR/world_nether"
        rm -rf "$SERVER_RUN_DIR/world_the_end"
        ;;
    *)
        rm -rf "$SERVER_RUN_DIR/world"
        cp -r "$WORLDS_DIR/base/world" "$SERVER_RUN_DIR/world"
        ;;
esac

# Step 5b: Stage client column cache. warm-rejoin clears too: its run 1 IS the
# cache-populating run (otherwise, under 'all' ordering, run 1 starts warm from the
# previous scenario's cache and the run1-vs-run2 named check has nothing to compare).
case "$SCENARIO" in
    fresh-backfill|dimension-trip|warm-rejoin)
        echo "[soak] Clearing client column cache"
        rm -rf "$CLIENT_RUN_DIR/config/lss/cache"
        ;;
    dirty-broadcast)
        echo "[soak] Keeping client column cache"
        ;;
esac

# Step 6a: Stage server config override
mkdir -p "$SERVER_RUN_DIR/config"
cp "$SCENARIO_CONFIG" "$SERVER_RUN_DIR/config/lss-server-config.json"

# Step 6b: Write server.properties + eula.txt. Superflat: fresh noise terrain carries
# minutes of unsettled fluid ticks (aquifers, gen-border flows) that mutate chunk content
# on every save cycle and keep the system from ever quiescing — flat terrain settles
# instantly and the conservation laws don't care about terrain shape.
cat > "$SERVER_RUN_DIR/server.properties" <<'PROPS'
online-mode=false
level-seed=soak-seed-42
level-type=minecraft\:flat
spawn-protection=0
max-tick-time=-1
pause-when-empty-seconds=-1
view-distance=8
gamemode=creative
force-gamemode=true
PROPS

echo "eula=true" > "$SERVER_RUN_DIR/eula.txt"

# Step 6c: Write client options.txt to bypass first-launch screens and pin render distance
cat > "$CLIENT_RUN_DIR/options.txt" <<'OPTS'
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
renderDistance:8
soundCategory_master:0.0
OPTS

# Step 7: Clear stale server log and stale soak-results from previous runs
rm -f "$SERVER_RUN_DIR/logs/latest.log"
rm -rf "$SERVER_RUN_DIR/soak-results" "$CLIENT_RUN_DIR/soak-results"

# Step 8: Pre-flight — refuse to start on top of a stale server
if soak_port_in_use; then
    echo "[soak] ERROR: Port 25565 is already in use — a stale dev server is likely still running."
    echo "[soak] Stop it first, e.g.: pkill -f net.fabricmc.devlaunchinjector"
    exit 1
fi

# Step 9: Start server and arm the kill switch once it is ready
mc_start_server "$RUN_RESULTS_DIR/server.log" :fabric:runSoakServer -Psoak.scenario="$SCENARIO_JSON" ${SOAK_EXTRA_GRADLE_ARGS:-}
mc_wait_server_ready "$SERVER_RUN_DIR/logs/latest.log" "$RUN_RESULTS_DIR/server.log" 120
DEADLINE_EPOCH=$(( $(date +%s) + RUNTIME_BUDGET ))

# Step 10: Client runs (the server kicks the client between runs / halts at scenario end)
for (( run=1; run<=CLIENT_RUNS; run++ )); do
    echo "[soak] Client run $run/$CLIENT_RUNS"
    mc_start_client "$RUN_RESULTS_DIR/client-run$run.log" :fabric:runSoakClient
    while kill -0 "$CLIENT_PID" 2>/dev/null; do
        soak_check_deadline
        sleep 1
    done
    wait "$CLIENT_PID" 2>/dev/null || true
    CLIENT_PID=""
    echo "[soak] Client run $run exited"
    if [[ -f "$CLIENT_RUN_DIR/soak-results/client.jsonl" ]]; then
        mv "$CLIENT_RUN_DIR/soak-results/client.jsonl" "$CLIENT_RUN_DIR/soak-results/client-run$run.jsonl"
    else
        echo "[soak] WARNING: No client.jsonl found after run $run"
    fi
done

# Step 11: Wait for the server to halt itself at scenario end
echo "[soak] Waiting for server to halt..."
while kill -0 "$SERVER_PID" 2>/dev/null; do
    soak_check_deadline
    sleep 1
done
wait "$SERVER_PID" 2>/dev/null || true
SERVER_PID=""
echo "[soak] Server exited"

# Step 12: Collect results (gradle logs were written there directly)
echo "[soak] Collecting results into $RUN_RESULTS_DIR"
if [[ -f "$SERVER_RUN_DIR/soak-results/server.jsonl" ]]; then
    cp "$SERVER_RUN_DIR/soak-results/server.jsonl" "$RUN_RESULTS_DIR/server.jsonl"
else
    echo "[soak] WARNING: No server.jsonl found"
fi
cp "$CLIENT_RUN_DIR/soak-results/"client-run*.jsonl "$RUN_RESULTS_DIR/" 2>/dev/null \
    || echo "[soak] WARNING: No client jsonl files found"
cp "$SCENARIO_JSON" "$RUN_RESULTS_DIR/"

# Step 13: Save world for reuse (fresh-backfill only)
if [[ "$SCENARIO" == "fresh-backfill" && -d "$SERVER_RUN_DIR/world" ]]; then
    echo "[soak] Saving world to $WORLDS_DIR/base/ for reuse"
    mkdir -p "$WORLDS_DIR/base"
    rm -rf "$WORLDS_DIR/base/world"
    cp -r "$SERVER_RUN_DIR/world" "$WORLDS_DIR/base/world"
fi

# Step 14: Run the checker — its exit code is this script's exit code
echo "[soak] Running checker..."
if python3 "$PROJECT_ROOT/scripts/check_soak.py" "$RUN_RESULTS_DIR" "$SCENARIO"; then
    echo "[soak] PASS: $SCENARIO — results in $RUN_RESULTS_DIR"
else
    code=$?
    echo "[soak] FAIL: $SCENARIO (checker exit $code) — results in $RUN_RESULTS_DIR"
    exit "$code"
fi
