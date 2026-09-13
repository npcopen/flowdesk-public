package com.flowdesk.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Service-local health policy. The defaults are deliberately fail-closed. */
@ConfigurationProperties(prefix = "flowdesk.health")
public class ServiceHealthProperties {

    private boolean databaseRequired = true;
    private boolean migrationRequired = true;

    public boolean isDatabaseRequired() {
        return databaseRequired;
    }

    public void setDatabaseRequired(boolean databaseRequired) {
        this.databaseRequired = databaseRequired;
    }

    public boolean isMigrationRequired() {
        return migrationRequired;
    }

    public void setMigrationRequired(boolean migrationRequired) {
        this.migrationRequired = migrationRequired;
    }
}
