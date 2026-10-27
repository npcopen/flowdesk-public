package com.flowdesk.iam.security;

import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.iam.mapper.CredentialMapper;
import com.flowdesk.iam.service.ServiceJwtIssuanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 为本地及集成环境签发服务 JWT 文件；须显式启用并配置租户与服务映射。
 * 全部映射校验通过后才写文件，只记录路径和到期时间；默认签发后退出，常驻模式通过临时文件替换轮换凭证。
 */
@Component
public class ServiceJwtBootstrapRunner implements ApplicationRunner, ApplicationContextAware,
        Ordered, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ServiceJwtBootstrapRunner.class);

    private final ServiceJwtBootstrapProperties properties;
    private final ServiceJwtIssuanceService issuanceService;
    private final CredentialMapper credentialRepository;
    private final Clock clock;
    private ApplicationContext applicationContext;
    private ScheduledExecutorService rotationExecutor;
    private List<Mapping> configuredMappings = List.of();

    /**
     * 组装当前组件依赖并委托完整构造方法；使用 UTC 系统时钟等默认运行配置，显式依赖版本可用于确定性测试。
     */
    public ServiceJwtBootstrapRunner(ServiceJwtBootstrapProperties properties,
                                     ServiceJwtIssuanceService issuanceService,
                                     CredentialMapper credentialRepository) {
        this(properties, issuanceService, credentialRepository, Clock.systemUTC());
    }

    /**
     * 注入 JWT 文件签发配置、签发能力、租户存在性查询及可替换时钟，供启动预检和持续轮换使用。
     */
    @Autowired
    public ServiceJwtBootstrapRunner(ServiceJwtBootstrapProperties properties,
                                     ServiceJwtIssuanceService issuanceService,
                                     CredentialMapper credentialRepository,
                                     Clock clock) {
        this.properties = properties;
        this.issuanceService = issuanceService;
        this.credentialRepository = credentialRepository;
        this.clock = clock;
    }

    /**
     * 保存 Spring 应用上下文，供一次性 JWT 签发完成后有序退出应用。
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 返回该启动任务在 Spring 启动回调中的顺序，使所需基础组件先完成初始化。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * 显式启用后校验全部租户服务映射，签发并写入 JWT 文件；根据配置退出应用或启动定期轮换。
     */
    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!properties.isEnabled()) {
            return;
        }

        List<Mapping> mappings = parseMappings(properties.getMappings());
        // 先校验全部映射：不存在的租户不能获得签名服务身份，也避免配置错误造成部分文件已生成。
        for (Mapping mapping : mappings) {
            if (!credentialRepository.tenantExists(mapping.tenantId())) {
                throw new IllegalArgumentException(
                        "service-jwt-bootstrap tenant does not exist: " + mapping.tenantId());
            }
        }
        configuredMappings = mappings;
        List<IssuedToken> issuedTokens = issueAll(mappings);
        validateRefreshInterval(issuedTokens);
        writeAll(issuedTokens);

        if (properties.isExitAfterIssue()) {
            SpringApplication.exit(applicationContext, () -> 0);
            return;
        }
        rotationExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "flowdesk-service-jwt-rotation");
            thread.setDaemon(true);
            return thread;
        });
        long delayMillis = properties.getRefreshInterval().toMillis();
        rotationExecutor.scheduleWithFixedDelay(this::rotateSafely,
                delayMillis, delayMillis, TimeUnit.MILLISECONDS);
        log.info("FlowDesk service JWT rotation active for {} mapping(s), interval {}",
                mappings.size(), properties.getRefreshInterval());
    }

    /**
     * 为全部已校验映射签发服务 JWT，先收集结果供统一校验与写入，避免边签发边产生部分输出。
     */
    private List<IssuedToken> issueAll(List<Mapping> mappings) {
        List<IssuedToken> issuedTokens = new ArrayList<>(mappings.size());
        for (Mapping mapping : mappings) {
            issuedTokens.add(new IssuedToken(mapping, issuanceService.issue(
                    mapping.tenantId(), mapping.serviceId())));
        }
        return List.copyOf(issuedTokens);
    }

    /**
     * 常驻轮换时要求刷新间隔短于最早 JWT 的剩余有效期，避免凭证过期前没有下一次刷新机会。
     */
    private void validateRefreshInterval(List<IssuedToken> issuedTokens) {
        if (properties.isExitAfterIssue()) {
            return;
        }
        Instant earliestExpiry = issuedTokens.stream()
                .map(token -> token.issued().expiresAt())
                .min(Instant::compareTo)
                .orElseThrow();
        // 刷新周期必须短于最早令牌的剩余有效期，保证常驻模式有机会在过期前更新凭证。
        Duration remaining = Duration.between(now(), earliestExpiry);
        if (properties.getRefreshInterval().compareTo(remaining) >= 0) {
            throw new IllegalArgumentException(
                    "service-jwt-bootstrap refresh-interval must be shorter than the service JWT TTL");
        }
    }

    /**
     * 重新签发并替换所有服务令牌文件，记录轮换失败并保留上一轮仍可读取的凭证文件。
     */
    private void rotateSafely() {
        try {
            writeAll(issueAll(configuredMappings));
        } catch (Exception e) {
            log.error("FlowDesk service JWT rotation failed; retaining the previous token files", e);
        }
    }

    /**
     * 读取当前时间并截断到微秒，使写入与数据库时间精度一致。
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 创建输出目录并按租户与服务命名写入令牌文件，只记录路径及到期时间。
     */
    private void writeAll(List<IssuedToken> issuedTokens) throws IOException {
        Path outputDirectory = Path.of(properties.getOutputDirectory()).toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);
        for (IssuedToken issuedToken : issuedTokens) {
            Mapping mapping = issuedToken.mapping();
            Path tokenFile = outputDirectory.resolve(
                    mapping.tenantId() + "-" + mapping.serviceId() + ".jwt");
            writeAtomically(tokenFile, issuedToken.issued().token() + System.lineSeparator());
            log.info("FlowDesk service JWT written to {}; expires at {}",
                    tokenFile, issuedToken.issued().expiresAt());
        }
    }

    /**
     * 先写同目录临时文件并收紧访问权限，再替换目标文件；文件系统不支持原子移动时使用普通替换并清理临时文件。
     */
    private static void writeAtomically(Path tokenFile, String content) throws IOException {
        Path temporary = Files.createTempFile(tokenFile.getParent(),
                tokenFile.getFileName().toString() + ".", ".tmp");
        try {
            // 先写同目录临时文件再替换目标，缩短调用方读到未完整令牌的窗口。
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            restrictToOwner(temporary);
            try {
                Files.move(temporary, tokenFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, tokenFile, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(tokenFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 停止服务 JWT 轮换线程，避免应用关闭后继续签发或写入凭证。
     */
    @Override
    public void destroy() {
        if (rotationExecutor != null) {
            rotationExecutor.shutdownNow();
        }
    }

    /**
     * 解析 tenantId:serviceId 映射并拒绝空配置、格式错误和重复项，返回不可变映射列表。
     */
    private static List<Mapping> parseMappings(List<String> rawMappings) {
        if (rawMappings == null || rawMappings.isEmpty()) {
            throw new IllegalArgumentException(
                    "flowdesk.iam.service-jwt-bootstrap.mappings must contain at least one tenantId:serviceId entry");
        }
        List<Mapping> mappings = new ArrayList<>(rawMappings.size());
        Set<Mapping> uniqueMappings = new HashSet<>();
        for (String raw : rawMappings) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("service-jwt-bootstrap mapping must not be blank");
            }
            String[] parts = raw.split(":", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException(
                        "service-jwt-bootstrap mapping must use tenantId:serviceId form: " + raw);
            }
            UUID tenantId = UUID.fromString(parts[0].trim());
            Mapping mapping = new Mapping(tenantId, parts[1].trim());
            if (!uniqueMappings.add(mapping)) {
                throw new IllegalArgumentException("duplicate service-jwt-bootstrap mapping: " + raw);
            }
            mappings.add(mapping);
        }
        return List.copyOf(mappings);
    }

    /**
     * 在支持 POSIX 权限的文件系统上限制令牌文件为所有者读写，其他平台沿用其原生访问控制。
     */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 等文件系统不支持 POSIX 权限，保留平台自身的访问控制。
        }
    }

    /**
     * 经格式和重复检查的租户与服务映射，决定服务 JWT 文件的签发身份与目标文件名。
     */
    private record Mapping(UUID tenantId, String serviceId) {
    }

    /**
     * 待写入文件的一组服务映射及签发结果，便于先完成全部签发与有效期校验再统一写入。
     */
    private record IssuedToken(Mapping mapping, IssuedJwt issued) {
    }
}
