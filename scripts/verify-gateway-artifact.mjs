#!/usr/bin/env node

import { createHash } from 'node:crypto'
import { existsSync, lstatSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const MODULE_PATH = fileURLToPath(import.meta.url)
export const REPOSITORY_ROOT = resolve(dirname(MODULE_PATH), '..')
export const GATEWAY_ARTIFACT_ID = 'flowdesk-gateway'
export const STATIC_ENTRY_PREFIX = 'BOOT-INF/classes/static/'
export const HASHED_ASSET_PATTERN = /^(?:[^/]+)-[A-Za-z0-9_-]{8,}\.(?:js|css)$/

const JAR_COMMAND = process.platform === 'win32' ? 'jar.exe' : 'jar'

/**
 * Inspect the names and shell references in a packaged Gateway archive.
 *
 * Keeping this function independent from the JAR command makes the contract
 * easy to exercise in a clean Node-only test and keeps the CLI fail-closed.
 */
export function inspectGatewayEntries(entries, indexHtml = undefined) {
  const rawEntries = entries
    .map((entry) => String(entry).trim())
    .filter((entry) => entry.length > 0)
  const unsafeEntries = rawEntries.filter((entry) => {
    const normalizedEntry = entry.replaceAll('\\', '/')
    return normalizedEntry.startsWith('/')
      || normalizedEntry.split('/').some((segment) => segment === '..')
  })
  const normalized = new Set(
    rawEntries
      .map((entry) => entry.replaceAll('\\', '/').replace(/^\/+/, ''))
      .filter((entry) => entry.length > 0 && !entry.endsWith('/'))
  )
  const errors = []
  if (unsafeEntries.length > 0) {
    errors.push(`unsafe JAR entries are not allowed: ${unsafeEntries.sort().join(', ')}`)
  }
  const indexEntry = `${STATIC_ENTRY_PREFIX}index.html`
  if (!normalized.has(indexEntry)) {
    errors.push(`missing ${indexEntry}`)
  }

  const staticAssets = [...normalized]
    .filter((entry) => entry.startsWith(`${STATIC_ENTRY_PREFIX}assets/`))
    .map((entry) => entry.slice(`${STATIC_ENTRY_PREFIX}assets/`.length))
  const nestedAssets = staticAssets.filter((entry) => entry.includes('/') || entry.includes('\\'))
  if (nestedAssets.length > 0) {
    errors.push(`nested static assets are not allowed: ${nestedAssets.sort().join(', ')}`)
  }
  const assets = staticAssets.filter((entry) => !entry.includes('/') && !entry.includes('\\'))
  const hashedAssets = assets.filter((asset) => HASHED_ASSET_PATTERN.test(asset))
  const javascriptAssets = hashedAssets.filter((asset) => asset.toLowerCase().endsWith('.js'))
  const stylesheetAssets = hashedAssets.filter((asset) => asset.toLowerCase().endsWith('.css'))
  const unhashedCodeAssets = assets.filter((asset) => {
    const isCodeAsset = /\.(?:js|css)$/i.test(asset)
    return isCodeAsset && !HASHED_ASSET_PATTERN.test(asset)
  })
  if (unhashedCodeAssets.length > 0) {
    errors.push(`unhashed JavaScript/CSS assets are not allowed: ${unhashedCodeAssets.sort().join(', ')}`)
  }
  const sourceMaps = staticAssets.filter((asset) => asset.toLowerCase().endsWith('.map'))
  if (sourceMaps.length > 0) {
    errors.push(`source maps are not allowed in the Gateway bundle: ${sourceMaps.sort().join(', ')}`)
  }
  if (javascriptAssets.length === 0) {
    errors.push('missing a hashed JavaScript asset under static/assets/')
  }
  if (stylesheetAssets.length === 0) {
    errors.push('missing a hashed CSS asset under static/assets/')
  }

  const referencedAssets = []
  if (typeof indexHtml === 'string') {
    if (indexHtml.trim().length === 0) {
      errors.push('static/index.html is empty')
    }
    if (!/<div\s+[^>]*\bid\s*=\s*["']app["'][^>]*>\s*<\/div>/i.test(indexHtml)) {
      errors.push('static/index.html has no #app mount element')
    }
    // Vite emits simple absolute src/href attributes. Restrict the extraction
    // to those attributes so arbitrary text in the shell cannot affect checks.
    const referencePattern = /(?:src|href)\s*=\s*["']\/assets\/([^"'?#\s]+)["']/gi
    for (const match of indexHtml.matchAll(referencePattern)) {
      const asset = match[1]
      referencedAssets.push(asset)
      if (asset.includes('/') || asset.includes('\\') || asset.includes('..')) {
        errors.push(`unsafe static asset reference ${asset}`)
        continue
      }
      const entry = `${STATIC_ENTRY_PREFIX}assets/${asset}`
      if (!normalized.has(entry)) {
        errors.push(`index.html references missing asset ${asset}`)
      }
      if (!HASHED_ASSET_PATTERN.test(asset)) {
        errors.push(`index.html references unhashed asset ${asset}`)
      }
    }
    if (!referencedAssets.some((asset) => asset.toLowerCase().endsWith('.js') && HASHED_ASSET_PATTERN.test(asset))) {
      errors.push('index.html does not reference a hashed JavaScript asset')
    }
    if (!referencedAssets.some((asset) => asset.toLowerCase().endsWith('.css') && HASHED_ASSET_PATTERN.test(asset))) {
      errors.push('index.html does not reference a hashed CSS asset')
    }
  }

  return {
    ok: errors.length === 0,
    errors,
    assets: assets.sort(),
    hashedAssets: hashedAssets.sort(),
    javascriptAssets: javascriptAssets.sort(),
    stylesheetAssets: stylesheetAssets.sort(),
    referencedAssets
  }
}

export function assertGatewayEntries(entries, indexHtml = undefined) {
  const inspection = inspectGatewayEntries(entries, indexHtml)
  if (!inspection.ok) {
    throw new Error([
      'Gateway static artifact validation failed:',
      ...inspection.errors.map((error) => `- ${error}`)
    ].join('\n'))
  }
  return inspection
}

export function findGatewayJar(targetDirectory = join(REPOSITORY_ROOT, 'flowdesk-gateway', 'target')) {
  if (!existsSync(targetDirectory) || !lstatSync(targetDirectory).isDirectory()) {
    throw new Error(`Gateway target directory does not exist: ${targetDirectory}`)
  }
  const candidates = readdirSync(targetDirectory)
    .filter((name) => name.startsWith(`${GATEWAY_ARTIFACT_ID}-`))
    .filter((name) => name.endsWith('.jar'))
    .filter((name) => !name.endsWith('.jar.original'))
    .filter((name) => !/(?:-exec|-sources|-javadoc|-tests?|-thin|-plain)\.jar$/i.test(name))
  if (candidates.length !== 1) {
    throw new Error(`Expected exactly one executable Gateway JAR, found ${candidates.length}: ${candidates.join(', ') || '(none)'}`)
  }
  return join(targetDirectory, candidates[0])
}

function runJar(args, options = {}) {
  const result = spawnSync(JAR_COMMAND, args, {
    encoding: 'utf8',
    windowsHide: true,
    ...options
  })
  if (result.error) {
    throw new Error(`Could not execute ${JAR_COMMAND}: ${result.error.message}`)
  }
  if (result.status !== 0) {
    const detail = `${result.stdout || ''}${result.stderr || ''}`.trim()
    throw new Error(`${JAR_COMMAND} ${args.join(' ')} failed with exit code ${result.status}${detail ? `: ${detail}` : ''}`)
  }
  return result.stdout || ''
}

function sha256(filePath) {
  return createHash('sha256').update(readFileSync(filePath)).digest('hex')
}

export function validateGatewayArtifact(jarPath) {
  const absoluteJarPath = resolve(jarPath)
  if (!existsSync(absoluteJarPath) || !statSync(absoluteJarPath).isFile()) {
    throw new Error(`Gateway JAR does not exist: ${absoluteJarPath}`)
  }

  const entries = runJar(['tf', absoluteJarPath]).split(/\r?\n/).filter(Boolean)
  // Validate archive names before extraction so a crafted path cannot escape
  // the temporary directory and so malformed bundles fail without side effects.
  assertGatewayEntries(entries)
  const extractionDirectory = mkdtempSync(join(tmpdir(), 'flowdesk-gateway-static-'))
  try {
    runJar(['xf', absoluteJarPath], { cwd: extractionDirectory })
    const indexPath = join(extractionDirectory, 'BOOT-INF', 'classes', 'static', 'index.html')
    const indexHtml = existsSync(indexPath) ? readFileSync(indexPath, 'utf8') : undefined
    const inspection = assertGatewayEntries(entries, indexHtml)
    const nonEmptyAssets = []
    for (const asset of [...inspection.javascriptAssets, ...inspection.stylesheetAssets]) {
      const assetPath = join(extractionDirectory, 'BOOT-INF', 'classes', 'static', 'assets', asset)
      if (!existsSync(assetPath) || !statSync(assetPath).isFile() || statSync(assetPath).size === 0) {
        throw new Error(`Gateway static asset is missing or empty: ${asset}`)
      }
      nonEmptyAssets.push(asset)
    }
    return {
      ...inspection,
      jarPath: absoluteJarPath,
      jarSha256: sha256(absoluteJarPath),
      nonEmptyAssets
    }
  } finally {
    rmSync(extractionDirectory, { recursive: true, force: true })
  }
}

function parseArguments(argv) {
  let jarPath
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === '--help' || argument === '-h') {
      return { help: true }
    }
    if (argument === '--jar' || argument === '-j') {
      jarPath = argv[++index]
      if (!jarPath) throw new Error('--jar requires a path')
      continue
    }
    throw new Error(`Unknown argument: ${argument}`)
  }
  return { jarPath }
}

export function main(argv = process.argv.slice(2)) {
  try {
    const options = parseArguments(argv)
    if (options.help) {
      console.log('Usage: node scripts/verify-gateway-artifact.mjs [--jar PATH]')
      return 0
    }
    const jarPath = options.jarPath ? resolve(options.jarPath) : findGatewayJar()
    const result = validateGatewayArtifact(jarPath)
    const displayPath = relative(REPOSITORY_ROOT, result.jarPath).replaceAll('\\', '/')
    console.log(`FLOWDESK_GATEWAY_STATIC_OK jar=${displayPath} sha256=${result.jarSha256} js=${result.javascriptAssets.length} css=${result.stylesheetAssets.length}`)
    return 0
  } catch (error) {
    console.error(`FLOWDESK_GATEWAY_STATIC_INVALID: ${error instanceof Error ? error.message : String(error)}`)
    return 1
  }
}

if (resolve(process.argv[1] || '') === MODULE_PATH) {
  process.exitCode = main()
}
