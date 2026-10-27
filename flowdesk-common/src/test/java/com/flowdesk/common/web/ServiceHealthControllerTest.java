package com.flowdesk.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
  * 验证服务本地存活与就绪探针；存活不依赖数据库，就绪按配置检查数据库和迁移且不泄露底层失败细节。
  */
 class ServiceHealthControllerTest {

    private static final UUID CORRELATION =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    /**
     * 验证存活探针始终返回 UP 且不访问数据源，使进程存活与数据库依赖解耦。
     */
    @Test
    void livenessIsAlwaysUpAndDoesNotTouchDatasource() {
        ObjectProvider<DataSource> provider = mock();
        ServiceHealthController controller = new ServiceHealthController(
                provider, new ServiceHealthProperties());

        ResponseEntity<SuccessEnvelope<ProbeStatus>> response = controller.liveness(request(null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().probe()).isEqualTo(ProbeStatus.Probe.LIVENESS);
        assertThat(response.getBody().data().status()).isEqualTo(ProbeStatus.Status.UP);
        assertThat(response.getBody().meta().correlationId().toString())
                .isEqualTo(response.getHeaders().getFirst("X-Correlation-Id"));
        verifyNoInteractions(provider);
    }

    /**
     * 验证有效关联标识在响应头和统一信封中一致保留。
     */
    @Test
    void validCorrelationIsPropagatedAcrossHeaderAndEnvelope() {
        ObjectProvider<DataSource> provider = mock();
        ServiceHealthController controller = new ServiceHealthController(
                provider, new ServiceHealthProperties());

        ResponseEntity<SuccessEnvelope<ProbeStatus>> response = controller.liveness(request(CORRELATION));

        assertThat(response.getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo(CORRELATION.toString());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().meta().correlationId()).isEqualTo(CORRELATION);
        verifyNoInteractions(provider);
    }

    /**
     * 验证配置不要求数据库时就绪探针不访问数据源，并仍返回正确就绪结构。
     */
    @Test
    void readinessCanBeConfiguredWithoutARequiredDatabase() {
        ObjectProvider<DataSource> provider = mock();
        ServiceHealthProperties properties = new ServiceHealthProperties();
        properties.setDatabaseRequired(false);
        ServiceHealthController controller = new ServiceHealthController(provider, properties);

        ResponseEntity<SuccessEnvelope<ProbeStatus>> response = controller.readiness(request(CORRELATION));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().probe()).isEqualTo(ProbeStatus.Probe.READINESS);
        assertThat(response.getBody().data().status()).isEqualTo(ProbeStatus.Status.UP);
        verifyNoInteractions(provider);
    }

    /**
     * 验证必须数据库但数据源缺失时就绪失败，不能误报可接流量。
     */
    @Test
    void readinessFailsClosedWhenDatasourceIsUnavailable() {
        ObjectProvider<DataSource> provider = mock();
        when(provider.getIfAvailable()).thenReturn(null);
        ServiceHealthController controller = new ServiceHealthController(
                provider, new ServiceHealthProperties());

        assertThatThrownBy(() -> controller.readiness(request(CORRELATION)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.statusValue()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("SERVICE_NOT_READY");
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.headers()).containsEntry("Cache-Control", "no-store");
                    assertThat(exception.getMessage()).isEqualTo("当前服务尚未准备好接收流量。");
                    assertThat(exception.toString()).doesNotContain("jdbc", "password", "flyway");
                });
    }

    /**
     * 验证数据源提供器异常被统一映射为服务未就绪错误。
     */
    @Test
    void readinessMapsDatasourceProviderFailureToServiceNotReady() {
        ObjectProvider<DataSource> provider = mock();
        when(provider.getIfAvailable()).thenThrow(new IllegalStateException(
                "datasource bootstrap failed for jdbc:mysql://db.internal"));
        ServiceHealthController controller = new ServiceHealthController(
                provider, new ServiceHealthProperties());

        assertThatThrownBy(() -> controller.readiness(request(CORRELATION)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.statusValue()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("SERVICE_NOT_READY");
                    assertThat(exception.getMessage()).isEqualTo("当前服务尚未准备好接收流量。");
                });
    }

    /**
     * 验证就绪成功同时要求数据库可访问和迁移状态完整成功。
     */
    @Test
    void readinessRequiresHealthyDatabaseAndSuccessfulMigration() throws Exception {
        ObjectProvider<DataSource> provider = mock();
        DataSource dataSource = mock();
        Connection connection = mock();
        PreparedStatement selectOne = mock();
        PreparedStatement migration = mock();
        ResultSet selectResult = mock();
        ResultSet migrationResult = mock();

        when(provider.getIfAvailable()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(connection.prepareStatement("SELECT 1")).thenReturn(selectOne);
        when(selectOne.executeQuery()).thenReturn(selectResult);
        when(selectResult.next()).thenReturn(true);
        when(selectResult.getInt(1)).thenReturn(1);
        when(connection.prepareStatement(
                "SELECT 1 FROM flyway_schema_history WHERE success = 1 LIMIT 1"))
                .thenReturn(migration);
        when(migration.executeQuery()).thenReturn(migrationResult);
        when(migrationResult.next()).thenReturn(true);

        ServiceHealthController controller = new ServiceHealthController(
                provider, new ServiceHealthProperties());
        ResponseEntity<SuccessEnvelope<ProbeStatus>> response = controller.readiness(request(CORRELATION));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().probe()).isEqualTo(ProbeStatus.Probe.READINESS);
        assertThat(response.getBody().data().status()).isEqualTo(ProbeStatus.Status.UP);
        assertThat(response.getBody().meta().correlationId()).isEqualTo(CORRELATION);
        verify(connection).isValid(2);
        verify(connection).prepareStatement("SELECT 1");
        verify(connection).prepareStatement(
                "SELECT 1 FROM flyway_schema_history WHERE success = 1 LIMIT 1");
    }

    /**
     * 验证数据库失败导致就绪拒绝，同时对外响应不泄露连接或 SQL 错误细节。
     */
    @Test
    void readinessFailsClosedWithoutLeakingDatabaseFailureDetails() throws Exception {
        ObjectProvider<DataSource> provider = mock();
        DataSource dataSource = mock();
        when(provider.getIfAvailable()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenThrow(new SQLException(
                "access denied for user password=super-secret jdbc:mysql://db.internal"));
        ServiceHealthController controller = new ServiceHealthController(
                provider, new ServiceHealthProperties());

        assertThatThrownBy(() -> controller.readiness(request(CORRELATION)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.statusValue()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("SERVICE_NOT_READY");
                    assertThat(exception.getMessage()).isEqualTo("当前服务尚未准备好接收流量。");
                    assertThat(exception.toString()).doesNotContain(
                            "super-secret", "db.internal", "jdbc:mysql");
                });
    }

    /**
     * 验证关闭迁移检查后不会访问迁移历史表，避免引入未要求的数据库依赖。
     */
    @Test
    void readinessDoesNotQueryMigrationTableWhenMigrationCheckIsDisabled() throws Exception {
        ObjectProvider<DataSource> provider = mock();
        DataSource dataSource = mock();
        Connection connection = mock();
        PreparedStatement selectOne = mock();
        ResultSet selectResult = mock();

        ServiceHealthProperties properties = new ServiceHealthProperties();
        properties.setMigrationRequired(false);
        when(provider.getIfAvailable()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(connection.prepareStatement("SELECT 1")).thenReturn(selectOne);
        when(selectOne.executeQuery()).thenReturn(selectResult);
        when(selectResult.next()).thenReturn(true);
        when(selectResult.getInt(1)).thenReturn(1);

        ServiceHealthController controller = new ServiceHealthController(provider, properties);
        ResponseEntity<SuccessEnvelope<ProbeStatus>> response = controller.readiness(request(CORRELATION));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(connection, never()).prepareStatement(
                "SELECT 1 FROM flyway_schema_history WHERE success = 1 LIMIT 1");
    }

    /**
     * 构造带可选关联标识属性的模拟 Servlet 请求，供探针响应头与信封一致性测试使用。
     */
    private static HttpServletRequest request(UUID correlation) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (correlation != null) {
            request.setAttribute(FlowDeskRequestAttributes.CORRELATION_ID, correlation);
        }
        return request;
    }
}
