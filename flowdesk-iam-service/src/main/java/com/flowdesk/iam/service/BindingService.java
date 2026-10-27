package com.flowdesk.iam.service;

import com.flowdesk.iam.dto.IamDtos;
import java.util.UUID;

/**
 * 角色权限和用户角色绑定的管理用例；统一处理授权查询、授予及撤销，并以幂等键和资源版本保护写入。
 */
public interface BindingService {

    /**
     * 校验权限目录读取权限，以白名单排序和签名游标查询当前租户的权限列表。
     */
    PageResult<IamDtos.PermissionResponse> listPermissions(int pageSize,
                                                                  String pageToken,
                                                                  String sort);

    /**
     * 确认角色属于当前租户，按绑定状态筛选并按权限编码分页返回角色的权限关系。
     */
    PageResult<IamDtos.AccessBindingResponse> listRolePermissions(UUID roleId,
                                                                         String state,
                                                                         int pageSize,
                                                                         String pageToken,
                                                                         String sort);

    /**
     * 校验权限上限、写入前提和幂等意图后授予或撤销角色权限；保留组织管理员的基础 IAM 能力。
     */
    PutBindingResult putRolePermission(UUID roleId, UUID permissionId,
                                              IamDtos.BindingWriteRequest request,
                                              String ifMatch,
                                              String ifNoneMatch,
                                              String idempotencyKey);

    /**
     * 确认用户属于当前租户，按绑定状态筛选并以角色编码分页返回用户的角色关系。
     */
    PageResult<IamDtos.AccessBindingResponse> listUserRoles(UUID userId,
                                                                   String state,
                                                                   int pageSize,
                                                                   String pageToken,
                                                                   String sort);

    /**
     * 按资源前提与幂等意图授予或撤销用户角色，禁止管理员撤销自身的组织管理入口。
     */
    PutBindingResult putUserRole(UUID userId, UUID roleId,
                                        IamDtos.BindingWriteRequest request,
                                        String ifMatch,
                                        String ifNoneMatch,
                                        String idempotencyKey);

    /**
     * 绑定写入的业务结果，区分新建与既有资源，并标记是否复用了已提交的幂等结果。
     */
    record PutBindingResult(IamDtos.AccessBindingResponse binding, boolean created,
                                   boolean replayed) {
    }
}
