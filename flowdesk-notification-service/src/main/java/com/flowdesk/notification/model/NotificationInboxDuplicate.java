package com.flowdesk.notification.model;
import java.util.*;
import java.time.Instant;

/**
 * 通知不可变数据快照，组合声明字段供当前业务处理阶段传递完整事实。
 */
public record NotificationInboxDuplicate(NotificationInboxDuplicateKind kind, NotificationInboxRow inbox, String conflictCode) {
        /**
         * 构造没有重复事实的结果，供首次消息进入正常接纳路径。
         */
        public static NotificationInboxDuplicate none() { return new NotificationInboxDuplicate(NotificationInboxDuplicateKind.NONE, null, null); }
    }
