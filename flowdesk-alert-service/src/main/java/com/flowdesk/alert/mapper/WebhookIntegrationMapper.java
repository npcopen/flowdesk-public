package com.flowdesk.alert.mapper;
import com.flowdesk.alert.model.*;

import org.apache.ibatis.annotations.Param;
import java.util.*;
import java.time.Instant;
import com.flowdesk.alert.event.*;
import com.flowdesk.alert.recovery.*;
import com.flowdesk.alert.webhook.*;
import com.flowdesk.alert.service.*;

/**
 * Webhook 认证候选查询接口，按 API Key 选择器摘要定位监控集成配置。
 * 认证尚未确定租户，返回所属租户、密钥校验、签名版本及有效期供调用方核验。
 */
public interface WebhookIntegrationMapper {
    /**
     * 按 API Key 选择器摘要读取候选配置，含租户、状态、校验器、签名版本及有效期，按租户和集成标识排序；最终认证由调用方执行。
     *
     * @return 符合条件的结果列表，未命中时为空列表。
     */
    List<WebhookIntegrationCandidate> findCandidates(@Param("selectorHash") byte[] selectorHash);
}
