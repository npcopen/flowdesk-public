-- Bind only future demo Ticket creation to the canonical M6 Workflow version.
-- Existing Ticket rows contain their own immutable version snapshot and are not
-- touched by this forward migration.

UPDATE service_catalog_item
SET workflow_version_id=UUID_TO_BIN('5c75cbbd-0bc5-4c7b-87ef-0d68fb07062b'),
    workflow_version_no=2,
    lock_version=lock_version+1,
    updated_at=UTC_TIMESTAMP(6),
    updated_by_type='SERVICE',
    updated_by_id=NULL
WHERE tenant_id IN (
        UUID_TO_BIN('12d7dca8-380c-5a6c-84ae-953502959486'),
        UUID_TO_BIN('978d86fe-2cb8-5d4b-9968-e2f738a5c3e2')
      )
  AND catalog_item_id=UUID_TO_BIN('dafb0463-774c-503d-89cb-abc3d77e48cb')
  AND workflow_definition_id=UUID_TO_BIN('0848bf3c-d603-5ece-9529-f9bba79f1498')
  AND workflow_version_id=UUID_TO_BIN('f777230e-43b7-5656-8bf1-8c3a358173be')
  AND workflow_version_no=1;

SET @fd_tkt_m6_bound_rows = ROW_COUNT();
SET @fd_tkt_m6_binding_sql = IF(
    @fd_tkt_m6_bound_rows = 2
      AND (SELECT COUNT(*) FROM service_catalog_item
            WHERE catalog_item_id=UUID_TO_BIN('dafb0463-774c-503d-89cb-abc3d77e48cb')
              AND workflow_definition_id=UUID_TO_BIN('0848bf3c-d603-5ece-9529-f9bba79f1498')
              AND workflow_version_id=UUID_TO_BIN('5c75cbbd-0bc5-4c7b-87ef-0d68fb07062b')
              AND workflow_version_no=2) = 2,
    'SELECT 1',
    'SELECT __flowdesk_m6_demo_binding_incomplete__ FROM service_catalog_item LIMIT 0'
);
PREPARE fd_tkt_m6_binding_stmt FROM @fd_tkt_m6_binding_sql;
EXECUTE fd_tkt_m6_binding_stmt;
DEALLOCATE PREPARE fd_tkt_m6_binding_stmt;
SET @fd_tkt_m6_bound_rows = NULL;
SET @fd_tkt_m6_binding_sql = NULL;
