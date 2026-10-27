package com.flowdesk.common.mybatis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查生产源码分层契约，避免控制器、服务与持久化职责在后续重构中重新混杂。
 */
class ProductionLayerBoundaryTest {
    private static final Set<String> MODULES = Set.of("iam", "ticket", "workflow", "alert", "notification");
    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
    private static final Pattern CONTROLLER = Pattern.compile("@(?:RestController|Controller)\\b");
    private static final Pattern PERSISTENCE_IMPORT = Pattern.compile(
            "\\bimport\\s+(?:static\\s+)?(?:com\\.flowdesk\\.[\\w.]*\\.(?:mapper|repository|impl)\\.[\\w.*]+|"
                    + "org\\.springframework\\.jdbc\\.[\\w.*]+|org\\.apache\\.ibatis\\.[\\w.*]+|java\\.sql\\.[\\w.*]+)\\s*;");

    /**
     * 验证生产源码遵守控制器、服务及持久化的既定分层边界，防止重构引入越层调用。
     */
    @Test
    void businessSourcesFollowTraditionalLayerBoundaries() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("flowdesk-common"))) {
            root = root.getParent();
        }
        assertThat(root).as("FlowDesk repository root").isNotNull();
        var violations = new ArrayList<String>();
        for (String module : MODULES) {
            Path source = root.resolve("flowdesk-" + module + "-service/src/main/java");
            try (var files = Files.walk(source)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String code = Files.readString(file, StandardCharsets.UTF_8);
                    var declaration = PACKAGE.matcher(code);
                    if (!declaration.find()) continue;
                    String packageName = declaration.group(1);
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    Set<String> segments = Set.of(packageName.split("\\."));
                    String filename = file.getFileName().toString();
                    if (filename.endsWith("Repository.java") || segments.contains("repository")) {
                        violations.add(relative + ": Repository intermediary must be removed");
                    }
                    if (segments.contains("api")) {
                        violations.add(relative + ": HTTP entry points use controller, contracts use dto");
                    }
                    if (filename.endsWith("ServiceImpl.java") && !segments.contains("impl")) {
                        violations.add(relative + ": Service implementation must live in impl");
                    }
                    if (CONTROLLER.matcher(code).find()) {
                        if (!packageName.equals("com.flowdesk." + module + ".controller")) {
                            violations.add(relative + ": Controller must live in the controller package");
                        }
                        if (PERSISTENCE_IMPORT.matcher(code).find()) {
                            violations.add(relative + ": Controller must depend on business Service interfaces");
                        }
                    }
                }
            }
        }
        assertThat(violations).as("Production three-layer architecture").isEmpty();
    }
}
