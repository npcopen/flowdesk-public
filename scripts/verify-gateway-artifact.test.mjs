import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { findGatewayJar, inspectGatewayEntries } from './verify-gateway-artifact.mjs'

const indexHtml = `<!doctype html>
<div id="app"></div>
<script type="module" src="/assets/index-AbCd1234.js"></script>
<link rel="stylesheet" href="/assets/index-EfGh5678.css">`
const validEntries = [
  'BOOT-INF/classes/static/index.html',
  'BOOT-INF/classes/static/assets/index-AbCd1234.js',
  'BOOT-INF/classes/static/assets/index-EfGh5678.css'
]

test('accepts a shell with hashed JavaScript and CSS references', () => {
  const result = inspectGatewayEntries(validEntries, indexHtml)
  assert.equal(result.ok, true)
  assert.deepEqual(result.javascriptAssets, ['index-AbCd1234.js'])
  assert.deepEqual(result.stylesheetAssets, ['index-EfGh5678.css'])
})

test('fails closed when the shell is absent', () => {
  const result = inspectGatewayEntries(validEntries.slice(1), indexHtml)
  assert.equal(result.ok, false)
  assert.match(result.errors.join('\n'), /missing BOOT-INF\/classes\/static\/index\.html/)
})

test('fails closed for unhashed or missing shell assets', () => {
  const result = inspectGatewayEntries([
    'BOOT-INF/classes/static/index.html',
    'BOOT-INF/classes/static/assets/index.js'
  ], '<script src="/assets/index.js"></script>')
  assert.equal(result.ok, false)
  assert.match(result.errors.join('\n'), /hashed JavaScript/)
  assert.match(result.errors.join('\n'), /hashed CSS/)
  assert.match(result.errors.join('\n'), /references missing asset|references unhashed asset/)
})

test('rejects path traversal in shell references', () => {
  const result = inspectGatewayEntries(validEntries, '<script src="/assets/../secret.js"></script>')
  assert.equal(result.ok, false)
  assert.match(result.errors.join('\n'), /unsafe static asset reference/)
})

test('rejects source maps and unhashed code assets', () => {
  const result = inspectGatewayEntries([
    ...validEntries,
    'BOOT-INF/classes/static/assets/debug.js',
    'BOOT-INF/classes/static/assets/chunks/debug.js.map'
  ], indexHtml)
  assert.equal(result.ok, false)
  assert.match(result.errors.join('\n'), /unhashed JavaScript\/CSS/)
  assert.match(result.errors.join('\n'), /source maps/)
})

test('rejects unsafe and nested static archive entries', () => {
  const result = inspectGatewayEntries([
    ...validEntries,
    'BOOT-INF/classes/static/assets/chunks/index-AbCd1234.js',
    'BOOT-INF/classes/static/../application.properties'
  ], indexHtml)
  assert.equal(result.ok, false)
  assert.match(result.errors.join('\n'), /unsafe JAR entries/)
  assert.match(result.errors.join('\n'), /nested static assets/)
})

test('requires a non-empty shell mount', () => {
  const result = inspectGatewayEntries(validEntries, '<html><body></body></html>')
  assert.equal(result.ok, false)
  assert.match(result.errors.join('\n'), /#app mount element/)
  assert.match(result.errors.join('\n'), /does not reference a hashed JavaScript/)
})

test('selects only the executable Gateway JAR and rejects ambiguity', () => {
  const target = mkdtempSync(join(tmpdir(), 'flowdesk-gateway-find-'))
  try {
    writeFileSync(join(target, 'flowdesk-gateway-0.1.0-SNAPSHOT.jar'), '')
    writeFileSync(join(target, 'flowdesk-gateway-0.1.0-SNAPSHOT.jar.original'), '')
    assert.equal(findGatewayJar(target), join(target, 'flowdesk-gateway-0.1.0-SNAPSHOT.jar'))
    writeFileSync(join(target, 'flowdesk-gateway-0.1.0-SNAPSHOT-other.jar'), '')
    assert.throws(() => findGatewayJar(target), /exactly one executable Gateway JAR/)
  } finally {
    rmSync(target, { recursive: true, force: true })
  }
})
