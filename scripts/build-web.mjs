#!/usr/bin/env node

import { spawnSync } from 'node:child_process'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { REPOSITORY_ROOT, validateWebArtifact } from './verify-web-artifact.mjs'

const WEB_ROOT = resolve(REPOSITORY_ROOT, 'flowdesk-web')
const NPM_COMMAND = process.platform === 'win32' ? 'npm.cmd' : 'npm'
const NPM_CACHE = resolve(REPOSITORY_ROOT, '.npm-cache')

/**
 * 将 Windows 返回的无符号进程退出码还原为有符号值，便于准确报告子进程失败。
 */
function signedExitCode(status) {
  // 将 Windows 原生进程以无符号数返回的负退出码恢复为有符号值。
  return status !== null && status > 0x7fffffff ? status - 0x100000000 : status
}

/**
 * 在指定目录同步执行构建命令，透传输出并使用仓库 npm 缓存；启动失败或非零退出时中止后续流程。
 */
function run(command, args, cwd) {
  console.log(`> ${command} ${args.join(' ')}`)
  const result = spawnSync(command, args, {
    cwd,
    stdio: 'inherit',
    windowsHide: true,
    shell: process.platform === 'win32',
    env: {
      ...process.env,
      // 使用仓库本地 npm 缓存，避免 CI 或 Windows 镜像中的全局缓存只读导致构建失败。
      npm_config_cache: NPM_CACHE
    }
  })
  if (result.error) {
    throw new Error(`${command} failed to start: ${result.error.message}`)
  }
  const status = signedExitCode(result.status)
  if (status !== 0) {
    throw new Error(`${command} exited with code ${status}`)
  }
}

// 前端依赖安装、测试、独立静态构建和制品校验，不触发后端构建。
/**
 * 依次安装锁定依赖、运行单元测试、生成独立前端制品并验证制品，输出完成标记和内容摘要。
 */
export function main() {
  run(NPM_COMMAND, ['ci'], WEB_ROOT)
  // 使用锁文件安装同一依赖树，先验证，再生成可独立部署的前端资源。
  run(NPM_COMMAND, ['run', 'test:unit'], WEB_ROOT)
  console.log('FLOWDESK_WEB_UNIT_OK')
  run(NPM_COMMAND, ['run', 'build'], WEB_ROOT)
  run(NPM_COMMAND, ['run', 'test:build'], WEB_ROOT)
  const result = validateWebArtifact()
  console.log(`FLOWDESK_WEB_BUILD_OK sha256=${result.bundleSha256} js=${result.javascriptAssets.length} css=${result.stylesheetAssets.length}`)
  return 0
}

if (resolve(process.argv[1] || '') === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = main()
  } catch (error) {
    console.error(`FLOWDESK_WEB_BUILD_INVALID: ${error instanceof Error ? error.message : String(error)}`)
    process.exitCode = 1
  }
}
