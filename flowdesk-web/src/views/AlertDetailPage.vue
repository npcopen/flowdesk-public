<script setup lang="ts">
/** 读取事件或告警详情及关联工单，按接口权限显示当前可见资源。 */
import { computed, onMounted, ref } from "vue";
import {
  ArrowLeft,
  BellRing,
  CircleCheck,
  RefreshCw,
  ShieldAlert,
} from "lucide-vue-next";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { alertApi, ticketApi } from "@/api/endpoints";
import { isOpaqueResourceNotFound } from "@/api/errors";
import { useAuthStore } from "@/stores/auth";
import type {
  AlertEvent,
  Incident,
  IncidentTicketLink,
  Ticket,
} from "@/types/api";
import StatusBadge from "@/components/StatusBadge.vue";
import ResourceState from "@/components/ResourceState.vue";
import ErrorNotice from "@/components/ErrorNotice.vue";
import { formatDate } from "@/lib/format";

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const resourceId = computed(() => String(route.params.id));
const loading = ref(true);
const error = ref<unknown>(null);
const incident = ref<Incident | null>(null);
const event = ref<AlertEvent | null>(null);
const link = ref<IncidentTicketLink | null>(null);
const ticket = ref<Ticket | null>(null);
const linkError = ref<unknown>(null);
const ticketError = ref<unknown>(null);
/**
 * 加载告警聚合或接入事件详情，并独立读取工单关联。
 * 仅在聚合返回通用不存在时尝试事件视图，权限或服务错误不会被回退隐藏。
 */
async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  incident.value = null;
  event.value = null;
  link.value = null;
  ticket.value = null;
  linkError.value = null;
  ticketError.value = null;
  try {
    try {
      incident.value = (await alertApi.incident(resourceId.value)).data;
    } catch (reason) {
      // `/alerts/:id` is shared by Incident and AlertEvent links. Only the
      // documented opaque Incident 404 permits trying the event endpoint;
      // permission and service failures must remain visible to the user.
      if (!isOpaqueResourceNotFound(reason)) throw reason;
      event.value = (await alertApi.event(resourceId.value)).data;
    }
    if (incident.value) {
      try {
        link.value = (await alertApi.ticketLink(resourceId.value)).data;
      } catch (reason) {
        // A missing mapping is a valid in-progress/limited-visibility state,
        // but an outage or authorization decision must not be hidden.
        if (!isOpaqueResourceNotFound(reason)) linkError.value = reason;
      }
      if (link.value?.ticketId) {
        try {
          ticket.value = (await ticketApi.ticket(link.value.ticketId)).data;
        } catch (reason) {
          if (!isOpaqueResourceNotFound(reason)) ticketError.value = reason;
        }
      }
    }
  } catch (reason) {
    error.value = reason;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>

<template>
  <div class="detail-back">
    <button
      class="button button--ghost button--small"
      type="button"
      @click="router.back"
    >
      <ArrowLeft :size="15" />返回告警列表
    </button>
  </div>
  <ResourceState
    :loading="loading"
    :error="error"
    :empty="!loading && !error && !incident && !event"
    empty-text="告警详情不可见"
    @retry="load"
    ><template v-if="incident"
      ><div class="page-heading detail-heading">
        <div>
          <p class="eyebrow">告警详情</p>
          <h1>{{ incident.summary || "未命名告警" }}</h1>
          <p class="lede">这轮告警共记录 {{ incident.eventCount }} 次变化。</p>
        </div>
        <div class="heading-actions">
          <StatusBadge :value="incident.status" /><button
            class="button button--secondary"
            type="button"
            :disabled="loading"
            @click="load"
          >
            <RefreshCw :size="15" />刷新
          </button>
        </div>
      </div>
      <div class="alert-detail-grid">
        <section class="panel">
          <div class="panel-header">
            <div>
              <h2>告警状态</h2>
              <p>当前告警的最新处理状态。</p>
            </div>
            <CircleCheck
              v-if="incident.status === 'RECOVERED'"
              :size="19"
              class="panel-heading-icon"
            />
          </div>
          <div class="panel-body">
            <div class="facts-grid">
              <div>
                <span>事件数量</span><strong>{{ incident.eventCount }}</strong>
              </div>
              <div>
                <span>{{ link?.status === 'NOT_REQUIRED' ? '首次收到恢复记录' : '首次发生' }}</span
                ><strong>{{ formatDate(incident.openedAt) }}</strong>
              </div>
              <div>
                <span>最近更新</span
                ><strong>{{ formatDate(incident.lastEventAt) }}</strong>
              </div>
              <div>
                <span>恢复时间</span
                ><strong>{{ formatDate(incident.recoveredAt) }}</strong>
              </div>
            </div>
          </div>
        </section>
        <section class="panel">
          <div class="panel-header">
            <div>
              <h2>关联工单</h2>
              <p>关联建立完成前会明确显示处理进度。</p>
            </div>
            <BellRing :size="19" class="panel-heading-icon" />
          </div>
          <div class="panel-body">
            <ErrorNotice v-if="linkError" :error="linkError" @retry="load" />
            <div v-else-if="link" class="link-facts">
              <StatusBadge :value="link.status" />
              <div>
                <span>工单</span
                ><RouterLink
                  v-if="link.ticketId"
                  class="link-strong"
                  :to="`/tickets/${link.ticketId}`"
                  >{{ ticket?.ticketNo || "查看关联工单" }}</RouterLink
                ><strong v-else>{{ link.status === 'NOT_REQUIRED' ? '故障已恢复，无需派单' : '正在建立' }}</strong>
              </div>
              <div v-if="link.requestedAt">
                <span>开始时间</span
                ><strong>{{ formatDate(link.requestedAt) }}</strong>
              </div>
              <div v-if="link.recoveryRequiredAt">
                <span>重新处理时间</span
                ><strong>{{ formatDate(link.recoveryRequiredAt) }}</strong>
              </div>
            </div>
            <div v-else class="feature-muted">关联工单信息暂不可见。</div>
          </div>
        </section>
        <section v-if="ticket || ticketError" class="panel ticket-observe">
          <div class="panel-header">
            <div>
              <h2>关联工单进展</h2>
              <p>告警恢复后，工单仍需单独完成。</p>
            </div>
            <ShieldAlert :size="19" class="panel-heading-icon" />
          </div>
          <div class="panel-body">
            <ErrorNotice v-if="ticketError" :error="ticketError" @retry="load" />
            <template v-if="ticket">
            <RouterLink class="link-strong" :to="`/tickets/${ticket.ticketId}`"
              >{{ ticket.ticketNo }} · {{ ticket.subject }}</RouterLink
            >
            <div class="observe-status">
              <StatusBadge :value="ticket.status" /><span
              >最后更新 {{ formatDate(ticket.updatedAt) }}</span
              >
            </div>
            </template>
          </div>
        </section>
      </div></template
    ><template v-else-if="event"
      ><div class="page-heading detail-heading">
        <div>
          <p class="eyebrow">告警更新</p>
          <h1>{{ event.summary || "告警事件" }}</h1>
          <p class="lede">查看这次告警更新的处理结果和时间。</p>
        </div>
        <div class="heading-actions">
          <StatusBadge :value="event.eventType" /><button
            class="button button--secondary"
            type="button"
            :disabled="loading"
            @click="load"
          >
            <RefreshCw :size="15" />刷新
          </button>
        </div>
      </div>
      <section class="panel">
        <div class="panel-header">
          <div><h2>事件摘要</h2></div>
        </div>
        <div class="panel-body">
          <div class="facts-grid">
            <div>
              <span>处理结果</span
              ><StatusBadge :value="event.disposition" size="small" />
            </div>
            <div>
              <span>关联状态</span
              ><strong>{{ event.incidentId ? "已关联告警" : "暂无关联告警" }}</strong>
            </div>
            <div>
              <span>发生时间</span
              ><strong>{{ formatDate(event.occurredAt) }}</strong>
            </div>
            <div>
              <span>接收时间</span
              ><strong>{{ formatDate(event.receivedAt) }}</strong>
            </div>
          </div>
          <div class="digest-block">
            <span>来源</span><strong>{{ event.integrationRef.integrationName }}</strong>
          </div>
        </div>
      </section></template
    ></ResourceState
  >
</template>

<style scoped>
.detail-back {
  margin-bottom: 9px;
}
.detail-heading {
  align-items: flex-start;
}
.panel-heading-icon {
  color: var(--teal);
}
.alert-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}
.alert-detail-grid > .ticket-observe {
  grid-column: 1 / -1;
}
.facts-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
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
.link-facts {
  display: grid;
  gap: 13px;
}
.link-facts > div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}
.link-facts span {
  color: var(--muted);
  font-size: 11px;
}
.link-facts strong,
.link-facts a {
  font-size: 12px;
  text-align: right;
}
.feature-muted {
  color: var(--muted);
  font-size: 12px;
}
.observe-status {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 15px;
  padding-top: 13px;
  border-top: 1px solid var(--line);
  color: var(--muted);
  font-size: 11px;
}
.digest-block {
  display: grid;
  gap: 7px;
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}
.digest-block span {
  color: var(--muted);
  font-size: 11px;
}
.digest-block code {
  overflow-wrap: anywhere;
  color: #40515a;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}
@media (max-width: 700px) {
  .alert-detail-grid {
    grid-template-columns: 1fr;
  }
  .alert-detail-grid > .ticket-observe {
    grid-column: auto;
  }
  .facts-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
