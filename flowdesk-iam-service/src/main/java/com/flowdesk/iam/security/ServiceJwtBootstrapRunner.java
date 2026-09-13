package com.flowdesk.iam.security;

import com.flowdesk.common.security.IssuedJwt;
import com.flowdesk.iam.repository.IamRepository;
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
 * File-backed service JWT minter for local and integration environments.
 *
 * <p>Activate explicitly with {@code flowdesk.iam.service-jwt-bootstrap.enabled=true}
 * and a mappings list such as
 * {@code 12d7dca8-380c-5a6c-84ae-953502959486:flowdesk-ticket-service}. The runner
 * validates every mapping first (tenant exists in IAM and serviceId is allow-listed),
 * writes only the signed token to the configured output directory (one file per
 * mapping) and prints file paths without token material. The default one-shot mode then
 * stops the process. Continuous mode keeps IAM running and atomically replaces every
 * token file on a fixed delay, allowing callers to re-read fresh credentials without a
 * partial-file window.</p>
 */
@Component
public class ServiceJwtBootstrapRunner implements ApplicationRunner, ApplicationContextAware,
        Ordered, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ServiceJwtBootstrapRunner.class);

    private final ServiceJwtBootstrapProperties properties;
    private final ServiceJwtIssuanceService issuanceService;
    private final IamRepository iamRepository;
    private final Clock clock;
    private ApplicationContext applicationContext;
    private ScheduledExecutorService rotationExecutor;
    private List<Mapping> configuredMappings = List.of();

    public ServiceJwtBootstrapRunner(ServiceJwtBootstrapProperties properties,
                                     ServiceJwtIssuanceService issuanceService,
                                     IamRepository iamRepository) {
        this(properties, issuanceService, iamRepository, Clock.systemUTC());
    }

    @Autowired
    public ServiceJwtBootstrapRunner(ServiceJwtBootstrapProperties properties,
                                     ServiceJwtIssuanceService issuanceService,
                                     IamRepository iamRepository,
                                     Clock clock) {
        this.properties = properties;
        this.issuanceService = issuanceService;
        this.iamRepository = iamRepository;
        this.clock = clock;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!properties.isEnabled()) {
            return;
        }

        List<Mapping> mappings = parseMappings(properties.getMappings());
        // Validate all mappings first: an unknown tenant must never receive a signed
        // tenant-scoped service identity, and a typo must not produce a partial file set.
        for (Mapping mapping : mappings) {
            if (!iamRepository.tenantExists(mapping.tenantId())) {
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

    private List<IssuedToken> issueAll(List<Mapping> mappings) {
        List<IssuedToken> issuedTokens = new ArrayList<>(mappings.size());
        for (Mapping mapping : mappings) {
            issuedTokens.add(new IssuedToken(mapping, issuanceService.issue(
                    mapping.tenantId(), mapping.serviceId())));
        }
        return List.copyOf(issuedTokens);
    }

    private void validateRefreshInterval(List<IssuedToken> issuedTokens) {
        if (properties.isExitAfterIssue()) {
            return;
        }
        Instant earliestExpiry = issuedTokens.stream()
                .map(token -> token.issued().expiresAt())
                .min(Instant::compareTo)
                .orElseThrow();
        Duration remaining = Duration.between(now(), earliestExpiry);
        if (properties.getRefreshInterval().compareTo(remaining) >= 0) {
            throw new IllegalArgumentException(
                    "service-jwt-bootstrap refresh-interval must be shorter than the service JWT TTL");
        }
    }

    private void rotateSafely() {
        try {
            writeAll(issueAll(configuredMappings));
        } catch (Exception e) {
            log.error("FlowDesk service JWT rotation failed; retaining the previous token files", e);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

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

    private static void writeAtomically(Path tokenFile, String content) throws IOException {
        Path temporary = Files.createTempFile(tokenFile.getParent(),
                tokenFile.getFileName().toString() + ".", ".tmp");
        try {
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

    @Override
    public void destroy() {
        if (rotationExecutor != null) {
            rotationExecutor.shutdownNow();
        }
    }

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

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows file systems do not support POSIX permissions.
        }
    }

    private record Mapping(UUID tenantId, String serviceId) {
    }

    private record IssuedToken(Mapping mapping, IssuedJwt issued) {
    }
}
