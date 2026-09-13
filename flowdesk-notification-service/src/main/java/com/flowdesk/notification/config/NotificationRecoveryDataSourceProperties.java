package com.flowdesk.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Credentials for the isolated, recovery-only Notification database principal. */
@ConfigurationProperties(prefix = "flowdesk.notification.recovery.datasource")
public class NotificationRecoveryDataSourceProperties {
    private String url;
    private String username = "fd_notification_recovery";
    private String password;
    private String driverClassName;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDriverClassName() { return driverClassName; }
    public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }

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
