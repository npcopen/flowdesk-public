package com.flowdesk.notification.model;

import java.util.UUID;
import java.time.Instant;

/**
 * 通知持久化行快照，保存数据库查询得到的业务字段，供服务校验与响应映射使用。
 */
public record NotificationRow(UUID notificationId, UUID recipientUserId, String notificationKind,
                                  String sourceService, byte[] sourceEffectKey, String sourceRefType,
                                  UUID sourceRefId, String title, String body, String state,
                                  Instant readAt, long lockVersion, Instant createdAt, Instant updatedAt) {
        /**
         * 构造不可变快照并防御性复制可变内容，防止外部修改原数组或集合改变已接纳事实。
         */
        public NotificationRow { sourceEffectKey = sourceEffectKey == null ? null : sourceEffectKey.clone(); }
        /**
         * 返回来源副作用键的防御性副本，避免调用方通过数组修改已保存事实。
         */
        @Override public byte[] sourceEffectKey() { return sourceEffectKey == null ? null : sourceEffectKey.clone(); }
    }
