package com.flowdesk.ticket.model;
/**
 * 数据库工单范围，区分申请人、处理人、混合可见与全租户。
 */
public enum TicketScope { REQUESTER, ASSIGNEE, VISIBLE, TENANT }
