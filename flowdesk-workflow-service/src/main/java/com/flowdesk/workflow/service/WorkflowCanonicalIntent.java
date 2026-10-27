package com.flowdesk.workflow.service;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.TreeMap;

/**
 * 生成规范 JSON 意图摘要的输入，用于持久化的工作流幂等审计锚点。
 */
public final class WorkflowCanonicalIntent {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 禁止直接实例化工具容器，相关静态规则与转换由调用方按业务需要复用。
     */
    private WorkflowCanonicalIntent() {
    }

    /**
     * 创建包含摘要与效果键的规范化意图，调用方用其核对幂等请求的一致性。
     */
    public static String of(String... fields) {
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException("Intent fields must be key/value pairs");
        }
        Map<String, String> sorted = new TreeMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            sorted.put(fields[index], fields[index + 1]);
        }
        return JSON.writeValueAsString(sorted);
    }
}
