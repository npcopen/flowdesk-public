package com.flowdesk.notification.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Recovery-only JDBC boundary. Its database principal is the sole application
 * identity permitted to read persisted Notification replay source bytes.
 */
@Repository("notificationRecoveryRepository")
public class NotificationRecoveryRepository extends NotificationRepository {

    public NotificationRecoveryRepository(
            @Qualifier("notificationRecoveryJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc, true);
    }
}
