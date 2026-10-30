# Frozen design: synthetic alert-request analysis Status: preparation only. Neither self-tests nor scenarios have been executed. Inputs are D assumptions; outputs, once produced by the unchanged engine, are C calculations. New listening/driver/field observations = 0. The goal is to reveal the fixed engine's response, including unfavorable results, not tune inputs toward a desired score.

## Fixed engine and scope

- Read current `app/core/src/main/kotlin` directly. Do not modify app sources, policy, APK, sim 2.3, or old test reports. No new dependencies, Android host, audio playback, vehicle commands, ADB, network, or recursive delegation.
- `GEstimator(wheelbaseM=2.9f.toDouble(), steeringRatio=15f.toDouble())`: production Float-to-Double initialization; default longitudinal EMA 0.5, brake maximum 8m/s², standard gravity 9.80665. `CargoStateMachine.Config()` unchanged (audible 6 / speech 3 / shared 8000ms).
- A fixed single speed sourceId=1; all speed/steering/brake inputs explicitly SYNTHETIC. These inputs are assumed to be downstream of source arbitration; this does not reproduce SDK timing or arbitration.
- Four 120-second mathematical profiles, no claim of typical urban travel. Nine independent fresh machines per profile use `CargoPreset.ALL`. Additional sampling comparison only for `repeated_boundary`: 50ms and 250ms versus its 100ms base. Total planned cases: 54. No 3D route reuse in this first analysis.
- All base motion samples every 100ms, including endpoints. Equal-time order: speed, steering, boolean brake, Tick. Tick every 250ms, independently of motion sampling. No brake percentage is invented.
- Initialization: P at 0ms; synthetic speed0/steering0/brake=false at 1ms; SelectPreset at 2ms. D at 1000ms; input profile t=0 maps to now=1000ms. Initialization effects are recorded separately and never counted as driving audio. No cabin effect is executed or acknowledged. No added navigation/status candidates in scenarios.
- Sampling includes final t=120000ms; summary is read while still Guarding. No synthetic arrival is asserted. Every next case starts a new machine. A separate self-test checks EndTrip/new preset resets budgets. Time fields include both absolute session time and elapsed driving time.

## Input formulas (chosen before engine output)

Speeds below are km/h; generator converts to m/s. Piecewise-linear interpolation includes exact endpoints. Steering is steering-wheel degrees, not road-wheel angle. These are arbitrary numerical profiles, not calibrated vehicle maneuvers.

| ID | Speed knots `(seconds, km/h)` | Steering |
| --- | --- | --- |
| gentle | (0,0), (12,30), (108,30), (120,0) | 0 outside [12,108]; within it `20*sin(2*pi*(t-12)/48)` (two smooth periods) |
| repeated_boundary | same speed knots as gentle | Six nonoverlapping pulses starting at 20,36,52,68,84,100 seconds: 0→80° in 1s, hold 4s, 80→0° in 1s, otherwise 0. “Boundary” does not promise every category crosses OVER. |
| sustained_over | same speed knots as gentle | knots (0,0), (20,0), (21,100), (99,100), (100,0), (120,0). The ID describes the designed high-steering segment; actual badge output remains to be measured. |
| hard_brake | (0,0), (20,50), (60,50), (61,5), (70,5), (85,30), (108,30), (120,0) | 0 throughout. Deliberately abrupt synthetic deceleration; high-G suppression/quality flags must be preserved. |

`brake_pressed` is true only when the scheduled piecewise speed segment has negative slope; percent is absent. At a knot, the outgoing segment determines the boolean (at final endpoint false). No pseudo-random values. Motion CSV values are serialized to 12 decimal places. The CSV bytes, not a regenerated formula, are the runner's input. Input files have only `t_ms,speed_mps,steering_deg,brake_pressed`. sourceId/provenance/time offsets/order are fixed by this spec and runner.

## Measures and interpretation

- Record every dispatch's phase/action/now, G availability/axes/instantaneous region, displayed badge before/after, cumulative speech/chime/suppression, allowed effects, source/provenance/quality. Compute instantaneous WARN/OVER entry counts on speed updates only to provide one stable observation grid; also retain all-dispatch region entries separately. Displayed badge transitions are separate from both.
- Gate candidate total per driving Guarding dispatch = delta admitted speech + delta admitted chime + sum(delta suppression counts). It excludes candidates never constructed by per-feature/parking gates. Suppressed speech/chime types and exact suppressed texts cannot be recovered through the public API and are not invented.
- Candidate totals are grouped by originating motion action and instantaneous region. All scenario driving candidates are inertial; self-test navigation inputs are separate and excluded from scenario aggregates.
- Record admitted speech/chime requests, suppression reason totals (including coalescing), peak G, fresh longitudinal sample count, first voice3 time, first audible6 time, and first TRIP_LIMIT suppression time. A budget not reached is null, never the run end. Suppressed request counts depend on event frequency and do not equal avoided audible interruptions.
- Counters describe reducer requests only. Host mute, TTS callbacks, speaker output, driver annoyance, real G accuracy and cargo safety remain unmeasured. Do not convert SYNTHETIC into within-limit/field PASS.

## Self-tests before scenarios

The separate `selftest` mode uses the real reducer and checks: Float-to-Double initialization; independent lateral formula and longitudinal EMA arithmetic; WARN/OVER boundary sides; public effect/counter reconciliation; shared cooldown at 7999/8000ms; voice3 and total6 exhaustion; TRIP_LIMIT after exhaustion; freshness expiration; EndTrip/new preset reset; synthetic provenance. These are specified checks, not observed passes.

Every driving dispatch additionally checks at most one allowed audio effect, speech<=3, audible<=6, at least8000ms between allowed audio effects, monotone per-session counts, and exact returned-effect/counter deltas. Scenario run requires a matching passing self-test certificate for the same frozen files and run-id. Independent static review and root's explicit scenario instruction are also required; a property flag alone is not authorization.

Self-test failures are written as FAIL with a failure message, exit nonzero, and block scenarios. Failed scenario runs retain their partial event output and a failed summary; never filter cases to keep only favorable results. Existing output names are not overwritten. Freeze verification rejects missing/changed inputs or sources before execution.

## Files, output isolation, and freeze

`prepare_inputs.py` only generates these deterministic input CSVs and hashes files; it does not import/run the engine. Its `prepare` and `freeze` modes are separate. `frozen-files.tsv` binds this spec, Kotlin runner, init script, generator, input CSVs, inputs.sha256, current core Kotlin sources, Gradle build/settings/version catalog/wrapper properties, and the SettingsStore/CargoSession source used to establish initialization. Manifest paths are repository-relative; they contain no credentials. Freeze digest is the SHA256 of the TSV itself. Do not regenerate the freeze after observing engine results to conceal changes.

The external Gradle init assigns every project's build directory under this lane's `gradle-build/`, and uses an analysis sourceSet with its own compile/output directories. It registers only `v24AlertSelftest` and `v24AlertRun` entry tasks; it never invokes `:core:test`. Use a separate Gradle project-cache directory, `--offline`, `--no-configuration-cache`, and `-PincludePleos=false`. Existing Gradle/JDK/cached dependencies must suffice; if unavailable, stop with the error rather than downloading/installing anything. No Gradle command has been run in this preparation stage.

Outputs are `out/<run-id>/selftest.json`, then `out/<run-id>/scenarios/<case>/<preset>/events.jsonl`, `summary.json`, a combined `summary.jsonl`, and `result-manifest.tsv`. Every output is local to the lane. No actual Android effect is executed. A final PASS means computational checks passed within this synthetic scope, never field validation.

See `RUN.md` for commands to use only after the root permits the relevant stage.
