<script setup lang="ts">
/** 组装工单详情页面，复用详情状态与操作组合函数连接各业务面板。 */
import { computed, onMounted } from "vue";
import { ArrowLeft, RefreshCw } from "lucide-vue-next";
import { useRoute, useRouter } from "vue-router";
import StatusBadge from "@/components/StatusBadge.vue";
import ErrorNotice from "@/components/ErrorNotice.vue";
import ResourceState from "@/components/ResourceState.vue";
import AsyncProgress from "@/components/AsyncProgress.vue";
import { formatDate } from "@/lib/format";
import TicketActionPanel from "./ticket-detail/TicketActionPanel.vue";
import TicketActionDialog from "./ticket-detail/TicketActionDialog.vue";
import TicketActivityPanels from "./ticket-detail/TicketActivityPanels.vue";
import TicketStatusPanels from "./ticket-detail/TicketStatusPanels.vue";
import { useTicketDetail } from "./ticket-detail/useTicketDetail";
import { useTicketActions } from "./ticket-detail/useTicketActions";

const route = useRoute();
const router = useRouter();
const ticketId = computed(() => String(route.params.id));
const detail = useTicketDetail(ticketId);
const {
  currentUserId, loading, error, ticket,
  comments, timeline, commentsError, timelineError,
  sla, slaUnavailable, slaNotStarted, workflowStart, workflowUnavailable,
  candidates, incidentLink, incidentLinkError, actionError,
  isOwner, isAssignee, canAssign, canTransfer, canAccept, canResolve,
  canClose, canReopen, canComment, load,
} = detail;
const {
  actionKind, actionTitle, actionText, selectedAssignee, actionLoading,
  commentText, commentLoading, effects,
  openAction, closeAction, submitAction, addComment,
} = useTicketActions(detail);

onMounted(load);
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
          <TicketActionPanel
            :can-assign="canAssign"
            :can-accept="canAccept"
            :can-transfer="canTransfer"
            :can-resolve="canResolve"
            :can-close="canClose"
            :can-reopen="canReopen"
            @open="openAction"
          />
          <TicketActivityPanels
            v-model:comment-text="commentText"
            :comments="comments"
            :timeline="timeline"
            :comments-error="commentsError"
            :timeline-error="timelineError"
            :can-comment="canComment"
            :comment-loading="commentLoading"
            :current-user-id="currentUserId"
            @retry="load"
            @comment="addComment"
          />
        </div>
        <TicketStatusPanels
          :ticket="ticket"
          :sla="sla"
          :sla-unavailable="slaUnavailable"
          :sla-not-started="slaNotStarted"
          :workflow-start="workflowStart"
          :workflow-unavailable="workflowUnavailable"
          :incident-link="incidentLink"
          :incident-link-error="incidentLinkError"
          @retry="load"
        />
      </div>
    </template>
  </ResourceState>

  <TicketActionDialog
    v-model:action-text="actionText"
    v-model:selected-assignee="selectedAssignee"
    :action-kind="actionKind"
    :action-title="actionTitle"
    :action-loading="actionLoading"
    :action-error="actionError"
    :candidates="candidates"
    @close="closeAction"
    @submit="submitAction"
    @clear-error="actionError = null"
  />
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
.detail-main {
  min-width: 0;
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
@media (max-width: 900px) {
  .detail-layout {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 620px) {
  .facts-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
