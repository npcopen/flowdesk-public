package com.flowdesk.iam.service.impl;


import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.TreeMap;

/**
 * 按键名排序生成规范 JSON，使语义相同的 IAM 请求具有相同意图摘要。
 */
final class CanonicalIntent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private CanonicalIntent() {
    }

    /**
     * 将成对字段名和值编码为规范化业务意图，拒绝不完整键值对，供幂等内容摘要统一使用。
     */
    static String of(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Intent fields must be key/value pairs");
        }
        Map<String, String> sorted = new TreeMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            sorted.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return MAPPER.writeValueAsString(sorted);
    }
}
