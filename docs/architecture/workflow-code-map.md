# 流程服务代码阅读地图

业务代码位于 `flowdesk-workflow-service/src/main/java/com/flowdesk/workflow`。HTTP 入口统一在 `controller`，请求、响应和参数校验类型在 `dto`；控制器只注入 Service 接口。业务实现位于 `service/**/impl` 或 `service/impl`，直接使用类型化 MyBatis Mapper，生产代码没有 Repository 层，也不通过 JdbcTemplate 执行 SQL。

## 入口与能力

| 入口与能力 | 调用路径与职责 |
| --- | --- |
| 定义、草稿和版本 API | `controller/WorkflowDefinitionController` → `service/WorkflowDefinitionService` → `service/impl/WorkflowDefinitionServiceImpl` → `mapper/WorkflowDefinitionMapper`。负责定义和版本查询，以及草稿校验、修改和不可变版本发布。 |
| 运行态读取 | `controller/WorkflowRuntimeController` → `service/WorkflowRuntimeQueryService` → `service/impl/WorkflowRuntimeQueryServiceImpl` → `mapper/WorkflowRuntimeMapper`。负责实例、任务、汇总和交接记录的权限范围与游标分页。 |
| 审批和交接 | RuntimeController → `WorkflowRuntimeService` → `service/impl/WorkflowRuntimeServiceImpl`。本地事务同时提交任务、实例、交接、审计及通知和结果事件。`WorkflowRuntimeResponseMapper` 只负责响应转换。 |
| 启动消费 | `start/WorkflowStartConsumer` 先验证契约并建立可信租户，通过 `service/start/WorkflowStartQueryService` 进行幂等快查和版本预检，在事务外调用 IAM，最后交给 `WorkflowStartCommandService` 的实现原子裁决。 |
| 启动死信 | `start/WorkflowStartDlqAcceptor` → `service/start/WorkflowStartDlqService` → `service/start/impl/WorkflowStartDlqServiceImpl`。有效耗尽消息的 Inbox、Recovery 和审计在同一本地事务内提交。 |
| 恢复查询和受理 | `controller/WorkflowRecoveryController` → `service/recovery/WorkflowRecoveryReadService` 或 `WorkflowRecoveryReplayService`。所有实现都位于 `service/recovery/impl`；HTTP DTO 不与 Broker 入口混放。 |
| 受控重放 | ReplayService → `WorkflowRecoveryAcceptanceService` 提交受理和租约 → `start/WorkflowRecoveryReplayWorker` → 原 StartConsumer → `WorkflowRecoveryExecutionService` 条件结算。Worker 只依赖执行服务接口，不直接访问 Mapper。 |
| 通知意图 | `notification/WorkflowNotificationIntentConfiguration` 提供统一 Writer，配置内直接调用 `WorkflowNotificationIntentMapper`，沿用调用方业务事务；不另设单纯转发的数据访问层。 |
| 通知发布 | `notification/WorkflowNotificationOutboxPublisher` → `service/WorkflowNotificationOutboxService` → `service/impl/WorkflowNotificationOutboxServiceImpl` → 通知 Mapper。服务拥有领取、CAS 结算、恢复和审计的短事务，Publisher 在事务外发送 Broker 请求。 |
| 流程结果事件 | `event` 定义实例建立和审批终局事件；`outbox/WorkflowOutboxPublisher` 调用 `service/WorkflowOutboxService`，`service/impl/WorkflowOutboxServiceImpl` 直接 Mapper，拥有领取、结算和恢复短事务。 |

## IAM 同步调用

审批人资格校验沿 `start/WorkflowApproverEligibilityChecker` → `WorkflowIamEligibilityClient` → `WorkflowIamEligibilityFeignClient` → IAM API-016 阅读。逐请求读取租户服务令牌、校验资格响应，Feign 接口只承担传输。

`flowdesk.workflow.iam.base-url` 留空时通过 Nacos 寻找 `flowdesk-iam-service`，填写 URL 时直连。连接和读取超时默认 2 秒、3 秒，Feign 与 LoadBalancer 重试关闭；配置仅作用于该客户端，不通过全局拦截器缓存身份。

## 建议阅读顺序

1. 配置流程：DefinitionController → DefinitionService → 对应 Impl → DefinitionMapper，受限规则摘要使用 `WorkflowRuleDigest`。
2. 查看待办：RuntimeController → RuntimeQueryService → 对应 Impl → RuntimeMapper，先看 `readScope` 再看分页 token 的 scope。
3. 办理审批或交接：RuntimeServiceImpl → 任务与实例状态 → 审计和本地 Outbox，沿同一事务阅读。
4. 启动流程：StartConsumer → StartQueryService → IAM → StartCommandServiceImpl → Inbox、实例、任务和 Outbox。死信受理由独立 DlqServiceImpl 完成。
5. 人工恢复：RecoveryReplayServiceImpl → AcceptanceServiceImpl → Worker → 原消费者 → ExecutionServiceImpl，Outbox 重发仍交给各自发布器。

## 必须保留的边界

- HTTP 路由与 API 契约保持不变；控制器不持有 Mapper，也不执行状态裁决或数据库事务。
- 运行态查询保留 TENANT 与 ASSIGNED 权限范围，scope 和筛选条件参与分页 token 绑定。
- 审批决定和交接仍先锁任务、再锁实例，业务事实、审计和本地 Outbox 原子提交。
- IAM 资格预检位于持锁事务之前；事务内重读版本并比较不可变快照，避免预检结果被用于另一套规则。
- 启动命令保持消息优先、业务意图其次的去重顺序，锁定后重新裁决；Inbox 终态、实例、任务、通知、结果 Outbox 和审计提交后才 ACK。
- 恢复受理和结算都先锁原 Inbox/Outbox，再锁 Recovery；Outbox 重放的 Recovery 和队列两次 CAS 必须同成同败。
- 重放执行受尝试 correlationId 和持久化租约共同约束，旧 Worker 不能结算新尝试；恢复的 Envelope 复用正常契约、租户和幂等路径。
- 发布领取和结算短事务归 Service；Broker 网络调用始终在数据库事务之外，保留 token、lockVersion、租约接管、第八次失败转恢复及条件结算语义。
- 通知意图继续使用原 publication/business intent、契约字段和数据库事务。无 Web 且未启用启动入口时，不创建依赖数据库的通知写入 Bean。

## MyBatis 与模型（2026-10-27）

- HTTP 数据访问为 `WorkflowDefinitionMapper`、`WorkflowRuntimeMapper`、`WorkflowAuditMapper` 和 `WorkflowResultOutboxMapper`；后者独立写入审批结果事件。
- 消息数据访问为 `WorkflowStartMapper`、`WorkflowRecoveryMapper`、`WorkflowNotificationIntentMapper`、`WorkflowNotificationOutboxMapper`、`WorkflowOutboxMapper` 和 `WorkflowRecoveryReplayMapper`。
- 业务 SQL 在 `src/main/resources/mapper/workflow/*.xml`；公共恢复续跑 SQL 由 `RecoveryReplayMapper` 注解契约提供。SQL 只用绑定参数和固定排序分支，没有通用原始 SQL 执行入口。
- `model/persistence` 保存 HTTP 持久化事实，`model/start/WorkflowStartModels` 和 `model/recovery/WorkflowRecoveryModels` 保存消息事实及服务决策；`model/WorkflowOutboxModels` 与 `model/WorkflowNotificationOutboxModels` 保存发布投影。Mapper XML 不引用业务实现的嵌套类型。
- 二进制字段保留防御复制，UUID 通过公共 `UuidBinaryTypeHandler` 保持 BINARY(16) 表示。MyBatis 本地缓存为 STATEMENT，二级缓存关闭，条件更新后重读可看到最新状态。
- `RecoveryReplayResumer` 通过 Workflow 专用 Mapper 领取持久化任务，租约 CAS 提交后再调用 Worker。
- 测试允许 JDBC 建库和断言；生产业务的验证使用真实 MyBatis SqlSessionTemplate 与生产 Mapper。跨模块验收夹具依赖 Ticket 的发布服务接口和独立投影。
