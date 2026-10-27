package com.flowdesk.common.mybatis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查生产 SQL 的有类型 MyBatis 访问边界，防止旁路数据访问重新进入业务源码。
 */
class ProductionSqlBoundaryTest {
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?:JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient|JdbcOperations|"
                    + "NamedParameterJdbcOperations|SimpleJdbcInsert|SimpleJdbcCall|JdbcDaoSupport)");
    private static final Pattern DIRECT_SQL = Pattern.compile(
            "(?:prepareStatement|prepareCall|createStatement)\\s*\\(");
    private static final String HEALTH_PROBE =
            "flowdesk-common/src/main/java/com/flowdesk/common/web/ServiceHealthController.java";

    /**
     * 验证生产 SQL 沿有类型的 MyBatis Mapper 执行，阻止重新引入绕过持久化边界的访问方式。
     */
    @Test
    void productionSqlUsesTypedMyBatisMappers() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("flowdesk-common"))) {
            root = root.getParent();
        }
        assertThat(root).as("FlowDesk repository root").isNotNull();
        var violations = new ArrayList<String>();
        try (var modules = Files.list(root)) {
            for (Path module : modules.filter(p -> p.getFileName().toString().startsWith("flowdesk-")).toList()) {
                Path source = module.resolve("src/main/java");
                if (!Files.isDirectory(source)) continue;
                try (var files = Files.walk(source)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        String code = Files.readString(file, StandardCharsets.UTF_8);
                        String relative = root.relativize(file).toString().replace('\\', '/');
                        if (FORBIDDEN.matcher(code).find()
                                || (!HEALTH_PROBE.equals(relative) && DIRECT_SQL.matcher(code).find())) {
                            violations.add(relative);
                        }
                    }
                }
            }
        }
        assertThat(violations).as("Production SQL must use typed MyBatis mappers; JDBC is for tests only")
                .isEmpty();
    }
}
