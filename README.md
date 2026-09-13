# FlowDesk

FlowDesk 是一个面向多租户团队的服务台系统。它把身份与权限、工单、审批工作流、告警、通知和统一入口拆分为独立服务，并提供一个 Vue 3 单页前端。服务之间通过 HTTP 和 RocketMQ 协作，数据使用 MySQL，缓存与部分时效索引使用 Redis，服务发现使用 Nacos。

这个仓库只包含可构建、可测试和可运行的项目文件。运行时密钥、数据库数据、浏览器缓存、截图、验收记录和本地开发过程材料不在仓库中。

## 能力范围

- Gateway：统一入口、静态前端交付、JWT 校验和服务路由。
- IAM：登录、租户隔离、用户、角色、权限和服务间 JWT。
- Ticket：目录、工单、审批关联、SLA 以及可靠投递。
- Workflow：流程定义、实例运行、审批和恢复处理。
- Alert：Webhook 告警接收、去重、事件关联和恢复处理。
- Notification：通知记录、消费、恢复和查询。
- Web：Vue 3 + TypeScript 单页应用，浏览器业务请求统一走 `/api/v1`。

## 环境要求

- Java 17
- 仓库内的 Maven Wrapper（当前为 3.9.16）；自行安装时要求 `>=3.9.9` 且 `<4.0.0`
- Node.js 22.22.2 或更高的 22.x LTS 版本，推荐 npm 11
- Docker Desktop（使用 Compose 运行完整本地环境时）
- OpenSSL（生成本地测试密钥时）

下文使用 PowerShell 7 命令。在 Linux/macOS 中，Maven 命令可改为 `sh mvnw`；环境变量设置方式需要相应调整。

## 获取代码后先做的准备

复制环境模板并为本机生成全新的值。不要把真实 `.env`、私钥或证书提交到 Git：

```powershell
Copy-Item .env.example .env
```

数据库密码必须为 16–128 位的字母、数字、下划线或连字符；RocketMQ secret 使用至少 16 位字母或数字。各 secret 应独立随机生成。Nacos token 和游标 HMAC key 使用至少 32 个随机字节的 Base64 值，例如 `openssl rand -base64 32`。`FLOWDESK_NACOS_PASSWORD` 与 `FLOWDESK_NACOS_ADMIN_PASSWORD` 使用相同的新密码。

Compose 需要 JWT 密钥和 Gateway 的 PKCS#12 证书。下面的命令会在本地 `.data/keys` 中生成它们；`.data/` 已被忽略：

```powershell
New-Item -ItemType Directory -Force .data/keys | Out-Null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .data/keys/flowdesk-jwt-private.pem
openssl pkey -in .data/keys/flowdesk-jwt-private.pem -pubout -out .data/keys/flowdesk-jwt-public.pem
openssl req -x509 -newkey rsa:2048 -nodes -days 365 `
  -keyout .data/keys/gateway.key -out .data/keys/gateway.crt `
  -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
openssl pkcs12 -export -out .data/keys/gateway-local.p12 `
  -inkey .data/keys/gateway.key -in .data/keys/gateway.crt
```

最后一条命令会提示输入证书导出密码。将 `.env` 中对应变量改为以下配置，证书密码填入刚才设置的值，并替换模板中的全部凭据占位符：

```dotenv
FLOWDESK_IAM_JWT_PRIVATE_KEY_FILE=.data/keys/flowdesk-jwt-private.pem
FLOWDESK_JWT_PUBLIC_KEY_FILE=.data/keys/flowdesk-jwt-public.pem
FLOWDESK_GATEWAY_TLS_KEY_STORE_FILE=.data/keys/gateway-local.p12
FLOWDESK_GATEWAY_TLS_KEY_STORE=/run/secrets/flowdesk-gateway-tls.p12
FLOWDESK_GATEWAY_TLS_KEY_STORE_PASSWORD=<刚才设置的证书导出密码>
FLOWDESK_GATEWAY_TLS_ENABLED=true
FLOWDESK_ROCKETMQ_BROKER_ADVERTISED_HOST=rocketmq-broker
FLOWDESK_ROCKETMQ_VERIFY_BROKER_HOST=rocketmq-broker
FLOWDESK_ROCKETMQ_ADMIN_BROKER_HOST=rocketmq-broker
FLOWDESK_ROCKETMQ_ACL_ENABLED=true
FLOWDESK_ROCKETMQ_M7_ENABLED=true
FLOWDESK_ROCKETMQ_M8_ENABLED=true
FLOWDESK_ALERT_WEBHOOK_TIMESTAMP_WINDOW=PT5M
FLOWDESK_ALERT_WEBHOOK_SECRETS_FILE=.data/keys/webhook-secrets.json
```

Alert 的容器配置还需要 Webhook 密钥注册表。以下命令生成一个可启动的空集成配置：

```powershell
$webhookRegistry = @{
  dummySecret = (openssl rand -base64 32).Trim()
  integrations = @{}
} | ConvertTo-Json
Set-Content -LiteralPath .data/keys/webhook-secrets.json -Value $webhookRegistry -Encoding utf8
```

空的 `integrations` 不接受有效的监控告警。接入监控时，需要在告警数据库中配置集成身份和 API key 的散列，并在此对象中按集成 UUID 设置对应的 Base64 HMAC secret；每个 secret 至少包含 32 个随机字节。上述配置不复用开发环境的凭据。

## 使用 Docker Compose 运行

容器拓扑包含 MySQL、Redis、Nacos、RocketMQ，以及含 Gateway 在内的六个 Spring Boot 服务。先初始化数据库运行权限、Nacos 账号和消息主题/ACL，再启动 Gateway 及其业务服务依赖。按顺序执行，并确认每个初始化命令成功退出：

```powershell
docker compose -p flowdesk-public run --rm nacos-initialize
docker compose -p flowdesk-public run --rm database-permissions
docker compose -p flowdesk-public run --rm rocketmq-acl-provision
docker compose -p flowdesk-public --profile m10 up -d --build gateway
docker compose -p flowdesk-public --profile m10 ps
```

`m10` 是现有 Compose 中应用服务的 profile 名。这里显式选择服务，避免启动依赖外部验收文件的辅助容器。首次构建会将前端静态资源打进 Gateway 镜像；业务服务依赖的 Flyway 迁移和演示数据会随依赖链执行。演示账号及租户仅用于本机体验。

Gateway 健康后通过 `https://localhost:8080/` 访问。服务端口使用本机回环绑定；自签名证书需要在本机建立信任。端口占用时请先调整 `.env` 的发布端口。停止并删除这个独立 Compose 项目的容器：

```powershell
docker compose -p flowdesk-public --profile m10 down
```

加上 `-v` 会同时删除该 Compose 项目的数据卷，包括本地数据库和消息系统数据。

## 构建与测试

后端所有模块：

```powershell
.\mvnw.cmd -B clean verify
```

只构建 Gateway 和它依赖的公共模块：

```powershell
.\mvnw.cmd -B -pl flowdesk-gateway -am package
```

前端开发和测试：

```powershell
Set-Location flowdesk-web
npm ci --cache .npm-cache
npm run test:unit
npm run build
npm run dev
```

开发服务器默认在 `http://127.0.0.1:5173`，并把 `/api/v1` 代理到 `http://127.0.0.1:8080`。连接上面的 HTTPS Compose 环境时，在 `flowdesk-web/.env.local` 中设置 `VITE_GATEWAY_DEV_URL=https://localhost:8080`，然后重启 Vite。

只体验前端时，可以在启动 Vite 前设置 `$env:VITE_E2E_BACKEND='mock'`，并将 `VITE_NOTIFICATION_MODE`、`VITE_RECOVERY_MODE`、`VITE_SLA_MODE` 都设为 `mock`。此模式使用浏览器内存数据，刷新后重置；登录可填写组织代码 `SHANCHENG_TECH`、用户名 `requester` 和任意非空测试密码。Gateway 交付构建强制使用真实 API。

浏览器自动化命令是 `npm run test:e2e`，默认运行前端 mock 场景。当前 Playwright 配置使用本机已安装的 Chrome；默认路径为 `C:\Program Files\Google\Chrome\Application\chrome.exe`，可通过 `FLOWDESK_CHROME_PATH` 指定其他路径。它不会自动安装浏览器。报告和截图均为本地生成文件，不应提交。

构建可交付的 Gateway 静态资源和 JAR：

```powershell
node scripts/build-gateway.mjs
```

该命令会执行锁文件安装、前端单元测试、类型检查、前端生产构建、Gateway 打包和制品检查。生成的 `target/` 与 Gateway 静态目录均属于构建产物，已被忽略。

## 仓库结构

```text
flowdesk-common/                 共享安全、Web 和基础设施代码
flowdesk-gateway/                统一入口与静态资源交付
flowdesk-iam-service/            身份、租户、用户、角色和权限
flowdesk-ticket-service/         目录、工单和 SLA
flowdesk-workflow-service/       流程与审批运行时
flowdesk-alert-service/          告警接收、关联和恢复
flowdesk-notification-service/   通知与恢复
flowdesk-web/                    Vue 3 前端
infra/                           Compose 依赖的初始化和校验脚本
contract-fixtures/               消息契约测试夹具
scripts/                         可重复的 Gateway 构建与制品检查
```

数据库迁移和演示数据位于各服务的 `src/main/resources/db/`。测试代码位于各模块的 `src/test/`，其中的 JWT 公钥仅用于测试。

## 配置与安全边界

服务配置通过环境变量覆盖，完整变量名和默认端口见 `.env.example`。本地 Compose 默认使用 8080–8085 的服务端口、3306 的 MySQL、6379 的 Redis、8848/9848 的 Nacos，以及 9876/10909/10911/10912 的 RocketMQ 端口。

请为每个环境独立生成数据库密码、Nacos token、JWT 密钥、Webhook secret、RocketMQ 凭据和 TLS 证书。不要把它们写入源代码、前端环境变量、日志、截图或 issue。生产部署还需要自行配置密钥轮换、备份、访问控制、监控和高可用策略；仓库中的 Compose 文件用于本地开发和集成测试。

## 许可证

当前仓库未附带 LICENSE，公开可见不代表已授予额外的使用、修改或再分发许可。
