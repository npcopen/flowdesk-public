package com.flowdesk.alert.mapper;
import com.flowdesk.alert.model.*;
import java.util.List;
import com.flowdesk.alert.service.AlertCycleReconciler;
/**
 * 告警周期补偿扫描接口，为周期协调器提供需要继续归并的候选指纹。
 * 扫描覆盖各租户，使用有界批次限制单次任务数据量。
 */
public interface AlertCycleMapper {
    /**
     * 扫描已恢复且当前没有活动实例、但仍有后续未归并 FIRING 周期的租户和指纹；按最近恢复时间稳定排序，最多返回 100 条。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<AlertCycleCandidate> findCandidates();
}
