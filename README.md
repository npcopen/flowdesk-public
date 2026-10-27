# FlowDesk

FlowDesk 是一个面向多租户团队的服务台系统。它把身份与权限、工单、审批工作流、告警、通知和统一入口拆分为独立服务，并提供一个 Vue 3 单页前端。服务之间通过 HTTP 和 RocketMQ 协作，数据使用 MySQL，缓存与部分时效索引使用 Redis，服务发现和 dev/test/prod 配置管理使用 Nacos。工单与流程服务的同步 HTTP 客户端统一使用注解式 OpenFeign；网关路由与 RocketMQ 异步消息继续由各自组件负责。

这个仓库只包含可构建、可测试和可运行的项目文件。运行时密钥、数据库数据、浏览器缓存、截图、验收记录和本地开发过程材料不在仓库中。

## 能力范围

- Gateway：API 统一入口、JWT 校验和服务路由。
- IAM：登录、租户隔离、用户、角色、权限和服务间 JWT。
- Ticket：目录、工单、审批关联、SLA 以及可靠投递。
- Workflow：流程定义、实例运行、审批和恢复处理。
- Alert：Webhook 告警接收、去重、事件关联和恢复处理。
- Notification：通知记录、消费、恢复和查询。
- Web：Vue 3 + TypeScript 单页应用，由独立 Nginx 容器交付，浏览器业务请求统一走同源 `/api/v1`。

前后端保留在同一仓库，分别构建和发布镜像：Nginx 返回前端页面，并将 `/api/` 代理到 Gateway；Gateway 只负责 API。开发时仍使用 Vite 与后端分别启动。部署和独立发布步骤见[前后端独立部署](docs/operations/frontend-deployment.md)。

工单分派、转派页面通过 [`GET /api/v1/users/ticket-assignee-candidates`](docs/api/ticket-assignee-candidates.md) 选择处理人。该接口仅返回同租户有效 Agent 的最小身份信息，普通处理人无需获得用户管理权限即可转派自己的工单；提交时工单服务仍会再次检查操作权限和目标资格。

## 环境要求

后端采用 controller → service 接口 → service.impl → MyBatis mapper 的三层结构，HTTP 契约独立放在 dto，不保留 Repository 中间层；所有生产业务 SQL（包含消息、SLA 和恢复）通过具名 Mapper 执行，职责和事务约束见[后端分层约定](docs/architecture/backend-layers.md)。可运行 `python scripts/verify-backend-layers.py` 检查分层边界。

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

数据库密码必须为 16–128 位的字母、数字、下划线或连字符；RocketMQ secret 使用至少 16 位字母或数字。各 secret 应独立随机生成。Nacos token 和游标 HMAC key 使用至少 32 个随机字节的 Base64 值，例如 `openssl rand -base64 32`。`FLOWDESK_NACOS_PASSWORD` 使用独立应用账号密码，不得与 `FLOWDESK_NACOS_ADMIN_PASSWORD` 相同；应用账号由配置初始化脚本授予对应环境权限。

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
FLOWDESK_WEB_PORT=8086
FLOWDESK_GATEWAY_TLS_CA_FILE=.data/keys/gateway.crt
FLOWDESK_GATEWAY_TLS_SERVER_NAME=localhost
FLOWDESK_ROCKETMQ_BROKER_ADVERTISED_HOST=rocketmq-broker
FLOWDESK_ROCKETMQ_VERIFY_BROKER_HOST=rocketmq-broker
FLOWDESK_ROCKETMQ_ADMIN_BROKER_HOST=rocketmq-broker
FLOWDESK_ROCKETMQ_ACL_ENABLED=true
FLOWDESK_ROCKETMQ_M7_ENABLED=true
FLOWDESK_ROCKETMQ_M8_ENABLED=true
FLOWDESK_ALERT_WEBHOOK_TIMESTAMP_WINDOW=PT5M
FLOWDESK_ALERT_WEBHOOK_SECRETS_FILE=.data/keys/webhook-secrets.json
```

Web/Nginx 会验证 Gateway 的 TLS 证书。上述 `FLOWDESK_GATEWAY_TLS_CA_FILE` 使用刚生成的公开证书，`FLOWDESK_GATEWAY_TLS_SERVER_NAME` 必须匹配该证书的 SAN；示例证书包含 `localhost`。更换证书后需同步更新信任链和名称，不能关闭证书校验。

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

容器拓扑包含 MySQL、Redis、Nacos、RocketMQ、含 Gateway 在内的六个 Spring Boot 服务，以及独立的 Web/Nginx 服务。先初始化数据库运行权限、Nacos 账号和消息主题/ACL，再启动 Web、Gateway 及其业务服务依赖。按顺序执行，并确认每个初始化命令成功退出：

```powershell
docker compose -p flowdesk-public run --rm nacos-initialize
docker compose -p flowdesk-public --profile m10 run --rm nacos-config-initialize
docker compose -p flowdesk-public run --rm database-permissions
docker compose -p flowdesk-public up -d rocketmq-topology-provision
docker compose -p flowdesk-public wait rocketmq-topology-provision
docker compose -p flowdesk-public run --rm rocketmq-acl-provision --provision-only
docker compose -p flowdesk-public --profile m10 up -d --build web
docker compose -p flowdesk-public --profile m10 ps
```

Compose 默认项目名为 `flowdesk-public`，与上述命令保持一致。`m10` 是应用服务的 profile 名；历史里程碑、验证和监控凭据辅助任务归入独立的 `acceptance` profile，日常运行不启用。需要单独验收时，显式指定目标服务，例如 `docker compose -p flowdesk-public --profile acceptance run --rm redis-verify`；部分历史任务仍依赖外部验收文件。前端镜像只包含生产构建的 `dist/` 和 Nginx 配置；后端镜像分别构建，不再包含前端页面。业务服务依赖的 Flyway 迁移和演示数据会随依赖链执行。演示账号及租户仅用于本机体验。

`rocketmq-topology-provision` 创建并读回业务 Topic、消费组及重试/死信 Topic，不发送消息；四个消息应用会等待它成功退出。ACL 初始化的 `--provision-only` 仅写入并读回用户和精确权限，跳过禁止发布探针；省略此参数会保留原有探针行为。ACL 初始化仍需显式执行，避免每次启动应用时重复重置权限。`rocketmq-verify` 保留独立的消息收发验收流程，不属于上述启动步骤。

Web 和 Gateway 健康后，通过 `http://localhost:8086/` 访问页面；可用 `FLOWDESK_WEB_PORT` 调整 Web 发布端口。浏览器的 `/api/v1` 请求由 Nginx 转发到 Gateway，因此无需配置跨域地址。`https://localhost:8080` 保留为 Gateway 的独立 API/TLS 入口，不再返回前端页面；外部 Webhook 继续使用 HTTPS Gateway 入口。服务端口使用本机回环绑定，自签名证书需要在直接访问 Gateway 时建立本机信任。这个 HTTP Web 入口仅供本机 dev/test，生产需要在统一入口终止 TLS。端口占用时请先调整 `.env` 的发布端口。停止并删除这个独立 Compose 项目的容器：

```powershell
docker compose -p flowdesk-public --profile m10 down
```

加上 `-v` 会同时删除该 Compose 项目的数据卷，包括本地数据库和消息系统数据。

## 构建与测试

人工消息重放的重启恢复机制、配置和新增数据库迁移顺序见 [人工消息重放的中断恢复](docs/operations/inbox-replay-recovery.md)。

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
npm run typecheck
npm run test:unit
npm run build
npm run dev
```

开发服务器默认在 `http://127.0.0.1:5173`，并把 `/api/v1` 代理到 `http://127.0.0.1:8080`。连接上面的 HTTPS Compose 环境时，在 `flowdesk-web/.env.local` 中设置 `VITE_GATEWAY_DEV_URL=https://localhost:8080`，然后重启 Vite。

`npm run typecheck` 显式检查应用与构建配置两个 TypeScript 项目；`npm run build` 也会先执行同样的检查。

只体验前端时，可以在启动 Vite 前设置 `$env:VITE_E2E_BACKEND='mock'`，并将 `VITE_NOTIFICATION_MODE`、`VITE_RECOVERY_MODE`、`VITE_SLA_MODE` 都设为 `mock`。此模式使用浏览器内存数据，刷新后重置；登录可填写组织代码 `SHANCHENG_TECH`、用户名 `requester` 和任意非空测试密码。前端生产构建强制使用真实 API，不携带开发 mock 功能。

浏览器自动化命令是 `npm run test:e2e`，默认运行前端 mock 场景。当前 Playwright 配置使用本机已安装的 Chrome；默认路径为 `C:\Program Files\Google\Chrome\Application\chrome.exe`，可通过 `FLOWDESK_CHROME_PATH` 指定其他路径。它不会自动安装浏览器。报告和截图均为本地生成文件，不应提交。

验证并构建可独立交付的前端（在仓库根目录执行）：

```powershell
node scripts/build-web.mjs
```

该命令会执行锁文件安装、前端单元测试、类型检查、生产构建和前端制品检查，不运行 Maven。也可在 `flowdesk-web/` 中执行 `npm run build:verified`。输出为 `flowdesk-web/dist/`；后端使用前述 Maven 命令单独打包。生成的 `dist/` 和 `target/` 均属于构建产物，已被忽略。

## 仓库结构

```text
flowdesk-common/                 共享安全、Web 和基础设施代码
flowdesk-gateway/                API 统一入口与服务路由
flowdesk-iam-service/            身份、租户、用户、角色和权限
flowdesk-ticket-service/         目录、工单和 SLA
flowdesk-workflow-service/       流程与审批运行时
flowdesk-alert-service/          告警接收、关联和恢复
flowdesk-notification-service/   通知与恢复
flowdesk-web/                    Vue 3 前端、独立镜像与 Nginx 配置
infra/                           Compose 依赖的初始化和校验脚本
contract-fixtures/               消息契约测试夹具
scripts/                         前端构建、制品检查和配置校验
```

数据库迁移和演示数据位于各服务的 `src/main/resources/db/`。测试代码位于各模块的 `src/test/`，其中的 JWT 公钥仅用于测试。

业务入口、能力包和事务边界见 [工单](docs/architecture/ticket-code-map.md)、[通知](docs/architecture/notification-code-map.md)、[流程](docs/architecture/workflow-code-map.md)、[告警](docs/architecture/alert-code-map.md) 和 [IAM](docs/architecture/iam-code-map.md) 的代码阅读地图。

前端工单详情的页面状态、操作和面板划分见 [工单详情代码阅读地图](docs/architecture/web-ticket-detail-code-map.md)。

## 配置与安全边界

同步服务调用通过 `@FeignClient` 声明，并由调用方显式注册客户端。以下 URL 留空时，配合 Nacos 和 LoadBalancer 按注册服务名寻址；填写 URL 时使用直连。

| 调用链路 | 注册服务名 | URL 环境变量 | 超时环境变量前缀 |
| --- | --- | --- | --- |
| 工单 → IAM：处理人及 SLA 目标资格 | `flowdesk-iam-service` | `FLOWDESK_TICKET_IAM_BASE_URL` | `FLOWDESK_TICKET_IAM` |
| 工单 → 流程：目录版本、审批任务与实例核验 | `flowdesk-workflow-service` | `FLOWDESK_TICKET_WORKFLOW_BASE_URL` | `FLOWDESK_TICKET_WORKFLOW` |
| 流程 → IAM：审批人资格 | `flowdesk-iam-service` | `FLOWDESK_WORKFLOW_IAM_BASE_URL` | `FLOWDESK_WORKFLOW_IAM` |

每个前缀的 `_CONNECT_TIMEOUT` 默认 `PT2S`，`_READ_TIMEOUT` 默认 `PT3S`。本地直连 IAM 可用 `http://127.0.0.1:8081`，流程服务可用 `http://127.0.0.1:8083`；Compose 容器内应使用 `http://iam:8081` 或 `http://workflow:8083`。Feign 和 LoadBalancer 自动重试均关闭，也不跟随重定向、不输出 Feign 请求头或正文。业务适配层保留租户核验、响应字段检查及原有 4xx/503 语义；IAM 服务令牌逐请求读取当前租户文件，流程查询透传当前用户身份。IAM、告警、通知和网关目前没有需要新增的同步业务客户端，因此不额外引入 OpenFeign。Nacos Config 已按 Spring Cloud Alibaba 2025.1 的 `spring.config.import` 接入，采用 `dev/test/prod` Profile、固定环境 Namespace 和 `FLOWDESK_GROUP`；公共配置后加载服务配置，首期关闭热刷新。完整部署、发布、回滚和隔离约定见[三环境配置管理](docs/operations/nacos-configuration.md)。

非敏感运行参数由 `infra/nacos/{dev,test,prod}/*.yaml` 管理；Secret、部署入口和连接地址覆盖仍通过环境变量或挂载文件提供，完整变量名和默认端口见 `.env.example`。本地 Compose 默认使用 8086 的 Web 端口、8080–8085 的服务端口、3306 的 MySQL、6379 的 Redis、8848/9848 的 Nacos，以及 9876/10909/10911/10912 的 RocketMQ 端口。

请为每个环境独立生成数据库密码、Nacos token、JWT 密钥、Webhook secret、RocketMQ 凭据和 TLS 证书。不要把它们写入源代码、前端环境变量、日志、截图或 issue。生产部署还需要自行配置密钥轮换、备份、访问控制、监控和高可用策略；仓库中的 Compose 文件用于本地开发和集成测试。

## 许可证

当前仓库未附带 LICENSE，公开可见不代表已授予额外的使用、修改或再分发许可。
