package com.flowdesk.alert.service;
import com.flowdesk.alert.recovery.AlertRecoveryView;
import java.util.*;
import java.time.Instant;
/**
 * 当前租户告警恢复记录的只读查询契约；支持受限筛选、签名游标分页与单条恢复详情。
 */
public interface AlertRecoveryQueryService {
    /**
     * 在当前租户内按方向、状态、来源和时间范围分页读取恢复记录，返回绑定筛选条件的续查游标。
     */
    Page list(String direction,String state,Boolean dead,String error,Instant from,Instant to,int size,String token,String sort);
    /**
     * 读取当前租户单条告警恢复记录并映射为对外视图，跨路由或不存在的记录统一拒绝。
     */
    AlertRecoveryView get(UUID id);
    /**
     * 查询页的不可变结果，组合当前页记录、下一页游标及规范化排序，不额外声明全量总数。
     */
    record Page(List<AlertRecoveryView> items,String next,String sort) {}
}
