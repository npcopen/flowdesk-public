package com.flowdesk.iam.service;

import com.flowdesk.iam.dto.IamDtos;
import java.util.UUID;

/**
 * 租户角色的分页查询、创建和修改契约；写入通过幂等审计与乐观锁协调重试和并发操作。
 */
public interface RoleManagementService {

    /**
     * 以受支持排序和签名游标分页读取当前租户角色，支持可选启停状态筛选。
     */
    PageResult<IamDtos.RoleResponse> list(String status, int pageSize,
                                                 String pageToken, String sort);

    /**
     * 读取当前租户的单个角色，包含客户端后续条件修改所需的锁版本。
     */
    IamDtos.RoleResponse get(UUID roleId);

    /**
     * 按幂等键创建租户角色，返回角色视图及是否复用既有创建结果。
     */
    CreateRoleResult create(IamDtos.RoleCreateRequest request, String idempotencyKey);

    /**
     * 根据 If-Match 条件部分修改角色名称或状态，返回更新后的角色视图。
     */
    IamDtos.RoleResponse patch(UUID roleId, IamDtos.RolePatchRequest request,
                                      long expectedLockVersion);

    /**
     * 创建角色的结果与幂等重放标记，重复业务意图复用原资源而不再次写入创建审计。
     */
    record CreateRoleResult(IamDtos.RoleResponse role, boolean replayed) {
    }
}
