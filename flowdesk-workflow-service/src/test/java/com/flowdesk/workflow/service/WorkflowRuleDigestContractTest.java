package com.flowdesk.workflow.service;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则摘要契约测试，保证规范演示数据的摘要与两侧迁移常量一致。
 */
class WorkflowRuleDigestContractTest {
    private static final UUID DEFINITION =
            UUID.fromString("0848bf3c-d603-5ece-9529-f9bba79f1498");
    private static final UUID LEVEL_ONE =
            UUID.fromString("33f0b408-5548-5f2b-9865-f1dd288c0493");
    private static final UUID LEVEL_TWO =
            UUID.fromString("fdecef4b-2a69-538e-9fa5-249048115d3d");

    /**
     * 验证里程碑六演示摘要与两侧前向迁移常量一致，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void canonicalM6DemoDigestsMatchBothForwardMigrationConstants() {
        assertThat(digest("12d7dca8-380c-5a6c-84ae-953502959486"))
                .isEqualTo("a35fc9b13fe585e5a11c002ec4bd3e52e575b33aa77dded9360e8c5d809102e6");
        assertThat(digest("978d86fe-2cb8-5d4b-9968-e2f738a5c3e2"))
                .isEqualTo("b5f88cb00a46437c3340436e92ce37ded28b25bb919650ba7c95d059f2972b0f");
    }

    /**
     * 按固定字段顺序计算规则摘要，使校验与发布使用一致的内容依据。
     */
    private static String digest(String tenant) {
        return HexFormat.of().formatHex(WorkflowRuleDigest.digest(UUID.fromString(tenant),
                DEFINITION, "SERVICE_RISK_LEVEL", "EQUALS", "STRING", "HIGH",
                LEVEL_ONE, LEVEL_TWO));
    }
}
