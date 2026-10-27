package com.flowdesk.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 独立通知恢复数据库账号的连接配置，用于读取受限的原始重放源。 */
@ConfigurationProperties(prefix = "flowdesk.notification.recovery.datasource")
public class NotificationRecoveryDataSourceProperties {
    private String url;
    private String username = "fd_notification_recovery";
    private String password;
    private String driverClassName;

    /**
     * 读取数据库地址，供组件装配及启动校验使用。
     */
    public String getUrl() { return url; }
    /**
     * 设置数据库地址，供配置绑定和组件初始化使用。
     */
    public void setUrl(String url) { this.url = url; }
    /**
     * 读取数据库账号，供组件装配及启动校验使用。
     */
    public String getUsername() { return username; }
    /**
     * 设置数据库账号，供配置绑定和组件初始化使用。
     */
    public void setUsername(String username) { this.username = username; }
    /**
     * 读取数据库密码，供组件装配及启动校验使用。
     */
    public String getPassword() { return password; }
    /**
     * 设置数据库密码，供配置绑定和组件初始化使用。
     */
    public void setPassword(String password) { this.password = password; }
    /**
     * 读取JDBC 驱动类名，供组件装配及启动校验使用。
     */
    public String getDriverClassName() { return driverClassName; }
    /**
     * 设置JDBC 驱动类名，供配置绑定和组件初始化使用。
     */
    public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }

    /**
     * 按专用配置构造数据源，使恢复权限与运行时数据库访问保持分离。
     */
    DriverManagerDataSource buildDataSource() {
        if (url == null || url.isBlank() || username == null || username.isBlank()) {
            throw new IllegalStateException("Notification recovery datasource URL and username are required");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password == null ? "" : password);
        if (driverClassName != null && !driverClassName.isBlank()) {
            dataSource.setDriverClassName(driverClassName);
        }
        return dataSource;
    }
}
