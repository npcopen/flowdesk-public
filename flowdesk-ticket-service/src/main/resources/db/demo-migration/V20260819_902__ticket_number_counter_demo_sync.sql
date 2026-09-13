-- Demo data is applied after production migrations on a fresh Compose database.
-- Re-synchronize the counter after the out-of-order V20260816_901 demo Ticket inserts.

UPDATE ticket_number_counter AS counter
JOIN (
    SELECT parsed.tenant_id,
           parsed.business_date,
           MAX(parsed.sequence_value) AS sequence_value
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
    GROUP BY parsed.tenant_id, parsed.business_date
) AS seeded
  ON seeded.tenant_id = counter.tenant_id
 AND seeded.business_date = counter.business_date
SET counter.sequence_value = GREATEST(counter.sequence_value, seeded.sequence_value),
    counter.updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO ticket_number_counter (tenant_id, business_date, sequence_value, created_at, updated_at)
SELECT seeded.tenant_id,
       seeded.business_date,
       seeded.sequence_value,
       CURRENT_TIMESTAMP(6),
       CURRENT_TIMESTAMP(6)
FROM (
    SELECT parsed.tenant_id,
           parsed.business_date,
           MAX(parsed.sequence_value) AS sequence_value
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
    GROUP BY parsed.tenant_id, parsed.business_date
) AS seeded
LEFT JOIN ticket_number_counter AS counter
  ON counter.tenant_id = seeded.tenant_id
 AND counter.business_date = seeded.business_date
WHERE counter.tenant_id IS NULL;