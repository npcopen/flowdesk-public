-- Atomic, tenant/day-scoped Ticket number allocation for host and multi-instance writers.
-- Fresh databases apply this after the immutable baseline; existing databases apply it forward.
-- The counter update is part of the same local transaction as Ticket/Timeline/Audit/Outbox writes.

CREATE TABLE ticket_number_counter (
    tenant_id BINARY(16) NOT NULL,
    business_date DATE NOT NULL,
    sequence_value BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT pk_tkt_56 PRIMARY KEY (tenant_id, business_date),
    CONSTRAINT ck_tkt_counter_positive CHECK (sequence_value > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

-- Preserve continuity for databases that already contain FD-yyyyMMdd-N tickets.
-- Malformed or zero-valued legacy numbers are deliberately ignored.
INSERT INTO ticket_number_counter (tenant_id, business_date, sequence_value, created_at, updated_at)
SELECT parsed.tenant_id,
       parsed.business_date,
       MAX(parsed.sequence_value),
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6)
FROM (
    SELECT tenant_id,
           SUBSTRING(ticket_no, 4, 8) AS business_date_text,
           STR_TO_DATE(SUBSTRING(ticket_no, 4, 8), '%Y%m%d') AS business_date,
           CAST(SUBSTRING(ticket_no, 13) AS UNSIGNED) AS sequence_value
    FROM ticket
    WHERE ticket_no REGEXP '^FD-[0-9]{8}-[0-9]+$'
) AS parsed
WHERE parsed.business_date IS NOT NULL
  AND DATE_FORMAT(parsed.business_date, '%Y%m%d') = parsed.business_date_text
  AND parsed.sequence_value > 0
GROUP BY parsed.tenant_id, parsed.business_date;
