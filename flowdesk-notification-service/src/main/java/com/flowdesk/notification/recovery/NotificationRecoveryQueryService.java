package com.flowdesk.notification.recovery;

import com.flowdesk.notification.dto.NotificationDtos;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 恢复用例接口。 */
public interface NotificationRecoveryQueryService {
    /**
     * 按声明的筛选和分页条件读取当前租户可见记录，返回本页数据及下一页边界。
     */
    Page<NotificationDtos.MessageRecoveryResponse> list(String direction, String state, Boolean deadLettered, String errorCode, Instant from, Instant to, String size, String token, String sort);
    /**
     * 读取当前租户内指定记录，不存在或不可见时返回统一资源错误。
     */
    NotificationDtos.MessageRecoveryResponse get(UUID recoveryId);
    /**
     * 通知处理结果封装，组合业务快照及分页或幂等元信息供调用入口使用。
     */
    record Page<T>(List<T> items, String nextPageToken, String sort) {}
}
