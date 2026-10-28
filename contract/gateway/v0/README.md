# Gateway v0 wire contract (recorded fixtures)

Machine-recorded HTTP contract of the dsh-Remote gateway, captured by
`tools/contract/record.mjs` against the stock `tests/fixtures/realistic-stack.cjs`
fake DSH from the dsh-Remote checkout. Read-only reference: nothing in dsh-Remote is
modified. Governance: DECISIONS.md D18, gate G03.

## Artifact format

- One `<scenario>.json` per recorded scenario, in `SCENARIO_ORDER` in
  `tools/contract/record.mjs`. Shape:
  `{ name, gatewayVersion, capturedAt, steps: [ { method, path, requestHeaders,
  status, responseHeaders, response } ] }`. `path` includes the query string as
  sent; `response` is the parsed JSON body (string when the body is not JSON),
  with object keys sorted recursively and array order preserved.
- `manifest.json` — `{ schemaVersion: 1, gatewayVersion, files: [{name, sha256, bytes}] }`
  (sha256 over the committed bytes; covers every file below except itself).
- `SHA256SUMS` — `<sha256>  <relative-path>` lines for every file in this
  directory including `manifest.json`, excluding `SHA256SUMS` itself.
- `capturedAt` is the constant placeholder `{{TS_0}}`: recording timestamps are
  stripped on purpose so re-recording is byte-comparable.

## Normalization rules (applied in this order)

1. Structural walk over captured JSON (after key sort, in scenario/step order):
   - numeric values under a key named `ts` or ending in `at`/`ms` (case-insensitive)
     → `{{TS_1}}`, `{{TS_2}}`, … occurrence-numbered in first-seen order across all
     files (occurrence-based so two timestamps landing in the same millisecond can
     never collapse to a different placeholder count between runs);
   - numeric `pid` → `{{PID}}` (gateway PID changes every run);
   - string value of key `epoch` (the event-stream epoch, F3) → `{{EPOCH}}`, and the
     captured literal is also substituted everywhere else it appears;
   - string values under id-ish keys (`rpcId`, `requestId`, `callId`, `approvalId`,
     `id`, `operationId`, `session`) → `{{ID_1}}`, `{{ID_2}}`, … value-keyed in
     first-seen order (the same value always maps to the same placeholder);
   - string values under session-ish keys (`sessionId`, `parentSessionId`,
     `workspaceId`) → `{{SESSION_1}}`, `{{SESSION_2}}`, … value-keyed in first-seen
     order.
2. Response headers: `date` and `upload-expires` → `{{TS_n}}` (same counter);
   `content-length` is recomputed from the normalized `response` bytes actually
   written into the artifact (raw lengths embed volatile digit counts).
3. Case-insensitive global text substitution on the serialized artifact, applied
   longest-captured-value-first so one token that is a substring of another cannot
   corrupt the replacement:
   - gateway base URL `http://127.0.0.1:<port>` → `{{BASE}}`;
   - the literal token (any casing) → `{{TOKEN}}` — Authorization/Cookie header
     values are normalized by exactly this rule;
   - any ws ticket ever issued → `{{TICKET}}` (v0 scenarios do not request one, so
     this set is currently empty);
   - the random tmp root (raw, realpath, and percent-encoded forms) → `{{TMP}}`;
   - remaining `127.0.0.1:<port>` triples (gateway, DSH upstream, mobile proxy) →
     `127.0.0.1:{{PORT}}`.

After writing, the recorder asserts no artifact contains the literal token, any
raw ticket, the tmp root path, or any ephemeral port string, and that SHA256SUMS
matches the files.

Recording notes:
- The recorder sets `GATEWAY_EVENT_BUFFER_MAX=100` (inherited by the gateway child
  process through the fixture env) before booting the one stack, so the
  `poll-truncated-and-reset` scenario can overrun the ring buffer with 130
  broadcast events in bounded time. All other scenarios stay far below 100 events,
  so this knob does not alter any other fixture.
- Scenarios share one stack run, so later poll fixtures contain events emitted by
  earlier scenarios; that coupling is deterministic because the scenario order is fixed.
- Settling waits (sleeps) are used so DSH→gateway event propagation completes before a
  poll is recorded; the recorded response is asserted to contain the expected events,
  and the recorder fails loudly (exit 2) instead of recording a timing-dependent frame.

## Not recordable

### approval-respond-rejected-accepted-false

gateway.js returns HTTP 200 {accepted:false} only on the modern-upstream branch (proxyModernApi is gated on detectUpstreamApiFlavor()==="modern", gateway.js:6291-6305). The stock realistic-stack fake DSH speaks the legacy dialect (it answers the flavor probe so the gateway classifies it "legacy") and its /api/respond handler always replies {accepted:true} before the rpcResponse override hook is consulted; observed on this run: POST /api/respond with an unknown rpcId returned HTTP 200 {"accepted":true}. Producing accepted:false would require modifying the read-only fixture or spoofing the session/list probe to flip the gateway into modern flavor mid-run, which restarts the event collectors and changes every poll artifact — fabrication, not recording. Omitted per the M0 rule.

## Re-recording and drift check

```sh
cd /home/keivn/AndroidStudioProjects/deepseek-harness/dsh-android
node tools/contract/record.mjs            # rewrite contract/gateway/v0/*
node tools/contract/record.mjs --check     # gate G03: re-record to a temp dir and
                                          # byte-compare; exit 1 on any DIFF
```

The fixture is resolved from `--dsh-remote <path>`, else `DSH_REMOTE_HOME`, else
`/home/keivn/AndroidStudioProjects/dsh-Remote`. If it is missing the recorder prints
`BLOCKED: realistic-stack fixture not found at <path>` and exits 3, which the
acceptance harness maps to gate status BLOCKED.
