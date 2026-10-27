# 后端分层与接口约定

当前五个业务服务统一沿 HTTP Controller → Service 接口 → ServiceImpl → MyBatis Mapper → MySQL 阅读。Service 接口和实现属于同一业务层，因此整体仍为传统三层架构。Gateway 负责响应式入口、鉴权和路由，不套用业务 CRUD 分层。

## 职责边界

- `controller`：HTTP 参数、基础格式校验、状态码和响应封装，只注入业务 Service 接口；不直接访问 Mapper、ServiceImpl 或 JDBC。所有业务 HTTP Controller 统一放在服务根包的 `controller` 包中，外部 URL 保持 `/api/...` 不变。
- `dto`：HTTP 请求、响应与查询参数契约，与 Controller 和持久化模型分开，不再使用含义混合的 `api` Java 包。
- Service 接口：表达业务用例和输入输出契约；不引用实现类，也不把 Mapper 内部类型作为 HTTP 契约。游标、密码散列等小型工具不为形式统一额外添加接口。
- `service.impl` 或能力服务的 `impl`：权限、可见范围、业务状态流转、幂等和事务编排，实现直接调用类型化 Mapper。消息消费、发布领取与结算、SLA 和恢复按能力拆分服务，Worker、Publisher、Consumer 依赖对应 Service 接口，不增加仓储中间层。
- `mapper` 与 `resources/mapper`：具名参数、明确的 SQL、结果映射与数据访问。按可独立变化的职责划分，不按每张表机械生成 Service。
- `model` 或能力专用的 `model`：持久化投影与查询条件独立定义，不嵌套在已删除的仓储中，也不依赖 ServiceImpl。HTTP 请求、响应 DTO 保持现有公开协议。

## 生产 SQL 边界

所有生产业务 SQL，包括消息 Inbox/Outbox、SLA、租约、恢复执行、Webhook 身份读取和公共恢复续跑器，统一通过具名的 MyBatis Mapper 执行。生产源码不使用 JdbcTemplate、NamedParameterJdbcTemplate、JdbcClient 或其他 Spring JDBC SQL 执行模板。

生产业务不保留 Repository 类或 `repository` 包。原仓储中的纯转发由调用方直接使用 Mapper；多条 Mapper 调用的顺序、事务前置条件、CAS 返回值判断、幂等内容检查和消息对象防御性复制归入对应能力 ServiceImpl，不用 Store、Adapter 等改名方式保留同一中间层。Mapper 负责明确的每个操作，SQL 保留在 XML 或共享注解契约中。

| 服务 | MyBatis 数据访问范围 |
| --- | --- |
| IAM | 用户、凭证、授权、资格、审计 |
| Ticket | 工单、编号、评论、时间线、审计、目录、流程关联、SLA 时钟/目标/租约、Inbox/Outbox、恢复 |
| Workflow | 定义、版本、运行态、审计、启动消费、通知意图、各类消息发布与恢复 |
| Alert | 事件、故障、消息结果、发布领取/结算、Webhook 集成身份、恢复和周期协调 |
| Notification | 通知、Inbox、审计、恢复记录、恢复专用账号的消息原文读取 |
| Common | 恢复续跑扫描与租约抢占的共享 Mapper 契约，由每个服务绑定到自己的数据源 |

测试可以使用 JdbcTemplate 准备数据和断言。JDBC 驱动、DataSource、Spring JDBC 事务管理器和 MyBatis TypeHandler 仍属于必要的底层支持；健康探针的连接有效性与迁移记录检查属于基础设施探测，不是业务 SQL 执行入口。

## 事务和 SQL 约束

- 同一用例调用多个 Mapper 仍在同一本地事务中。保留原有 `TransactionTemplate`、声明式事务、隔离级别和锁顺序。外部资格预检与 Broker 发送保持在原有持锁事务之外。
- IAM 登录失败计数由独立的 `CredentialFailureService` 使用 `REQUIRES_NEW` 提交，避免登录失败异常回滚计数。
- 每条租户 SQL 保留原租户谓词；更新保留 `lock_version`、状态、租约等条件，不能靠接口分层代替并发控制。
- UUID 保持 BINARY(16) 的原字节顺序，由公共 `UuidBinaryTypeHandler` 处理。record 使用明确构造映射，特别注意 nullable 列与字段别名。
- MyBatis 使用 `STATEMENT` 本地缓存并关闭二级缓存，避免事务内多阶段判断读取到旧投影。Notification 普通账号的 SqlSessionFactory 与恢复账号事务保持显式隔离。
- 排序列和方向使用固定选择分支，所有值通过 `#{...}` 绑定，不使用 `${...}` 接收原始 SQL。游标保持复合排序键、租户与权限范围绑定。

## 验证

```powershell
python scripts/verify-backend-layers.py
.\mvnw.cmd clean test
```

分层脚本检查 Controller 包名、Service 接口解耦、Repository 中间层禁用、生产 JDBC 执行 API 禁用以及 Mapper XML 与接口对应关系。公共模块的 ProductionLayerBoundaryTest 和 ProductionSqlBoundaryTest 同时在 Maven 测试阶段扫描所有模块，防止分层退化、重新引入生产模板 SQL 或绕过 Mapper 直接创建 JDBC Statement；直接 JDBC 探测仅允许出现在 ServiceHealthController 基础设施健康探针中。测试覆盖权限与租户隔离、分页、业务幂等、并发写入、事务回滚和恢复租约。需要真实 MySQL/Redis/RocketMQ 的已有 opt-in 用例仍需按各服务的真实基础设施验收说明启用，普通测试不会自动启动它们。
