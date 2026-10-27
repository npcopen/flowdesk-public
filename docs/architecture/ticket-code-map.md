# 工单服务代码阅读地图

业务代码位于 flowdesk-ticket-service/src/main/java/com/flowdesk/ticket。按能力组织包，各 HTTP 入口统一沿 Controller → Service 接口 → Impl → Mapper 阅读。总的边界约束见 [后端分层约定](backend-layers.md)。

| 能力 | 阅读路径 |
| --- | --- |
| 工单读写 | controller/TicketController → service/TicketService 或 TicketQueryService 接口 → service/impl。前者负责创建、指派、接单、转派、评论、解决、关闭和重开；后者处理可见范围、列表、详情、评论、时间线、SLA 状态与启动进度。 |
| 数据访问 | mapper/TicketMapper 管主体；TicketNumberMapper 管租户每日编号；TicketCommentMapper、TicketTimelineMapper、TicketAuditMapper 管各自事实；TicketWorkflowLinkMapper 管启动关联；TicketOutboxWriteMapper 管事务内消息意图写入。 |
| 目录 | controller/CatalogController、CatalogManagementController → 业务接口 → catalog/impl → CatalogMapper、SlaPolicyMapper 和 TicketAuditMapper。CatalogVersionClient 校验 Workflow 版本。响应转换为实现包内部工具，不暴露为业务接口。 |
| 策略与时效 | controller/SlaPolicyController → SlaPolicyService → sla/impl/SlaPolicyServiceImpl → SlaPolicyMapper。TicketSlaService 接口与 sla/impl/TicketSlaServiceImpl 协调建立、提醒、超时及通知补偿；SlaClockService 封装时钟转换、触发幂等及不可变收件人快照；SlaRebuildLeaseService 封装重建租约与栅栏。纯查询直接使用 SLA Mapper。 |
| 审批摘要 | controller/ApprovalContextController → ApprovalContextService → approval/impl/ApprovalContextServiceImpl。审批任务资格、工单与目录读取在业务实现协调，HTTP 层不查询数据库。 |
| 消息消费 | incident/IncidentMessageConsumer 接收 Alert 事件；reverse/WorkflowReverseConsumer 接收 Workflow 结果。Envelope Guard 校验后，消费者依赖 IncidentInboxService / WorkflowReverseInboxService 接口；对应 impl 直接调用 Mapper，协调确定性业务事实、Inbox 与恢复结算。IncidentResultDispatchService / WorkflowStartDispatchService 分别管理结果和流程启动 Outbox 的领取、CAS 与恢复。 |
| 人工恢复 | controller/TicketRecoveryController → TicketRecoveryService → recovery/impl/TicketRecoveryServiceImpl。受理实现直接调用恢复 Mapper；后台 TicketRecoveryReplayService → recovery/impl/TicketRecoveryReplayWorker 通过独立 TicketReplayWork 恢复原消费路径并直接结算 Mapper，不经过 Repository 包装。 |
| 通知 | notification/TicketNotificationIntent 定义意图，TicketNotificationOutboxWriter 接口 → notification/impl/TicketNotificationOutboxWriterImpl 直接使用 Mapper，参与调用方事务并核对不可变内容；TicketNotificationDispatchService 管理领取和结算，专用发布器在事务外发送。 |
| SQL 和投影 | 业务 SQL 位于 resources/mapper，共享恢复续跑 SQL 由公共 RecoveryReplayMapper 注解契约提供；Mapper 按工单、消息、恢复与 SLA 能力划分。所有 Mapper 行模型与消息投影均独立位于 model 及能力子包，HTTP DTO 与查询参数辅助类位于 dto；persistence/Uuids 转换已有二进制参数，UUID 结果由公共 TypeHandler 映射。 |

## 同步服务接口

- IAM 资格校验沿 AssigneeEligibilityChecker / SlaTargetQualificationChecker → IamEligibilityClient → IamEligibilityFeignClient → IAM API-016。业务适配层逐请求校验身份、租户和返回字段；Feign 只声明传输契约。
- 目录绑定沿 CatalogVersionClient → workflow/TicketWorkflowFeignClient → 流程版本接口。审批资格沿 WorkflowApprovalAccessClient → 同一 Feign 接口 → 审批任务与实例接口。
- 两个 Feign 配置只作用于各自客户端，保留超时、关闭重试及逐请求令牌传递。URL 留空时使用 Nacos，填写时直连。RocketMQ 链路仍由消息组件负责。

## 事务与一致性

- 人工命令由 TicketServiceImpl 的 TransactionTemplate 控制事务，工单、时间线、审计、相关 SLA 和 Outbox 一次提交；IAM 资格预检仍在写事务之前。
- 目录维护保留声明式事务；目录、审计和策略读取使用相同事务绑定连接。配置时的 Workflow 版本校验保持原调用时机。
- SLA 写入参与调用方事务；扫描入口自行开启事务。保留先锁工单再锁时钟的顺序，Redis 索引在 MySQL 提交后更新。SlaClockService / SlaRebuildLeaseService 的实现直接使用 typed MyBatis Mapper；目标写入、资格刷新分别由 SlaTargetProvisioningService / SlaTargetQualificationService 的 impl 直接协调 Mapper。事务、唯一内容核验及栅栏断言保留。
- Inbox 终态、业务副作用、审计及结果 Outbox 一起提交，成功才 ACK；重放沿原入口和原幂等标识执行。
- Outbox 领取与结算是两个短事务，Broker 调用在其外；Recovery 先提交受理和租约，再由 Worker 执行与结算。消息领取/结算、Inbox、恢复均使用专用 MyBatis Mapper；共享 RecoveryReplayResumer 通过工单侧 Mapper 持久化租约。
- MyBatis 查询缓存使用 STATEMENT 并关闭二级缓存，保证同一事务内并发领取与写入后的重读读取数据库当前事实。所有租户、版本和状态谓词均保留。

## 结构约束

- 生产代码不存在 api/repository 包或 *Repository 类型；六个 HTTP Controller 统一位于 controller。
- 应用和能力服务依赖接口，所有 ServiceImpl 位于 impl 包；独立模型不反向依赖实现。
- 生产 SQL 仅由 MyBatis Mapper 执行；JdbcTemplate 仅用于测试夹具。SQL、租户谓词、锁顺序、领取租约和版本 CAS 均保持既有语义。
