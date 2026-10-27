package com.flowdesk.ticket.config;
import com.flowdesk.common.mybatis.UuidBinaryTypeHandler;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * 工单服务 MyBatis 配置入口，统一 Mapper 所需映射行为。
 */
@Configuration(proxyBeanMethods = false)
public class TicketMyBatisConfiguration {
    /**
     * 装配工单的 ticketMapperConfiguration 组件，将配置与依赖交由 Spring 管理。
     */
    @Bean
    ConfigurationCustomizer ticketMapperConfiguration() {
        return configuration -> {
            configuration.getTypeHandlerRegistry().register(java.util.UUID.class, new UuidBinaryTypeHandler());
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.setCacheEnabled(false);
            configuration.setMapUnderscoreToCamelCase(true);
        };
    }
}
