package com.flowdesk.alert.config;

import com.flowdesk.common.mybatis.UuidBinaryTypeHandler;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import javax.sql.DataSource;

/** 所有业务 SQL 均通过显式绑定普通运行数据源的 MyBatis Mapper 执行。 */
@Configuration(proxyBeanMethods=false)
@MapperScan(basePackages="com.flowdesk.alert.mapper", sqlSessionFactoryRef="alertSqlSessionFactory")
public class AlertMyBatisConfiguration {
    /**
     * 向 Spring 容器提供 SqlSessionFactory 组件，集中绑定持久化、认证配置或后台处理依赖，供对应业务入口复用。
     */
    @Bean
    SqlSessionFactory alertSqlSessionFactory(DataSource dataSource) throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        configuration.getTypeHandlerRegistry().register(java.util.UUID.class, new UuidBinaryTypeHandler());
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/alert/*.xml"));
        return factory.getObject();
    }
}
