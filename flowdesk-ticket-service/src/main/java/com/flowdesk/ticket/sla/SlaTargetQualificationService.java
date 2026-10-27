package com.flowdesk.ticket.sla;

import com.flowdesk.ticket.model.sla.*;
import com.flowdesk.ticket.model.*;
import java.time.Instant;
import java.util.UUID;

/** 业务用例契约，调用者依赖接口。 */
public interface SlaTargetQualificationService {
    /**
     * 按当前时间与允许时钟偏差校验资格快照，判断是否可用于通知。
     */
    Validation validate(UUID tenantId, Instant at);

    /**
     * 按当前时间与允许时钟偏差校验资格快照，判断是否可用于通知。
     */
    Validation validate(UUID tenantId, Instant at, boolean lock);

    /**
     * 刷新指定租户资格，远程检查与本地持久化分开执行。
     */
    RefreshResult refreshTenant(UUID tenantId);

    /**
     * 按有界租户批次刷新资格，逐租户隔离处理与失败结果。
     */
    int refreshAll();
}
