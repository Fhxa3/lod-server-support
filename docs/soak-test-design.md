# Soak Test Harness — Design

Date: 2026-06-10. Status: approved (adversarial-reviewed, all amendments folded in).

## Purpose

A dev-loop tool Claude runs via bash after significant changes: a real dedicated server +
real headless client execute scripted scenarios; both sides emit periodic diagnostic
snapshots; a checker enforces exact conservation invariants and per-scenario expectations;
Claude reads the verdict + time series and reports. Explicitly NOT a CI gate. Perf
baselines and multi-client are out of scope.

## Architecture

```
scripts/soak.sh <scenario>|all          bash orchestrator (sources scripts/lib/mc-run.sh)
scripts/soak-scenarios/<name>.json      timeline for the Java driver (ONLY driver concerns)
scripts/soak-scenarios/<name>-config.json  sparse lss-server-config.json overrides (GSON fills defaults)
scripts/check_soak.py                   stdlib python checker (--validate pre-flight + post-run laws)
fabric: dev.vox.lss.benchmark.SoakScenarioDriver   server-side timeline executor + jsonl snapshots
fabric: BenchmarkHook/BenchmarkBridge soak branches; client jsonl appender + sync cache flush
paper:  dev.vox.lss.paper.soak.PaperSoakScenarioDriver + PaperSoakMetricsExporter
        Paper twins behind PaperSoakBridge (SOAK_PLATFORM=paper; :paper:runSoakServer
        fed by the dev-only soakShadowJar; soak-worlds/base-paper)
soak-worlds/base/                       produced by fresh-backfill only (never shared with benchmark-worlds/)
soak-results/<scenario>-<timestamp>/    server.jsonl, client-run<N>.jsonl, logs, verdict.json
```

SOAK_PLATFORM=paper runs the identical scenario JSON against a real Paper server with the
UNCHANGED Fabric soak client and checker. Paper keeps its own base-world snapshot
(soak-worlds/base-paper); world_the_end/ is not snapshotted and regenerates from the fixed
seed (acceptable — the laws are delta-based).

Key separations (from adversarial review):
- Scenario JSON contains ONLY what the Java driver consumes (timeline + end + intervals).
  Staging lives in bash case branches; config overrides are sparse JSON files; checks are a
  python dict keyed by scenario name. No phase labels — the driver appends command/join
  event rows to `server.jsonl` and the checker anchors on those.
- Soak has its own gates and run configs: `-Dlss.soak.scenario=<path>` (server),
  `-Dlss.soak=true` (client), loom runs `soakServer`/`soakClient` with NO JFR. The
  benchmark 60s auto-halt must not arm in soak mode. `BenchmarkBridge.invoke` fires when
  either benchmark or soak properties are set.

## Scenario JSON (driver contract)

```json
{
  "snapshotIntervalSeconds": 5,
  "joinTimeoutSeconds": 240,
  "steps": [
    { "anchor": 1, "at": 2,   "cmd": "gamerule randomTickSpeed 0" },
    { "anchor": 1, "at": 120, "cmd": "kick @a soak-phase-end" },
    { "anchor": 2, "at": 60,  "cmd": "tp @a 400 150 0" }
  ],
  "end": { "anchor": 2, "at": 170 }
}
```

- `anchor: N` = the Nth player JOIN event (`ServerPlayConnectionEvents.JOIN`); `at` =
  seconds since that join. Post-kick steps re-anchor on the NEXT join (a fresh client takes
  30–90 s of Gradle boot; absolute offsets would race it).
- Driver counts ticks (`END_SERVER_TICK`); a step fires when its anchor exists and elapsed
  ≥ `at`. `pause-when-empty-seconds=-1` is load-bearing: ticks (and the scheduler) stop on
  an empty paused server.
- Commands run via `server.getCommands().performPrefixedCommand(source, cmd)` with the
  server console source. The call returns void — failures are invisible — hence
  `--validate` + named checks must catch missing effects.
- Join timeout: if a referenced anchor N hasn't occurred `joinTimeoutSeconds` after it
  became the next expected join, append `{"event":"end","reason":"join-timeout"}` and halt.
- At `end`: final snapshot, `{"event":"end","reason":"timeline-complete"}`, `halt(false)`.

## JSONL row schemas (checker contract)

Compact GSON (NOT the pretty-printing instance). All rows carry `"wallMs"` =
`System.currentTimeMillis()` (same host on both sides; immune to the WSL2 monotonic-clock
lag we measured). Field names below are the contract.

`server.jsonl` events: `snapshot`, `command` (`cmd`, `anchor`, `at`), `join` (`player`,
`joinIndex`), `end` (`reason`). Snapshot payload:

```json
{"event":"snapshot","wallMs":0,"tick":0,
 "service":{"requests_received":0,"columns_sent":0,"bytes_sent":0,"duplicate_skips":0,
            "queue_full":0,"up_to_date":0,"in_memory":0,"disk_resolved":0,"gen_drained":0},
 "disk":{"submitted":0,"completed":0,"not_found":0,"all_air":0,"errors":0,"saturated":0,
         "successful":0,"pending":0},
 "generation":{"submitted":0,"completed":0,"timeouts":0,"removed_in_flight":0,"active":0},
 "dirty":{"pending":0,"broadcast_positions":0},
 "bandwidth":{"total_bytes":0},
 "players":[{"name":"","held_sync":0,"held_gen":0,"send_queue":0,"sent":0,"bytes":0,
             "requests":0}]}
```

`client.jsonl` events: `snapshot` (every 5 s), `action` (scripted client action — `action`,
`atSeconds` fields; a counter-reset segment boundary like a dimension change), `disconnect`
(final snapshot inline).

```json
{"event":"snapshot","wallMs":0,"dimension":"minecraft:overworld","server_enabled":true,
 "received_columns":0,"received_bytes":0,"dropped":0,
 "responses":{"columns":0,"up_to_date":0,"not_generated":0,"rate_limited":0},
 "requested_total":0,"send_cycles":0,
 "columns":{"known":0,"empty":0,"dirty":0},
 "scan":{"confirmed":0,"ring":0,"missing_vanilla":0},
 "tracker_in_flight":0,"queued":0}
```

`probes` (optional, only when `-Dlss.soak.probes=x:z,...` is set): per-position client
timestamp map.

`service.*` are NEW cumulative service-level counters (see below) — per-player rows reset
on kick AND on dimension change (removePlayer+registerPlayer) and are diagnostic color
only. Client counters are per-process-run and reset on dimension change → the checker
segments windows accordingly.

## Production diagnostics additions (common/, parity via shared formatter; Paper free except gen service)

1. `DiskReaderDiagnostics`: replace `empty` with `notFound` + `allAir`; fix
   `getSuccessfulReadCount()` to `completed − notFound − allAir − errors − saturated`
   (the saturation path records a completion today, so the current partition is already
   false whenever saturated > 0). Update the `DiskReader:` diag line.
2. `ProcessingDiagnostics`: cumulative `totalDuplicateSkips` (today per-tick only — the
   server SILENTLY drops duplicate-while-pending requests and the client legitimately
   produces them: 10 s client retry vs up-to-60 s server gen pending), cumulative
   `totalRequestsReceived`, cumulative `totalColumnsSent`/`totalBytesSent` (incremented at
   actual send; the existing "sent" sums live player states and resets at boundaries).
3. Generation services (Fabric `ChunkGenerationService` + Paper twin): cumulative
   `totalRemovedInFlight`, incremented when `removePlayer` drops active entries (fires on
   kick AND dimension change; today those vanish unaccounted and `submitted == completed`
   can never re-balance).
4. Dirty: expose dirty-tracker pending size + cumulative broadcast-positions counter.
5. `DiagnosticsFormatter`: surface the new counters (`/lsslod diag` parity on both
   platforms comes free through the shared formatter).

No wire/protocol changes. Unit tests for each new counter path.

## Soak client mode (`-Dlss.soak=true`)

- Registers the no-op column consumer (capability bit), appends a snapshot row every 5 s
  (100 client ticks) to `soak-results/client.jsonl` under the client run dir.
- `-Dlss.soak.probes` adds per-position timestamp probes to every snapshot;
  `-Dlss.soak.clientActionAt=SECONDS:ACTION` fires one scripted action (currently
  `clearcache` = `LodRequestManager.flushCache`) that many enabled-seconds in.
- On DISCONNECT: append final snapshot, then **synchronously flush the column cache**
  (new `ColumnCacheStore.flushPendingIo()` drain method — submits a no-op to the
  single-threaded cache IO executor and waits for it, serializing behind the queued save;
  no timeout, the soak.sh runtime budget is the backstop) BEFORE `Runtime.halt(0)` — the
  disconnect save is async on a daemon IO thread and `halt` loses it, which would
  silently turn warm-rejoin run 2 into a cold run.

## Orchestrator (`scripts/soak.sh`)

- `scripts/lib/mc-run.sh` extracts the shared lifecycle from benchmark.sh (~70 lines:
  cleanup/trap, start server + wait-ready "Done" grep, wait-client-exit); benchmark.sh is
  refactored to source it with byte-identical behavior. soak.sh and benchmark.sh stay
  separate tools.
- Staging per scenario (bash case): world (`fresh` | copy `soak-worlds/base`), client cache
  (`clear` = delete `<client run dir>/config/lss/cache/`, else keep), copy
  `<name>-config.json` → `<server run dir>/config/lss-server-config.json`, write
  server.properties (`online-mode=false`, fixed seed, `level-type=minecraft\:flat`,
  `generate-structures=false` — the classic flat preset generates villages, and live
  villagers toggle doors/path around: real block changes the content filter correctly
  marks, nondeterministic across base rebuilds; timelines also set `mobGriefing false`
  because chunk-gen initial population spawns sheep regardless of doMobSpawning, and
  sheep eating grass (AI, not random-tick) churns grass->dirt —
  fresh noise terrain carries minutes of unsettled fluid ticks that re-dirty chunks on
  every save and block quiescence, `pause-when-empty-seconds=-1`, `view-distance=8`,
  `gamemode=creative`, `force-gamemode=true`, `spawn-protection=0`, `max-tick-time=-1`)
  and options.txt (onboarding skips + `renderDistance:8` — the
  exclusion circle is min(client option, server view distance) and must be deterministic).
- Pre-flight: port 25565 free (pkill stale game JVMs with a clear message), then
  `python3 scripts/check_soak.py --validate <scenario>` BEFORE the server boots (check
  names resolve, timeline ordering sane, end after last step).
- Client-run loop: scenario→runs map in bash (warm-rejoin/dirty-while-offline/dimension-rejoin-warm=2,
  others=1). After each client exit, move `client.jsonl` → `client-run<N>.jsonl`.
  Total-runtime kill switch = scenario end estimate + 240 s slack.
- `all`: every scenario in ALL_SCENARIOS order (17 Fabric; 4 on SOAK_PLATFORM=paper); any
  scenario needing `soak-worlds/base` auto-runs fresh-backfill first if missing.
- fresh-backfill saves its world to `soak-worlds/base/` at the end.
- `.gitignore`: add `soak-results/`, `soak-worlds/`.
- Collect into `soak-results/<scenario>-<timestamp>/`, run checker, propagate exit code.

## Checker (`scripts/check_soak.py`, python3 stdlib)

**Quiescence predicate** (all laws evaluate as deltas between verified-quiescent
snapshots): across ≥2 consecutive server snapshots, `service.requests_received`,
`service.columns_sent`, `disk.submitted`, `generation.submitted/completed` unchanged AND
`players[].held_sync/held_gen/send_queue == 0`, `disk.pending == 0`,
`generation.active == 0`, `dirty.pending == 0`; client mirror: `tracker_in_flight == 0`,
`queued == 0`. Worlds run with `randomTickSpeed 0`, `doMobSpawning false`,
`doFireTick false` (timeline steps — they're gamerules, not server.properties), otherwise
ambient chunk churn re-dirties every broadcast interval and quiescence never arrives.
Scenarios also set `doDaylightCycle false`. Gamerules alone proved insufficient: vanilla
re-saves loaded chunks every ~10 s for metadata-only changes (inhabitedTime), so the
Fabric save hook is additionally gated by `DirtyContentFilter` — an FNV-1a hash of the
served section bytes that lets a save mark a column dirty only when LOD-visible content
actually changed. This is a production change (Fabric only; Paper's dirty detection is
event-driven), motivated by the soak harness but shipped generally.
Client `columns.dirty` may legitimately stay > 0 (in-exclusion parked marks) — it is NOT
part of global quiescence.

**Laws** (within same-dimension, same-client-run windows bounded by quiescent snapshots;
dimension/join boundaries get drain+anomaly checks only — the boundary intentionally drops
in-flight tracking). Window engine (`find_quiescent`/`evaluate_laws`): client-involving
laws take their endpoints from each quiescent point's bounded at-or-before client row
(`QPoint.cib`) — the latest client row inside the server stillness pair's span, whose
counters provably equal the instant's (a nearest-row pairing can lag into resumed traffic
and desync a window; a two-sided plateau requirement would exclude every window containing
traffic and make the laws structurally vacuous). Each client run additionally gets a
virtual run-start window anchored on the last pre-join server snapshot with an all-zero
client baseline, so the backfill/resync burst before the first natural quiescent point is
law-checked rather than skipped:

- A1 requests: `Δclient.requested_total == Δresponses(columns+up_to_date+not_generated+rate_limited) + Δserver.duplicate_skips + Δserver.queue_full`
- A2 delivery: `Δserver.service.columns_sent == Δclient.received_columns` and
  `Δserver.service.bytes_sent == Δclient.received_bytes`; `dropped == 0`
- A3 sources (sanity bound, not exact — resolution counters increment on failure paths
  too): `Δcolumns_sent ≤ Δ(in_memory + disk.successful + gen.completed)`
- A4 generation: `Δsubmitted == Δcompleted + Δtimeouts + Δremoved_in_flight`
- A5 disk triage: `Δnot_found == Δgen.submitted_via_escalation…` stated practically:
  `Δdisk.not_found == Δgeneration.submitted + Δnot_generated_responses` (valid only when
  timeouts == 0 in window, single client; gen-capacity bounces don't bump submitted)
  and `Δdisk.successful == Δdisk.completed − Δnot_found − Δall_air − Δerrors − Δsaturated`
- A6 monotonic whitelist: disk.*, generation totals, service.* cumulatives,
  bandwidth.total_bytes (server, process lifetime); client counters within one run only
  and only within one dimension window
- A7 anomalies: `errors, timeouts, dropped > 0` always fail; `saturated`/`rate_limited`
  fail unless the scenario opts in (saturation surfaces as rate-limited BY DESIGN when the
  reader pool (threads×32 queue) is smaller than the sync cap)
- B1 rate-limit: `Δclient.responses.rate_limited == Δ(service.sync_rate_limited +
  service.gen_rate_limited + disk.saturated)` (single client; disk saturation surfaces to
  the client as rate-limited BY DESIGN)
- B2 pacing: `Δbandwidth.total_bytes ≤ bytesPerSecondLimitGlobal × Δt × 1.3` over every
  consecutive server snapshot pair, plus a whole-run cumulative bound of cap × Δt × 1.05
  (runs longer than 30 s) that catches sustained creep the per-pair jitter headroom would
  absorb — armed only when the scenario config sets the global cap
- Vacuous-pass guards: every scenario declares per-(run, dimension-segment) floors on the
  number of client-law windows actually evaluated (`MIN_CLIENT_WINDOWS`) — a run where
  A1/A2/A5/B1 never fired fails loudly — and at least one evaluated window must carry
  nonzero request deltas (`TRAFFIC_FLOOR_EXEMPT={'enabled-false'}`, the only scenario with
  legitimately zero LSS traffic); `--validate` additionally rejects scenario-config keys
  that are not real `lss-server-config.json` fields.

**Scenario preconditions encoded as data:** teleports ≤ 512 blocks (beyond
lodDistance+32 the server silently drops), setblock targets inside client LOD distance,
outside render distance, on a column the client received.

**Named checks** — python dict `CHECKS = {"warm-rejoin": [...], ...}`; each check declares
`required_fields` validated against the first row (loud failure on schema drift; direct
indexing, `.get()` defaults banned):
- fresh-backfill: gen completed > 500; converges (final window quiescent; scan.confirmed >
  lod distance)
- warm-rejoin: run-2 `up_to_date` ≥ 500 and ≥ 0.5 × run-1 `columns`; run-2 `columns` <
  run-1 `columns` — NOT near-zero: the kick unload-saves the whole disc and disk-served
  columns have no content-filter baseline (only live serves seed it), so one bounded
  re-send wave follows the rejoin; run-2 `requested_total` ≥ 1000 (full revalidation is
  expected shape)
- dimension-trip: dimension field changes twice; drains hold at each boundary; per-window
  A-laws hold inside each dimension segment
- dirty-broadcast: after the setblock command event, the server's cumulative
  `broadcast_positions` rises within 2× broadcast interval (+5 s slack), the client's
  final `columns.dirty` returns to ≤ its pre-event baseline, and `received_columns`
  increments (real re-send). No 'dirty visibly rises' assertion: the
  mark→re-request→clear transient (~1-2 s, one scan cycle) is shorter than the 5 s
  snapshot cadence, so missing it is the normal fast-drain case. Baseline-relative
  because parked in-exclusion marks are legitimate.

Every scenario additionally registers a handshake check and, except enabled-false, per-run
disc-completeness checks (the client must end each run holding an unbroken known-column
disc out to the scenario's effective radius). The 14 post-approval scenarios each carry
their own named checks (rate-limit storm convergence, saturation surfacing,
generation-disabled parking + dirty rescue, bandwidth pacing, cold-restart up_to_date
resync, enabled-false zero-traffic, teleport pruning, dirty-range filtering, offline-dirty
probe deltas, clearcache segmentation, warm dimension rejoin, Paper falling-block dirty
detection) — see the CHECKS dict in scripts/check_soak.py.

**Modes:** `--validate <scenario>` (pre-boot, seconds),
`check_soak.py <results-dir> <scenario>` → `verdict.json` + human-readable violations,
exit 1 on any failure, and `--selftest` — 96 in-memory pass/catch cases: every law
(A1-A7, B1, B2, incl. the re_resolved / suppressed_total monotonic guards), the quiescence
predicate, disc completeness, window floors, the config allowlist, action segmentation, and
every named check each pass consistent data and catch a doctored inconsistency. Unrecognized
new fields → one-line warning, never a failure.

**Post-run digest:** `scripts/soak_report.py <results-dir>` (also auto-written to
`report.md` per run) is a lens, never a gate — it surfaces rate spikes/stalls, unexpected
concerning counters, high-water marks, cadence gaps, law margins, and cross-identity drift
so a reviewer can spot problems a law can't name. `--strict` exits nonzero on any anomaly.

## Scenarios (lodDistanceChunks=24, genPerPlayer=40, genGlobal=64, dirtyBroadcast=5s)

| Scenario | World/cache | Timeline sketch | ~Runtime |
|---|---|---|---|
| fresh-backfill | fresh, clear | gamerules → backfill → `save-all flush` @195s (forces the post-gen re-mark wave — border light settles after the gen-path filter seed — to drain in-window instead of racing vanilla's ~5-min staggered autosave) → quiesce → end; saves soak-worlds/base | ~4.5 min |
| warm-rejoin | base, clear (run 1 populates the cache; run 2 rejoins warm — under `all` ordering a kept cache would leave the run1-vs-run2 check nothing to compare) | backfill→quiesce→kick @160; join2: resync→quiesce→tp 400→quiesce→end | ~6 min |
| dimension-trip | base, clear | quiesce → `execute in minecraft:the_end run tp @a 100 52 0` → End quiesce → return tp → quiesce | ~7 min |
| dirty-broadcast | base, keep | quiesce → `forceload add` → `setblock 320 310 0 stone` → `save-all flush` → two no-edit `save-all flush` re-saves (region stays forceloaded: unload-saves serialize late-settled light and would leak correct-but-confusing marks into the quiet window) → end | ~4 min |

Post-approval additions (same staging/laws machinery): rate-limit-storm, disk-saturation,
generation-disabled, generation-capacity-stress, bandwidth-throttle, cold-restart-resync,
enabled-false, teleport-prune, dirty-range-filter, dirty-during-backfill,
dirty-while-offline (2 runs), clearcache-mid-session, dimension-rejoin-warm (2 runs), and
the Paper-native paper-dirty-falling-block (SOAK_PLATFORM=paper only).

dimension-trip routes via the End, not the Nether: the End is the only second dimension
whose terrain settles (no fluids — nether lava-ocean gen borders churn content forever),
and its void columns exercise the all-air generation path.

Creative + safe-y teleports: a dead headless client never respawns (`tick()` early-returns
on `isDeadOrDying`) and would silently stall the run.

## Validation of the harness itself

1. `./gradlew :fabric:test -x runGameTest -x runClientGameTest` + `:paper:shadowJar` green.
2. All 17 Fabric + 4 Paper scenarios green end-to-end.
3. Prove the checker can fail: doctor a jsonl copy (decrement `generation.completed`),
   expect violation + exit 1.

## Out of scope

Multi-client/dedup scenarios, perf baselines, CI gametest distillation (possible follow-up
once scenarios stabilize).
