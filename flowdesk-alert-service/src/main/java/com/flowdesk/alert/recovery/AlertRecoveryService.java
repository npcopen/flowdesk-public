package com.flowdesk.alert.recovery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 告警恢复查询与受理的业务契约。 */
public interface AlertRecoveryService {
    /**
     * 核对人工操作者、原因、幂等键和 If-Match，在事务内接纳重放；Inbox 任务仅于提交成功后交给工作线程。
     */
    ReplayResult replay(UUID id, String reason, String key, String ifMatch);
    /**
     * 人工重放接纳响应，返回最新恢复视图和幂等重放标记；接纳成功并不等同于原消息已经处理成功。
     */
    record ReplayResult(AlertRecoveryView recovery, boolean replayed) {}
    /**
     * 一次持久化 Inbox 人工尝试的执行凭据；attempt 标识人工操作，sourceCorrelation 保留原消息链路。
     * 租约接管时保留尝试标识并更新 leaseUntil，旧任务因此无法结算新执行者的记录。
     */
    record Work(
            UUID tenant,
            UUID recovery,
            UUID source,
            UUID actor,
            String reason,
            UUID attempt,
            UUID sourceCorrelation,
            Instant leaseUntil) {}
}
