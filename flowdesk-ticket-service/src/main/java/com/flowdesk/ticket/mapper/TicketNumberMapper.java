package com.flowdesk.ticket.mapper;

import com.flowdesk.ticket.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
/**
 * 工单编号计数器接口，以租户和业务日期划分序列并在事务内分配编号。
 * 具体 SQL 由 MyBatis 提供；更新操作遵循对应语句的状态和版本条件。
 */
@Mapper
public interface TicketNumberMapper {
    /**
     * 在已开启的事务中递增并锁定读取当日计数器；缺少事务或返回非正数时抛出异常。
     *
     * @param tenantId 数据所属租户标识
     * @return 当前租户和业务日期对应的序列值
     */
    default long nextTicketSequence(UUID tenantId, LocalDate businessDate, Instant now) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Ticket number allocation requires an active transaction");
        incrementSequence(tenantId, businessDate, now);
        Long value = lockSequence(tenantId, businessDate);
        if (value == null || value <= 0) throw new IllegalStateException("Ticket number counter did not return a positive value");
        return value;
    }
    /**
     * 以租户和业务日期初始化序列为 1；计数器已存在时原子递增。
     *
     * @param tenantId 数据所属租户标识
     * @return 数据库报告的受影响行数
     */
    int incrementSequence(@Param("tenantId") UUID tenantId, @Param("businessDate") LocalDate businessDate, @Param("now") Instant now);
    /**
     * 锁定并读取指定租户在业务日期下的当前序列，需与递增操作处于同一事务。
     * 查询使用 FOR UPDATE 行锁，应在调用方事务内执行。
     *
     * @param tenantId 数据所属租户标识
     * @return 当前租户和业务日期对应的序列值
     */
    Long lockSequence(@Param("tenantId") UUID tenantId, @Param("businessDate") LocalDate businessDate);
}
