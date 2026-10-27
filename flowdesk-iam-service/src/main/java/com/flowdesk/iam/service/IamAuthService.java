package com.flowdesk.iam.service;

import com.flowdesk.iam.dto.IamDtos;
import java.util.UUID;

/**
 * 登录认证和当前用户资料查询的业务契约；身份、角色及权限由 IAM 当前数据确定。
 */
public interface IamAuthService {

    /**
     * 验证登录凭证、账户状态和锁定期限，签发租户内的人类 JWT，并返回从当前 IAM 数据加载的用户授权。
     */
    IamDtos.AuthSessionResponse login(IamDtos.LoginRequest request, UUID correlationId);

    /**
     * 按认证上下文读取当前用户与最新角色权限，不直接复用登录令牌中的历史授权快照。
     */
    IamDtos.CurrentUserResponse currentUser();
}
