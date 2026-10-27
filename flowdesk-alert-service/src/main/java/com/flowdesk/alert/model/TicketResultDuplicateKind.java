package com.flowdesk.alert.model;
import java.util.*;
import java.time.Instant;

/**
 * 工单结果去重分类：NONE 为首次接纳，TERMINAL 为已有终态，RECOVERABLE 允许人工重放，CONFLICT 表示相同身份内容不同。
 */
public enum TicketResultDuplicateKind {
        NONE,
        TERMINAL,
        RECOVERABLE,
        CONFLICT
    }
