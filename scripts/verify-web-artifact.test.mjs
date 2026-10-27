import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { inspectWebEntries, inspectProductionScript, validateWebArtifact } from './verify-web-artifact.mjs'

const indexHtml = `<!doctype html>
<div id="app"></div>
<script type="module" src="/assets/index-AbCd1234.js"></script>
<link rel="stylesheet" href="/assets/index-EfGh5678.css">`
const validEntries = ['index.html', 'assets/index-AbCd1234.js', 'assets/index-EfGh5678.css']

test('accepts a shell with hashed JavaScript and CSS references', () => {
  const result = inspectWebEntries(validEntries, indexHtml)
  assert.equal(result.ok, true)
  assert.deepEqual(result.javascriptAssets, ['index-AbCd1234.js'])
  assert.deepEqual(result.stylesheetAssets, ['index-EfGh5678.css'])
})

test('fails closed when the shell is absent', () => {
  const result = inspectWebEntries(validEntries.slice(1), indexHtml)
  assert.equal(result.ok, false)
  assert.match(result.errors.join('\n'), /missing index\.html/)
})

test('fails closed for unhashed or missing shell assets', () => {
  const result = inspectWebEntries(['index.html', 'assets/index.js'], '<script src="/assets/index.js"></script>')
  assert.equal(result.ok, false)
  assert.match(result.errors.join('\n'), /hashed JavaScript/)
  assert.match(result.errors.join('\n'), /hashed CSS/)
  assert.match(result.errors.join('\n'), /references missing asset|references unhashed asset/)
})

test('rejects path traversal in shell references', () => {
  const result = inspectWebEntries(validEntries, '<script src="/assets/../secret.js"></script>')
  assert.equal(result.ok, false)
  assert.match(result.errors.join('\n'), /unsafe static asset reference/)
})

test('rejects source maps anywhere and unhashed code assets', () => {
  const result = inspectWebEntries([...validEntries, 'assets/debug.js', 'debug.js.map'], indexHtml)
  assert.equal(result.ok, false)
  assert.match(result.errors.join('\n'), /unhashed JavaScript\/CSS/)
  assert.match(result.errors.join('\n'), /source maps/)
})

test('rejects unsafe and nested artifact entries', () => {
  const result = inspectWebEntries([...validEntries, 'assets/chunks/index-AbCd1234.js', '../application.properties'], indexHtml)
  assert.equal(result.ok, false)
  assert.match(result.errors.join('\n'), /unsafe artifact entries/)
  assert.match(result.errors.join('\n'), /nested static assets/)
})

test('requires a shell mount and asset references', () => {
  const result = inspectWebEntries(validEntries, '<html><body></body></html>')
  assert.equal(result.ok, false)
  assert.match(result.errors.join('\n'), /#app mount element/)
  assert.match(result.errors.join('\n'), /does not reference a hashed JavaScript/)
})

test('detects browser mock hooks and fixture data in production code', () => {
  assert.deepEqual(inspectProductionScript('fetch("/api/v1/users/me")'), [])
  assert.deepEqual(inspectProductionScript('window.__FLOWDESK_E2E_RESET = reset'), ['__FLOWDESK_E2E_RESET'])
})

test('validates standalone dist and rejects empty or mock assets', () => {
  const directory = mkdtempSync(join(tmpdir(), 'flowdesk-web-dist-'))
  try {
    mkdirSync(join(directory, 'assets'))
    writeFileSync(join(directory, 'index.html'), indexHtml)
    writeFileSync(join(directory, 'assets/index-AbCd1234.js'), 'fetch("/api/v1/users/me")')
    writeFileSync(join(directory, 'assets/index-EfGh5678.css'), 'body{margin:0}')
    const result = validateWebArtifact(directory)
    assert.equal(result.ok, true)
    assert.match(result.bundleSha256, /^[0-9a-f]{64}$/)
    writeFileSync(join(directory, 'assets/index-AbCd1234.js'), '')
    assert.throws(() => validateWebArtifact(directory), /asset is empty/)
    writeFileSync(join(directory, 'assets/index-AbCd1234.js'), 'window.__FLOWDESK_E2E_RESET = reset')
    assert.throws(() => validateWebArtifact(directory), /Browser mock leaked/)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})
