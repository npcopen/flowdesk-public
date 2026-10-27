package com.flowdesk.notification.config;

import com.flowdesk.common.mybatis.UuidBinaryTypeHandler;
import com.flowdesk.notification.recovery.mapper.NotificationRecoveryReplayMapper;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * 通知配置与组件装配入口，按声明的开关、连接和依赖约束建立一致的运行链路。
 */
@Configuration(proxyBeanMethods=false)
@MapperScan(basePackages="com.flowdesk.notification.mapper", sqlSessionFactoryRef="notificationSqlSessionFactory")
public class NotificationMyBatisConfiguration {
    /**
     * 装配会话工厂，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean
    @Primary
    SqlSessionFactory notificationSqlSessionFactory(DataSource dataSource) throws Exception {
        return buildFactory(dataSource, false);
    }
    /**
     * 装配会话工厂，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean
    SqlSessionFactory notificationRecoverySqlSessionFactory(
            NotificationRecoveryDatabaseConfiguration.NotificationRecoveryDatabase database) throws Exception {
        return buildFactory(database.dataSource(), true);
    }
    /**
     * 装配会话模板，将声明的协作依赖与配置绑定到当前服务的统一处理链路。
     */
    @Bean
    SqlSessionTemplate notificationRecoverySqlSessionTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("notificationRecoverySqlSessionFactory") SqlSessionFactory factory) {
        return new SqlSessionTemplate(factory);
    }
    /**
     * 按指定数据源及映射资源构造 MyBatis 工厂，使映射与事务使用同一连接来源。
     */
    private static SqlSessionFactory buildFactory(DataSource dataSource, boolean recovery) throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        configuration.getTypeHandlerRegistry().register(java.util.UUID.class, new UuidBinaryTypeHandler());
        if (recovery) configuration.addMapper(NotificationRecoveryReplayMapper.class);
        var resolver = new PathMatchingResourcePatternResolver();
        List<Resource> resources = new ArrayList<>(List.of(resolver.getResources("classpath*:mapper/notification/*.xml")));
        if (recovery) resources.addAll(List.of(resolver.getResources("classpath*:mapper/notification-recovery/*.xml")));
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(resources.toArray(Resource[]::new));
        return factory.getObject();
    }
}
