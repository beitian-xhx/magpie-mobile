// Real bundled plugin boundary; every fetch intercepted, no vendor credentials/network.
import assert from 'node:assert/strict'
import { gunzipSync } from 'node:zlib'
import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
const path = process.argv[2] || 'app/src/main/assets/magpie-plugins/workbuddy/index.mjs'
const { WorkBuddyAuthPlugin } = await import(pathToFileURL(resolve(path)))
const original = globalThis.fetch
const configDelay = 350
const records = []
let configCalls = 0
const auth = { type: 'oauth', access: 'fixture-access', refresh: 'fixture-refresh', expires: Date.now() + 3600000, uid: 'fixture-user' }
globalThis.fetch = async (input, init = {}) => {
  const url = String(input)
  if (url.endsWith('/v3/config')) {
    configCalls++
    await new Promise(r => setTimeout(r, configDelay))
    return Response.json({ data: { agents: [{ name: 'cli', models: ['deepseek-v4.1-flash'] }], models: [{ id: 'deepseek-v4.1-flash', reasoning: { supportedEfforts: ['low', 'high'], defaultEffort: 'high', effort: 'high' } }] } })
  }
  assert.match(url, /\/chat\/completions$/)
  const bytes = init.headers?.get('Content-Encoding') === 'gzip' ? gunzipSync(init.body) : init.body
  records.push(JSON.parse(String(bytes)))
  return new Response('data: {"choices":[{"delta":{"content":"pong"},"finish_reason":null}]}\n\ndata: [DONE]\n\n', { headers: { 'content-type': 'text/event-stream' } })
}
try {
  for (const mobile of [false, true]) {
    if (mobile) process.env.MAGPIE_MOBILE = '1'; else delete process.env.MAGPIE_MOBILE
    const hooks = await WorkBuddyAuthPlugin({ client: {}, magpie: { option: () => false } })
    const loader = await hooks.auth.loader(async () => auth)
    const before = configCalls
    const at = performance.now()
    const response = await loader.fetch('https://copilot.tencent.com/v2/chat/completions', { method: 'POST', body: JSON.stringify({ model: 'deepseek-v4.1-flash', stream: true, max_tokens: 4096, messages: [{ role: 'user', content: 'pong' }] }) })
    await response.text()
    const elapsed = Math.round(performance.now() - at)
    const sent = records.at(-1)
    console.log(JSON.stringify({ mode: mobile ? 'mobile' : 'desktop', configCalls: configCalls - before, milliseconds: elapsed, effort: sent.reasoning_effort ?? '(omitted)' }))
    if (mobile && !process.argv.includes('--baseline')) {
      assert.equal(configCalls, before, 'mobile inference must not wait for model config')
      assert.equal(sent.reasoning_effort, undefined, 'no hidden high reasoning')
      assert.ok(elapsed < configDelay, 'mobile must bypass delayed preflight')
    } else if (!mobile) assert.equal(configCalls, before + 1)
    for (const effort of ['none', 'low', 'high']) {
      const r = await loader.fetch('https://copilot.tencent.com/v2/chat/completions', { method: 'POST', body: JSON.stringify({ model: 'deepseek-v4.1-flash', stream: true, reasoning_effort: effort, messages: [{ role: 'user', content: 'pong' }] }) })
      await r.text()
      assert.equal(records.at(-1).reasoning_effort, effort)
    }
  }
  // Free-only is still enforced before sending a billable chat.
  process.env.MAGPIE_MOBILE = '1'
  const hooks = await WorkBuddyAuthPlugin({ client: {}, magpie: { option: (_, key) => key === 'free_only' } })
  const loader = await hooks.auth.loader(async () => auth)
  const before = records.length
  const res = await loader.fetch('https://copilot.tencent.com/v2/chat/completions', { method: 'POST', body: JSON.stringify({ model: 'paid-unknown-model', stream: true, messages: [{ role: 'user', content: 'pong' }] }) })
  assert.ok(res.status >= 400)
  assert.equal(records.length, before)
  console.log('PASS explicit effort and free-only checks')
} finally { globalThis.fetch = original; delete process.env.MAGPIE_MOBILE }
