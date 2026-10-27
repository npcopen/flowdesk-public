package com.flowdesk.ticket.model.reverse;

/**
 * 终态落地分类，区分成功推进、重复终态及不可覆盖冲突。
 */
public enum FinalizationResult { APPLIED, ALREADY_APPLIED }
