-- Fresh demo tenants are created after formal migrations. Provision their handoff grant here.
INSERT INTO permission (tenant_id, permission_id, permission_code, permission_name, description, created_by_type)
SELECT t.tenant_id, UNHEX(SUBSTRING(SHA2(CONCAT(HEX(t.tenant_id), ':APPROVAL_REASSIGN_TENANT'), 256), 1, 32)),
       'APPROVAL_REASSIGN_TENANT', '审批人员交接', '为运行中的审批更换办理人，并记录原因。', 'SERVICE'
FROM tenant t WHERE NOT EXISTS (SELECT 1 FROM permission p
    WHERE p.tenant_id=t.tenant_id AND p.permission_code='APPROVAL_REASSIGN_TENANT');

-- Only administrators already entrusted with editing workflows receive the grant.
INSERT INTO role_permission (tenant_id,role_id,permission_id,state,granted_at,granted_by_type)
SELECT r.tenant_id,r.role_id,p.permission_id,'GRANTED',UTC_TIMESTAMP(6),'SERVICE'
FROM role r JOIN permission p ON p.tenant_id=r.tenant_id AND p.permission_code='APPROVAL_REASSIGN_TENANT'
WHERE r.role_code='SERVICE_DESK_ADMIN'
  AND EXISTS (SELECT 1 FROM role_permission rp JOIN permission old_p
      ON old_p.tenant_id=rp.tenant_id AND old_p.permission_id=rp.permission_id
      WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.role_id AND rp.state='GRANTED'
        AND old_p.permission_code='WORKFLOW_DRAFT_MANAGE')
  AND NOT EXISTS (SELECT 1 FROM role_permission existing WHERE existing.tenant_id=r.tenant_id
      AND existing.role_id=r.role_id AND existing.permission_id=p.permission_id);
