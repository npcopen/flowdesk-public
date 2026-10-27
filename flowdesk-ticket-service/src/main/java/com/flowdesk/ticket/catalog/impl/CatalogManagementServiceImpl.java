package com.flowdesk.ticket.catalog.impl;

import com.flowdesk.ticket.dto.CatalogWriteRequest;
import com.flowdesk.ticket.mapper.*;
import com.flowdesk.ticket.model.*;
import com.flowdesk.ticket.persistence.Uuids;

import com.flowdesk.ticket.catalog.*;

import com.flowdesk.common.authz.PermissionCode;
import com.flowdesk.common.web.ApiException;
import com.flowdesk.ticket.dto.TicketDtos;

import com.flowdesk.ticket.service.IdempotencyCodec;
import com.flowdesk.ticket.service.TicketSupport;
import com.flowdesk.ticket.mapper.SlaPolicyMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 维护目录编码、风险等级、SLA 策略与审批版本绑定，保存配置时同步记录审计事实。 */
@Service
public class CatalogManagementServiceImpl implements CatalogManagementService {

    private final CatalogMapper repository;
    private final SlaPolicyMapper slaRepository;
    private final TicketAuditMapper ticketAuditMapper;
    private final CatalogVersionClient versions;

    /**
     * 构造服务目录组件并保存调用依赖。
     */
    public CatalogManagementServiceImpl(CatalogMapper repository, SlaPolicyMapper slaRepository,
                                    TicketAuditMapper ticketAuditMapper, CatalogVersionClient versions) {
        this.repository = repository;
        this.slaRepository = slaRepository;
        this.ticketAuditMapper = ticketAuditMapper;
        this.versions = versions;
    }

    /** 高风险目录必须绑定已发布的高风险审批版本；普通目录不能携带审批引用。 */
    private void validate(CatalogWriteRequest request, String authorization) {
        TicketSupport.require(PermissionCode.CATALOG_UPDATE_MINIMAL);
        if (slaRepository.findActiveSlaPolicy(
                TicketSupport.context().tenantId(), request.slaPolicyId()).isEmpty()) {
            throw ApiException.unprocessable("SLA_POLICY_NOT_AVAILABLE",
                    "请选择有效的处理时效策略。", List.of());
        }
        if (request.riskLevel() == CatalogWriteRequest.RiskLevel.HIGH) {
            if (request.workflowRef() == null) {
                throw ApiException.unprocessable("WORKFLOW_VERSION_REQUIRED",
                        "高风险类型必须配置审批规则。", List.of());
            }
            versions.validate(request.workflowRef(), authorization);
        } else if (request.workflowRef() != null) {
            throw ApiException.unprocessable("WORKFLOW_VERSION_INVALID",
                    "普通类型不使用审批规则。", List.of());
        }
    }

    /** 由创建幂等键派生稳定目录身份，并通过审计摘要拒绝同键不同配置的重用。 */
    @Transactional
    public Result create(CatalogWriteRequest request, String key, String authorization) {
        TicketSupport.require(PermissionCode.CATALOG_UPDATE_MINIMAL);
        var ctx = TicketSupport.context();
        UUID id = IdempotencyCodec.uuidFromDigest(IdempotencyCodec.effectKey(
                ctx, "CATALOG-CREATE", "COLLECTION", TicketSupport.requireIdempotencyKey(key)));
        String digest = IdempotencyCodec.requestDigestHex(
                new tools.jackson.databind.ObjectMapper().writeValueAsString(request));
        var existing = repository.findCatalogItem(ctx.tenantId(), id);
        if (existing.isPresent()) {
            var audit = ticketAuditMapper.findAudit(ctx.tenantId(), id)
                    .orElseThrow(() -> ApiException.conflict(
                            "IDEMPOTENCY_KEY_REUSED", "创建记录不完整。"));
            if (!digest.equals(audit.reason())) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "该提交标识已用于另一项类型配置。");
            }
            return new Result(CatalogResponseMapper.toResponse(existing.get()), true);
        }
        validate(request, authorization);
        Instant now = Instant.now();
        try {
            repository.insertCatalogConfiguration(
                    ctx.tenantId(), id, request, TicketSupport.actorId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CATALOG_CODE_EXISTS",
                    "类型编码已存在，请刷新列表或使用其他编码。");
        }
        ticketAuditMapper.insertAudit(ctx.tenantId(), id, "CATALOG_CREATE", "CATALOG_ITEM", id,
                "SUCCEEDED", "USER", TicketSupport.actorId(), digest, ctx.correlationId(), now);
        return new Result(CatalogResponseMapper.toResponse(
                repository.findCatalogItem(ctx.tenantId(), id).orElseThrow()), false);
    }

    /** 锁定既有目录，校验客户端版本和不可变编码后保存配置及审计。 */
    @Transactional
    public TicketDtos.ServiceCatalogItemResponse configure(UUID id, CatalogWriteRequest request,
                                                           long version, String authorization) {
        validate(request, authorization);
        var ctx = TicketSupport.context();
        var existing = repository.findCatalogItemForUpdate(ctx.tenantId(), id)
                .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "类型不存在。"));
        if (existing.lockVersion() != version) {
            throw TicketSupport.versionConflict(TicketSupport.etagHeaders(existing.lockVersion()));
        }
        if (!existing.itemCode().equals(request.itemCode())) {
            throw ApiException.unprocessable("CATALOG_CODE_IMMUTABLE",
                    "类型编码创建后不可修改。", List.of());
        }
        Instant now = Instant.now();
        if (repository.updateCatalogConfiguration(
                ctx.tenantId(), id, request, version, TicketSupport.actorId(), now) != 1) {
            throw TicketSupport.versionConflict(TicketSupport.etagHeaders(existing.lockVersion()));
        }
        ticketAuditMapper.insertAudit(ctx.tenantId(), UUID.randomUUID(), "CATALOG_CONFIGURE",
                "CATALOG_ITEM", id, "SUCCEEDED", "USER", TicketSupport.actorId(),
                IdempotencyCodec.requestDigestHex(
                        new tools.jackson.databind.ObjectMapper().writeValueAsString(request)),
                ctx.correlationId(), now);
        return CatalogResponseMapper.toResponse(repository.findCatalogItem(ctx.tenantId(), id).orElseThrow());
    }


}
