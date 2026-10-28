# dsh-android acceptance harness

Single entry point for the acceptance gates defined in [`docs/ACCEPTANCE.md`](../../docs/ACCEPTANCE.md).
Machine-readable decisions live in [`acceptance/gates.json`](../../acceptance/gates.json); the
per-run evidence is written to [`acceptance/manifest.json`](../../acceptance/manifest.json)
(schema per ACCEPTANCE §4) and [`acceptance/REPORT.md`](../../acceptance/REPORT.md).

## Run

```sh
cd dsh-android
tools/acceptance/run-gates.sh                 # every in-scope gate
tools/acceptance/run-gates.sh G05 G09         # a subset by gate id
```

The harness exports `JAVA_HOME=/home/keivn/jdk17/jdk-17.0.20+8` and
`ANDROID_HOME=/home/keivn/Android/Sdk` itself and adds `platform-tools`,
`cmdline-tools/latest/bin` and the JDK `bin` to `PATH`. It makes **no network calls of its own**
and is idempotent / re-runnable. The last stdout line is machine-readable:

```
VERDICT accepted=<true|false> failed=<n> blocked=<n> degraded=<n>
```

and the process exits non-zero when `accepted` is `false`.

### Gradle must be opted in explicitly

`gradle`-type units (`./gradlew …`) run **only** when the caller sets `DSH_ALLOW_GRADLE=1`:

```sh
DSH_ALLOW_GRADLE=1 tools/acceptance/run-gates.sh            # real, full run
```

Without it every `gradle` unit is classified `BLOCKED`. This is deliberate: on a half-built tree
the Kotlin sources are still being written, and an unbuilt module must never be reported as `PASS`.

## Status taxonomy (ACCEPTANCE §0)

| Status | Meaning | Counts toward acceptance? |
|---|---|---|
| PASS | command ran, all assertions held | yes (needs `assertions` JUnit counts or `artifacts` sha256, or the reproducible log) |
| FAIL | command ran, assertions failed | no |
| BLOCKED | prerequisite missing / command exited `3` | no (for in-scope blocking gates) |
| DEGRADED | a weaker-but-real check substituted, with reason + recovery recorded | only if `gates.json` sets `degradedAllowed:true` **and** the notes cite a DECISIONS id |
| NOT_IN_SCOPE | not implemented this round (DECISIONS §3) | neutral (must be listed in the report) |
| ADVISORY | non-blocking check, failure only warns | neutral |

Every gate command uses the same exit-code contract: **`0` = PASS, `3` = BLOCKED, any other = FAIL**.
`tools/contract/record.mjs` (G03) signals `BLOCKED` by exiting `3`; the check-`*.sh` scripts follow
the same rule.

`verdict.accepted` is computed mechanically: all in-scope `blocking:true` gates are `PASS` or an
allowed `DEGRADED`, and none is `FAIL`/`BLOCKED`.

## The check scripts (also runnable standalone; same 0/3/other discipline)

| Script | Gate | What it enforces |
|---|---|---|
| `check-source.sh` | G09.3 | `android.util.Log` only in `DshLog.kt`; no trust-all `X509TrustManager` / `HostnameVerifier -> true`; no `allowBackup="true"` / `dshremote` scheme in **source** manifests; exported components = launcher only; magic numbers only in `SyncTuning.kt`, test source sets, and the `:core:testing` fixture module; no `-keep class ** { *; }`. |
| `check-i18n.sh` | G14 | `values/` vs `values-en/` key-set equality, empty values, duplicate keys; no hardcoded `Text("…")` in Composables outside previews/tests. |
| `check-modules.sh` | G17 | feature/* depend only on `:core:model`/`:core:domain`/`:core:designsystem`; `:app` composition-root import fence (impl imports only under `di/`, `DshApplication.kt`, `AppScope.kt`); `:core:model`/`:core:domain` are pure JVM. Prints the resolved dependency graph. |
| `check-golden-cases.sh` | G05/G13 | aggregates **every** `acceptance/golden-cases-*.json`; bidirectional check (declared ids must exist in module test sources; `@Test`s in manifest classes must be declared). |
| `check-release-artifact.sh` | G11 | if release APK/AAB exist: apkanalyzer manifest/summary, exported set diff vs `acceptance/manifest-baseline.txt`, size budgets **read from `docs/TUNING.md`**, mapping.txt present, debug canary `DSH-CANARY-DEBUG-ONLY-4f2b9c` absent. Prints `BLOCKED` (exit 3) until the artifacts are assembled. `--regen-baseline` rebuilds the baseline from the source manifest. |

## What each gate needs from other modules

- **G02–G08, G10, G12, G15–G19**: the corresponding Gradle unit tests must exist and compile.
  Pure-JVM modules (`:core:model`, `:core:domain`, `:core:protocol`, `:core:testing`) use `test`;
  Android modules use `testDebugUnitTest`. Where ACCEPTANCE names `testDebugUnitTest` on a pure-JVM
  module, `gates.json` uses `test` and records the deviation in `notes`.
- **G03**: `tools/contract/record.mjs` (another engineer) + `DSH_REMOTE_HOME` set and the committed
  `contract/gateway/v0/*` fixtures. Missing → `BLOCKED` (exit 3) or the recorder's own `3`.
- **G05 / G13**: `acceptance/golden-cases-projector.json` and `acceptance/golden-cases-sync.json`
  (and any other `golden-cases-*.json`) written by the domain/data owners.
- **G09.2/G09.4**: `:core:data` unit tests (`*Redaction*`, `*SecretStore*`).
- **G10**: `tools/acceptance/check-diagnostics.sh` is **owned by another engineer**; when absent the
  harness reports `BLOCKED` for that segment. The `:core:domain` `*Diagnostics*` tests are the
  other segment.
- **G11**: `./gradlew :app:assembleRelease :app:bundleRelease` output under
  `app/build/outputs/{apk,bundle,mapping}/release/`.
- **G14**: every library module must ship `values/strings.xml` **and** `values-en/strings.xml`
  with identical key sets; Composables must reference `ui/text` resources.
- **G16**: `:core:database` `*Retirement*` test + committed `schemas/` JSON; Robolectric migration
  runs are subject to the D21 limitation below (`degradedAllowed:true`).

## Machine limitations (encode these; never fake a green)

1. **No emulator / system images / devices.** `adb devices` is empty and `emulator/`,
   `system-images/` do not exist, so instrumented and Compose-UI device tests are
   **NOT_IN_SCOPE** (DECISIONS D20). The human [`docs/DEVICE_CHECKLIST.md`](../../docs/DEVICE_CHECKLIST.md)
   is the advisory stand-in (G20) and never counts as PASS.
2. **Mirror / Robolectric hazard (DECISIONS D21).** The host `~/.gradle/init.d/mirrors.init.gradle`
   injects CN mirrors; the TUN proxy can make Robolectric's own Maven resolver hang fetching
   `android-all-instrumented`. Room JVM tests therefore legitimately run in a **DEGRADED** mode
   (schema export + SQL-level assertions); gates that depend on it (`G13`, `G16`) declare
   `degradedAllowed:true` and must cite D21/D03 in `notes`, with a stated recovery condition.

## apkanalyzer (verified working invocation)

```sh
export JAVA_HOME=/home/keivn/jdk17/jdk-17.0.20+8
export ANDROID_HOME=/home/keivn/Android/Sdk
"$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" manifest print <apk>
"$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" apk summary  <apk>
```

## Files owned by this harness

`acceptance/gates.json`, `acceptance/manifest-baseline.txt`, and everything under
`tools/acceptance/` (scripts + this README). Per-run outputs (`acceptance/manifest.json`,
`acceptance/REPORT.md`, `acceptance/logs/*.log`) are generated, not authored.
