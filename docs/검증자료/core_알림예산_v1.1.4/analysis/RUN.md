# Execution handoff (not yet executed)

Input preparation only: `python3 prepare_inputs.py prepare`, then after all implementation/spec edits `python3 prepare_inputs.py freeze`. Both refuse replacement. They write only this lane and do not run the engine. The freeze is finalized before any engine result is viewed. Do not silently update it after execution.

From `app/`, after independent static review and root permission for the stage:

```sh
./gradlew --offline --no-configuration-cache --no-daemon -PincludePleos=false \
  --project-cache-dir ../.omx/qa/submission-v24/alert-analysis/gradle-project-cache \
  -I ../.omx/qa/submission-v24/alert-analysis/analysis.init.gradle \
  -Pv24RunId=reviewed-01 :core:v24AlertSelftest
```

The self-test task compiles current unchanged core sources into the lane, then writes a dedicated selftest.json. It does not run scenario inputs or old `:core:test`. The external sourceSet uses only already-declared dependencies.

Only after the self-test passes, independent review completes, and root explicitly instructs scenario execution:

```sh
./gradlew --offline --no-configuration-cache --no-daemon -PincludePleos=false \
  --project-cache-dir ../.omx/qa/submission-v24/alert-analysis/gradle-project-cache \
  -I ../.omx/qa/submission-v24/alert-analysis/analysis.init.gradle \
  -Pv24RunId=reviewed-01 -Pv24AllowScenarios=true :core:v24AlertRun
```

No reruns to improve outputs. Existing output paths cause a refusal. A corrective rerun needs a separately named root-approved run and must retain the original failure. Network/cache/compiler failures are blockers, not invitations to add dependencies. `v24AllowScenarios` is a guard, not independent authorization.

Inspect raw events, all 54 summaries including failed cases, result-manifest.tsv, and the frozen manifest. A computational PASS is bounded to D-input/C-engine semantics; listening and field results remain absent.
