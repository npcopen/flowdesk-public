package com.flowdesk.ticket.service;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.TreeMap;

/** 幂等请求的规范 JSON 编码，对字段键排序，使相同业务意图产生稳定摘要。 */
public final class CanonicalIntent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 私有构造方法，阻止工具类实例化，调用方应使用静态入口。
     */
    private CanonicalIntent() {
    }

    /**
     * 按规范顺序组织字段，生成用于幂等判断的稳定业务内容。
     */
    public static String of(String... keyValuePairs) {
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
