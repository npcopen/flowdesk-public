package com.flowdesk.iam.service;

import com.flowdesk.iam.dto.IamDtos;

/**
 * 向受信服务提供当前租户的只读资格检查，返回可用性结论、原因及本次授权事实版本。
 */
public interface EligibilityService {

    /**
     * 校验调用服务与用途、角色的白名单组合，再按当前租户权威事实返回资格结果及事实版本。
     */
    IamDtos.EligibilityCheckResult check(IamDtos.EligibilityCheckRequest request);
}
