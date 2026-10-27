# 人工消息重放的中断恢复

Ticket、Workflow、Alert、Notification 接受 Inbox 人工重放时，在原恢复记录中同时保存人工尝试信息和两分钟执行租约（`next_attempt_at`）。接受操作和审计在同一事务内提交，随后通过原消费者执行，保留原消息标识、内容和业务幂等规则。

服务每五秒扫描已过期的 Inbox `REPLAYING` 租约。进程退出、任务队列丢失或执行再次中断后，其他实例可以按版本号领取并继续原人工尝试；历史没有租约的 `REPLAYING` 记录也会被接管。接管不会增加人工重放次数或生成新的人工操作记录。旧执行的失败结算必须匹配尝试标识和租约，不能覆盖接管者的状态。

正常消费成功或明确拒绝会将恢复记录置为 `RESOLVED` 并清空租约；实际重放失败会回到 `OPEN`，由操作员排查原因后再次发起人工重放。后台扫描只接管中断的已接受操作，不会自动接受新的人工尝试。租约到期后的实际恢复时间还受实例可用性、队列容量和数据库状态影响。

`flowdesk.recovery.resume-enabled` 默认 `true`，可设为 `false` 暂停后台扫描。Notification 还要求原有的 `flowdesk.notification.recovery.enabled=true`，继续使用独立 recovery 数据库账号。

## 部署顺序

启动新版服务前，通过现有 Flyway 迁移流程执行四个服务的新增迁移：

- Ticket：`V20261022_006__ticket_durable_inbox_replay.sql`
- Workflow：`V20261022_005__workflow_durable_inbox_replay.sql`
- Alert：`V20261022_001__alert_recovery_replay_lease.sql`
- Notification：`V20261022_001__notification_recovery_replay_lease.sql`

迁移保留原恢复记录和人工历史，调整状态约束并增加过期租约扫描索引。旧版本的 Ticket、Alert、Notification 状态约束不允许 `REPLAYING` 持有租约，因此不能跳过迁移直接运行新代码。运行账号继续使用现有授权，迁移由原 Flyway 账号执行。

Ticket、Workflow 的通知 Outbox 也已接入各自人工恢复入口：发送耗尽后进入 `OPEN`；人工重放后若再次耗尽，会复用同一恢复记录回到 `OPEN`，保留人工历史；后续发送成功则落为 `RESOLVED`。每次重发都使用原消息内容与消息标识。
