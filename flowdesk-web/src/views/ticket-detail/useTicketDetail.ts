import { computed, ref, type Ref } from "vue";
import { alertApi, iamApi, ticketApi } from "@/api/endpoints";
import {
  isApiClientError,
  isCapabilityUnavailable,
  isFeatureUnavailableError,
  isOpaqueResourceNotFound,
} from "@/api/errors";
import { useAuthStore } from "@/stores/auth";
import { allPages } from "@/lib/allPages";
import type {
  IncidentTicketLink,
  SlaStatus,
  Ticket,
  TicketComment,
  TimelineEntry,
  UserIdentity,
  WorkflowStartStatus,
} from "@/types/api";

/** 维护整个页面生命周期的详情状态、操作权限和可独立降级的附属数据。 */
export function useTicketDetail(ticketId: Readonly<Ref<string>>) {
  const auth = useAuthStore();
  const currentUserId = computed(() => auth.user?.userId);
  const loading = ref(true);
  const error = ref<unknown>(null);
  const ticket = ref<Ticket | null>(null);
  const ticketEtag = ref("");
  const comments = ref<TicketComment[]>([]);
  const timeline = ref<TimelineEntry[]>([]);
  const commentsError = ref<unknown>(null);
  const timelineError = ref<unknown>(null);
  const sla = ref<SlaStatus | null>(null);
  const slaUnavailable = ref(false);
  const slaNotStarted = ref(false);
  const workflowStart = ref<WorkflowStartStatus | null>(null);
  const workflowUnavailable = ref(false);
  const candidates = ref<UserIdentity[]>([]);
  const incidentLink = ref<IncidentTicketLink | null>(null);
  const incidentLinkError = ref<unknown>(null);
  const actionError = ref<unknown>(null);

  const isOwner = computed(
    () => ticket.value?.requesterUserId === auth.user?.userId,
  );
  const isAssignee = computed(
    () => ticket.value?.assigneeUserId === auth.user?.userId,
  );
  const canAssign = computed(
    () =>
      auth.can("TICKET_ASSIGN_TENANT") &&
      ticket.value?.status === "PENDING_ASSIGNMENT",
  );
  // 操作入口同时受权限、资源归属和工单状态限制，后端仍会再次校验。
  const canTransfer = computed(() => {
    if (!["ASSIGNED", "IN_PROGRESS"].includes(ticket.value?.status || ""))
      return false;
    const adminTransfer =
      auth.hasRole("SERVICE_DESK_ADMIN") && auth.can("TICKET_TRANSFER_TENANT");
    const agentTransfer =
      auth.can("TICKET_TRANSFER_ASSIGNED") &&
      isAssignee.value;
    return adminTransfer || agentTransfer;
  });
  const canAccept = computed(
    () =>
      auth.can("TICKET_ACCEPT_ASSIGNED") &&
      isAssignee.value &&
      ticket.value?.status === "ASSIGNED",
  );
  const canResolve = computed(
    () =>
      auth.can("TICKET_RESOLVE_ASSIGNED") &&
      isAssignee.value &&
      ticket.value?.status === "IN_PROGRESS",
  );
  const canClose = computed(
    () =>
      auth.can("TICKET_CLOSE_OWN") &&
      isOwner.value &&
      ticket.value?.originType === "MANUAL" &&
      ticket.value?.status === "RESOLVED",
  );
  const canReopen = computed(
    () =>
      auth.can("TICKET_REOPEN_OWN") &&
      isOwner.value &&
      ticket.value?.originType === "MANUAL" &&
      ticket.value?.status === "RESOLVED",
  );
  const canComment = computed(() =>
    Boolean(
      ticket.value &&
        !["CLOSED", "APPROVAL_REJECTED"].includes(ticket.value.status) &&
        ((isOwner.value && auth.can("TICKET_COMMENT_OWN")) ||
          (isAssignee.value && auth.can("TICKET_COMMENT_ASSIGNED"))),
    ),
  );
  /**
   * 取得当前工单用于写操作的版本条件。
   * 优先使用响应 ETag，缺失时按工单锁版本构造约定格式。
   */
  function currentEtag(): string {
    return ticketEtag.value || `"lv:${ticket.value?.lockVersion ?? 0}"`;
  }
  /**
   * 判断工作流辅助读取是否属于可忽略的通用不存在。
   * 保留权限、服务故障等其他错误，避免把异常误判为没有流程。
   */
  function optionalWorkflowError(reason: unknown): boolean {
    return isOpaqueResourceNotFound(reason);
  }

  /**
   * 先读取工单主体，再独立加载评论、时间线、SLA 和工作流状态。
   * 按权限读取候选人员及来源关联，辅助信息失败不阻断主要详情。
   */
  async function load(): Promise<void> {
    loading.value = true;
    error.value = null;
    actionError.value = null;
    comments.value = [];
    timeline.value = [];
    commentsError.value = null;
    timelineError.value = null;
    sla.value = null;
    slaNotStarted.value = false;
    workflowStart.value = null;
    incidentLink.value = null;
    incidentLinkError.value = null;
    candidates.value = [];
    slaUnavailable.value = false;
    workflowUnavailable.value = false;
    try {
      const base = await ticketApi.ticket(ticketId.value);
      // 复制响应，避免模拟后端或缓存直接修改原对象而绕过 Vue 的依赖追踪。
      ticket.value = { ...base.data };
      ticketEtag.value =
        base.headers.get("ETag") || `"lv:${base.data.lockVersion}"`;
      // 各附属请求独立结算，评论或可选能力失败不会隐藏已经读到的工单主体。
      const [commentsResult, timelineResult, slaResult, workflowResult] =
        await Promise.allSettled([
          ticketApi.comments(ticketId.value, {
            pageSize: 100,
            sort: "createdAt",
          }),
          ticketApi.timeline(ticketId.value, {
            pageSize: 100,
            sort: "occurredAt",
          }),
          ticketApi.slaStatus(ticketId.value),
          ticketApi.workflowStart(ticketId.value),
        ]);
      if (commentsResult.status === "fulfilled")
        comments.value = commentsResult.value.items;
      else commentsError.value = commentsResult.reason;
      if (timelineResult.status === "fulfilled")
        timeline.value = timelineResult.value.items;
      else timelineError.value = timelineResult.reason;
      if (slaResult.status === "fulfilled") sla.value = slaResult.value.data;
      else if (isFeatureUnavailableError(slaResult.reason))
        slaUnavailable.value = true;
      else if (isApiClientError(slaResult.reason) && slaResult.reason.code === "SLA_NOT_STARTED")
        slaNotStarted.value = true;
      else if (isCapabilityUnavailable(slaResult.reason, "sla"))
        slaUnavailable.value = true;
      else actionError.value = slaResult.reason;
      if (workflowResult.status === "fulfilled")
        workflowStart.value = workflowResult.value.data;
      else if (isFeatureUnavailableError(workflowResult.reason))
        workflowUnavailable.value = true;
      else if (!optionalWorkflowError(workflowResult.reason) && ticket.value.workflowRef)
        actionError.value = workflowResult.reason;
      // 只有具备当前工单分派或转派资格时，才读取最小身份信息的候选人接口。
      if (canAssign.value || canTransfer.value) {
        try {
          candidates.value = await allPages(pageToken => iamApi.ticketAssigneeCandidates({
            pageSize: 100, pageToken, sort: "displayName",
          }));
        } catch (reason) {
          actionError.value = reason;
        }
      }
      if (ticket.value.originType === "INCIDENT") {
        // 告警服务维护关联关系；优先采用时间线中的固定引用，仅为旧记录保留主题文本回退。
        try {
          const timelineIncidentId = timeline.value.find(
            (entry) =>
              entry.relatedRefType?.toUpperCase() === "INCIDENT" &&
              Boolean(entry.relatedRefId),
          )?.relatedRefId;
          let incidentId = timelineIncidentId;
          if (!incidentId) {
            const incidents = (await alertApi.incidents({ pageSize: 100 })).items;
            incidentId = incidents.find((item) =>
              item.summary?.includes(ticket.value?.subject || ""),
            )?.incidentId || null;
          }
          if (incidentId) {
            try {
              incidentLink.value = (await alertApi.ticketLink(incidentId)).data;
            } catch (reason) {
              // 关联投影缺失时不改查其他资源；其余错误保留在独立关联面板中展示。
              if (!isOpaqueResourceNotFound(reason)) incidentLinkError.value = reason;
            }
          }
        } catch (reason) {
          if (!isOpaqueResourceNotFound(reason)) incidentLinkError.value = reason;
        }
      }
    } catch (reason) {
      error.value = reason;
    } finally {
      loading.value = false;
    }
  }

  return {
    ticketId, currentUserId, loading, error, ticket, ticketEtag,
    comments, timeline, commentsError, timelineError,
    sla, slaUnavailable, slaNotStarted, workflowStart, workflowUnavailable,
    candidates, incidentLink, incidentLinkError, actionError,
    isOwner, isAssignee, canAssign, canTransfer, canAccept, canResolve,
    canClose, canReopen, canComment, currentEtag, load,
  };
}

export type TicketDetailState = ReturnType<typeof useTicketDetail>;
