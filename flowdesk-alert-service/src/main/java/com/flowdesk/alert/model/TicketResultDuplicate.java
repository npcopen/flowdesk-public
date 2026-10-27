package com.flowdesk.alert.model;
import java.util.*;
import java.time.Instant;

/**
 * 工单结果消息的去重裁决及命中 Inbox；首次接纳没有既有记录，内容冲突时附带明确错误码。
 */
public record TicketResultDuplicate(TicketResultDuplicateKind kind, TicketResultInbox inbox, String code) {
        /**
         * 构造首次接纳的去重结果，不附带已存在的 Inbox 或冲突错误码。
         */
        public static TicketResultDuplicate none() {
            return new TicketResultDuplicate(TicketResultDuplicateKind.NONE, null, null);
        }
    }
