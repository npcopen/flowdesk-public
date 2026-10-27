package com.flowdesk.notification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import com.flowdesk.common.recovery.RecoveryReplayResumer;
import com.flowdesk.notification.recovery.NotificationRecoveryReplayService;
import com.flowdesk.notification.mapper.*;
import com.flowdesk.notification.recovery.mapper.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import javax.sql.DataSource;

/**
 * 通知配置与组件装配入口，按声明的开关、连接和依赖约束建立一致的运行链路。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationRecoveryDataSourceProperties.class)
public class NotificationRecoveryDatabaseConfiguration {
    /**
     * 装配持久化映射，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean("notificationPrivilegedRecoveryMapper")
    NotificationRecoveryMapper notificationPrivilegedRecoveryMapper(
            @Qualifier("notificationRecoverySqlSessionTemplate") SqlSessionTemplate session) {
        return session.getMapper(NotificationRecoveryMapper.class);
    }
    /**
     * 装配持久化映射，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean("notificationPrivilegedAuditMapper")
    NotificationAuditMapper notificationPrivilegedAuditMapper(
            @Qualifier("notificationRecoverySqlSessionTemplate") SqlSessionTemplate session) {
        return session.getMapper(NotificationAuditMapper.class);
    }
    /**
     * 装配持久化映射，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean("notificationRecoverySourceMapper")
    NotificationRecoverySourceMapper notificationRecoverySourceMapper(
            @Qualifier("notificationRecoverySqlSessionTemplate") SqlSessionTemplate session) {
        return session.getMapper(NotificationRecoverySourceMapper.class);
    }
    /**
     * 装配持久化映射，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean("notificationRecoveryReplayMapper")
    NotificationRecoveryReplayMapper notificationRecoveryReplayMapper(
            @Qualifier("notificationRecoverySqlSessionTemplate") SqlSessionTemplate session) {
        return session.getMapper(NotificationRecoveryReplayMapper.class);
    }
    /**
     * 装配重启恢复扫描器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean
    RecoveryReplayResumer notificationRecoveryReplayResumer(
            @Qualifier("notificationRecoveryReplayMapper") NotificationRecoveryReplayMapper mapper,
            @Qualifier("notificationRecoveryTransactionManager") PlatformTransactionManager transactionManager,
            NotificationRecoveryReplayService service,
            @Value("${flowdesk.recovery.resume-enabled:true}") boolean enabled,
            @Value("${flowdesk.notification.recovery.enabled:false}") boolean recoveryEnabled) {
        return new RecoveryReplayResumer(mapper, transactionManager, service::resume, enabled && recoveryEnabled);
    }
    /**
     * 装配事务管理器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean(name = "transactionManager") @Primary
    PlatformTransactionManager notificationRuntimeTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
    /**
     * 装配恢复数据源封装，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean(name = "notificationRecoveryDatabase")
    NotificationRecoveryDatabase notificationRecoveryDatabase(NotificationRecoveryDataSourceProperties properties) {
        return new NotificationRecoveryDatabase(properties.buildDataSource());
    }
    /**
     * 装配事务管理器，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean(name = "notificationRecoveryTransactionManager")
    PlatformTransactionManager notificationRecoveryTransactionManager(NotificationRecoveryDatabase database) {
        return new DataSourceTransactionManager(database.dataSource());
    }
    /**
     * 通知内部协作类型，组合当前处理所需的结构与行为，供所在用例的依赖链调用。
     */
    static final class NotificationRecoveryDatabase {
        private final DataSource dataSource;
        /**
         * 初始化组件依赖与运行参数，使后续调用使用一致的协作对象。
         */
        NotificationRecoveryDatabase(DataSource dataSource) { this.dataSource = dataSource; }
        /**
         * 返回恢复专用数据源，使相关映射与事务使用一致的连接来源。
         */
        DataSource dataSource() { return dataSource; }
    }
}
