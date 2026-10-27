# 告警服务代码阅读地图

业务代码位于 `flowdesk-alert-service/src/main/java/com/flowdesk/alert`。

## 先分清这些概念

| 概念 | 含义与用途 |
| --- | --- |
| AlertEvent（告警事件） | 监控源一次 FIRING（触发）或 RESOLVED（恢复）的报告，保存接纳时的事实。 |
| Incident（故障） | 一轮故障的聚合状态，可以由多次告警事件组成。 |
| fingerprint | 识别同一监控问题；同一租户、同一指纹最多有一个 ACTIVE 故障。 |
| cycleRef | 监控源提供的故障周期标识，用来区分当前周期、旧周期和下一周期。 |
| generationNo | 本服务为同一指纹的不同故障依次分配的代次序号。 |
| nonce | 一次签名请求的防重放值，防止原请求再次使用。 |
| eventId / externalEventId | 监控源事件标识，用于识别业务事件重复；与请求 nonce 是两种约束。 |
| createTicketIntentKey | “为这个故障建工单”的固定业务意图键，跨服务回执用它核对关联。 |
| messageId / publicationKey | 一条消息的身份及其发布幂等键，发送重试继续使用原身份。 |
| Outbox / Inbox | 本服务待发送的消息事实 / 本服务已接收的消息事实，用于可靠发送和消费去重。 |
| Recovery | 发送或消费无法自动完成后留下的人工恢复记录，与故障的 RECOVERED 状态不同。 |

## 按问题选择入口

| 想了解什么 | 建议阅读顺序 |
| --- | --- |
| 外部告警怎样进入系统 | `webhook/WebhookSecurityConfiguration` → `WebhookAuthenticationFilter` → `controller/WebhookController` → `service/WebhookAcceptanceService` |
| 告警怎样形成、聚合、恢复故障 | `WebhookAcceptanceService` → `service/impl/WebhookAcceptanceServiceImpl.accept` → `acceptOnce` → `decide`，再查看按事件、故障、关联消息和审计划分的 Mapper XML |
| 为什么后续周期暂时没有工单 | `WebhookAcceptanceServiceImpl.historical`、`reconcileWaitingCycles` → `service/AlertCycleReconciler` |
| 本地接纳后怎样请求工单服务 | `event/AlertEventContract` → `AlertOutboxPublisher.publishOnce` → `service/AlertOutboxDeliveryService → service/impl/AlertOutboxDeliveryServiceImpl → AlertOutboxMapper` |
| 工单建立结果怎样回填 | `event/TicketResultRocketMqConsumers` → `TicketResultEnvelopeGuard` → `TicketResultConsumer` → `service/TicketResultProcessingService → service/impl/TicketResultProcessingServiceImpl → TicketResultMapper` |
| 消息耗尽重试或进入死信后怎么办 | `event/TicketResultDlqAcceptor`，以及 Outbox 发布器的失败结算 → `controller/AlertRecoveryController` |
| 人工恢复怎样受理和继续执行 | `recovery/AlertRecoveryService` → `AlertRecoveryServiceImpl.replay` → `accept` → `service/AlertRecoveryExecutionService`；重启接管装配见 `AlertRecoveryConfiguration` |
| 页面列表和详情怎样查询 | `controller/AlertController` → `AlertQueryParams` → `service/AlertQueryService` → `service/impl/AlertQueryServiceImpl` → `mapper/AlertEventMapper` / `IncidentMapper` / `AlertMessageMapper` |

## 一次告警的完整链路

以同一 `fingerprint`、同一 `cycleRef` 的 FIRING → FIRING → RESOLVED 为例：

1. 过滤器检查集成状态、签名、请求时间及原始字节，控制器解析已验签的报文。
2. 第一次 FIRING 在本地事务内写入 nonce、AlertEvent、ACTIVE Incident、REQUESTED 工单关联、建单 Outbox 和审计。HTTP 返回 202，此时工单可能尚未建立。
3. Outbox 发布器领取持久化发送权，在数据库事务外调用 RocketMQ，再按领取凭据结算。只有明确的 SEND_OK 才标记发布成功。
4. 工单服务完成建单后发送结果，Alert 的结果消费者通过契约与幂等校验，再回填 IncidentTicketLink。工单关联状态与故障状态分别维护。
5. 第二次 FIRING 聚合进当前故障，增加事件数、更新摘要，不再发出新的建单意图。
6. RESOLVED 将故障推进为 RECOVERED，并在同一事务内保存恢复 Outbox。工单服务异步补充恢复时间线。

若 RESOLVED 先到，服务直接记录已恢复周期，不建立工单关联；同周期迟到的 FIRING 不应再开新工单。

若一个活动周期尚未恢复，却收到未知 `cycleRef` 的告警，先保留 REVIEW_REQUIRED 事件。前一周期恢复后，服务可以依据已持久化事实补建后续周期，定时任务也会补偿尚未推进的候选。事件原始接纳事实保持不变；查询层在能唯一匹配补建周期时投影其归属。

## 读事务和重试时要看什么

- **Webhook 接纳**：一次尝试中的 nonce、事件、故障、关联、Outbox、审计共同提交或回滚。故障并发竞争由数据库约束及版本条件裁决，失败后重开事务读取胜出结果。
- **Outbox 发送**：领取和结算各使用短事务，网络调用在二者之间。进程中断或发送结果未知时可能再次发送，因此消费者仍需幂等。
- **结果消费**：先校验信封，再建立租户上下文。ACK 只表示无需再次投递，可能是成功、已处理重复或已持久化的业务拒绝；不能把它直接当成工单关联成功。
- **人工 Inbox 重放**：先在事务中校验源记录、版本与幂等命令，保存执行凭据；提交后由 Worker 还原原消息并调用正常消费者，最后回查持久化状态结算。
- **人工 Outbox 重放**：将原发送记录重新排队，继续由正常发布器发送，不创建新的消息身份。
- **租约与过期 Worker**：attempt 标识和租约截止值共同作为执行权凭据。结算时必须核对数据库当前凭据，防止旧 Worker 覆盖已接管任务的状态。

## 各包的职责边界

| 包 | 负责什么 |
| --- | --- |
| `controller` | 全部 HTTP 入口，负责路由、接口权限声明、状态码和响应头；通过业务接口进入应用层。 |
| `dto` | HTTP 请求/响应结构和查询参数工具，不依附于持久层。 |
| `webhook` | 集成身份、原文验签、请求时间窗和载荷格式；不提前消耗业务 nonce。 |
| `service`、`service/impl` | 公共业务接口及其实现；Controller 注入接口，Impl 负责接纳与查询编排、故障周期裁决及本地事务。游标签名、标识编码及周期补偿按原能力保留。 |
| `mapper`、`resources/mapper/alert` | `AlertEventMapper` 管 nonce 和事件，`IncidentMapper` 管故障周期，`AlertMessageMapper` 管工单关联和发送意图，`AlertAuditMapper` 管审计。AlertOutboxMapper、TicketResultMapper、AlertRecoveryMapper、WebhookIntegrationMapper 和 AlertCycleMapper 覆盖投递租约、结果 Inbox、恢复生命周期、集成候选与周期扫描；SQL 使用 XML 与绑定参数。 |
| `model` | 独立事件、故障、关联、Outbox、结果 Inbox 和 Recovery 行模型及查询条件；Mapper/XML 不引用已删除的 Repository 嵌套类型。 |
| `event` | MQ 契约、原始信封校验、Outbox 发布、结果消费、Inbox 去重和死信接纳。 |
| `recovery` | 恢复命令接口、响应视图、幂等工具与条件租约装配；查询、命令受理和实际执行分别由三个能力接口与 service/impl 实现承担。 |
| `config` | 核心查询与接纳依赖的装配；消息与恢复能力的开关和装配位于各自包中。 |

配置类适合在了解流程之后阅读，用于确认能力开关、线程池、调度和依赖关系。阅读持久层时先找 Mapper 契约，再查看 resources/mapper/alert 的同名 XML 与 model 中的独立结果模型。

## 三层与兼容边界

- HTTP 调用方向为 Controller → Service 接口 → ServiceImpl → Mapper；恢复入口同样依赖 Service 接口。接口不暴露实现类，筛选模型不隶属于 Mapper。
- MyBatis 的 UUID 类型处理器映射 BINARY(16)。排序由 XML 固定分支选择，筛选值与游标均使用绑定参数。
- MyBatis 工厂使用运行数据源；一级缓存限定为 STATEMENT，关闭二级缓存，确保同一本地事务中的消息写入后重新查询能读取当前事实。
- 所有生产业务 SQL（包括 Outbox、Inbox、Recovery、认证候选和跨租户扫描）均已迁移为专用类型化 MyBatis Mapper/XML。生产 Repository 类和 repository 包均已删除；纯 SQL 转发由调用者直接注入 Mapper，业务不变量与同事务协调进入明确的能力 Service。AlertRecoveryReplayMapper 继承公共租约 Mapper，保留版本、人工尝试和到期时间的条件接管。

## 能力接口如何划分

- AlertOutboxDeliveryService 负责领取、重试、发送确认及重试耗尽后的恢复结算；保留租户/事务断言、token、lease、attempt 和版本条件。DeliveryServiceImpl 自己持有各次领取/结算的短事务，Publisher 不再注入事务管理器，只通过该接口协调数据库状态，并在事务外发送 Broker。
- TicketResultProcessingService 负责 Inbox 不可变信封判重、工单关联 CAS、拒绝/完成及恢复记录结算；能力入口 process / acceptDeadLetter 直接持有业务事务及多 Mapper 锁定顺序；Consumer/DLQ 仅验信封、建立租户作用域并映射 ACK/RETRY，ACK 仍在事务提交之后决定。
- AlertRecoveryQueryService 仅负责租户内查询、过滤和游标；AlertRecoveryService 负责权限、人工命令幂等、If-Match 与原消息/恢复记录/审计原子受理；AlertRecoveryExecutionService 负责提交后还原原消息、复用正常消费者及租约约束的完成/失败结算。三个实现均位于 service/impl，直接依赖 AlertRecoveryMapper。
- Webhook 集成候选查询原来只有 SQL 转发，现在由认证过滤器直接注入 WebhookIntegrationMapper；验签逻辑与跨租户认证候选扫描语义保持不变，不增加一层改名的存储门面。
