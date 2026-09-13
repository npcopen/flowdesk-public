#!/usr/bin/env node

import { spawnSync } from 'node:child_process'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { findGatewayJar, REPOSITORY_ROOT, validateGatewayArtifact } from './verify-gateway-artifact.mjs'

const WEB_ROOT = resolve(REPOSITORY_ROOT, 'flowdesk-web')
const NPM_COMMAND = process.platform === 'win32' ? 'npm.cmd' : 'npm'
const NPM_CACHE = resolve(REPOSITORY_ROOT, '.npm-cache')

function signedExitCode(status) {
  // Windows reports native negative exit codes as unsigned 32-bit values.
  return status !== null && status > 0x7fffffff ? status - 0x100000000 : status
}

function run(command, args, cwd) {
  console.log(`> ${command} ${args.join(' ')}`)
  const result = spawnSync(command, args, {
    cwd,
    stdio: 'inherit',
    windowsHide: true,
    shell: process.platform === 'win32',
    env: {
      ...process.env,
      // A repository-local cache keeps a clean build independent from a
      // machine-wide npm cache that may be read-only on CI/Windows images.
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

function runMaven(args) {
  if (process.platform === 'win32') {
    run('mvnw.cmd', args, REPOSITORY_ROOT)
  } else {
    // The wrapper is tracked as a portable shell script without an executable
    // bit, so invoke it through sh on Unix checkouts.
    run('sh', ['mvnw', ...args], REPOSITORY_ROOT)
  }
}

export function main() {
  run(NPM_COMMAND, ['ci'], WEB_ROOT)
  // Test and type-check the same lockfile-installed dependency tree before
  // producing the deployable gateway bundle.
  run(NPM_COMMAND, ['run', 'test:unit'], WEB_ROOT)
  console.log('FLOWDESK_WEB_UNIT_OK')
  run(NPM_COMMAND, ['run', 'build'], WEB_ROOT)
  run(NPM_COMMAND, ['run', 'build:gateway'], WEB_ROOT)
  runMaven(['-B', '-pl', 'flowdesk-gateway', '-am', 'clean', 'package'])
  const result = validateGatewayArtifact(findGatewayJar())
  console.log(`FLOWDESK_GATEWAY_BUILD_OK sha256=${result.jarSha256} js=${result.javascriptAssets.length} css=${result.stylesheetAssets.length}`)
  return 0
}

if (resolve(process.argv[1] || '') === fileURLToPath(import.meta.url)) {
  try {
    process.exitCode = main()
  } catch (error) {
    console.error(`FLOWDESK_GATEWAY_BUILD_INVALID: ${error instanceof Error ? error.message : String(error)}`)
    process.exitCode = 1
  }
}
