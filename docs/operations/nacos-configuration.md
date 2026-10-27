# dev / test / prod 配置管理

FlowDesk 使用 Spring Cloud Alibaba 2025.1 的 `application.yml` + `spring.config.import` 接入 Nacos Config。不要新增 `bootstrap.yml`、`shared-configs` 或 `extension-configs`。六个后端服务分别构建，各服务在三种环境中复用自身构建产物，部署时选择一个环境；Web 通过独立镜像交付。

## 环境和配置清单

| Spring Profile | Namespace ID | Group | 配置源 |
| --- | --- | --- | --- |
| `dev` | `flowdesk-dev` | `FLOWDESK_GROUP` | `infra/nacos/dev/*.yaml` |
| `test` | `flowdesk-test` | `FLOWDESK_GROUP` | `infra/nacos/test/*.yaml` |
| `prod` | `flowdesk-prod` | `FLOWDESK_GROUP` | `infra/nacos/prod/*.yaml` |

Namespace ID 是固定标识，不是控制台显示名。每个环境均有七份 YAML：`flowdesk-common.yaml` 和以六个 `spring.application.name` 命名的服务配置。配置中心和服务发现使用相同 Namespace 和 Group；不要同时激活多个环境 Profile。`m10` 仅为历史 Compose 应用分组，不表示 Spring 环境。

各服务的 `application-{env}.yml` 按顺序显式导入公共配置和服务配置，后者覆盖前者。两份配置均为必需，`refreshEnabled=false` 和 `spring.cloud.nacos.config.refresh-enabled=false` 禁用热刷新。公共文件包含环境标识，服务文件包含服务名标识；应用启动校验配置源及标识，阻止缺失配置或环境不匹配时继续启动。配置变更发布后需滚动重启。

不激活这三个 Profile 时，基础配置关闭 Config 客户端，用于现有单元测试和显式本地调试；不会自动把 Maven 测试接到远程 `test` 环境。

## 配置归属

- Nacos：非敏感运行参数、健康依赖、客户端超时、扫描间隔和批量、应用启动开关、服务发现路由。
- 部署入口：Spring Profile、Nacos 地址和应用凭据、密钥与服务令牌挂载路径、端口与 TLS、基础设施连接地址覆盖。
- Secret 或受控环境变量：数据库密码、Redis 密码、MQ 凭据、JWT 私钥、游标 HMAC、Webhook 密钥、TLS 证书密码。
- 业务数据库：租户 SLA、审批流程、权限规则、目录和集成身份等已有业务数据。

远程 YAML 使用 `spring.*` / `flowdesk.*` 层级属性，不上传 `.env` 内容。DB/MQ/Redis 的非生产默认地址为容器 DNS；宿主 JVM 调试必须提供对应 `FLOWDESK_*` 地址。prod 的连接地址无开发回退，部署时必须设置。Secret 占位符不在发布时展开，发布器不上传真实密码。

原基础 `application.yml` 保留无 Profile 时的默认值和 Secret 绑定。对于已经迁入 Nacos 的参数，应修改该环境的远程 YAML；旧 `FLOWDESK_*` 开关/超时通常只影响基础文件，不应再当作远程参数覆盖入口。需要紧急覆盖时，使用准确的 Spring 属性名和明确的命令行或系统环境变量，并记录覆盖来源。

## 首次启动本机 dev

现有 `.env.example` 是完整本地密钥/部署变量清单；`config/environments/*.env.example` 仅补充环境选择和端口约定。真实 `.env` / `.env.test` 已被 Git 忽略。不要将选择器模板当成完整 Secret 文件。

1. 按 README 生成本机 `.env`、独立凭据、JWT/TLS/Webhook 文件。设置 `FLOWDESK_ENVIRONMENT=dev`、`SPRING_PROFILES_ACTIVE=dev`，Nacos 应用账号使用 `flowdesk-dev` 及独立随机密码，不复用管理员密码。
2. 执行 `docker compose -p flowdesk-public run --rm nacos-initialize` 初始化管理员。
3. 执行 `docker compose -p flowdesk-public --profile m10 run --rm nacos-config-initialize` 创建 `flowdesk-dev`、发布七份配置并配置应用账号。
4. 按 README 完成数据库与 RocketMQ 初始化，再启动 `docker compose -p flowdesk-public --profile m10 up -d --build web`。Web 使用独立 Nginx 镜像并依赖 Gateway 健康，页面默认通过 `http://localhost:8086/` 访问。应用依赖配置初始化成功，Gateway 通过 `lb://` 及同环境注册表访问后端。

初始化使用 Python 标准库镜像，无第三方 Python 依赖；配置文件只读挂载。应用仅获得自身环境 Group 的配置读取权限和服务发现读写权限；发布器持有管理员凭据，业务服务不再使用管理员账号。

Nacos 3 控制台独立监听容器 8080。本机默认通过 `http://localhost:28080/` 访问（由 `FLOWDESK_NACOS_CONSOLE_PUBLISH_PORT` 配置，只绑定回环）；8848 是服务端 HTTP API，9848 为客户端 gRPC，不能把 API 端口当作控制台端口。应用账号只能读取对应环境的配置，发布修改需管理员或另行配置的发布身份。

## 核对和发布

从宿主机执行（需要 Python 3）：

```powershell
# 仅列出本地文件及 SHA-256，无网络访问
python scripts/nacos-config.py plan --environment dev

# 只读比较当前 .env 指向的服务器；不打印内容和凭据
python scripts/nacos-config.py check --environment dev

# 首次发布；默认拒绝覆盖已有不同内容
python scripts/nacos-config.py publish --environment dev --provision-user

# 审核 YAML 差异后，显式更新已有配置并回读核验
python scripts/nacos-config.py publish --environment dev --overwrite
docker compose -p flowdesk-public --profile m10 up -d --no-deps --force-recreate iam workflow ticket alert notification gateway
```

`--env-file .env.test` 指定另一环境的凭据，`--server-addr` 可显式指定该 Nacos HTTP 地址。发布器使用 Nacos 3 Admin API，在写入前核对全部冲突，更新使用 CAS，发布后回读校验。发布多个 Data ID 并非原子事务；中途失败时按输出核对、修复并重试。不同环境必须使用各自账号，既存用户密码不符时不会被自动重置，遇到额外角色或宽权限也会中止。

七份文件是发布基线。控制台临时修改后，应同步回仓库并审核；常规启动发现远程内容与仓库不同会拒绝悄悄覆盖。先 `check`，再决定保留远程修改还是显式发布版本。回滚使用 Git 中已审核的上一版 YAML，重新发布并重启。

Nacos 客户端可能使用本地快照；启动校验保证必需配置及标识存在，不代表每次启动都强制从在线服务器获取最新内容。常规发布流程应先在线 `check`，再启动新实例。

## test 和 prod 部署边界

test 使用独立 `.env.test`、项目名 `flowdesk-test`、数据卷、网络和端口。可参考 `config/environments/test.env.example`；所有密码、JWT、TLS、Webhook 文件及宿主服务令牌路径均需独立生成，不能复制 dev 的 Secret。`docker compose --env-file .env.test -p flowdesk-test --profile m10 ...` 会创建单独本地基础设施。模板的 Web 入口为 `http://localhost:18086/`，Gateway TLS 端口为 18080，与 dev 隔离。Nacos 的 HTTP/gRPC 映射保持相差 1000；RocketMQ 主端口与 VIP 端口保持相差 2。

prod 使用独立生产 Nacos 集群、MySQL、Redis、RocketMQ 和应用凭据，按 `config/environments/prod.env.example` 提供真实连接端点。由生产部署平台运行同一应用镜像，挂载密钥和服务令牌；数据库迁移使用独立迁移账号，不能运行演示 seed。当前 Compose 含单机 Nacos 和演示数据，仅允许 dev/test 配置初始化，不能作为生产部署。仓库提供 prod 配置模板，不会在本机创建或冒充生产环境。

前端不读取 Nacos，也不包含环境 Secret。它通过独立 Web 镜像交付，由同源反向代理把 `/api/` 转到对应环境 Gateway。生产需配置入口 HTTPS、可信上游证书和路由；前端与后端分别发布和回滚，见[前后端独立部署](frontend-deployment.md)。

## 验证

```powershell
python -m unittest discover -s scripts -p test_nacos_config.py
docker compose -p flowdesk-public --profile m10 config --quiet
.\mvnw.cmd test '-Dtest=*ApplicationTests,ManagedNacosConfigurationGuardTest,GatewayLbRouteIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

真实 Nacos 的可选测试为 Gateway 的 `NacosConfigurationSmokeTest`。在当前进程安全加载 `.env` 中 Nacos 地址和应用账号后设置 `FLOWDESK_NACOS_SMOKE_TEST=true` 再运行该类；不要为 Maven 全局设置 `SPRING_PROFILES_ACTIVE=dev/test/prod`。它验证实际导入、环境标识和缺失服务配置拒绝启动。

官方参考：

- [Spring Cloud Alibaba 2025.x Nacos 快速开始](https://sca.aliyun.com/docs/2025.x/user-guide/nacos/quick-start/)
- [Spring Cloud Alibaba 2025.x Nacos 高级指南](https://sca.aliyun.com/docs/2025.x/user-guide/nacos/advanced-guide/)
- [Spring Boot 外部化配置](https://docs.spring.io/spring-boot/reference/features/external-config.html)
