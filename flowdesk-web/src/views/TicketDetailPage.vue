<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  ArrowLeft,
  Check,
  CircleAlert,
  Clock3,
  MessageSquare,
  RefreshCw,
  Send,
  UserRound,
  X,
} from "lucide-vue-next";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { alertApi, iamApi, ticketApi } from "@/api/endpoints";
import { featureFlags, featureNotice } from "@/api/features";
import {
  isApiClientError,
  isCapabilityUnavailable,
  isFeatureUnavailableError,
  isOpaqueResourceNotFound,
} from "@/api/errors";
import { useAuthStore } from "@/stores/auth";
import { mutationIntent } from "@/lib/mutationIntent";
import type {
  ApprovalTask,
  IncidentTicketLink,
  ServiceCatalogItem,
  SlaStatus,
  Ticket,
  TicketComment,
  TimelineEntry,
  UserSummary,
  WorkflowStartStatus,
} from "@/types/api";
import StatusBadge from "@/components/StatusBadge.vue";
import ErrorNotice from "@/components/ErrorNotice.vue";
import ResourceState from "@/components/ResourceState.vue";
import AsyncProgress from "@/components/AsyncProgress.vue";
import { formatDate, timelineLabel } from "@/lib/format";

type ActionKind =
  | "assign"
  | "accept"
  | "transfer"
  | "resolve"
  | "close"
  | "reopen";
const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const ticketId = computed(() => String(route.params.id));
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
const candidates = ref<UserSummary[]>([]);
const incidentLink = ref<IncidentTicketLink | null>(null);
const incidentLinkError = ref<unknown>(null);
const actionKind = ref<ActionKind | null>(null);
const actionText = ref("");
const selectedAssignee = ref("");
const actionLoading = ref(false);
const actionError = ref<unknown>(null);
const commentText = ref("");
const commentLoading = ref(false);
const effects = ref<AsyncProgress[]>([]);

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
const canTransfer = computed(() => {
  if (!["ASSIGNED", "IN_PROGRESS"].includes(ticket.value?.status || ""))
    return false;
  const adminTransfer =
    auth.hasRole("SERVICE_DESK_ADMIN") && auth.can("TICKET_TRANSFER_TENANT");
  const agentTransfer =
    auth.can("TICKET_TRANSFER_ASSIGNED") &&
    isAssignee.value &&
    candidates.value.length > 0;
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
const actionTitle = computed(
  () =>
    ({
      assign: "分配工单",
      accept: "接单",
      transfer: "转派工单",
      resolve: "解决工单",
      close: "关闭工单",
      reopen: "重新打开工单",
    })[actionKind.value || "assign"],
);

function currentEtag(): string {
  return ticketEtag.value || `"lv:${ticket.value?.lockVersion ?? 0}"`;
}
function optionalWorkflowError(reason: unknown): boolean {
  return isOpaqueResourceNotFound(reason);
}

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
    // Clone the response so an in-memory mock (or a cache) cannot mutate the
    // reactive view behind Vue's dependency tracker.
    ticket.value = { ...base.data };
    ticketEtag.value =
      base.headers.get("ETag") || `"lv:${base.data.lockVersion}"`;
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
    if (
      auth.hasRole("SERVICE_DESK_ADMIN") &&
      (canAssign.value || canTransfer.value)
    )
      candidates.value = (
        await iamApi.users({
          status: "ACTIVE",
          roleCode: "AGENT",
          pageSize: 100,
          sort: "displayName",
        })
      ).items;
    if (ticket.value.originType === "INCIDENT") {
      // Alert owns the mapping. Prefer the immutable timeline reference; the
      // subject-text fallback is retained only for older records without it.
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
            // A missing projection is not an Incident/Event fallback. Keep
            // other errors visible in the dedicated association panel.
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

onMounted(load);

function openAction(kind: ActionKind): void {
  actionKind.value = kind;
  actionText.value = "";
  actionError.value = null;
  selectedAssignee.value = candidates.value[0]?.userId || "";
}
function closeAction(): void {
  if (!actionLoading.value) actionKind.value = null;
}
async function submitAction(): Promise<void> {
  if (!ticket.value || !actionKind.value) return;
  actionLoading.value = true;
  actionError.value = null;
  try {
    let result;
    const etag = currentEtag();
    if (actionKind.value === "assign")
      result = await ticketApi.assign(
        ticket.value.ticketId,
        selectedAssignee.value,
        etag,
      );
    if (actionKind.value === "accept")
      result = await ticketApi.accept(ticket.value.ticketId, etag);
    if (actionKind.value === "transfer")
      result = await ticketApi.transfer(
        ticket.value.ticketId,
        { newAssigneeUserId: selectedAssignee.value, reason: actionText.value },
        etag,
      );
    if (actionKind.value === "resolve")
      result = await ticketApi.resolve(
        ticket.value.ticketId,
        actionText.value,
        etag,
      );
    if (actionKind.value === "close")
      result = await ticketApi.close(
        ticket.value.ticketId,
        actionText.value || null,
        etag,
      );
    if (actionKind.value === "reopen")
      result = await ticketApi.reopen(
        ticket.value.ticketId,
        actionText.value,
        etag,
      );
    if (result) {
      effects.value = result.meta.asyncEffects || [];
      actionKind.value = null;
      await load();
    }
  } catch (reason) {
    actionError.value = reason;
  } finally {
    actionLoading.value = false;
  }
}
const commentIntent = mutationIntent();
async function addComment(): Promise<void> {
  if (!commentText.value.trim()) return;
  commentLoading.value = true;
  actionError.value = null;
  try {
    await ticketApi.addComment(ticketId.value, {
      body: commentText.value.trim(),
    }, commentIntent.key({ticketId: ticketId.value, body: commentText.value.trim()}));
    commentIntent.reset();
    commentText.value = "";
    await load();
  } catch (reason) {
    actionError.value = reason;
  } finally {
    commentLoading.value = false;
  }
}
</script>

<template>
  <div class="detail-back">
    <button
      class="button button--ghost button--small"
      type="button"
      @click="router.back()"
    >
      <ArrowLeft :size="15" />返回工单列表
    </button>
  </div>
  <ResourceState
    :loading="loading"
    :error="error"
    :empty="!loading && !error && !ticket"
    empty-text="工单不可见"
    @retry="load"
  >
    <template v-if="ticket">
      <div class="page-heading detail-heading">
        <div>
          <p class="eyebrow">
            {{ ticket.ticketNo }} ·
            {{ ticket.originType === "INCIDENT" ? "系统告警" : "人工提交" }}
          </p>
          <h1>{{ ticket.subject }}</h1>
          <p class="lede">
            创建于 {{ formatDate(ticket.createdAt) }} · 最近更新
            {{ formatDate(ticket.updatedAt) }}
          </p>
        </div>
        <div class="heading-actions">
          <StatusBadge :value="ticket.status" /><button
            class="button button--secondary"
            type="button"
            :disabled="loading"
            @click="load"
          >
            <RefreshCw :size="15" />刷新
          </button>
        </div>
      </div>
      <AsyncProgress :effects="effects" />
      <ErrorNotice
        v-if="actionError && !actionKind"
        :error="actionError"
        @retry="load"
      />
      <div class="detail-layout">
        <div class="detail-main stack">
          <section class="panel">
            <div class="panel-header">
              <div>
                <h2>请求内容</h2>
                <p>查看工单详情和当前处理进度。</p>
              </div>
            </div>
            <div class="panel-body">
              <p class="description">{{ ticket.description }}</p>
              <div class="facts-grid">
                <div>
                  <span>请求人</span
                  ><strong>{{
                    isOwner ? "本人" : ticket.requesterUserId ? "提交人" : "系统创建"
                  }}</strong>
                </div>
                <div>
                  <span>当前处理人</span
                  ><strong>{{
                    isAssignee ? "当前账号" : ticket.assigneeUserId ? "已分配" : "未分配"
                  }}</strong>
                </div>
                <div>
                  <span>可开始处理时间</span
                  ><strong>{{ formatDate(ticket.firstProcessableAt) }}</strong>
                </div>
                <div>
                  <span>重新打开次数</span
                  ><strong>{{ ticket.reopenCount }}</strong>
                </div>
              </div>
            </div>
          </section>
          <section class="panel action-panel">
            <div class="panel-header">
              <div>
                <h2>处理操作</h2>
                <p>提交后系统会再次确认权限和最新状态。</p>
              </div>
              <CircleAlert :size="18" class="panel-heading-icon" />
            </div>
            <div class="action-buttons">
              <button
                v-if="canAssign"
                class="button"
                type="button"
                @click="openAction('assign')"
              >
                <UserRound :size="15" />分配</button
              ><button
                v-if="canAccept"
                class="button"
                type="button"
                @click="openAction('accept')"
              >
                <Check :size="15" />接单</button
              ><button
                v-if="canTransfer"
                class="button button--secondary"
                type="button"
                @click="openAction('transfer')"
              >
                <UserRound :size="15" />转派</button
              ><button
                v-if="canResolve"
                class="button"
                type="button"
                @click="openAction('resolve')"
              >
                <Check :size="15" />标记已解决</button
              ><button
                v-if="canClose"
                class="button"
                type="button"
                @click="openAction('close')"
              >
                关闭工单</button
              ><button
                v-if="canReopen"
                class="button button--secondary"
                type="button"
                @click="openAction('reopen')"
              >
                重新打开</button
              ><span
                v-if="
                  !canAssign &&
                  !canAccept &&
                  !canTransfer &&
                  !canResolve &&
                  !canClose &&
                  !canReopen
                "
                class="muted action-empty"
                >当前账号没有可执行操作，或工单正在处理/已结束。</span
              >
            </div>
          </section>
          <section class="panel">
            <div class="panel-header">
              <div>
                <h2>评论</h2>
                <p>评论会立即追加到业务时间线。</p>
              </div>
              <MessageSquare :size="18" class="panel-heading-icon" />
            </div>
            <div class="panel-body">
              <ErrorNotice
                v-if="commentsError"
                :error="commentsError"
                @retry="load"
              />
              <form
                v-if="canComment"
                class="comment-form"
                @submit.prevent="addComment"
              >
                <textarea
                  v-model="commentText"
                  required
                  maxlength="10000"
                  placeholder="写下处理进展或补充信息…"
                  aria-label="评论内容"
                />
                <div class="form-actions">
                  <button
                    class="button button--small"
                    type="submit"
                    :disabled="commentLoading || !commentText.trim()"
                  >
                    <Send :size="14" />{{
                      commentLoading ? "发送中…" : "添加评论"
                    }}
                  </button>
                </div>
              </form>
              <div v-else class="muted comment-readonly">
                当前账号只能查看评论，或工单已结束。
              </div>
              <div v-if="!commentsError && comments.length" class="comment-list">
                <article
                  v-for="comment in comments"
                  :key="comment.commentId"
                  class="comment-item"
                >
                  <div class="avatar avatar--small">
                    {{ comment.authorUserId.slice(0, 1) }}
                  </div>
                  <div>
                    <p>{{ comment.body }}</p>
                    <time
                      >{{ formatDate(comment.createdAt) }} ·
                      {{ comment.authorUserId === auth.user?.userId ? "我" : "处理成员" }}</time
                    >
                  </div>
                </article>
              </div>
              <div v-else-if="!commentsError" class="muted empty-inline">暂无评论</div>
            </div>
          </section>
          <section class="panel">
            <div class="panel-header">
              <div>
                <h2>业务时间线</h2>
                <p>记录工单的重要处理节点。</p>
              </div>
              <Clock3 :size="18" class="panel-heading-icon" />
            </div>
            <ErrorNotice
              v-if="timelineError"
              :error="timelineError"
              @retry="load"
            />
            <div v-if="!timelineError" class="timeline-list">
              <div
                v-for="entry in timeline"
                :key="entry.timelineId"
                class="timeline-item"
              >
                <span class="timeline-dot" />
                <div>
                  <strong>{{ entry.summary }}</strong
                  ><span
                    >{{ timelineLabel(entry.eventType) }} ·
                    {{ formatDate(entry.occurredAt) }}</span
                  >
                </div>
              </div>
              <div v-if="!timeline.length" class="muted empty-inline">
                暂无时间线记录
              </div>
            </div>
          </section>
        </div>
        <aside class="detail-side stack">
          <section class="panel">
            <div class="panel-header">
              <div>
                <h2>处理时效</h2>
                <p>{{ ticket.reopenCount ? '当前为重开后的处理轮次，历史时效保留在下方。' : '查看预计响应和解决时间。' }}</p>
              </div>
            </div>
            <div class="panel-body">
              <div v-if="slaUnavailable" class="feature-muted">
                <template v-if="featureFlags.sla === 'mock'">
                  {{ featureNotice(featureFlags.sla) }}
                </template>
                <template v-else>
                  当前环境暂时无法提供处理时效，稍后可再次刷新。
                </template>
              </div>
              <div v-else-if="slaNotStarted" class="feature-muted">
                处理时效尚未开始，当前状态暂无计时记录。
              </div>
              <div v-else-if="sla" class="sla-list">
                <div
                  v-for="clock in sla.clocks"
                  :key="clock.slaClockId"
                  class="sla-row"
                >
                  <div>
                    <strong>{{
                      clock.clockType === "FIRST_RESPONSE"
                        ? "首次响应"
                        : (ticket.reopenCount ? "本轮预计解决时间" : "预计解决时间")
                    }}</strong
                    ><span>截止 {{ formatDate(clock.deadlineAt) }}</span>
                    <span v-if="clock.triggers.some(trigger => trigger.notificationStatus === 'PENDING_DELIVERY')">提醒暂未发出，系统将自动补发。</span>
                  </div>
                  <StatusBadge :value="clock.status" size="small" />
                </div>
                <details v-if="sla.historyClocks?.length">
                  <summary>查看历史处理时效</summary>
                  <div v-for="clock in sla.historyClocks" :key="clock.slaClockId" class="sla-row">
                    <div><strong>第 {{ (clock.cycleNo ?? 0) + 1 }} 轮 · {{ clock.clockType === 'FIRST_RESPONSE' ? '响应' : '解决' }}</strong>
                      <span>截止 {{ formatDate(clock.deadlineAt) }}</span></div>
                    <StatusBadge :value="clock.status" size="small" />
                  </div>
                </details>
              </div>
              <div v-else class="feature-muted">
                处理时效尚未开始，或当前状态暂无记录。
              </div>
            </div>
          </section>
          <section v-if="ticket.workflowRef || workflowStart" class="panel">
            <div class="panel-header">
              <div>
                <h2>审批进度</h2>
                <p>申请提交后，系统会在这里更新进展。</p>
              </div>
            </div>
            <div class="panel-body">
              <div v-if="workflowUnavailable" class="feature-muted">
                审批进度暂不可用。
              </div>
              <div v-else-if="workflowStart" class="workflow-status">
                <StatusBadge :value="workflowStart.status" />
                <dl>
                  <div>
                    <dt>审批申请</dt>
                    <dd>已提交</dd>
                  </div>
                  <div>
                    <dt>提交时间</dt>
                    <dd>{{ formatDate(workflowStart.requestedAt) }}</dd>
                  </div>
                  <div v-if="workflowStart.recoveryRequiredAt">
                    <dt>再次处理时间</dt>
                    <dd>{{ formatDate(workflowStart.recoveryRequiredAt) }}</dd>
                  </div>
                </dl>
              </div>
              <div v-else class="feature-muted">
                审批正在启动，请稍后刷新查看。
              </div>
            </div>
          </section>
          <section v-if="ticket.originType === 'INCIDENT'" class="panel">
            <div class="panel-header">
              <div>
                <h2>关联告警</h2>
                <p>查看触发此工单的告警。</p>
              </div>
            </div>
            <div class="panel-body">
              <ErrorNotice
                v-if="incidentLinkError"
                :error="incidentLinkError"
                @retry="load"
              />
              <div v-else-if="incidentLink" class="workflow-status">
                <StatusBadge :value="incidentLink.status" /><RouterLink
                  v-if="incidentLink.incidentId"
                  class="panel-link"
                  :to="`/alerts/${incidentLink.incidentId}`"
                  >查看告警</RouterLink
                ><span v-if="incidentLink.ticketId">已关联当前工单</span>
              </div>
              <div v-else class="feature-muted">关联告警信息暂不可见。</div>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </ResourceState>

  <div v-if="actionKind" class="modal-layer" @click.self="closeAction">
    <section
      class="modal-panel panel"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="`action-title-${actionKind}`"
    >
      <div class="panel-header">
        <div>
          <h2 :id="`action-title-${actionKind}`">{{ actionTitle }}</h2>
          <p>提交后系统会再次确认权限和最新状态。</p>
        </div>
        <button
          class="icon-button"
          type="button"
          aria-label="关闭"
          title="关闭"
          @click="closeAction"
        >
          <X :size="18" />
        </button>
      </div>
      <form class="panel-body" @submit.prevent="submitAction">
        <ErrorNotice
          v-if="actionError"
          :error="actionError"
          @retry="
            () => {
              actionError = null;
            }
          "
        />
        <div
          v-if="actionKind === 'assign' || actionKind === 'transfer'"
          class="form-field"
        >
          <label for="action-assignee">处理人</label
          ><select id="action-assignee" v-model="selectedAssignee" required>
            <option
              v-for="candidate in candidates"
              :key="candidate.userId"
              :value="candidate.userId"
            >
              {{ candidate.displayName }}（{{ candidate.username }}）
            </option></select
          ><span v-if="!candidates.length" class="form-help"
            >暂时没有可用的处理人，请稍后重试。</span
          >
        </div>
        <div
          v-if="['transfer', 'resolve', 'close', 'reopen'].includes(actionKind)"
          class="form-field"
        >
          <label for="action-reason"
            >{{ actionKind === "resolve" ? "解决摘要" : "原因"
            }}<span v-if="actionKind !== 'close'">（必填）</span></label
          ><textarea
            id="action-reason"
            v-model.trim="actionText"
            :required="actionKind !== 'close'"
            maxlength="1000"
          />
        </div>
        <div class="form-actions">
          <button
            class="button button--secondary"
            type="button"
            :disabled="actionLoading"
            @click="closeAction"
          >
            取消</button
          ><button
            class="button"
            type="submit"
            :disabled="
              actionLoading ||
              ((actionKind === 'assign' || actionKind === 'transfer') &&
                !selectedAssignee)
            "
          >
            <span v-if="actionLoading" class="spinner spinner--light" />{{
              actionLoading ? "提交中…" : "确认操作"
            }}
          </button>
        </div>
      </form>
    </section>
  </div>
</template>

<style scoped>
.detail-back {
  margin-bottom: 9px;
}
.detail-heading {
  align-items: flex-start;
}
.detail-heading .heading-actions {
  align-items: center;
}
.detail-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(280px, 0.55fr);
  gap: 18px;
}
.detail-main,
.detail-side {
  min-width: 0;
}
.panel-heading-icon {
  color: var(--teal);
}
.description {
  margin-bottom: 20px;
  color: #35464e;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
}
.facts-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  padding-top: 17px;
  border-top: 1px solid #edf1f2;
}
.facts-grid span,
.facts-grid strong {
  display: block;
}
.facts-grid span {
  color: var(--muted);
  font-size: 11px;
}
.facts-grid strong {
  margin-top: 5px;
  color: #35464e;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.action-panel .panel-header {
  align-items: center;
}
.action-buttons {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 9px;
  padding: 18px 21px;
}
.action-empty {
  font-size: 12px;
}
.comment-form textarea {
  width: 100%;
  min-height: 92px;
  padding: 9px 10px;
  border: 1px solid #cbd9dc;
  border-radius: 6px;
  resize: vertical;
  font-size: 13px;
}
.comment-form textarea:focus {
  border-color: #4da89f;
  outline: none;
  box-shadow: 0 0 0 3px rgba(77, 168, 159, 0.15);
}
.comment-form .form-actions {
  margin-top: 9px;
}
.comment-readonly {
  padding: 8px 0 17px;
  font-size: 12px;
}
.comment-list {
  display: grid;
  gap: 13px;
  margin-top: 17px;
  padding-top: 15px;
  border-top: 1px solid var(--line);
}
.comment-item {
  display: flex;
  align-items: flex-start;
  gap: 9px;
}
.avatar--small {
  width: 27px;
  height: 27px;
  flex: 0 0 27px;
  font-size: 11px;
}
.comment-item p {
  margin-bottom: 5px;
  color: #3d4d56;
  font-size: 13px;
  line-height: 1.55;
  white-space: pre-wrap;
}
.comment-item time {
  color: var(--muted);
  font-size: 10px;
}
.empty-inline {
  padding: 20px 0 3px;
  font-size: 12px;
}
.timeline-list {
  padding: 19px 21px 21px;
}
.timeline-item {
  position: relative;
  display: flex;
  gap: 12px;
  min-height: 52px;
}
.timeline-item:not(:last-child)::before {
  position: absolute;
  top: 14px;
  left: 4px;
  bottom: -8px;
  width: 1px;
  background: #d8e5e5;
  content: "";
}
.timeline-dot {
  position: relative;
  z-index: 1;
  width: 9px;
  height: 9px;
  flex: 0 0 9px;
  margin-top: 4px;
  border: 2px solid #8bc9c1;
  border-radius: 50%;
  background: #fff;
}
.timeline-item strong,
.timeline-item span {
  display: block;
}
.timeline-item strong {
  color: #3e5058;
  font-size: 12px;
}
.timeline-item span {
  margin-top: 4px;
  color: var(--muted);
  font-size: 11px;
}
.feature-muted {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}
.sla-list {
  display: grid;
  gap: 12px;
}
.sla-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-bottom: 11px;
  border-bottom: 1px solid #edf1f2;
}
.sla-row:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}
.sla-row strong,
.sla-row span {
  display: block;
}
.sla-row strong {
  font-size: 12px;
}
.sla-row span {
  margin-top: 4px;
  color: var(--muted);
  font-size: 10px;
}
.workflow-status {
  display: grid;
  gap: 13px;
}
.workflow-status dl {
  display: grid;
  gap: 8px;
  margin: 0;
}
.workflow-status dl > div {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.workflow-status dt {
  color: var(--muted);
  font-size: 11px;
}
.workflow-status dd {
  margin: 0;
  color: #40515a;
  font-size: 11px;
  text-align: right;
}
.modal-layer {
  position: fixed;
  z-index: 50;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(14, 34, 39, 0.5);
}
.modal-panel {
  width: min(540px, 100%);
  max-height: calc(100vh - 40px);
  overflow: auto;
}
.modal-panel .panel-header {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #fff;
}
@media (max-width: 900px) {
  .detail-layout {
    grid-template-columns: 1fr;
  }
  .detail-side {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .detail-side > .panel:last-child {
    grid-column: 1 / -1;
  }
}
@media (max-width: 620px) {
  .facts-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .detail-side {
    display: grid;
    grid-template-columns: 1fr;
  }
  .detail-side > .panel:last-child {
    grid-column: auto;
  }
  .action-buttons {
    padding: 15px 16px;
  }
  .timeline-list {
    padding: 16px;
  }
}
</style>
