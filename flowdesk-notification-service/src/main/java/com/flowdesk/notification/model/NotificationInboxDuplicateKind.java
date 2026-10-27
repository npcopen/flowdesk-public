package com.flowdesk.notification.model;
import java.util.*;
import java.time.Instant;

    /**
     * 通知内部协作类型，组合当前处理所需的结构与行为，供所在用例的依赖链调用。
     */
    public enum NotificationInboxDuplicateKind { NONE, TERMINAL, RECOVERABLE, CONFLICT }
