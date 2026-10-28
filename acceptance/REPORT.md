# Acceptance Report

- runId: `20261028T022352Z-4043562`
- startedAt: 2026-10-28T02:23:52Z  finishedAt: 2026-10-28T02:24:13Z  duration: 21s
- toolchain: jdk=`openjdk version "17.0.20" 2026-07-21` gradle=9.6.1 agp=9.4.1 compileSdk=37
- verdict: **accepted=true**  blockingFailures=0  blockedInScope=0  degraded=G13,G16

## Gates

| Gate | Status | Tests/Fail/Skip | Artifacts | Log |
|---|---|---|---|---|
| G01 toolchain-sanity | PASS | 0/0/0 | 4 | acceptance/logs/G01.log |
| G02 module-build | PASS | 0/0/0 | 123 | acceptance/logs/G02.log |
| G03 contract-drift | PASS | 0/0/0 | 18 | acceptance/logs/G03.log |
| G04 contract-replay | PASS | 40/0/0 | 4 | acceptance/logs/G04.log |
| G05 projector-goldens | PASS | 255/0/0 | 22 | acceptance/logs/G05.log |
| G06 cursor-and-reset | PASS | 101/0/0 | 8 | acceptance/logs/G06.log |
| G07 pending-state-machine | PASS | 41/0/0 | 6 | acceptance/logs/G07.log |
| G08 capability-matrix | PASS | 7/0/0 | 2 | acceptance/logs/G08.log |
| G09 security-invariants | PASS | 16/0/0 | 3 | acceptance/logs/G09.log |
| G10 diagnostics-schema | PASS | 6/0/0 | 2 | acceptance/logs/G10.log |
| G11 release-artifact | PASS | 0/0/0 | 4 | acceptance/logs/G11.log |
| G12 paging-and-large-data | PASS | 13/0/0 | 3 | acceptance/logs/G12.log |
| G13 coverage-floor | DEGRADED | 0/0/0 | 2 | acceptance/logs/G13.log |
| G14 i18n-parity | PASS | 0/0/0 | 1 | acceptance/logs/G14.log |
| G15 v0-v1-projection-parity | PASS | 2/0/0 | 2 | acceptance/logs/G15.log |
| G16 database-schema-migration | DEGRADED | 4/0/0 | 2 | acceptance/logs/G16.log |
| G17 dependency-fence | PASS | 0/0/0 | 1 | acceptance/logs/G17.log |
| G18 worker-handoff-semantics | PASS | 16/0/0 | 3 | acceptance/logs/G18.log |
| G19 pairing-flow-invariants | PASS | 5/0/0 | 2 | acceptance/logs/G19.log |
| G20 device-checklist-evidence | ADVISORY | 0/0/0 | 1 | acceptance/logs/G20.log |

## Not verifiable on this machine

- Instrumented / Compose-UI device tests: **NOT_IN_SCOPE** (DECISIONS D20 — no emulator, no system images, `adb devices` empty).
- G20 device-checklist-evidence: ADVISORY — Builtin advisory gate
