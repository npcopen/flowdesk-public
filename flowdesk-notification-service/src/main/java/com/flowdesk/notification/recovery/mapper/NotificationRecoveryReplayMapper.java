package com.flowdesk.notification.recovery.mapper;
/**
 * 通知恢复专用数据库账户使用的重放租约接口，不参与运行时 Mapper 扫描。
 * 继承公共恢复 SQL，扫描到期人工重放请求并使用乐观锁领取执行租约。
 */
public interface NotificationRecoveryReplayMapper extends com.flowdesk.common.recovery.RecoveryReplayMapper { }
