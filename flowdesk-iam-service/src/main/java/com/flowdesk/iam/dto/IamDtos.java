package com.flowdesk.iam.dto;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.validation.NoControlCharacters;
import com.flowdesk.common.authz.RoleCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * IAM HTTP 契约的请求与响应模型；字段校验约束在进入业务服务前执行。
 */
public final class IamDtos {

    /**
     * 禁止直接实例化该无状态工具类；调用方通过其静态方法或嵌套契约模型使用能力。
     */
    private IamDtos() {
    }

    /**
     * 登录请求载荷；租户代码、用户名和密码接受长度与控制字符校验，后续由认证服务核验凭证。
     */
    public record LoginRequest(
            @NotBlank @Size(max = 64) @NoControlCharacters String tenantCode,
            @NotBlank @Size(max = 128) @NoControlCharacters String username,
            @NotBlank @Size(max = 256) @NoControlCharacters String password) {
    }

    /**
     * 面向客户端展示的租户代码和名称，用于当前用户及登录会话响应。
     */
    public record TenantInfo(@NotBlank @NoControlCharacters String tenantCode,
                             @NotBlank @NoControlCharacters String displayName) {
    }

    /**
     * 当前用户的基础资料与有效授权快照，包含租户、角色及权限，不暴露登录凭证。
     */
    public record CurrentUserResponse(
            UUID userId,
            String username,
            String displayName,
            String status,
            TenantInfo tenant,
            List<RoleCode> roles,
            List<PermissionCode> permissions) {
    }

    /**
     * 登录成功响应，组合 Bearer 访问令牌、到期时间和当前用户资料。
     */
    public record AuthSessionResponse(
            String accessToken,
            String tokenType,
            Instant expiresAt,
            CurrentUserResponse user) {
    }

    /**
     * 创建用户的输入模型，要求用户名、显示名称、初始密码与初始状态，由服务负责密码派生和持久化。
     */
    public record UserCreateRequest(
            @NotBlank @Size(max = 128) @NoControlCharacters String username,
            @NotBlank @Size(max = 128) @NoControlCharacters String displayName,
            @NotBlank @Size(max = 256) @NoControlCharacters String initialPassword,
            @NotNull Status status) {
        /**
         * 账户或角色的启停状态；ACTIVE 表示可参与业务，INACTIVE 表示停用，具体可用性仍由服务结合授权事实判断。
         */
        public enum Status { ACTIVE, INACTIVE }
    }

    /**
     * 用户部分更新模型；未提供字段保留原值，提供的显示名称必须包含非空白字符并满足长度约束。
     */
    public record UserPatchRequest(
            @Size(min = 1, max = 128)
            @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*", message = "displayName 不能为空白。")
            @NoControlCharacters String displayName,
            Status status) {
        /**
         * 账户或角色的启停状态；ACTIVE 表示可参与业务，INACTIVE 表示停用，具体可用性仍由服务结合授权事实判断。
         */
        public enum Status { ACTIVE, INACTIVE }
    }

    /**
     * 用户列表及详情视图，携带有效角色、资源版本和审计时间，供客户端展示与条件更新。
     */
    public record UserSummaryResponse(
            UUID userId,
            String username,
            String displayName,
            String status,
            List<RoleCode> roleCodes,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * 租户角色创建请求，使用固定角色编码及受校验的名称、状态，防止任意扩展角色能力边界。
     */
    public record RoleCreateRequest(
            @NotNull RoleCode roleCode,
            @NotBlank @Size(max = 128) @NoControlCharacters String roleName,
            @NotNull Status status) {
        /**
         * 账户或角色的启停状态；ACTIVE 表示可参与业务，INACTIVE 表示停用，具体可用性仍由服务结合授权事实判断。
         */
        public enum Status { ACTIVE, INACTIVE }
    }

    /**
     * 角色部分更新请求，仅允许调整名称及启停状态；未指定的字段由服务保留原值。
     */
    public record RolePatchRequest(
            @Size(min = 1, max = 128)
            @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*", message = "roleName 不能为空白。")
            @NoControlCharacters String roleName,
            Status status) {
        /**
         * 账户或角色的启停状态；ACTIVE 表示可参与业务，INACTIVE 表示停用，具体可用性仍由服务结合授权事实判断。
         */
        public enum Status { ACTIVE, INACTIVE }
    }

    /**
     * 角色对外视图，保留角色编码、名称、启停状态与乐观锁版本，支持后续 If-Match 修改。
     */
    public record RoleResponse(
            UUID roleId,
            RoleCode roleCode,
            String roleName,
            String status,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * 权限目录视图，包含权限说明并由权限编码计算可授予角色上限，供授权管理页面选择。
     */
    public record PermissionResponse(
            UUID permissionId,
            PermissionCode permissionCode,
            String permissionName,
            String description) {
        /**
         * 从权限领域枚举读取固定角色上限，作为 allowedRoles 属性序列化给授权管理客户端。
         */
        @com.fasterxml.jackson.annotation.JsonProperty("allowedRoles")
        public java.util.Set<RoleCode> allowedRoles() { return permissionCode.ceiling(); }
    }

    /**
     * 授予或撤销绑定的写入请求；具体资源存在性、权限上限与并发前提由业务服务校验。
     */
    public record BindingWriteRequest(@NotNull BindingState state) {
        /**
         * 授权关系的目标状态；GRANTED 表示授予，REVOKED 表示撤销，历史关系通过状态而非物理删除保留。
         */
        public enum BindingState { GRANTED, REVOKED }
    }

    /**
     * 授权关系视图，包含主体、目标、当前状态及授予/撤销时间，并返回用于条件写入的锁版本。
     */
    public record AccessBindingResponse(
            UUID subjectId,
            UUID targetId,
            String state,
            Instant grantedAt,
            Instant revokedAt,
            long lockVersion) {
    }

    /**
     * 受信服务的资格预检请求，明确目标用户、所需角色与业务用途，角色和用途须匹配调用服务白名单。
     */
    public record EligibilityCheckRequest(
            @NotNull UUID subjectUserId,
            @NotNull RoleCode requiredRoleCode,
            @NotNull EligibilityPurpose purpose) {
        /**
         * 同步资格检查的业务用途，分别用于工单接单人、流程审批人和 SLA 升级目标，限制服务身份的探测范围。
         */
        public enum EligibilityPurpose {
            TICKET_ASSIGNEE,
            WORKFLOW_APPROVER,
            SLA_ESCALATION_TARGET
        }
    }

    /**
     * API-016 资格结果；checkedAt 由服务注入的 UTC 时钟生成，统一对外资格检查时间。
     */
    public record EligibilityCheckResult(UUID subjectUserId, boolean eligible, String reasonCode,
                                         long qualificationVersion, Instant checkedAt) {
    }

}
