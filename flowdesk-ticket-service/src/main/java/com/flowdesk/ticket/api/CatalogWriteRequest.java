package com.flowdesk.ticket.api;

import com.flowdesk.common.validation.NoControlCharacters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record CatalogWriteRequest(
        @NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{0,63}") String itemCode,
        @NotBlank @Size(max=128) @NoControlCharacters String itemName,
        @Size(max=1000) @NoControlCharacters String description,
        @NotNull RiskLevel riskLevel, @NotNull UUID slaPolicyId,
        @Valid WorkflowBinding workflowRef, @NotNull Status status) {
    public enum RiskLevel { NORMAL, HIGH }
    public enum Status { ACTIVE, INACTIVE }
    public record WorkflowBinding(@NotNull UUID definitionId, @NotNull UUID workflowVersionId,
                                  @Min(1) int versionNo) { }
}
