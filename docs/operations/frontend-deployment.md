# 前后端独立部署

FlowDesk 在同一仓库中维护前后端源码，分别构建和发布镜像。Web 镜像运行 Nginx，只包含 `flowdesk-web/dist/` 的 HTML、JavaScript 和 CSS；Gateway 镜像只包含后端 API，不再携带或返回前端页面。

```text
浏览器 → Web / Nginx
          ├─ 页面、静态资源 → dist/
          └─ /api/         → HTTPS Gateway → 业务服务
监控 Webhook             → HTTPS Gateway → Alert
```

## 本机 dev/test

先完成 README 中的凭据、证书、Nacos、数据库与 RocketMQ 初始化，再从仓库根目录执行：

```powershell
docker compose -p flowdesk-public --profile m10 up -d --build web
docker compose -p flowdesk-public --profile m10 ps
```

默认页面入口是 `http://localhost:8086/`，由 `FLOWDESK_WEB_PORT` 配置。Web 等待 Gateway 健康后启动，浏览器请求同源 `/api/v1`，Nginx 转发时保留请求路径和查询参数。Nginx 对页面路由提供 `index.html` 回退；不存在的 `/assets/` 文件和 API 请求不会被替换成 HTML。入口页不缓存，带哈希的静态资源使用长期缓存。`/healthz` 仅验证 Web 自身存活，API 是否可用需另外检查实际 API 和 Gateway readiness。

Nginx 通过 Compose 服务名 `gateway` 和 `FLOWDESK_GATEWAY_PORT` 连接 HTTPS Gateway，校验上游证书：

```dotenv
FLOWDESK_WEB_PORT=8086
FLOWDESK_GATEWAY_TLS_CA_FILE=.data/keys/gateway.crt
FLOWDESK_GATEWAY_TLS_SERVER_NAME=localhost
```

CA 文件必须是签发 Gateway 证书的可信 CA 链，或本机自签名的公开证书；它不应包含私钥。`FLOWDESK_GATEWAY_TLS_SERVER_NAME` 必须与 Gateway 证书 SAN 匹配，README 的本机证书使用 `localhost`。Web 不挂载 Gateway 私钥或 PKCS#12 文件。

Gateway 的 `https://localhost:8080` 保留为独立 API/TLS 入口。Web 的 HTTP 入口拒绝 `/api/v1/webhooks` 及其子路径，Webhook 发送方必须直接使用 Gateway 的 HTTPS 地址，保留现有 TLS 校验约束。此 HTTP Web 入口只用于回环地址上的本机开发和集成测试；生产需使用配置了 HTTPS 的统一入口。

test 使用独立 `.env.test` 和 Compose 项目。例如合并 `config/environments/test.env.example` 中的端口选择后：

```powershell
docker compose --env-file .env.test -p flowdesk-test --profile m10 up -d --build web
```

该模板的页面入口为 `http://localhost:18086/`，Gateway TLS 入口为 `https://localhost:18080`。凭据、TLS/JWT/Webhook 文件和数据库/消息数据卷均须与 dev 独立，不能仅更换端口后复用 dev Secret。

## 从原合包方式迁移

已有环境升级时，先按[三环境配置管理](nacos-configuration.md)核对并发布当前 Gateway 配置，再重建 Gateway 和 Web。旧 `flowdesk-gateway/src/main/resources/static/` 是原构建生成的页面目录，新构建只输出到 `flowdesk-web/dist/`；网关不再依赖前端资源进行 readiness 检查。浏览器书签和部署浏览器测试应改用 Web 入口，而不是 Gateway 的根路径。

## 分别构建和发布

从仓库根目录验证并构建前端：

```powershell
node scripts/build-web.mjs
```

也可在 `flowdesk-web/` 中执行 `npm run build:verified`。前端安装锁定依赖、运行单元测试与类型检查、构建并校验 `dist/`，不调用 Maven。普通 `npm run build` 同样输出 `dist/`，生产构建强制真实 API；开发用 mock 只在 Vite 开发和测试场景启用。

后端独立构建，例如仅验证 Gateway 及其公共依赖：

```powershell
.\mvnw.cmd -B -pl flowdesk-gateway -am clean verify
```

更新本机前端时，只重建和替换 Web，不重启后端：

```powershell
docker compose -p flowdesk-public --profile m10 build web
docker compose -p flowdesk-public --profile m10 up -d --no-deps web
```

更新 Gateway 时，只重建和替换 Gateway；Web 中的 Nginx 会重新解析 Compose 服务名：

```powershell
docker compose -p flowdesk-public --profile m10 build gateway
docker compose -p flowdesk-public --profile m10 up -d --no-deps gateway
```

上述命令假设完整环境已经初始化并运行，`--no-deps` 不适用于首次启动。重建镜像前应运行对应测试；Gateway 配置变更还需按[三环境配置管理](nacos-configuration.md)发布 Nacos 配置，不能仅重建镜像。

## 生产发布边界

生产分别发布 Web 镜像和各后端镜像，并为发布版本设置不可变镜像标签，以便独立回滚。Web 不保存环境密钥，也不读取 Nacos；浏览器始终请求相对路径 `/api/v1`。入口反向代理将页面与 API 暴露在同一 HTTPS 协议、域名和端口下，Gateway 和业务服务使用部署平台提供的内部地址。

生产入口需要配置可信 TLS 证书、上游证书链和与 SAN 匹配的服务端名称，Webhook 应保持专用 HTTPS 入口。仓库中的 Nginx 模板按本机 Compose DNS 编写，生产需按部署平台的内部 DNS 和 TLS 入口配置调整；不能把本机 HTTP 入口直接作为生产入口。仓库 Compose 还包含单机中间件和演示 seed，仅用于 dev/test，不承担生产编排。

前后端独立发布仍需保持 API 契约兼容；接口破坏性变更应按兼容窗口协调发布。回滚 Web 不应回滚数据库迁移，数据库变更按已有迁移流程单独管理。
