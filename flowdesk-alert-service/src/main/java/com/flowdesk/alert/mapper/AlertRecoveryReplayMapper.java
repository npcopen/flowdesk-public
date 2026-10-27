package com.flowdesk.alert.mapper;

/**
 * 告警运行时数据库账户使用的持久化重放租约接口。
 * 继承公共恢复 SQL，供后台任务扫描到期人工收件箱重放请求并以版本条件抢占租约。
 */
public interface AlertRecoveryReplayMapper extends com.flowdesk.common.recovery.RecoveryReplayMapper { }
