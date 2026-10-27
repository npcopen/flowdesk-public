# 通知服务代码阅读地图

业务代码位于 `flowdesk-notification-service/src/main/java/com/flowdesk/notification`。

| 入口 / 包 | 职责与阅读路径 |
| --- | --- |
| `controller/NotificationController` | 当前用户通知列表、未读数和已读状态 → `service/NotificationService` → `service/impl/NotificationServiceImpl` → `mapper/NotificationMapper`。 |
| `event` | Broker 主队列 / DLQ → Envelope Guard → `NotificationConsumerService`；消费服务编排通知副作用、Inbox、恢复记录和审计。 |
| `controller/NotificationRecoveryController` | 恢复列表与详情 → `recovery/NotificationRecoveryQueryService`；人工重放 → `NotificationRecoveryReplayService`。 |
| `recovery` | 查询、重放受理与执行按职责分开。重放服务负责领取租约、校验原始消息、调用原消费入口与结算，也供后台 Resumer 在重启后继续执行。 |
| `dto` | HTTP DTO、命令和查询参数解析工具；与持久化投影分离。 |
| `mapper`、`resources/mapper/notification` | 专用类型化 SQL 契约及 XML：通知主体、Inbox、Recovery 和审计；恢复原文 Mapper 单独位于 recovery/mapper，只绑定恢复账户。 |
| `model` | 独立 NotificationRow、Inbox、Recovery 和审计投影，以及 Inbox 判重结果；字节数组字段继续防御性复制。 |
| `service`、`service/impl` | `NotificationAuditWriter` 为用户命令和消息消费写入幂等审计；`NotificationSupport` 统一权限、租户上下文及命令头校验；游标服务校验分页 scope。 |
| `config/NotificationMyBatisConfiguration`、`config/NotificationRecoveryDatabaseConfiguration` | 前者分别建立普通账户与恢复账户的 MyBatis 会话工厂；后者显式接线匹配的数据源、事务管理器、恢复记录 / 审计 Mapper 及后台 Resumer。 |

## 事务与数据边界

- 用户已读状态的版本更新和审计在普通账户的同一事务内提交。
- 消息消费由 `NotificationConsumerService` 开启普通账户事务；通知效果、Inbox 终态、恢复状态和审计一起提交，持久化失败返回重试。
- 人工重放先在恢复账户事务内校验源消息、领取租约并写入审计，提交后调用原消费入口，再按持久化的 Inbox 终态结算。HTTP 与后台恢复复用同一条执行路径，过期 Worker 受租约校验约束。
- 两种账户各自有显式限定名称的恢复与审计 Mapper 代理：普通 notificationRecoveryMapper / notificationAuditMapper 与恢复 notificationPrivilegedRecoveryMapper / notificationPrivilegedAuditMapper。普通 Inbox 查询固定投影 `NULL AS body_bytes`；原文读取只由恢复工厂绑定的 NotificationRecoverySourceMapper 暴露。原文 Mapper 位于 recovery/mapper 包且不参与普通 MapperScan，权限不通过继承或构造布尔值切换。
- 消息标识、业务效果标识、审计标识和恢复记录标识沿用原有派生规则，数组字段继续使用防御性复制。

## 三层与 MyBatis

- 普通 HTTP 入口为 Controller → Service 接口 → ServiceImpl → MyBatis Mapper。恢复查询和重放也分别暴露接口，实现位于 recovery/impl。
- NotificationRow、NotificationInboxRow、NotificationRecoveryRow 和 NotificationAuditRow 位于独立 model 包；XML 覆盖主键查询、副作用去重查询、收件人游标分页、未读数、插入和版本条件更新。原收件人条件、租户断言、写事务断言与数组防御性复制保留。
- NotificationMyBatisConfiguration 分别建立普通和恢复 SqlSessionFactory。普通 MapperScan 只覆盖 notification.mapper；恢复原文与后台租约 Mapper 仅在独立恢复工厂注册。工厂与各自事务管理器绑定同一个 DataSource 对象，恢复 DataSource 保持包装对象而非普通 DataSource Bean。
- 所有生产 SQL 均通过专用类型化 MyBatis Mapper/XML 执行。一级缓存限定为 STATEMENT 并关闭二级缓存，所有通知、审计、Inbox 和恢复写入由原 Service/Consumer 事务统一提交。
- 生产 Repository 类和 repository 包已删除。纯查询和条件更新直接进入现有应用 Service；不可变内容一致性、确定性身份和多 Mapper 协调形成明确能力接口，并非把 Repository 换名后保留同一万能门面。
- NotificationInboxService 校验消息身份、publication key 和不可变信封，处理并发唯一键冲突并推进原 Inbox 状态；NotificationRecoveryRegistrationService 仅负责恢复身份注册与来源 Inbox 的一致性校验；NotificationAuditWriter 仅负责稳定审计锚点及冲突内容核对。调用者保留原事务边界，能力实现保留租户/事务断言。
- 原文单独由 NotificationRecoverySourceMapper 查询；NotificationRecoveryReplayMapper 复用公共条件租约 SQL。测试继续检查两工厂 Mapper 注册集合、DataSource 对象隔离及与事务管理器匹配。
