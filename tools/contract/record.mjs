#!/usr/bin/env node
/**
 * dsh-android M0 gateway-contract recorder (DECISIONS D18, ACCEPTANCE gate G03).
 *
 * Boots the real dsh-Remote gateway once against the realistic-stack fake DSH,
 * drives a fixed scenario list, captures every HTTP request/response pair, and
 * writes normalized, deterministic JSON fixtures to contract/gateway/v0/
 * (one file per scenario + manifest.json + SHA256SUMS + README.md).
 *
 *   node tools/contract/record.mjs [--out contract/gateway/v0] [--check] [--dsh-remote <path>]
 *
 * Exit codes:
 *   0  recorded / check clean
 *   1  --check found drift (byte diff or SHA256SUMS mismatch)
 *   2  internal failure (assertion, non-deterministic timing, secret leak)
 *   3  BLOCKED: realistic-stack fixture not found (acceptance harness reads 3 as BLOCKED)
 *
 * Zero third-party dependencies: Node builtins only. The recorder never writes
 * into the dsh-Remote checkout; the stack itself runs against a throwaway tmp
 * root (HOME/USERPROFILE/DSH_REMOTE_FS_ROOT are pointed at it by the fixture).
 */

import crypto from 'node:crypto'
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const PROJECT_ROOT = path.resolve(__dirname, '..', '..')
const DEFAULT_DSH_REMOTE = '/home/keivn/AndroidStudioProjects/dsh-Remote'
const SCENARIO_ORDER = [
  'health-and-capabilities',
  'session-list-roster',
  'session-history-page',
  'session-create-rename',
  'prompt-accepted',
  'prompt-operations-receipt',
  'approval-asked-and-respond-accepted',
  'approval-respond-rejected-accepted-false',
  'question-toolcall-and-result',
  'poll-baseline-since-zero',
  'poll-incremental',
  'poll-truncated-and-reset',
  'unauthorized-401',
  'fs-list',
  'fs-preview-too-large',
  'upload-probe-and-control-offset-mismatch',
]

class NotRecordable extends Error {
  constructor (reason) { super(reason); this.notRecordable = true }
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs (argv) {
  const opts = { out: path.join(PROJECT_ROOT, 'contract', 'gateway', 'v0'), check: false, dshRemote: null }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === '--check') opts.check = true
    else if (arg === '--out') {
      if (!argv[i + 1]) fail(2, 'missing value for --out')
      opts.out = path.resolve(process.cwd(), argv[++i])
    } else if (arg === '--dsh-remote') {
      if (!argv[i + 1]) fail(2, 'missing value for --dsh-remote')
      opts.dshRemote = path.resolve(argv[++i])
    } else fail(2, `unknown argument: ${arg}`)
  }
  return opts
}

function fail (code, message) {
  console.error(`record.mjs: ${message}`)
  process.exit(code)
}

function resolveFixturePath (opts) {
  const root = opts.dshRemote || process.env.DSH_REMOTE_HOME || DEFAULT_DSH_REMOTE
  return path.join(root, 'tests', 'fixtures', 'realistic-stack.cjs')
}

// ---------------------------------------------------------------------------
// HTTP capture (node:http only; the recorder IS the wire client)
// ---------------------------------------------------------------------------

function makeCaller (port) {
  const agent = new http.Agent({ keepAlive: true })
  const captured = []

  function call (method, requestPath, { headers = {}, body = null } = {}) {
    const payload = body === null || body === undefined ? null
      : Buffer.isBuffer(body) ? body : Buffer.from(String(body))
    const finalHeaders = { host: `127.0.0.1:${port}`, ...headers }
    if (payload) finalHeaders['content-length'] = String(payload.length)
    return new Promise((resolve, reject) => {
      const req = http.request({ agent, hostname: '127.0.0.1', port, method, path: requestPath, headers: finalHeaders }, res => {
        const chunks = []
        res.on('data', chunk => chunks.push(chunk))
        res.on('end', () => {
          const step = {
            method,
            path: requestPath,
            requestHeaders: lowerHeaders(req.getHeaders()),
            status: res.statusCode,
            responseHeaders: rawHeadersToObject(res.rawHeaders),
            responseText: Buffer.concat(chunks).toString('utf8'),
          }
          captured.push(step)
          resolve(step)
        })
      })
      req.on('error', reject)
      if (payload) req.write(payload)
      req.end()
    })
  }

  function lowerHeaders (headers) {
    const out = {}
    for (const [key, value] of Object.entries(headers)) {
      out[key.toLowerCase()] = Array.isArray(value) ? value.join(', ') : String(value)
    }
    return out
  }

  function rawHeadersToObject (raw) {
    const out = {}
    for (let i = 0; i < raw.length; i += 2) {
      const key = raw[i].toLowerCase()
      out[key] = out[key] === undefined ? raw[i + 1] : `${out[key]}, ${raw[i + 1]}`
    }
    return out
  }

  return { call, agent, captured }
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

function parseJsonOrText (text) {
  try { return JSON.parse(text) } catch { return text }
}

// ---------------------------------------------------------------------------
// Scenarios. Each drives one stack side, records its steps in order, and
// asserts the response shape so a silent contract change fails loudly.
// ---------------------------------------------------------------------------

const auth = token => ({ authorization: `Bearer ${token}`, 'x-dsh-remote-client': 'app' })
const authJson = token => ({ ...auth(token), 'content-type': 'application/json' })
const POLL_PATH = '/api/events.poll?kind=mux&wait=0&since=0'

function rpcBody (rpcId, method, payload) {
  return JSON.stringify({ type: 'client-request', rpcId, method, payload })
}

const scenarios = {
  // GET /health is unauthenticated and publishes the integer capability
  // table (F6). Captured first so gatewayVersion comes from the same run.
  async ['health-and-capabilities'] (ctx) {
    const step = await ctx.call('GET', '/health')
    const body = parseJsonOrText(step.responseText)
    ctx.assertEq(step.status, 200, '/health status')
    ctx.assertEq(body.ok, true, '/health ok')
    ctx.assertEq(body.readiness?.ok, true, '/health readiness')
    ctx.assertEq(typeof body.version === 'string' && body.version.length > 0, true, '/health version')
    ctx.assertEq(typeof body.capabilities?.wsTicket, 'number', '/health capabilities.wsTicket')
    ctx.assertEq(typeof body.capabilities?.eventPolling, 'number', '/health capabilities.eventPolling')
    ctx.gatewayVersion = body.version
  },

  // session.list ignores cursor and returns the full roster in first-seen
  // order (session-1, then the subagent child).
  async ['session-list-roster'] (ctx) {
    const step = await ctx.call('POST', '/api/session.list', {
      headers: authJson(ctx.token), body: rpcBody('recorder-rpc-list', 'session.list', {}),
    })
    const body = parseJsonOrText(step.responseText)
    ctx.assertEq(step.status, 200, 'session.list status')
    ctx.assertEq(body.result?.ok, true, 'session.list result.ok')
    ctx.assertEq(body.result?.value?.items?.[0]?.sessionId, 'session-1', 'session.list roster head')
  },

  // session.history page: `events[].event` wrappers, hasMore flag, and —
  // per F2 — no `cut` field on the response.
  async ['session-history-page'] (ctx) {
    const step = await ctx.call('POST', '/api/session.history', {
      headers: authJson(ctx.token),
      body: rpcBody('recorder-rpc-history', 'session.history', { sessionId: 'session-1', maxMessages: 60 }),
    })
    const body = parseJsonOrText(step.responseText)
    ctx.assertEq(step.status, 200, 'session.history status')
    ctx.assertEq(body.result?.ok, true, 'session.history result.ok')
    ctx.assertEq('cut' in (body.result?.value || {}), false, 'session.history must not carry cut (F2)')
    ctx.assertEq(body.result?.value?.events?.[0]?.event?.type, 'user/message', 'session.history first event type')
  },

  // session.create then session.rename. The fake DSH answers rename through
  // its default echo branch; that passthrough shape is exactly what a legacy
  // DSH would send, so it is recorded as-is.
  async ['session-create-rename'] (ctx) {
    const create = await ctx.call('POST', '/api/session.create', {
      headers: authJson(ctx.token),
      body: rpcBody('recorder-rpc-create', 'session.create', { cwd: path.join(ctx.tmpRaw, 'project-one') }),
    })
    const created = parseJsonOrText(create.responseText)
    ctx.assertEq(create.status, 200, 'session.create status')
    ctx.assertEq(/^session-created-\d+$/.test(String(created.result?.value?.sessionId)), true, 'session.create sessionId shape')
    const rename = await ctx.call('POST', '/api/session.rename', {
      headers: authJson(ctx.token),
      body: rpcBody('recorder-rpc-rename', 'session.rename', { sessionId: 'session-1', title: 'Recorder 改名' }),
    })
    const renamed = parseJsonOrText(rename.responseText)
    ctx.assertEq(rename.status, 200, 'session.rename status')
    ctx.assertEq(renamed.result?.ok, true, 'session.rename result.ok')
  },

  // A text prompt that the fake DSH accepts; it also emits the user echo and
  // assistant reply into the gateway event buffer (they surface in the poll
  // fixtures recorded later in this same stack run).
  async ['prompt-accepted'] (ctx) {
    const step = await ctx.call('POST', '/api/session.prompt', {
      headers: authJson(ctx.token),
      body: rpcBody('recorder-rpc-prompt', 'session.prompt', {
        sessionId: 'session-1', mode: 'queue',
        content: [{ type: 'text', text: 'recorder prompt message one' }],
      }),
    })
    const body = parseJsonOrText(step.responseText)
    ctx.assertEq(step.status, 200, 'session.prompt status')
    ctx.assertEq(body.result?.value?.accepted, true, 'session.prompt accepted')
    await sleep(400) // let the emitted session events reach the gateway buffer
  },

  // POST /api/operations persists a durable receipt (F15): 202 while
  // running, then the GET returns the completed receipt.
  async ['prompt-operations-receipt'] (ctx) {
    const opId = 'recorder-op-0123456789abcdef'
    const payload = {
      sessionId: 'session-1', mode: 'queue',
      content: [{ type: 'text', text: 'recorder operation receipt message' }],
    }
    const post = await ctx.call('POST', `/api/operations?id=${opId}`, {
      headers: authJson(ctx.token), body: JSON.stringify({ method: 'session.prompt', payload }),
    })
    const posted = parseJsonOrText(post.responseText)
    ctx.assertEq(post.status, 202, 'operations POST status')
    ctx.assertEq(posted.phase, 'running', 'operations POST phase running')
    await sleep(800) // the gateway replies before the upstream completes; settle
    const get = await ctx.call('GET', `/api/operations?id=${opId}`, { headers: auth(ctx.token) })
    const got = parseJsonOrText(get.responseText)
    ctx.assertEq(get.status, 200, 'operations GET status')
    // A still-running receipt here would make the fixture timing-dependent;
    // fail loudly instead of recording a flaky phase.
    ctx.assertEq(got.phase, 'complete', 'operations receipt settled deterministically (never retry-record)')
    ctx.assertEq(got.result?.result?.ok, true, 'operations receipt result.ok (operationResult envelope)')
  },

  // The fake DSH broadcasts approval/requested over the mux collector; the
  // gateway replays it in the poll baseline and the legacy /api/respond
  // passthrough answers accepted:true (F5 branch one).
  async ['approval-asked-and-respond-accepted'] (ctx) {
    ctx.stack.dsh.requestApproval()
    await sleep(400)
    const poll = await ctx.call('GET', POLL_PATH, { headers: auth(ctx.token) })
    const polled = parseJsonOrText(poll.responseText)
    ctx.assertEq(poll.status, 200, 'approval poll status')
    ctx.assertEq(
      JSON.stringify(polled.events).includes('approval/requested'), true,
      'approval poll must contain approval/requested')
    ctx.assertEq(
      polled.baseline.some(frame => frame.payload?.type === 'approval/requested'), true,
      'approval must sit in the replay baseline')
    ctx.pollCursor.muxLatestSeq = polled.latestSeq
    const respond = await ctx.call('POST', '/api/respond', {
      headers: authJson(ctx.token),
      body: JSON.stringify({
        type: 'client-response', rpcId: 'approval-rpc-1',
        result: { ok: true, value: { sessionId: 'session-1', approvalId: 'approval-1', outcome: 'allowed-once' } },
      }),
    })
    const answered = parseJsonOrText(respond.responseText)
    ctx.assertEq(respond.status, 200, 'respond status')
    ctx.assertEq(answered.accepted, true, 'respond accepted')
  },

  // Not recordable on the stock fixture — see the reason below. Per the
  // M0 rule we do not fake a stack into producing this frame.
  async ['approval-respond-rejected-accepted-false'] (ctx) {
    // The probe below documents the observed behavior in the README reason.
    const probe = await ctx.call('POST', '/api/respond', {
      headers: authJson(ctx.token),
      body: JSON.stringify({ type: 'client-response', rpcId: 'recorder-unknown-rpc-id', result: { ok: true, value: {} } }),
    })
    ctx.notRecordableProbe = { status: probe.status, body: probe.responseText }
    throw new NotRecordable(
      'gateway.js returns HTTP 200 {accepted:false} only on the modern-upstream branch ' +
      '(proxyModernApi is gated on detectUpstreamApiFlavor()==="modern", gateway.js:6291-6305). The stock ' +
      'realistic-stack fake DSH speaks the legacy dialect (it answers the flavor probe so the gateway classifies ' +
      'it "legacy") and its /api/respond handler always replies {accepted:true} before the rpcResponse override ' +
      'hook is consulted; observed on this run: POST /api/respond with an unknown rpcId returned HTTP ' +
      `${probe.status} ${probe.responseText.trim()}. Producing accepted:false would require modifying the read-only ` +
      'fixture or spoofing the session/list probe to flip the gateway into modern flavor mid-run, which restarts the ' +
      'event collectors and changes every poll artifact — fabrication, not recording. Omitted per the M0 rule.')
  },

  // F9: questions ride on ask_user_question tool calls. The recorder emits a
  // tool/call and a tool/result through the fake DSH broadcast knob and
  // captures them in two incremental polls.
  async ['question-toolcall-and-result'] (ctx) {
    const base = ctx.pollCursor.muxLatestSeq
    ctx.stack.dsh.broadcast('mux', {
      type: 'session/event', sessionId: 'session-1',
      event: {
        seq: 501, type: 'tool/call',
        data: {
          callId: 'recorder-call-1', name: 'ask_user_question',
          arguments: { questions: [{ id: 'recorder-choice', header: 'Recorder question', question: 'Proceed?', options: [{ label: 'Yes', description: 'Continue' }], multiSelect: false }] },
        },
      },
    }, 'recorder-question-rpc-1')
    await sleep(200)
    const asked = await ctx.call('GET', `/api/events.poll?kind=mux&wait=0&since=${base}`, { headers: auth(ctx.token) })
    const askedBody = parseJsonOrText(asked.responseText)
    ctx.assertEq(asked.status, 200, 'question tool/call poll status')
    ctx.assertEq(askedBody.events?.some(row => row.event?.payload?.event?.type === 'tool/call'), true, 'tool/call observed')
    ctx.stack.dsh.broadcast('mux', {
      type: 'session/event', sessionId: 'session-1',
      event: {
        seq: 502, type: 'tool/result',
        data: { message: { role: 'tool', toolCallId: 'recorder-call-1', content: [{ type: 'text', text: 'answer: Yes' }], source: { kind: 'user-question-reply', callId: 'recorder-call-1' } } },
      },
    }, 'recorder-question-rpc-2')
    await sleep(200)
    const answered = await ctx.call('GET', `/api/events.poll?kind=mux&wait=0&since=${askedBody.latestSeq}`, { headers: auth(ctx.token) })
    const answeredBody = parseJsonOrText(answered.responseText)
    ctx.assertEq(answeredBody.events?.some(row => row.event?.payload?.event?.type === 'tool/result'), true, 'tool/result observed')
    ctx.pollCursor.muxLatestSeq = answeredBody.latestSeq
  },

  // F3: the poll response carries ok/kind/epoch/waitSupported plus
  // baseline+events for since=0, on both mux and host channels.
  async ['poll-baseline-since-zero'] (ctx) {
    const mux = await ctx.call('GET', POLL_PATH, { headers: auth(ctx.token) })
    const muxBody = parseJsonOrText(mux.responseText)
    ctx.assertEq(mux.status, 200, 'mux baseline status')
    ctx.assertEq(muxBody.ok, true, 'mux ok')
    ctx.assertEq(muxBody.kind, 'mux', 'mux kind')
    ctx.assertEq(typeof muxBody.epoch === 'string' && muxBody.epoch.length > 0, true, 'mux epoch (F3)')
    ctx.assertEq(muxBody.waitSupported, false, 'mux waitSupported with wait=0')
    ctx.assertEq(
      JSON.stringify(muxBody.events).includes('recorder prompt message one'), true,
      'mux baseline must contain the prompt-accepted events')
    ctx.assertEq(
      JSON.stringify(muxBody.events).includes('recorder operation receipt message'), true,
      'mux baseline must contain the operations prompt events')
    ctx.pollCursor.muxLatestSeq = muxBody.latestSeq
    ctx.pollCursor.muxEpoch = muxBody.epoch
    const host = await ctx.call('GET', '/api/events.poll?kind=host&wait=0&since=0', { headers: auth(ctx.token) })
    const hostBody = parseJsonOrText(host.responseText)
    ctx.assertEq(hostBody.kind, 'host', 'host kind')
    ctx.assertEq(hostBody.events?.length >= 1, true, 'host buffer populated')
  },

  // Incremental fetch: since=latestSeq plus the echoed epoch (F3 requires
  // the client to return it); exactly one new event arrives.
  async ['poll-incremental'] (ctx) {
    ctx.stack.dsh.broadcast('mux', {
      type: 'session/event', sessionId: 'session-1',
      event: { seq: 601, type: 'assistant/message', data: { role: 'assistant', content: [{ type: 'text', text: 'recorder incremental push' }] } },
    }, 'recorder-push-1')
    await sleep(200)
    const step = await ctx.call('GET',
      `/api/events.poll?kind=mux&wait=0&since=${ctx.pollCursor.muxLatestSeq}&epoch=${encodeURIComponent(ctx.pollCursor.muxEpoch)}`,
      { headers: auth(ctx.token) })
    const body = parseJsonOrText(step.responseText)
    ctx.assertEq(body.events?.length, 1, 'incremental poll returns exactly the one new event')
    ctx.assertEq(body.reset, false, 'incremental poll not reset')
    ctx.assertEq(body.epoch, ctx.pollCursor.muxEpoch, 'epoch echoed unchanged')
    ctx.pollCursor.muxLatestSeq = body.latestSeq
  },

  // Overshoot the ring buffer (GATEWAY_EVENT_BUFFER_MAX=100, set by the
  // recorder before booting the stack) so since=0 reports truncated:true
  // and reset:true in the same response (F3/D12 reset conditions).
  async ['poll-truncated-and-reset'] (ctx) {
    for (let i = 0; i < 130; i++) {
      ctx.stack.dsh.broadcast('mux', {
        type: 'session/event', sessionId: 'session-1',
        event: { seq: 700 + i, type: 'note/update', data: { index: i } },
      }, `recorder-bulk-${i}`)
    }
    await sleep(600)
    const step = await ctx.call('GET', `/api/events.poll?kind=mux&wait=0&since=0&epoch=${encodeURIComponent(ctx.pollCursor.muxEpoch)}`,
      { headers: auth(ctx.token) })
    const body = parseJsonOrText(step.responseText)
    ctx.assertEq(body.truncated, true, 'poll must report truncated after buffer overrun')
    ctx.assertEq(body.reset, true, 'truncated implies reset')
    ctx.assertEq(body.events.length, 100, 'buffer keeps EVENT_BUFFER_MAX rows')
  },

  // 401 is the only device-auth failure code (F14 note / sanity table).
  async ['unauthorized-401'] (ctx) {
    const anonymous = await ctx.call('GET', POLL_PATH)
    ctx.assertEq(anonymous.status, 401, 'unauthenticated poll 401')
    const wrong = await ctx.call('GET', POLL_PATH, { headers: { authorization: 'Bearer recorder-wrong-token', 'x-dsh-remote-client': 'app' } })
    ctx.assertEq(wrong.status, 401, 'wrong-token poll 401')
    ctx.assertEq(parseJsonOrText(wrong.responseText).error, 'unauthorized', '401 error body')
  },

  // /fs/list has no pagination; entries carry name/path/type/size/mtimeMs.
  async ['fs-list'] (ctx) {
    const step = await ctx.call('GET', `/fs/list?path=${encodeURIComponent(ctx.tmpRaw)}`, { headers: auth(ctx.token) })
    const body = parseJsonOrText(step.responseText)
    ctx.assertEq(step.status, 200, 'fs/list status')
    ctx.assertEq(body.entries.some(entry => entry.name === 'sample.txt'), true, 'fs/list sees sample.txt')
  },

  // /fs/preview enforces the 1 MiB limit with 413 preview-too-large (F14).
  async ['fs-preview-too-large'] (ctx) {
    const target = path.join(ctx.tmpRaw, 'large-preview.md')
    fs.writeFileSync(target, `# recorder oversized preview\n${'x'.repeat(1024 * 1024)}`)
    const step = await ctx.call('GET', `/fs/preview?path=${encodeURIComponent(target)}`, { headers: auth(ctx.token) })
    const body = parseJsonOrText(step.responseText)
    ctx.assertEq(step.status, 413, 'preview oversize status')
    ctx.assertEq(body.error, 'preview-too-large', 'preview-too-large error code')
    ctx.assertEq(body.limit, 1024 * 1024, 'preview limit 1 MiB')
  },

  // Resumable upload (F14): POST /fs/upload chunk, GET /fs/upload-probe,
  // an out-of-range offset that must return 409 offset-mismatch, and
  // POST /fs/upload-control?action=cancel.
  async ['upload-probe-and-control-offset-mismatch'] (ctx) {
    const common = `path=${encodeURIComponent(ctx.tmpRaw)}&name=recorder-upload.bin&session=recorder-upload-1`
    const chunk = Buffer.alloc(8, 0x41)
    const upload = await ctx.call('POST', `/fs/upload?${common}&offset=0`, {
      headers: { ...auth(ctx.token), 'content-type': 'application/octet-stream' }, body: chunk,
    })
    const uploaded = parseJsonOrText(upload.responseText)
    ctx.assertEq(upload.status, 200, 'upload chunk status')
    ctx.assertEq(uploaded.partial, true, 'upload chunk partial')
    ctx.assertEq(uploaded.size, 8, 'upload chunk size')
    const probe = await ctx.call('GET', `/fs/upload-probe?${common}`, { headers: auth(ctx.token) })
    const probed = parseJsonOrText(probe.responseText)
    ctx.assertEq(probe.status, 200, 'upload-probe status')
    ctx.assertEq(probed.partialSize, 8, 'probe sees the 8-byte part')
    const mismatch = await ctx.call('POST', `/fs/upload?${common}&offset=99`, {
      headers: { ...auth(ctx.token), 'content-type': 'application/octet-stream' }, body: chunk,
    })
    const failed = parseJsonOrText(mismatch.responseText)
    ctx.assertEq(mismatch.status, 409, 'offset beyond part must 409')
    ctx.assertEq(failed.error, 'offset-mismatch', 'offset-mismatch error code')
    ctx.assertEq(failed.partialSize, 8, 'offset-mismatch reports current size')
    const cancel = await ctx.call('POST', `/fs/upload-control?${common}&action=cancel`, { headers: auth(ctx.token) })
    const cancelled = parseJsonOrText(cancel.responseText)
    ctx.assertEq(cancel.status, 200, 'upload-control status')
    ctx.assertEq(cancelled.cancelled, true, 'upload-control cancelled')
  },
}

// ---------------------------------------------------------------------------
// Normalization (deterministic placeholders; see contract/gateway/v0/README.md)
// ---------------------------------------------------------------------------

const ID_HEADER_KEYS = new Set(['rpcid', 'requestid', 'callid', 'approvalid', 'id', 'operationid', 'session'])
const SESSION_KEYS = new Set(['sessionid', 'parentsessionid', 'workspaceid'])
const TS_RESPONSE_HEADER_KEYS = new Set(['date', 'upload-expires'])

function createNormalizer (ctx) {
  const state = {
    tsCounter: 0,
    sessions: new Map(),
    ids: new Map(),
    epochs: new Set(ctx.epochs),
  }
  const nextTs = () => `{{TS_${++state.tsCounter}}}`

  function isTimestampKey (key) {
    return key === 'ts' || key.endsWith('at') || key.endsWith('ms')
  }

  // Structural pass over parsed JSON: numbers under timestamp-named keys
  // become {{TS_n}} (occurrence-numbered, so same-millisecond values can
  // never collapse differently between runs); the stream epoch becomes
  // {{EPOCH}}; id-ish keys are mapped in first-seen order.
  function walk (value, key) {
    if (Array.isArray(value)) return value.map(item => walk(item, key))
    if (value !== null && typeof value === 'object') {
      const out = {}
      for (const name of Object.keys(value).sort()) out[name] = walk(value[name], name.toLowerCase())
      return out
    }
    if (typeof value === 'number') {
      if (key === 'pid') return '{{PID}}'
      if (isTimestampKey(key)) return nextTs()
      return value
    }
    if (typeof value === 'string') {
      if (key === 'epoch' && value) { state.epochs.add(value); return '{{EPOCH}}' }
      if (ID_HEADER_KEYS.has(key) && value) {
        if (!state.ids.has(value)) state.ids.set(value, `{{ID_${state.ids.size + 1}}}`)
        return state.ids.get(value)
      }
      if (SESSION_KEYS.has(key) && value) {
        if (!state.sessions.has(value)) state.sessions.set(value, `{{SESSION_${state.sessions.size + 1}}}`)
        return state.sessions.get(value)
      }
      return value
    }
    return value
  }

  return { state, walk, nextTs }
}

function buildSubstitutions (ctx, normalizer) {
  const pairs = [
    [ctx.base, '{{BASE}}'],
    [ctx.token, '{{TOKEN}}'],
    [ctx.tmpRaw, '{{TMP}}'],
    [ctx.tmpReal, '{{TMP}}'],
    [encodeURIComponent(ctx.tmpRaw), '{{TMP}}'],
    [encodeURIComponent(ctx.tmpReal), '{{TMP}}'],
  ]
  for (const ticket of ctx.tickets) pairs.push([ticket, '{{TICKET}}'])
  for (const epoch of normalizer.state.epochs) pairs.push([epoch, '{{EPOCH}}'])
  for (const port of ctx.ports) pairs.push([`127.0.0.1:${port}`, '127.0.0.1:{{PORT}}'])
  // Longest value first: a token that is a substring of another captured
  // value can then never corrupt the shorter replacement.
  pairs.sort((a, b) => b[0].length - a[0].length || a[1].localeCompare(b[1]))
  const compiled = pairs.map(([value, placeholder]) => [
    new RegExp(value.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&'), 'gi'), placeholder, value,
  ])

  return function substitute (text) {
    for (const [pattern, placeholder, literal] of compiled) {
      if (literal.length === 0) fail(2, 'refusing to normalize an empty captured value')
      text = text.replace(pattern, placeholder)
    }
    return text
  }
}

function normalizeArtifact (scenarioName, steps, ctx, normalizer, gatewayVersion) {
  const artifact = {
    name: scenarioName,
    gatewayVersion,
    capturedAt: '{{TS_0}}',
    steps: steps.map(step => ({
      method: step.method,
      path: step.path,
      requestHeaders: reorder(step.requestHeaders, key => normalizer.walk(step.requestHeaders[key], key.toLowerCase())),
      status: step.status,
      responseHeaders: reorder(step.responseHeaders, key => {
        const lower = key.toLowerCase()
        if (TS_RESPONSE_HEADER_KEYS.has(lower)) return normalizer.nextTs()
        return step.responseHeaders[key]
      }),
      response: normalizer.walk(parseJsonOrText(step.responseText)),
    })),
  }
  return artifact

  function reorder (source, mapper) {
    const out = {}
    for (const key of Object.keys(source).sort()) out[key] = mapper(key)
    return out
  }
}

function sortObject (value) {
  if (Array.isArray(value)) return value.map(sortObject)
  if (value !== null && typeof value === 'object') {
    const out = {}
    for (const key of Object.keys(value).sort()) out[key] = sortObject(value[key])
    return out
  }
  return value
}

// ---------------------------------------------------------------------------
// Recording pipeline
// ---------------------------------------------------------------------------

async function recordInto (outDir, opts) {
  const require = createRequire(import.meta.url)
  const fixturePath = resolveFixturePath(opts)
  if (!fs.existsSync(fixturePath)) {
    process.stdout.write(`BLOCKED: realistic-stack fixture not found at ${fixturePath}\n`)
    process.exit(3)
  }
  const { createRealisticStack } = require(fixturePath)

  // Shrink the event ring buffer so the truncated/reset scenario can overrun
  // it with a bounded number of events; the fixture passes this through to
  // the gateway child (its env is {...process.env, ...}).
  if (!process.env.GATEWAY_EVENT_BUFFER_MAX) process.env.GATEWAY_EVENT_BUFFER_MAX = '100'

  const stack = await createRealisticStack()
  const caller = makeCaller(Number(new URL(stack.base).port))
  const tmpReal = fs.realpathSync(stack.tmpRoot)
  const ctx = {
    stack,
    token: stack.token,
    base: stack.base,
    tmpRaw: stack.tmpRoot,
    tmpReal,
    ports: [
      Number(new URL(stack.base).port),
      Number(new URL(stack.mobileBase).port),
      stack.dsh.port,
    ],
    tickets: new Set(), // no ws-ticket scenario in v0; kept for the rule + secret scan
    epochs: new Set(),
    gatewayVersion: '',
    pollCursor: { muxLatestSeq: 0, muxEpoch: '' },
    assertEq (actual, expected, label) {
      if (actual !== expected) {
        fail(2, `contract assertion failed in scenario: ${label} (expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)})`)
      }
    },
    call: async (method, requestPath, options) => caller.call(method, requestPath, options),
  }

  const recorded = []
  const omitted = []
  try {
    for (const name of SCENARIO_ORDER) {
      const before = caller.captured.length
      try {
        await scenarios[name](ctx)
        recorded.push({ name, steps: caller.captured.slice(before) })
      } catch (error) {
        if (error.notRecordable) {
          // The scenario may issue probe calls purely to document the
          // observed behavior inside the not-recordable reason; their
          // steps are intentionally dropped below.
          caller.captured.length = before
          omitted.push({ name, reason: error.message })
          console.warn(`WARN: scenario omitted (not recordable): ${name}`)
        } else throw error
      }
    }
  } finally {
    await stack.stop()
    caller.agent.destroy()
    // Last-resort teardown: the fixture only sends SIGTERM to the gateway
    // child and rm -recycles tmpRoot without reaping; a lost or ignored
    // signal can leak the child (observed once). Reap any survivor whose
    // environment points at our private tmp root, then remove the tree.
    if (process.platform === 'linux') {
      for (const entry of fs.readdirSync('/proc')) {
        if (!/^\d+$/.test(entry)) continue
        let env
        try { env = fs.readFileSync(`/proc/${entry}/environ`) } catch { continue }
        if (!env.includes(Buffer.from(`DSH_REMOTE_FS_ROOT=${stack.tmpRoot}`))) continue
        try { process.kill(Number(entry), 'SIGKILL') } catch {}
      }
    }
    fs.rmSync(stack.tmpRoot, { recursive: true, force: true })
  }

  const normalizer = createNormalizer(ctx)
  // Stage A: structural walk of every artifact in scenario order. This also
  // discovers the captured stream epochs, which stage B needs before its
  // substitution table can be compiled (they appear verbatim in request
  // paths like `?epoch=<uuid>`).
  const artifacts = recorded.map(scenario =>
    normalizeArtifact(scenario.name, scenario.steps, ctx, normalizer, ctx.gatewayVersion))
  const substitute = buildSubstitutions(ctx, normalizer)
  const files = []

  for (let i = 0; i < recorded.length; i++) {
    const scenario = recorded[i]
    const text0 = substitute(JSON.stringify(artifacts[i], null, 2))
    // Deterministic framing: content-length is recomputed against the bytes
    // of the normalized `response` field actually written to the artifact.
    const parsed = JSON.parse(text0)
    for (const step of parsed.steps) {
      if (step.responseHeaders && Object.prototype.hasOwnProperty.call(step.responseHeaders, 'content-length')) {
        step.responseHeaders['content-length'] = String(Buffer.byteLength(JSON.stringify(step.response, null, 2)))
      }
    }
    const text = substitute(JSON.stringify(parsed, null, 2))
    if (text.includes(ctx.token)) fail(2, `secret leak: token survived normalization in ${scenario.name}`)
    files.push({ name: `${scenario.name}.json`, contents: text + '\n' })
  }

  files.push({ name: 'README.md', contents: renderReadme(omitted) })
  const manifest = {
    schemaVersion: 1,
    gatewayVersion: ctx.gatewayVersion,
    files: files.map(file => ({
      name: file.name,
      sha256: crypto.createHash('sha256').update(file.contents).digest('hex'),
      bytes: Buffer.byteLength(file.contents),
    })),
  }
  files.push({ name: 'manifest.json', contents: JSON.stringify(sortObject(manifest), null, 2) + '\n' })
  const sums = files.map(file =>
    `${crypto.createHash('sha256').update(file.contents).digest('hex')}  ${file.name}`).join('\n') + '\n'
  files.push({ name: 'SHA256SUMS', contents: sums })

  // Never write a secret: token, tickets, tmp root, and every ephemeral port
  // must be absent from every artifact.
  for (const file of files) {
    const haystack = file.contents.toLowerCase()
    for (const secret of [ctx.token, tmpReal, ctx.tmpRaw, ...ctx.tickets, ...ctx.ports.map(p => `127.0.0.1:${p}`)]) {
      if (secret && haystack.includes(String(secret).toLowerCase())) {
        fail(2, `secret leak: ${JSON.stringify(secret)} found in ${file.name}`)
      }
    }
  }

  return { files, omitted, gatewayVersion: ctx.gatewayVersion }
}

function renderReadme (omitted) {
  const lines = []
  lines.push('# Gateway v0 wire contract (recorded fixtures)')
  lines.push('')
  lines.push('Machine-recorded HTTP contract of the dsh-Remote gateway, captured by')
  lines.push('`tools/contract/record.mjs` against the stock `tests/fixtures/realistic-stack.cjs`')
  lines.push('fake DSH from the dsh-Remote checkout. Read-only reference: nothing in dsh-Remote is')
  lines.push('modified. Governance: DECISIONS.md D18, gate G03.')
  lines.push('')
  lines.push('## Artifact format')
  lines.push('')
  lines.push('- One `<scenario>.json` per recorded scenario, in `SCENARIO_ORDER` in')
  lines.push('  `tools/contract/record.mjs`. Shape:')
  lines.push('  `{ name, gatewayVersion, capturedAt, steps: [ { method, path, requestHeaders,')
  lines.push('  status, responseHeaders, response } ] }`. `path` includes the query string as')
  lines.push('  sent; `response` is the parsed JSON body (string when the body is not JSON),')
  lines.push('  with object keys sorted recursively and array order preserved.')
  lines.push('- `manifest.json` — `{ schemaVersion: 1, gatewayVersion, files: [{name, sha256, bytes}] }`')
  lines.push('  (sha256 over the committed bytes; covers every file below except itself).')
  lines.push('- `SHA256SUMS` — `<sha256>  <relative-path>` lines for every file in this')
  lines.push('  directory including `manifest.json`, excluding `SHA256SUMS` itself.')
  lines.push('- `capturedAt` is the constant placeholder `{{TS_0}}`: recording timestamps are')
  lines.push('  stripped on purpose so re-recording is byte-comparable.')
  lines.push('')
  lines.push('## Normalization rules (applied in this order)')
  lines.push('')
  lines.push('1. Structural walk over captured JSON (after key sort, in scenario/step order):')
  lines.push('   - numeric values under a key named `ts` or ending in `at`/`ms` (case-insensitive)')
  lines.push('     → `{{TS_1}}`, `{{TS_2}}`, … occurrence-numbered in first-seen order across all')
  lines.push('     files (occurrence-based so two timestamps landing in the same millisecond can')
  lines.push('     never collapse to a different placeholder count between runs);')
  lines.push('   - numeric `pid` → `{{PID}}` (gateway PID changes every run);')
  lines.push('   - string value of key `epoch` (the event-stream epoch, F3) → `{{EPOCH}}`, and the')
  lines.push('     captured literal is also substituted everywhere else it appears;')
  lines.push('   - string values under id-ish keys (`rpcId`, `requestId`, `callId`, `approvalId`,')
  lines.push('     `id`, `operationId`, `session`) → `{{ID_1}}`, `{{ID_2}}`, … value-keyed in')
  lines.push('     first-seen order (the same value always maps to the same placeholder);')
  lines.push('   - string values under session-ish keys (`sessionId`, `parentSessionId`,')
  lines.push('     `workspaceId`) → `{{SESSION_1}}`, `{{SESSION_2}}`, … value-keyed in first-seen')
  lines.push('     order.')
  lines.push('2. Response headers: `date` and `upload-expires` → `{{TS_n}}` (same counter);')
  lines.push('   `content-length` is recomputed from the normalized `response` bytes actually')
  lines.push('   written into the artifact (raw lengths embed volatile digit counts).')
  lines.push('3. Case-insensitive global text substitution on the serialized artifact, applied')
  lines.push('   longest-captured-value-first so one token that is a substring of another cannot')
  lines.push('   corrupt the replacement:')
  lines.push('   - gateway base URL `http://127.0.0.1:<port>` → `{{BASE}}`;')
  lines.push('   - the literal token (any casing) → `{{TOKEN}}` — Authorization/Cookie header')
  lines.push('     values are normalized by exactly this rule;')
  lines.push('   - any ws ticket ever issued → `{{TICKET}}` (v0 scenarios do not request one, so')
  lines.push('     this set is currently empty);')
  lines.push('   - the random tmp root (raw, realpath, and percent-encoded forms) → `{{TMP}}`;')
  lines.push('   - remaining `127.0.0.1:<port>` triples (gateway, DSH upstream, mobile proxy) →')
  lines.push('     `127.0.0.1:{{PORT}}`.')
  lines.push('')
  lines.push('After writing, the recorder asserts no artifact contains the literal token, any')
  lines.push('raw ticket, the tmp root path, or any ephemeral port string, and that SHA256SUMS')
  lines.push('matches the files.')
  lines.push('')
  lines.push('Recording notes:')
  lines.push('- The recorder sets `GATEWAY_EVENT_BUFFER_MAX=100` (inherited by the gateway child')
  lines.push('  process through the fixture env) before booting the one stack, so the')
  lines.push('  `poll-truncated-and-reset` scenario can overrun the ring buffer with 130')
  lines.push('  broadcast events in bounded time. All other scenarios stay far below 100 events,')
  lines.push('  so this knob does not alter any other fixture.')
  lines.push('- Scenarios share one stack run, so later poll fixtures contain events emitted by')
  lines.push('  earlier scenarios; that coupling is deterministic because the scenario order is fixed.')
  lines.push('- Settling waits (sleeps) are used so DSH→gateway event propagation completes before a')
  lines.push('  poll is recorded; the recorded response is asserted to contain the expected events,')
  lines.push('  and the recorder fails loudly (exit 2) instead of recording a timing-dependent frame.')
  lines.push('')
  lines.push('## Not recordable')
  lines.push('')
  if (omitted.length === 0) {
    lines.push('(none)')
  }
  for (const item of omitted) {
    lines.push(`### ${item.name}`)
    lines.push('')
    lines.push(item.reason)
    lines.push('')
  }
  lines.push('## Re-recording and drift check')
  lines.push('')
  lines.push('```sh')
  lines.push('cd /home/keivn/AndroidStudioProjects/deepseek-harness/dsh-android')
  lines.push('node tools/contract/record.mjs            # rewrite contract/gateway/v0/*')
  lines.push('node tools/contract/record.mjs --check     # gate G03: re-record to a temp dir and')
  lines.push('                                          # byte-compare; exit 1 on any DIFF')
  lines.push('```')
  lines.push('')
  lines.push('The fixture is resolved from `--dsh-remote <path>`, else `DSH_REMOTE_HOME`, else')
  lines.push('`/home/keivn/AndroidStudioProjects/dsh-Remote`. If it is missing the recorder prints')
  lines.push('`BLOCKED: realistic-stack fixture not found at <path>` and exits 3, which the')
  lines.push('acceptance harness maps to gate status BLOCKED.')
  lines.push('')
  return lines.join('\n')
}

// ---------------------------------------------------------------------------
// SHA256SUMS verification + --check
// ---------------------------------------------------------------------------

function verifySums (dir, label) {
  const sumsPath = path.join(dir, 'SHA256SUMS')
  if (!fs.existsSync(sumsPath)) fail(2, `${label}: SHA256SUMS missing`)
  let failures = 0
  for (const line of fs.readFileSync(sumsPath, 'utf8').split('\n')) {
    if (!line.trim()) continue
    const match = /^([0-9a-f]{64})  (.+)$/.exec(line)
    if (!match) { console.error(`SUMS  MALFORMED line in ${label}: ${line}`); failures++; continue }
    const [, expected, name] = match
    const target = path.join(dir, name)
    if (!fs.existsSync(target)) { console.error(`DIFF  ${name}: committed SHA256SUMS references a missing file`); failures++; continue }
    const actual = crypto.createHash('sha256').update(fs.readFileSync(target)).digest('hex')
    if (actual !== expected) { console.error(`DIFF  ${name}: SHA256SUMS mismatch`); failures++ }
  }
  if (failures) fail(1, `${label}: SHA256SUMS verification failed (${failures} problems)`)
  console.log(`SUMS  OK ${label}`)
}

async function main () {
  const opts = parseArgs(process.argv.slice(2))
  const fixturePath = resolveFixturePath(opts)
  if (!fs.existsSync(fixturePath)) {
    process.stdout.write(`BLOCKED: realistic-stack fixture not found at ${fixturePath}\n`)
    process.exit(3)
  }

  if (!opts.check) {
    const { files, omitted, gatewayVersion } = await recordInto(opts.out, opts)
    fs.mkdirSync(opts.out, { recursive: true })
    const written = new Set(files.map(file => file.name))
    for (const existing of fs.readdirSync(opts.out)) {
      if ((existing.endsWith('.json') || existing === 'README.md' || existing === 'SHA256SUMS') && !written.has(existing)) {
        fs.rmSync(path.join(opts.out, existing))
      }
    }
    for (const file of files) fs.writeFileSync(path.join(opts.out, file.name), file.contents)
    verifySums(opts.out, 'committed')
    console.log(`RECORDED ${files.length - 3} scenario fixtures (gateway ${gatewayVersion}), ${omitted.length} omitted`)
    console.log(`OK ${opts.out}`)
    return
  }

  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-contract-check-'))
  let drift = 0
  try {
    const { files } = await recordInto(tempDir, opts)
    for (const file of files) fs.writeFileSync(path.join(tempDir, file.name), file.contents)
    const expected = new Set(files.map(file => file.name))
    for (const name of [...expected].sort()) {
      const committedPath = path.join(opts.out, name)
      const freshPath = path.join(tempDir, name)
      if (!fs.existsSync(committedPath)) { console.log(`DIFF  ${name}: missing in committed ${opts.out}`); drift++; continue }
      const committed = fs.readFileSync(committedPath)
      const fresh = fs.readFileSync(freshPath)
      if (committed.equals(fresh)) console.log(`OK    ${name}`)
      else { console.log(`DIFF  ${name}`); drift++ }
    }
    for (const name of fs.readdirSync(opts.out).sort()) {
      if (!expected.has(name)) { console.log(`DIFF  ${name}: present in committed dir but not produced by the recorder`); drift++ }
    }
    verifySums(opts.out, 'committed')
  } finally {
    fs.rmSync(tempDir, { recursive: true, force: true })
  }
  if (drift) {
    console.error(`CHECK FAILED: ${drift} artifact(s) drifted from the committed contract`)
    process.exit(1)
  }
  console.log('CHECK OK: re-recorded bytes match the committed contract')
}

main().catch(error => {
  console.error(error)
  process.exit(2)
})
