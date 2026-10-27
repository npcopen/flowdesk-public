#!/usr/bin/env node

import { createHash } from 'node:crypto'
import { existsSync, lstatSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const MODULE_PATH = fileURLToPath(import.meta.url)
export const REPOSITORY_ROOT = resolve(dirname(MODULE_PATH), '..')
export const WEB_DIST_DIRECTORY = join(REPOSITORY_ROOT, 'flowdesk-web', 'dist')
export const HASHED_ASSET_PATTERN = /^(?:[^/]+)-[A-Za-z0-9_-]{8,}\.(?:js|css)$/

/**
 * 校验独立前端制品的文件名与页面资源引用，不依赖 Java 或后端打包。
 * 任一规则失败均拒绝该制品。
 */
export function inspectWebEntries(entries, indexHtml = undefined) {
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
    errors.push(`unsafe artifact entries are not allowed: ${unsafeEntries.sort().join(', ')}`)
  }
  const indexEntry = 'index.html'
  if (!normalized.has(indexEntry)) {
    errors.push(`missing ${indexEntry}`)
  }

  const staticAssets = [...normalized]
    .filter((entry) => entry.startsWith('assets/'))
    .map((entry) => entry.slice('assets/'.length))
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
  const sourceMaps = [...normalized].filter((entry) => entry.toLowerCase().endsWith('.map'))
  if (sourceMaps.length > 0) {
    errors.push(`source maps are not allowed in the web bundle: ${sourceMaps.sort().join(', ')}`)
  }
  if (javascriptAssets.length === 0) {
    errors.push('missing a hashed JavaScript asset under assets/')
  }
  if (stylesheetAssets.length === 0) {
    errors.push('missing a hashed CSS asset under assets/')
  }

  const referencedAssets = []
  if (typeof indexHtml === 'string') {
    if (indexHtml.trim().length === 0) {
      errors.push('index.html is empty')
    }
    if (!/<div\s+[^>]*\bid\s*=\s*["']app["'][^>]*>\s*<\/div>/i.test(indexHtml)) {
      errors.push('index.html has no #app mount element')
    }
    // 只从 Vite 生成的绝对 src/href 属性提取资源，避免页面中的任意文字影响校验。
    const referencePattern = /(?:src|href)\s*=\s*["']\/assets\/([^"'?#\s]+)["']/gi
    for (const match of indexHtml.matchAll(referencePattern)) {
      const asset = match[1]
      referencedAssets.push(asset)
      if (asset.includes('/') || asset.includes('\\') || asset.includes('..')) {
        errors.push(`unsafe static asset reference ${asset}`)
        continue
      }
      const entry = `assets/${asset}`
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

/**
 * 调用文件清单校验并在失败时汇总抛出全部规则错误，成功返回结构化检查结果。
 */
export function assertWebEntries(entries, indexHtml = undefined) {
  const inspection = inspectWebEntries(entries, indexHtml)
  if (!inspection.ok) {
    throw new Error([
      'Web artifact validation failed:',
      ...inspection.errors.map((error) => `- ${error}`)
    ].join('\n'))
  }
  return inspection
}

const MOCK_BUNDLE_MARKERS = [
  '__FLOWDESK_E2E_TRIGGER_ALERT', '__FLOWDESK_E2E_RESET',
  'mock-access-token', 'mock-refresh-token',
  '0848bf3c-d603-5ece-9529-f9bba79f1498',
]

/**
 * 查找产物中已知的浏览器模拟钩子及样例数据标记，返回命中项供生产制品检查拒绝泄漏。
 */
export function inspectProductionScript(source) {
  return MOCK_BUNDLE_MARKERS.filter((marker) => source.includes(marker))
}

/**
 * 递归收集相对制品路径，拒绝符号链接及非常规文件，避免校验越出真实制品目录。
 */
function collectFiles(directory, prefix = '') {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name)
    const entry = prefix + name
    const info = lstatSync(path)
    if (info.isSymbolicLink()) throw new Error(`Symbolic links are not allowed in web artifacts: ${entry}`)
    if (info.isDirectory()) return collectFiles(path, `${entry}/`)
    if (!info.isFile()) throw new Error(`Unsupported web artifact entry: ${entry}`)
    return [entry]
  })
}

/**
 * 验证目录、入口和哈希资源引用，拒绝空文件及模拟代码，再按排序后的路径和内容计算整体 SHA-256。
 */
export function validateWebArtifact(distDirectory = WEB_DIST_DIRECTORY) {
  const directory = resolve(distDirectory)
  if (!existsSync(directory) || !lstatSync(directory).isDirectory() || lstatSync(directory).isSymbolicLink()) {
    throw new Error(`Web dist directory does not exist or is unsafe: ${directory}`)
  }
  const entries = collectFiles(directory)
  const indexPath = join(directory, 'index.html')
  const indexHtml = existsSync(indexPath) ? readFileSync(indexPath, 'utf8') : undefined
  const inspection = assertWebEntries(entries, indexHtml)
  const digest = createHash('sha256')
  for (const entry of entries.sort()) {
    const path = join(directory, entry)
    if (statSync(path).size === 0) throw new Error(`Web asset is empty: ${entry}`)
    const content = readFileSync(path)
    if (entry.endsWith('.js')) {
      const leaked = inspectProductionScript(content.toString('utf8'))
      if (leaked.length) throw new Error(`Browser mock leaked into ${entry}: ${leaked.join(', ')}`)
    }
    digest.update(entry).update('\0').update(content).update('\0')
  }
  return { ...inspection, distDirectory: directory, bundleSha256: digest.digest('hex') }
}

/**
 * 解析帮助和自定义 dist 路径，遇到缺少参数值或未知选项时直接报错。
 */
function parseArguments(argv) {
  let distDirectory
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === '--help' || argument === '-h') {
      return { help: true }
    }
    if (argument === '--dist' || argument === '-d') {
      distDirectory = argv[++index]
      if (!distDirectory) throw new Error('--dist requires a path')
      continue
    }
    throw new Error(`Unknown argument: ${argument}`)
  }
  return { distDirectory }
}

/**
 * 执行命令行制品验证，成功打印资源数量与摘要；失败输出稳定诊断并返回非零退出码。
 */
export function main(argv = process.argv.slice(2)) {
  try {
    const options = parseArguments(argv)
    if (options.help) {
      console.log('Usage: node scripts/verify-web-artifact.mjs [--dist PATH]')
      return 0
    }
    const result = validateWebArtifact(options.distDirectory)
    const displayPath = relative(REPOSITORY_ROOT, result.distDirectory).replaceAll('\\', '/')
    console.log(`FLOWDESK_WEB_ARTIFACT_OK dist=${displayPath} sha256=${result.bundleSha256} js=${result.javascriptAssets.length} css=${result.stylesheetAssets.length}`)
    return 0
  } catch (error) {
    console.error(`FLOWDESK_WEB_ARTIFACT_INVALID: ${error instanceof Error ? error.message : String(error)}`)
    return 1
  }
}

if (resolve(process.argv[1] || '') === MODULE_PATH) {
  process.exitCode = main()
}
