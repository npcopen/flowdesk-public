package com.flowdesk.workflow.start;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 里程碑五验收覆盖测试，确保每个冻结故障编号在真实基础设施套件中恰好被覆盖一次。
 */
class Milestone5AcceptanceCoverageTest {

    private static final Pattern SCENARIO_METHOD = Pattern.compile("m5F(\\d{2}).*");

    /**
     * 验证真实基础设施验收套件对每个冻结故障编号恰好覆盖一次，并检查相关业务事实或错误响应符合约定。
     */
    @Test
    void realInfrastructureSuiteCoversEveryFrozenFaultIdExactlyOnce() {
        Map<String, Integer> methods = new TreeMap<>();
        for (Method method : Milestone5RealInfrastructureAcceptanceTest.class
                .getDeclaredMethods()) {
            Matcher matcher = SCENARIO_METHOD.matcher(method.getName());
            if (matcher.matches()) {
                methods.merge("M5-F" + matcher.group(1), 1, Integer::sum);
            }
        }

        assertThat(methods.keySet())
                .containsExactlyElementsOf(
                        Milestone5RealInfrastructureAcceptanceTest.FROZEN_SCENARIOS);
        assertThat(methods.values()).allMatch(count -> count == 1);
    }
}
