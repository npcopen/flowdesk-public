package com.flowdesk.notification.service;

import com.flowdesk.notification.dto.NotificationDtos;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 当前用户通知的查询与读状态管理契约，供 HTTP 入口调用。
 * 查询范围由租户和接收人共同限定；写入同时使用资源版本与幂等键处理并发更新和请求重试。
 */
public interface NotificationService {
    /** 按状态、类型和创建时间分页查询当前接收人的通知，返回下一页游标及实际排序规则。 */
    Page<NotificationDtos.NotificationResponse> list(String state, String kind, Instant from, Instant to, int pageSize, String pageToken, String sort);
    /** 统计当前租户内属于当前用户的未读通知数量，供通知角标展示。 */
    NotificationDtos.UnreadCountResponse unreadCount();
    /**
     * 修改当前用户所收通知的已读或未读状态，并将状态变更与成功审计一并提交。
     * 相同幂等键只允许重放相同业务意图；首次执行须通过 If-Match 版本检查。
     */
    ReadStateResult updateReadState(UUID notificationId, NotificationDtos.ReadStateWrite request, String ifMatch, String idempotencyKey);
    /** 封装读状态写入后的通知快照，以及本次是否命中已成功执行请求的幂等重放。 */
    record ReadStateResult(NotificationDtos.NotificationResponse notification, boolean replayed) {}
    /** 分页查询结果，携带本页数据、下一页游标和服务端实际采用的排序表达式。 */
    record Page<T>(List<T> items, String nextPageToken, String sort) {}
}
