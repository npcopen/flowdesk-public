<script setup lang="ts">
/** 按当前用户的可见权限并行加载工作台摘要和待处理事项。 */
import { computed, onMounted, ref } from "vue";
import {
  ArrowUpRight,
  ClipboardCheck,
  Inbox,
  Plus,
  RefreshCw,
  Siren,
  Ticket as TicketIcon,
  Users,
} from "lucide-vue-next";
import { RouterLink } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import {
  alertApi,
  notificationApi,
  ticketApi,
  workflowApi,
} from "@/api/endpoints";
import { isCapabilityUnavailable, isFeatureUnavailableError } from "@/api/errors";
import { featureFlags, featureNotice } from "@/api/features";
import type { ApprovalTask, Incident, Notification, Ticket } from "@/types/api";
import { formatDate, label } from "@/lib/format";
import StatusBadge from "@/components/StatusBadge.vue";
import ResourceState from "@/components/ResourceState.vue";
import ErrorNotice from "@/components/ErrorNotice.vue";
import TenantAdminPanel from "@/components/TenantAdminPanel.vue";

const auth = useAuthStore();
const loading = ref(true);
const error = ref<unknown>(null);
const tickets = ref<Ticket[]>([]);
const tasks = ref<ApprovalTask[]>([]);
const incidents = ref<Incident[]>([]);
const notifications = ref<Notification[]>([]);
const unreadCount = ref<number | null>(null);
const notificationUnavailable = ref(false);
const notificationCountUnavailable = ref(false);
const notificationError = ref<unknown>(null);

const canSeeApprovals = computed(() =>
  auth.canAny(["APPROVAL_READ_ASSIGNED", "WORKFLOW_RUNTIME_READ_TENANT"]),
);
const canSeeTickets = computed(() =>
  auth.canAny(["TICKET_READ_OWN", "TICKET_READ_ASSIGNED", "TICKET_READ_TENANT"]),
);
const canSeeAlerts = computed(() => auth.can("INCIDENT_READ_TENANT"));
const canSeeNotifications = computed(() => auth.can("NOTIFICATION_READ_SELF"));
const canManageTenant = computed(
  () =>
    auth.hasRole("TENANT_ADMIN") &&
    auth.canAny([
      "IAM_USER_READ",
      "IAM_USER_MANAGE",
      "IAM_ROLE_READ",
      "IAM_ROLE_MANAGE",
      "IAM_PERMISSION_READ",
      "IAM_BINDING_READ",
      "IAM_BINDING_MANAGE",
    ]),
);
const pendingTickets = ref<number | null>(null);
const pendingTasks = ref<number | null>(null);
const activeIncidents = ref<number | null>(null);

/**
 * 按当前身份权限加载仪表盘汇总及业务列表。
 * 通知等可选能力单独降级，避免辅助服务异常掩盖主要运营数据。
 */
async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  notificationUnavailable.value = false;
  tickets.value = [];
  tasks.value = [];
  incidents.value = [];
  notifications.value = [];
  unreadCount.value = null;
  notificationCountUnavailable.value = false;
  notificationError.value = null;
  pendingTickets.value = pendingTasks.value = activeIncidents.value = null;
  const jobs: Promise<unknown>[] = [];
  if (canSeeTickets.value)
    jobs.push(
      ticketApi.tickets({ pageSize: 8, sort: "-updatedAt" }).then(result => { tickets.value = result.items; }),
      ticketApi.summary().then(result => { pendingTickets.value = result.data.pendingCount; }),
    );
  if (canSeeApprovals.value)
    jobs.push(
      workflowApi.tasks({ pageSize: 8, status: "PENDING", sort: "-createdAt" }).then(result => { tasks.value = result.items; }),
      workflowApi.taskSummary().then(result => { pendingTasks.value = result.data.pendingCount; }),
    );
  if (canSeeAlerts.value)
    jobs.push(
      alertApi.incidents({ pageSize: 8, sort: "-lastEventAt" }).then(result => { incidents.value = result.items; }),
      alertApi.summary().then(result => { activeIncidents.value = result.data.activeCount; }),
    );
  try {
    const results = await Promise.allSettled(jobs);
    results.forEach((result) => {
      if (result.status === "rejected" && !error.value) {
        error.value = result.reason;
      }
    });
    if (canSeeNotifications.value) {
      const [listResult, countResult] = await Promise.allSettled([
        notificationApi.list({ pageSize: 5, sort: "-createdAt" }),
        notificationApi.unreadCount(),
      ]);
      if (listResult.status === "fulfilled") {
        notifications.value = listResult.value.items;
      } else if (
        isFeatureUnavailableError(listResult.reason) ||
        isCapabilityUnavailable(listResult.reason, "notification")
      ) {
        notificationUnavailable.value = true;
      } else {
        notificationError.value = listResult.reason;
      }
      if (countResult.status === "fulfilled") {
        unreadCount.value = countResult.value.data.unreadCount;
      } else if (
        isFeatureUnavailableError(countResult.reason) ||
        isCapabilityUnavailable(countResult.reason, "notification")
      ) {
        notificationCountUnavailable.value = true;
      } else {
        notificationError.value = countResult.reason;
      }
    } else {
      notificationUnavailable.value = true;
    }
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">工作概览</p>
      <h1>你好，{{ auth.user?.displayName }}</h1>
      <p class="lede">这里汇总与你相关的工单、审批和运行信息。</p>
    </div>
    <div class="heading-actions">
      <RouterLink
        v-if="auth.can('TICKET_CREATE')"
        to="/tickets?create=1"
        class="button"
        ><Plus :size="16" />新建工单</RouterLink
      >
      <button
        class="button button--secondary"
        type="button"
        :disabled="loading"
        @click="load"
      >
        <RefreshCw :size="16" />刷新
      </button>
    </div>
  </div>

  <ErrorNotice v-if="error" :error="error" @retry="load" />
  <ResourceState :loading="loading" :empty="false">
    <div class="grid grid--4 metric-grid">
      <div class="metric-tile">
        <div class="metric-icon metric-icon--teal">
          <TicketIcon :size="19" />
        </div>
        <div>
          <span>待处理工单</span><strong>{{ pendingTickets ?? "—" }}</strong
          ><small>{{ canSeeTickets ? "当前可见范围全部待办" : "当前角色不可见" }}</small>
        </div>
      </div>
      <div class="metric-tile">
        <div class="metric-icon metric-icon--amber">
          <ClipboardCheck :size="19" />
        </div>
        <div>
          <span>待审批任务</span
          ><strong>{{ pendingTasks ?? "—" }}</strong
          ><small>{{
            canSeeApprovals ? (auth.can("WORKFLOW_RUNTIME_READ_TENANT") ? "当前租户全部待审批" : "分配给你的全部待审批") : "当前角色不可见"
          }}</small>
        </div>
      </div>
      <div class="metric-tile">
        <div class="metric-icon metric-icon--red"><Siren :size="19" /></div>
        <div>
          <span>进行中告警</span
          ><strong>{{ activeIncidents ?? "—" }}</strong
          ><small>{{ canSeeAlerts ? "当前租户全部进行中告警" : "当前角色不可见" }}</small>
        </div>
      </div>
      <div class="metric-tile">
        <div class="metric-icon metric-icon--blue"><Inbox :size="19" /></div>
        <div>
          <span>未读通知</span
          ><strong>{{
            notificationUnavailable || notificationCountUnavailable
              ? "—"
              : (unreadCount ?? "…")
          }}</strong
          ><small>{{
            notificationUnavailable
              ? "通知暂不可用"
              : notificationCountUnavailable
                ? "未读数量暂不可用"
                : "仅当前账号"
          }}</small>
        </div>
      </div>
    </div>

    <div class="dashboard-grid">
      <section class="panel dashboard-panel">
        <div class="panel-header">
          <div>
            <h2>最近工单</h2>
            <p>按最近更新时间显示</p>
          </div>
          <RouterLink v-if="canSeeTickets" to="/tickets" class="panel-link"
            >查看全部 <ArrowUpRight :size="14"
          /></RouterLink><span v-else class="panel-link muted">当前账号不可见</span>
        </div>
        <ResourceState
          v-if="!canSeeTickets"
          :empty="true"
          empty-text="当前账号无工单权限"
        />
        <ResourceState
          v-else
          :loading="false"
          :empty="tickets.length === 0"
          empty-text="暂无工单"
        >
          <div class="compact-list">
            <RouterLink
              v-for="ticket in tickets.slice(0, 6)"
              :key="ticket.ticketId"
              :to="`/tickets/${ticket.ticketId}`"
              class="compact-row"
            >
              <div class="compact-row-main">
                <strong>{{ ticket.ticketNo }}</strong
                ><span>{{ ticket.subject }}</span>
              </div>
              <div class="compact-row-meta">
                <StatusBadge :value="ticket.status" size="small" /><time>{{
                  formatDate(ticket.updatedAt)
                }}</time>
              </div>
            </RouterLink>
          </div>
        </ResourceState>
      </section>
      <section class="panel dashboard-panel">
        <div class="panel-header">
          <div>
            <h2>待办审批</h2>
            <p>只展示分配给你的任务</p>
          </div>
          <RouterLink v-if="canSeeApprovals" to="/approvals" class="panel-link"
            >查看全部 <ArrowUpRight :size="14"
          /></RouterLink>
        </div>
        <ResourceState
          v-if="!canSeeApprovals"
          :empty="true"
          empty-text="当前角色无审批待办"
        />
        <ResourceState
          v-else
          :loading="false"
          :empty="tasks.length === 0"
          empty-text="暂无待审批任务"
        >
          <div class="compact-list">
            <RouterLink
              v-for="task in tasks.slice(0, 5)"
              :key="task.approvalTaskId"
              :to="`/approvals/${task.approvalTaskId}`"
              class="compact-row"
            >
              <div class="compact-row-main">
                <strong>审批申请</strong
                ><span>第 {{ task.levelNo }} 级待处理</span>
              </div>
              <div class="compact-row-meta">
                <StatusBadge :value="task.status" size="small" /><time>{{
                  formatDate(task.createdAt)
                }}</time>
              </div>
            </RouterLink>
          </div>
        </ResourceState>
      </section>
      <section class="panel dashboard-panel">
        <div class="panel-header">
          <div>
            <h2>运行告警</h2>
            <p>查看告警及其关联工单</p>
          </div>
          <RouterLink v-if="canSeeAlerts" to="/alerts" class="panel-link"
            >查看全部 <ArrowUpRight :size="14"
          /></RouterLink>
        </div>
        <ResourceState
          v-if="!canSeeAlerts"
          :empty="true"
          empty-text="当前角色无告警权限"
        />
        <ResourceState
          v-else
          :loading="false"
          :empty="incidents.length === 0"
          empty-text="暂无告警"
        >
          <div class="compact-list">
            <RouterLink
              v-for="incident in incidents.slice(0, 5)"
              :key="incident.incidentId"
              :to="`/alerts/${incident.incidentId}`"
              class="compact-row"
            >
              <div class="compact-row-main">
                <strong>{{ incident.summary || "未命名告警" }}</strong
                ><span>{
                  incident.status === "ACTIVE" ? "正在处理" : "已恢复"
                }</span>
              </div>
              <div class="compact-row-meta">
                <StatusBadge :value="incident.status" size="small" /><time>{{
                  formatDate(incident.lastEventAt)
                }}</time>
              </div>
            </RouterLink>
          </div>
        </ResourceState>
      </section>
      <section class="panel dashboard-panel">
        <div class="panel-header">
          <div>
            <h2>通知摘要</h2>
            <p>及时了解重要更新</p>
          </div>
          <RouterLink v-if="canSeeNotifications" to="/notifications" class="panel-link"
            >打开通知 <ArrowUpRight :size="14"
          /></RouterLink><span v-else class="panel-link muted">当前账号不可见</span>
        </div>
        <ResourceState
          v-if="!canSeeNotifications"
          :empty="true"
          empty-text="当前账号无通知权限"
        />
        <ResourceState
          v-else-if="notificationUnavailable"
          :empty="true"
          empty-text="通知暂时无法提供"
        />
        <ResourceState
          v-else-if="notificationError"
          :error="notificationError"
          :empty="false"
          @retry="load"
        />
        <ResourceState
          v-else
          :loading="false"
          :empty="notifications.length === 0"
          empty-text="暂无通知"
        >
          <div class="compact-list">
            <RouterLink
              v-for="notice in notifications.slice(0, 4)"
              :key="notice.notificationId"
              to="/notifications"
              class="compact-row"
            >
              <div class="compact-row-main">
                <strong>{{ notice.title }}</strong
                ><span>{{ notice.body }}</span>
              </div>
              <div class="compact-row-meta">
                <StatusBadge :value="notice.state" size="small" /><time>{{
                  formatDate(notice.createdAt)
                }}</time>
              </div>
            </RouterLink>
          </div>
          <div v-if="notificationCountUnavailable" class="feature-inline">
            未读数量暂时无法获取。
          </div>
          <div v-else-if="featureFlags.notification === 'mock'" class="feature-inline">
            {{ featureNotice(featureFlags.notification) }}
          </div>
        </ResourceState>
      </section>
    </div>
  </ResourceState>

  <section v-if="canManageTenant" class="tenant-admin-section">
    <div class="section-heading">
      <div>
        <p class="eyebrow">组织设置</p>
        <h2>组织管理</h2>
        <p class="lede">在这里维护成员、角色、可用操作和授权关系。</p>
      </div>
      <Users :size="22" class="section-heading-icon" />
    </div>
    <TenantAdminPanel />
  </section>
</template>

<style scoped>
.metric-grid {
  margin-bottom: 20px;
}
.metric-tile {
  display: flex;
  align-items: center;
  gap: 13px;
  min-height: 112px;
  padding: 17px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
  box-shadow: var(--shadow);
}
.metric-icon {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  border-radius: 10px;
}
.metric-icon--teal {
  color: #087b72;
  background: #e2f5f0;
}
.metric-icon--amber {
  color: #9b6508;
  background: #fff3cf;
}
.metric-icon--red {
  color: #b42318;
  background: #ffebe8;
}
.metric-icon--blue {
  color: #275dad;
  background: #eaf1ff;
}
.metric-tile span,
.metric-tile small {
  display: block;
  color: var(--muted);
  font-size: 11px;
}
.metric-tile strong {
  display: block;
  margin: 3px 0;
  color: var(--ink);
  font-size: 25px;
  line-height: 1;
}
.metric-tile small {
  font-size: 10px;
}
.dashboard-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}
.dashboard-panel .panel-header {
  align-items: center;
}
.panel-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}
.compact-list {
  display: grid;
}
.feature-inline {
  margin: 0 20px;
  padding: 10px 0 0;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: 11px;
  line-height: 1.5;
}
.compact-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 20px;
  border-bottom: 1px solid #edf1f2;
}
.compact-row:last-child {
  border-bottom: 0;
}
.compact-row:hover {
  background: #fbfdfd;
}
.compact-row-main {
  min-width: 0;
}
.compact-row-main strong,
.compact-row-main span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.compact-row-main strong {
  color: var(--teal-dark);
  font-size: 12px;
}
.compact-row-main span {
  max-width: 310px;
  margin-top: 4px;
  color: #4a5a63;
  font-size: 12px;
}
.compact-row-meta {
  display: flex;
  align-items: flex-end;
  flex-direction: column;
  gap: 5px;
  flex: 0 0 auto;
}
.compact-row-meta time {
  color: #829099;
  font-size: 10px;
  white-space: nowrap;
}
.tenant-admin-section {
  margin-top: 38px;
}
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.section-heading h2 {
  margin-bottom: 4px;
}
.section-heading .lede {
  font-size: 12px;
}
.section-heading-icon {
  color: var(--teal);
}
@media (max-width: 760px) {
  .dashboard-grid {
    grid-template-columns: 1fr;
  }
  .compact-row {
    padding: 12px 15px;
  }
  .compact-row-main span {
    max-width: 180px;
  }
}
</style>
