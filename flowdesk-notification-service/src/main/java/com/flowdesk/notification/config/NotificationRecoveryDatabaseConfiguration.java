package com.flowdesk.notification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Keeps the recovery connection out of Spring Boot's primary datasource
 * auto-configuration, so normal Notification consumption remains bound to the
 * runtime account while raw replay reads use a distinct principal.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationRecoveryDataSourceProperties.class)
public class NotificationRecoveryDatabaseConfiguration {

    /**
     * Defining the recovery manager makes Boot's conditional JDBC transaction
     * auto-configuration back off. Keep the runtime manager explicit so the
     * main consumer and human APIs always transact against the normal service
     * datasource rather than the raw-source recovery connection.
     */
    @Bean(name = "transactionManager")
    @Primary
    PlatformTransactionManager notificationRuntimeTransactionManager(
            DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * The recovery template is itself a JdbcTemplate bean, which makes Boot's
     * conditional JdbcTemplate auto-configuration back off.  Name and expose
     * the runtime template explicitly so normal APIs and consumers never bind
     * to the recovery-only principal.
     */
    @Bean(name = "jdbcTemplate")
    @Primary
    JdbcTemplate notificationRuntimeJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "notificationRecoveryDatabase")
    NotificationRecoveryDatabase notificationRecoveryDatabase(
            NotificationRecoveryDataSourceProperties properties) {
        return new NotificationRecoveryDatabase(properties.buildDataSource());
    }

    @Bean(name = "notificationRecoveryJdbcTemplate")
    JdbcTemplate notificationRecoveryJdbcTemplate(NotificationRecoveryDatabase database) {
        return new JdbcTemplate(database.dataSource());
    }

    @Bean(name = "notificationRecoveryTransactionManager")
    PlatformTransactionManager notificationRecoveryTransactionManager(
            NotificationRecoveryDatabase database) {
        return new DataSourceTransactionManager(database.dataSource());
    }

    static final class NotificationRecoveryDatabase {
        private final javax.sql.DataSource dataSource;

        NotificationRecoveryDatabase(javax.sql.DataSource dataSource) {
            this.dataSource = dataSource;
        }

        javax.sql.DataSource dataSource() {
            return dataSource;
        }
    }
}
