package com.flowdesk.workflow.support;

import com.flowdesk.common.mybatis.UuidBinaryTypeHandler;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 工作流内部协作类型，组合当前处理所需的结构与行为，供所在用例的依赖链调用。
 */
public final class WorkflowTestMappers {
    private static final Map<DataSource, SqlSessionTemplate> SESSIONS = new WeakHashMap<>();

    /**
     * 禁止直接实例化工具容器，当前类型通过静态方法提供共享规则和转换。
     */
    private WorkflowTestMappers() { }

    /**
     * 为指定数据源取得测试 Mapper，使集成测试使用真实映射与类型处理器。
     */
    public static synchronized <T> T mapper(DataSource dataSource, Class<T> mapperType) {
        SqlSessionTemplate session = SESSIONS.computeIfAbsent(dataSource, WorkflowTestMappers::session);
        if (!session.getConfiguration().hasMapper(mapperType)) {
            session.getConfiguration().addMapper(mapperType);
        }
        return session.getMapper(mapperType);
    }

    /**
     * 为测试数据源构造并复用 MyBatis 会话模板，保持映射资源和事务连接一致。
     */
    private static SqlSessionTemplate session(DataSource dataSource) {
        try {
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            var configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setArgNameBasedConstructorAutoMapping(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.setCacheEnabled(false);
            factory.setConfiguration(configuration);
            factory.setTypeHandlers(new UuidBinaryTypeHandler());
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/**/*.xml"));
            SqlSessionFactory sessions = factory.getObject();
            return new SqlSessionTemplate(sessions);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot create workflow test MyBatis session", failure);
        }
    }
}
