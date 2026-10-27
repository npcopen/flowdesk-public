<script setup lang="ts">
/** 展示工单 SLA、审批启动和告警关联，保留各能力独立的不可用或失败状态。 */
import { RouterLink } from "vue-router";
import { featureFlags, featureNotice } from "@/api/features";
import StatusBadge from "@/components/StatusBadge.vue";
import ErrorNotice from "@/components/ErrorNotice.vue";
import { formatDate } from "@/lib/format";
import type { IncidentTicketLink, SlaStatus, Ticket, WorkflowStartStatus } from "@/types/api";

defineProps<{
  ticket: Ticket;
  sla: SlaStatus | null;
  slaUnavailable: boolean;
  slaNotStarted: boolean;
  workflowStart: WorkflowStartStatus | null;
  workflowUnavailable: boolean;
  incidentLink: IncidentTicketLink | null;
  incidentLinkError: unknown;
}>();
const emit = defineEmits<{ retry: [] }>();
</script>

<template>
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
          @retry="emit('retry')"
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
</template>

<style scoped>
.detail-side {
  min-width: 0;
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
@media (max-width: 900px) {
  .detail-side {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .detail-side > .panel:last-child {
    grid-column: 1 / -1;
  }
}
@media (max-width: 620px) {
  .detail-side {
    display: grid;
    grid-template-columns: 1fr;
  }
  .detail-side > .panel:last-child {
    grid-column: auto;
  }
}
</style>
