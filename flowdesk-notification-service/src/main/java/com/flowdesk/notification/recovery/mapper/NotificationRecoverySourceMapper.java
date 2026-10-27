package com.flowdesk.notification.recovery.mapper;
import com.flowdesk.notification.model.*;

import org.apache.ibatis.annotations.Param;
import java.util.*;
import java.time.Instant;
import com.flowdesk.notification.event.*;
import com.flowdesk.notification.recovery.*;

/**
 * 通知恢复专用数据源的源消息读取接口。
 * 按租户与收件箱标识读取包含原始消息体的恢复数据，可在重放事务中加行锁。
 */
public interface NotificationRecoverySourceMapper {
    /**
     * 使用恢复专用数据源按租户及收件箱读取含 body_bytes 的完整消息，供校验及重放；lock 为 true 时加行锁。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<NotificationInboxRow> findSource(@Param("tenantId") UUID tenantId, @Param("inboxId") UUID inboxId, @Param("lock") boolean lock);
}
