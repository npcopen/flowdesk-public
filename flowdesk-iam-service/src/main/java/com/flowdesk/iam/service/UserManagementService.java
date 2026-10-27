package com.flowdesk.iam.service;

import com.flowdesk.iam.dto.IamDtos;
import java.util.UUID;

/**
 * 租户用户、最小身份及工单接单候选人的业务契约；账户创建、凭证和审计由实现层在事务中协调。
 */
public interface UserManagementService {

    /**
     * 校验身份读取权限后查询同租户最小用户资料，仅返回业务展示所需字段。
     */
    Identity identity(UUID userId);

    /**
     * 仅分页返回当前租户中启用且具备 Agent 角色的最小身份；查询作用域固定用于工单分派和转派。
     */
    PageResult<Identity> ticketAssigneeCandidates(int pageSize, String pageToken, String sort);

    /**
     * 按调用者权限范围分页查询租户用户或可用业务候选，支持状态、角色与排序筛选。
     */
    PageResult<IamDtos.UserSummaryResponse> list(String status,
                                                        String roleCode,
                                                        int pageSize,
                                                        String pageToken,
                                                        String sort);

    /**
     * 读取同租户用户摘要，并由实现层继续约束仅持有候选查询权限时的可见范围。
     */
    IamDtos.UserSummaryResponse get(UUID userId);

    /**
     * 在幂等键保护下创建用户及初始凭证，返回资源和重复请求标记。
     */
    CreateUserResult create(IamDtos.UserCreateRequest request, String idempotencyKey);

    /**
     * 基于 If-Match 版本部分更新同租户用户，返回带新版本的用户摘要。
     */
    IamDtos.UserSummaryResponse patch(UUID userId, IamDtos.UserPatchRequest request,
                                             long expectedLockVersion);

    /**
     * 同租户用户的最小身份视图，仅提供标识、用户名和显示名称，供业务选择或展示。
     */
    record Identity(UUID userId, String username, String displayName) { }

    /**
     * 创建用户的结果与幂等重放标记，供控制器确定响应元数据并返回同一稳定用户资源。
     */
    record CreateUserResult(IamDtos.UserSummaryResponse user, boolean replayed) {
    }
}
