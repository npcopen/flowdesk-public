package com.flowdesk.iam.api;

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

/** Request/response records for the IAM HTTP contract (API-SPEC §5.2). */
public final class IamDtos {

    private IamDtos() {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) @NoControlCharacters String tenantCode,
            @NotBlank @Size(max = 128) @NoControlCharacters String username,
            @NotBlank @Size(max = 256) @NoControlCharacters String password) {
    }

    public record TenantInfo(@NotBlank @NoControlCharacters String tenantCode,
                             @NotBlank @NoControlCharacters String displayName) {
    }

    public record CurrentUserResponse(
            UUID userId,
            String username,
            String displayName,
            String status,
            TenantInfo tenant,
            List<RoleCode> roles,
            List<PermissionCode> permissions) {
    }

    public record AuthSessionResponse(
            String accessToken,
            String tokenType,
            Instant expiresAt,
            CurrentUserResponse user) {
    }

    public record UserCreateRequest(
            @NotBlank @Size(max = 128) @NoControlCharacters String username,
            @NotBlank @Size(max = 128) @NoControlCharacters String displayName,
            @NotBlank @Size(max = 256) @NoControlCharacters String initialPassword,
            @NotNull Status status) {
        public enum Status { ACTIVE, INACTIVE }
    }

    public record UserPatchRequest(
            @Size(min = 1, max = 128)
            @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*", message = "displayName 不能为空白。")
            @NoControlCharacters String displayName,
            Status status) {
        public enum Status { ACTIVE, INACTIVE }
    }

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

    public record RoleCreateRequest(
            @NotNull RoleCode roleCode,
            @NotBlank @Size(max = 128) @NoControlCharacters String roleName,
            @NotNull Status status) {
        public enum Status { ACTIVE, INACTIVE }
    }

    public record RolePatchRequest(
            @Size(min = 1, max = 128)
            @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*", message = "roleName 不能为空白。")
            @NoControlCharacters String roleName,
            Status status) {
        public enum Status { ACTIVE, INACTIVE }
    }

    public record RoleResponse(
            UUID roleId,
            RoleCode roleCode,
            String roleName,
            String status,
            long lockVersion,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record PermissionResponse(
            UUID permissionId,
            PermissionCode permissionCode,
            String permissionName,
            String description) {
        @com.fasterxml.jackson.annotation.JsonProperty("allowedRoles")
        public java.util.Set<RoleCode> allowedRoles() { return permissionCode.ceiling(); }
    }

    public record BindingWriteRequest(@NotNull BindingState state) {
        public enum BindingState { GRANTED, REVOKED }
    }

    public record AccessBindingResponse(
            UUID subjectId,
            UUID targetId,
            String state,
            Instant grantedAt,
            Instant revokedAt,
            long lockVersion) {
    }

    public record EligibilityCheckRequest(
            @NotNull UUID subjectUserId,
            @NotNull RoleCode requiredRoleCode,
            @NotNull EligibilityPurpose purpose) {
        public enum EligibilityPurpose {
            TICKET_ASSIGNEE,
            WORKFLOW_APPROVER,
            SLA_ESCALATION_TARGET
        }
    }

    /**
     * API-016 result. The service owns checkedAt so every observable
     * qualification time comes from its injected UTC Clock.
     */
    public record EligibilityCheckResult(UUID subjectUserId, boolean eligible, String reasonCode,
                                         long qualificationVersion, Instant checkedAt) {
    }

}
