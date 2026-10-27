<script setup lang="ts">
/** 展示告警与原始事件，并分别维护加载状态、错误及告警分页。 */
import { computed, onMounted, ref } from "vue";
import {
  Activity,
  ArrowUpRight,
  RefreshCw,
  ShieldAlert,
} from "lucide-vue-next";
import { RouterLink } from "vue-router";
import { alertApi } from "@/api/endpoints";
import { useAuthStore } from "@/stores/auth";
import type { AlertEvent, Incident } from "@/types/api";
import StatusBadge from "@/components/StatusBadge.vue";
import ResourceState from "@/components/ResourceState.vue";
import PaginationControls from "@/components/PaginationControls.vue";
import { formatDate } from "@/lib/format";
import RecoveryPanel from "@/components/RecoveryPanel.vue";

const auth = useAuthStore();
const loading = ref(true);
const eventsLoading = ref(false);
const error = ref<unknown>(null);
const eventsError = ref<unknown>(null);
const incidents = ref<Incident[]>([]);
const events = ref<AlertEvent[]>([]);
const nextToken = ref<string | null>(null);
const tokenStack = ref<string[]>([]);
const currentToken = ref("");
const statusFilter = ref("");
const busy = computed(() => loading.value || eventsLoading.value);
const isAdmin = computed(
  () =>
    auth.hasRole("SERVICE_DESK_ADMIN") && auth.can("ALERT_EVENT_READ_TENANT"),
);
const canReadIncidents = computed(() => auth.can("INCIDENT_READ_TENANT"));
const canReadRecovery = computed(
  () => auth.hasRole("SERVICE_DESK_ADMIN") && auth.can("RECOVERY_READ_TENANT"),
);

/**
 * 按当前状态筛选和游标读取告警聚合列表。
 * 查询前清理旧页数据，失败时保留明确反馈，避免把旧结果当作新页。
 */
async function loadIncidents(token = ""): Promise<void> {
  if (!canReadIncidents.value) {
    loading.value = false;
    incidents.value = [];
    nextToken.value = null;
    return;
  }
  loading.value = true;
  error.value = null;
  currentToken.value = token;
  // Replace the visible page while it is being fetched. This prevents an old
  // page from being presented as current when a refresh or cursor request fails.
  incidents.value = [];
  nextToken.value = null;
  try {
    const result = await alertApi.incidents({
      pageSize: 20,
      pageToken: token || undefined,
      status: statusFilter.value || undefined,
      sort: "-lastEventAt",
    });
    incidents.value = result.items;
    nextToken.value = result.meta.page?.nextPageToken ?? null;
  } catch (reason) {
    error.value = reason;
    incidents.value = [];
    nextToken.value = null;
  } finally {
    loading.value = false;
  }
}
/**
 * 加载告警接入事件列表。
 * 仅对具备相应管理权限的用户查询，单独维护加载和错误状态。
 */
async function loadEvents(): Promise<void> {
  if (!isAdmin.value) {
    eventsLoading.value = false;
    events.value = [];
    return;
  }
  eventsLoading.value = true;
  eventsError.value = null;
  events.value = [];
  try {
    const result = await alertApi.events({ pageSize: 20, sort: "-receivedAt" });
    events.value = result.items;
  } catch (reason) {
    eventsError.value = reason;
    events.value = [];
  } finally {
    eventsLoading.value = false;
  }
}
/**
 * 将告警聚合分页重置至第一页并刷新相关列表。
 * 事件与聚合分别加载，互不覆盖请求结果。
 */
async function refresh(): Promise<void> {
  tokenStack.value = [];
  currentToken.value = "";
  await Promise.all([
    loadIncidents(""),
    loadEvents(),
  ]);
}
/**
 * 保存当前游标并读取下一页告警聚合。
 * 没有下一页游标时保持当前列表不变。
 */
function nextPage(): void {
  if (nextToken.value && !loading.value) {
    tokenStack.value.push(nextToken.value);
    void loadIncidents(nextToken.value);
  }
}
/**
 * 从历史游标栈恢复上一页告警聚合。
 * 页码与筛选条件保持一致，避免重新从整个集合计算偏移。
 */
function previousPage(): void {
  if (loading.value) return;
  if (tokenStack.value.length < 2) {
    tokenStack.value = [];
    void loadIncidents("");
    return;
  }
  tokenStack.value.pop();
  void loadIncidents(tokenStack.value[tokenStack.value.length - 1] || "");
}
/**
 * 在告警状态筛选变化后重置分页。
 * 使用新条件重新读取第一页，避免沿用旧筛选的游标。
 */
function filterChanged(): void {
  tokenStack.value = [];
  void loadIncidents("");
}
/**
 * 使用当前页游标重试告警聚合查询。
 * 保留用户所在页及筛选条件，便于临时故障后恢复。
 */
function retryIncidents(): void {
  void loadIncidents(currentToken.value);
}
onMounted(refresh);
</script>

<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">运行监控</p>
      <h1>告警</h1>
      <p class="lede">
        集中查看告警、处理状态及关联工单。告警恢复后，工单仍需单独处理。
      </p>
    </div>
    <div class="heading-actions">
      <button
        class="button button--secondary"
        type="button"
        :disabled="busy"
        @click="refresh"
      >
        <RefreshCw :size="16" />刷新
      </button>
    </div>
  </div>
  <div class="callout callout--info">
    <ShieldAlert :size="17" /><span
      >告警由受控监控渠道接入，页面只展示处理所需的信息。</span
    >
  </div>
  <section v-if="canReadIncidents" class="panel list-panel">
    <div class="filter-bar">
      <Activity :size="16" class="filter-icon" />
      <div class="filter-group">
        <label for="incident-status">告警状态</label
        ><select
          id="incident-status"
          v-model="statusFilter"
          class="filter-control"
          @change="filterChanged"
        >
          <option value="">全部状态</option>
          <option value="ACTIVE">进行中</option>
          <option value="RECOVERED">已恢复</option>
        </select>
      </div>
      <div class="filter-summary">{{ incidents.length }} 条当前页告警</div>
    </div>
    <ResourceState
      :loading="loading"
      :error="error"
      :empty="!loading && !error && incidents.length === 0"
      empty-text="暂无告警"
      @retry="retryIncidents"
      ><div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>告警</th>
              <th>告警内容</th>
              <th>状态</th>
              <th>发生次数</th>
              <th>最近变化</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="incident in incidents" :key="incident.incidentId">
              <td>
                <RouterLink
                  class="link-strong"
                  :to="`/alerts/${incident.incidentId}`"
                  >查看详情</RouterLink
                ><small class="table-subtext"
                  >第 {{ incident.generationNo }} 轮告警</small
                >
              </td>
              <td>
                <strong class="table-subject">{{
                  incident.summary || "未命名告警"
                }}</strong>
              </td>
              <td><StatusBadge :value="incident.status" /></td>
              <td>{{ incident.eventCount }}</td>
              <td>{{ formatDate(incident.lastEventAt) }}</td>
              <td>
                <RouterLink
                  class="icon-link"
                  :to="`/alerts/${incident.incidentId}`"
                  aria-label="查看告警"
                  title="查看告警"
                  ><ArrowUpRight :size="16"
                /></RouterLink>
              </td>
            </tr>
          </tbody>
        </table></div></ResourceState
    ><PaginationControls
      :has-next="Boolean(nextToken)"
      :has-previous="tokenStack.length > 0"
      :disabled="loading"
      :label="`第 ${tokenStack.length + 1} 页`"
      @next="nextPage"
      @previous="previousPage"
    />
  </section>
  <section v-if="isAdmin" class="panel events-panel">
    <div class="panel-header">
      <div>
        <h2>告警记录</h2>
        <p>显示告警更新的时间和处理结果。</p>
      </div>
    </div>
    <ResourceState
      :loading="eventsLoading"
      :error="eventsError"
      :empty="!eventsError && events.length === 0"
      empty-text="暂无告警记录"
      @retry="loadEvents"
      ><div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>更新</th>
              <th>变化</th>
              <th>处理结果</th>
              <th>关联告警</th>
              <th>接收时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="event in events" :key="event.alertEventId">
              <td>
                <RouterLink
                  class="link-strong"
                  :to="`/alerts/${event.alertEventId}`"
                  >查看更新</RouterLink
                ><small class="table-subtext">{{ event.summary }}</small>
              </td>
              <td><StatusBadge :value="event.eventType" /></td>
              <td><StatusBadge :value="event.disposition" size="small" /></td>
              <td>{{ event.incidentId ? "已关联" : "未关联" }}</td>
              <td>{{ formatDate(event.receivedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div></ResourceState
    >
  </section>
  <RecoveryPanel v-if="canReadRecovery" />
</template>

<style scoped>
.filter-icon {
  color: var(--teal);
  margin: 0 3px 9px 2px;
}
.filter-summary {
  margin-left: auto;
  padding-bottom: 9px;
  color: var(--muted);
  font-size: 11px;
}
.callout {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  margin: -7px 0 18px;
  padding: 11px 13px;
  border: 1px solid #cfe1f7;
  border-radius: 7px;
  color: #315c90;
  background: var(--blue-soft);
  font-size: 12px;
  line-height: 1.5;
}
.panel-heading-icon {
  color: var(--teal);
}
.events-panel {
  margin-top: 18px;
}
@media (max-width: 760px) {
  .filter-summary {
    width: 100%;
    margin-left: 0;
  }
}
</style>
