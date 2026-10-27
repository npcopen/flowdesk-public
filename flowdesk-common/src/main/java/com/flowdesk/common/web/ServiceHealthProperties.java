package com.flowdesk.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务本地健康检查策略，默认要求依赖满足条件后才能接收流量。
 */
@ConfigurationProperties(prefix = "flowdesk.health")
public class ServiceHealthProperties {

    private boolean databaseRequired = true;
    private boolean migrationRequired = true;

    /**
     * 返回就绪检查的数据库要求，供配置绑定和运行时组件初始化使用。
     */
    public boolean isDatabaseRequired() {
        return databaseRequired;
    }

    /**
     * 设置就绪检查的数据库要求，供配置绑定和运行时组件初始化使用。
     */
    public void setDatabaseRequired(boolean databaseRequired) {
        this.databaseRequired = databaseRequired;
    }

    /**
     * 返回就绪检查的迁移要求，供配置绑定和运行时组件初始化使用。
     */
    public boolean isMigrationRequired() {
        return migrationRequired;
    }

    /**
     * 设置就绪检查的迁移要求，供配置绑定和运行时组件初始化使用。
     */
    public void setMigrationRequired(boolean migrationRequired) {
        this.migrationRequired = migrationRequired;
    }
}
