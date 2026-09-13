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
- Maven 3.9.9 或更高版本（推荐使用仓库内的 Maven Wrapper）
- Node.js 22 或更高版本，以及 npm 11 或更高版本
- Docker Desktop（使用 Compose 运行完整本地环境时）
- OpenSSL（生成本地测试密钥时）

## 获取代码后先做的准备

复制环境模板并为本机生成全新的值。不要把真实 `.env`、私钥或证书提交到 Git：

```powershell
Copy-Item .env.example .env
```

Compose 需要 JWT 密钥和 Gateway 的 PKCS#12 证书。下面的命令会在本地 `.data/keys` 中生成它们；`.data/` 已被忽略：

```powershell
New-Item -ItemType Directory -Force .data/keys | Out-Null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .data/keys/flowdesk-jwt-private.pem
openssl pkey -in .data/keys/flowdesk-jwt-private.pem -pubout -out .data/keys/flowdesk-jwt-public.pem
openssl req -x509 -newkey rsa:2048 -nodes -days 365 `
  -keyout .data/keys/gateway.key -out .data/keys/gateway.crt `
  -subj "/CN=localhost"
openssl pkcs12 -export -out .data/keys/gateway-local.p12 `
  -inkey .data/keys/gateway.key -in .data/keys/gateway.crt `
  -passout pass:change-this-keystore-password
```

在 `.env` 中把以下变量改为实际路径和同一个证书密码，并替换所有密码、token 和 RocketMQ secret：

```dotenv
FLOWDESK_IAM_JWT_PRIVATE_KEY_FILE=.data/keys/flowdesk-jwt-private.pem
FLOWDESK_JWT_PUBLIC_KEY_FILE=.data/keys/flowdesk-jwt-public.pem
FLOWDESK_GATEWAY_TLS_KEY_STORE_FILE=.data/keys/gateway-local.p12
FLOWDESK_GATEWAY_TLS_KEY_STORE_PASSWORD=change-this-keystore-password
```

## 使用 Docker Compose 运行

完整环境包含 MySQL、Redis、Nacos、RocketMQ、六个 Spring Boot 服务和 Gateway。第一次启动会构建前端并把静态资源打进 Gateway 镜像：

```powershell
docker compose --profile m10 up --build
```

Gateway 只绑定到本机回环地址，启动后通过 `https://127.0.0.1:8080/` 访问。上面的证书是本地自签名证书，浏览器会显示证书警告。停止并删除容器：

```powershell
docker compose --profile m10 down
```

如需同时删除 Compose 创建的数据卷，请明确执行 `docker compose --profile m10 down -v`。这会删除本地数据库和消息系统数据。

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
npm ci
npm run test:unit
npm run build
npm run dev
```

开发服务器默认在 `http://127.0.0.1:5173`，并把 `/api/v1` 代理到 `http://127.0.0.1:8080`。可以通过 `VITE_GATEWAY_DEV_URL` 修改代理目标。需要浏览器自动化时再执行 `npm run test:e2e`；Playwright 浏览器会安装到本机缓存，不应提交。

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

当前仓库未附带许可证文件。若要向公众授予使用、修改和再分发权限，请在发布前补充与项目所有者意图一致的 LICENSE。
