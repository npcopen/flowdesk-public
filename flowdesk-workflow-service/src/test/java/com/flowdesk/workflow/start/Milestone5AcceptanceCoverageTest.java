package com.flowdesk.workflow.start;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Fast guard proving that the opt-in real-infrastructure suite has one executable case per F-ID. */
class Milestone5AcceptanceCoverageTest {

    private static final Pattern SCENARIO_METHOD = Pattern.compile("m5F(\\d{2}).*");

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
