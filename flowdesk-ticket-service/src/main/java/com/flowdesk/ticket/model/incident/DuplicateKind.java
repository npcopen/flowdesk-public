package com.flowdesk.ticket.model.incident;

/**
 * 消息重复类型，区分首次处理、一致重放与身份冲突。
 */
public enum DuplicateKind { NONE, TERMINAL, RECOVERABLE, CONFLICT }
