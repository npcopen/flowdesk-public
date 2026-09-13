<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Bell, Check, CheckCheck, Eye, RefreshCw, X } from "lucide-vue-next";
import { notificationApi, ticketApi } from "@/api/endpoints";
import { useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { featureFlags, featureNotice } from "@/api/features";
import { isCapabilityUnavailable, isFeatureUnavailableError } from "@/api/errors";
import type { Notification } from "@/types/api";
import StatusBadge from "@/components/StatusBadge.vue";
import ResourceState from "@/components/ResourceState.vue";
import ErrorNotice from "@/components/ErrorNotice.vue";
import PaginationControls from "@/components/PaginationControls.vue";
import {
  formatDate,
  notificationKindLabel,
  sourceDomainLabel,
} from "@/lib/format";

const loading = ref(true);
const router = useRouter();
const auth = useAuthStore();
const openingRelated = ref(false);
const relatedError = ref<unknown>(null);
function relatedLabel(notification: Notification): string | null {
  if (notification.sourceDomain === "TICKET" && ["TICKET", "SLA_CLOCK"].includes(notification.sourceRefType)
      && auth.canAny(["TICKET_READ_OWN", "TICKET_READ_ASSIGNED", "TICKET_READ_TENANT"])) return "查看工单";
  if (notification.sourceDomain === "WORKFLOW" && notification.sourceRefType === "APPROVAL_TASK"
      && auth.canAny(["APPROVAL_READ_ASSIGNED", "WORKFLOW_RUNTIME_READ_TENANT"])) return "查看审批";
  return null;
}
async function openRelated(notification: Notification): Promise<void> {
  if (openingRelated.value || !relatedLabel(notification)) return;
  openingRelated.value = true;
  relatedError.value = null;
  try {
    const id = notification.sourceRefType === "SLA_CLOCK"
      ? (await ticketApi.ticketForSlaClock(notification.sourceRefId)).data.ticketId
      : notification.sourceRefId;
    await router.push(`/${notification.sourceRefType === "APPROVAL_TASK" ? "approvals" : "tickets"}/${encodeURIComponent(id)}`);
  } catch (reason) { relatedError.value = reason; }
  finally { openingRelated.value = false; }
}
const error = ref<unknown>(null);
const unavailable = ref(false);
const countUnavailable = ref(false);
const countError = ref<unknown>(null);
const notifications = ref<Notification[]>([]);
const unreadCount = ref<number | null>(null);
const nextToken = ref<string | null>(null);
const tokenStack = ref<string[]>([]);
const savingId = ref<string | null>(null);
const mutationError = ref<unknown>(null);
const mutationUnavailable = ref(false);
const selectedNotification = ref<Notification | null>(null);
const modeText = computed(() => featureNotice(featureFlags.notification));
const currentToken = ref("");

async function load(token = "", reset = false): Promise<void> {
  loading.value = true;
  error.value = null;
  countError.value = null;
  unavailable.value = false;
  countUnavailable.value = false;
  mutationError.value = null;
  mutationUnavailable.value = false;
  currentToken.value = token;
  if (reset) {
    notifications.value = [];
    unreadCount.value = null;
    nextToken.value = null;
    tokenStack.value = [];
    selectedNotification.value = null;
  }
  // A cursor request replaces the visible page. Keeping the previous page
  // around after a failed request would make it look like current data.
  if (!reset) {
    notifications.value = [];
    nextToken.value = null;
  }
  try {
    if (featureFlags.notification === "disabled") {
      unavailable.value = true;
      return;
    }
    const [listResult, countResult] = await Promise.allSettled([
      notificationApi.list({
        pageSize: 20,
        pageToken: token || undefined,
        sort: "-createdAt",
      }),
      notificationApi.unreadCount(),
    ]);
    if (listResult.status === "fulfilled") {
      notifications.value = listResult.value.items;
      nextToken.value = listResult.value.meta.page?.nextPageToken ?? null;
    } else if (
      isFeatureUnavailableError(listResult.reason) ||
      isCapabilityUnavailable(listResult.reason, "notification")
    ) {
      unavailable.value = true;
      notifications.value = [];
      nextToken.value = null;
    } else {
      error.value = listResult.reason;
    }
    if (countResult.status === "fulfilled") {
      unreadCount.value = countResult.value.data.unreadCount;
    } else if (
      isFeatureUnavailableError(countResult.reason) ||
      isCapabilityUnavailable(countResult.reason, "notification")
    ) {
      countUnavailable.value = true;
      unreadCount.value = null;
    } else {
      countError.value = countResult.reason;
      unreadCount.value = null;
    }
  } finally {
    loading.value = false;
  }
}
function refresh(): void {
  void load("", true);
}
function nextPage(): void {
  if (nextToken.value) {
    tokenStack.value.push(nextToken.value);
    void load(nextToken.value);
  }
}
function previousPage(): void {
  if (tokenStack.value.length < 2) {
    tokenStack.value = [];
    void load("", true);
    return;
  }
  tokenStack.value.pop();
  void load(tokenStack.value[tokenStack.value.length - 1] || "");
}
function retryList(): void {
  void load(currentToken.value);
}
async function toggle(notification: Notification): Promise<void> {
  savingId.value = notification.notificationId;
  mutationError.value = null;
  mutationUnavailable.value = false;
  try {
    const target = notification.state === "READ" ? "UNREAD" : "READ";
    const result = await notificationApi.setReadState(
      notification.notificationId,
      target,
      `"lv:${notification.lockVersion}"`,
    );
    notification.state = result.data.state;
    notification.readAt = result.data.readAt;
    notification.lockVersion = result.data.lockVersion;
    // API-056 is authoritative for the whole recipient scope. Do not derive
    // a total from the current page, which may be only a cursor slice.
    try {
      const countResult = await notificationApi.unreadCount();
      unreadCount.value = countResult.data.unreadCount;
      countUnavailable.value = false;
      countError.value = null;
    } catch (countReason) {
      if (
        isFeatureUnavailableError(countReason) ||
        isCapabilityUnavailable(countReason, "notification")
      ) {
        countUnavailable.value = true;
      } else {
        countError.value = countReason;
      }
    }
  } catch (reason) {
    // A failed write must not hide an otherwise readable list. For a disabled
    // feature show the rollout notice; all server errors stay actionable.
    if (isFeatureUnavailableError(reason)) mutationUnavailable.value = true;
    else mutationError.value = reason;
  } finally {
    savingId.value = null;
  }
}
function openDetail(notification: Notification): void {
  relatedError.value = null;
  selectedNotification.value = notification;
}
function closeDetail(): void {
  selectedNotification.value = null;
}
onMounted(load);
</script>

<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">消息中心</p>
      <h1>通知</h1>
      <p class="lede">查看与你有关的工单、审批和告警更新。</p>
    </div>
    <div class="heading-actions">
      <span v-if="!unavailable" class="unread-counter"
        ><Bell :size="15" />{{ unreadCount ?? "…" }} 未读</span
      ><button
        class="button button--secondary"
        type="button"
        :disabled="loading"
        @click="refresh"
      >
        <RefreshCw :size="16" />刷新
      </button>
    </div>
  </div>
  <div v-if="unavailable" class="feature-banner feature-banner--large">
    <Bell :size="18" />
    <div>
      <strong>{{
        featureFlags.notification === "mock"
          ? "当前显示本地通知"
          : "当前环境暂时无法提供通知"
      }}</strong
        ><span>{{
          modeText || "服务准备好后可再次刷新，当前不会把通知状态当作已同步。"
        }}</span>
    </div>
  </div>
  <div v-if="countUnavailable" class="feature-banner feature-banner--compact">
    <Bell :size="16" /><span>未读数量暂时无法获取，通知列表仍可查看。</span>
  </div>
  <div v-if="mutationUnavailable" class="feature-banner feature-banner--compact">
    <Bell :size="16" /><span>这项操作暂时无法完成，通知列表仍可查看。</span>
  </div>
  <ErrorNotice v-if="mutationError" :error="mutationError" @retry="refresh" />
  <ErrorNotice v-if="countError" :error="countError" @retry="refresh" />
  <section v-if="!unavailable" class="panel list-panel">
    <ResourceState
      :loading="loading"
      :error="error"
      :empty="!loading && !error && notifications.length === 0"
      empty-text="暂无通知"
      @retry="retryList"
      ><div class="notification-list">
        <article
          v-for="notification in notifications"
          :key="notification.notificationId"
          class="notification-row"
          :class="{ unread: notification.state === 'UNREAD' }"
        >
          <div class="notification-mark"><span /></div>
          <div class="notification-content">
            <div class="notification-title">
              <strong>{{ notification.title }}</strong
              ><StatusBadge :value="notification.state" size="small" />
            </div>
            <p>{{ notification.body }}</p>
            <div class="notification-meta">
              <span
                >{{ sourceDomainLabel(notification.sourceDomain) }} ·
                {{ notificationKindLabel(notification.notificationKind) }}</span
              ><time>{{ formatDate(notification.createdAt) }}</time>
            </div>
          </div>
          <button
            class="icon-button notification-detail-action"
            type="button"
            aria-label="查看通知详情"
            title="查看详情"
            @click="openDetail(notification)"
          >
            <Eye :size="16" />
          </button>
          <button
            class="button button--ghost button--small notification-action"
            type="button"
            :disabled="savingId === notification.notificationId"
            :aria-label="
              notification.state === 'READ' ? '标记为未读' : '标记为已读'
            "
            @click="toggle(notification)"
          >
            <CheckCheck v-if="notification.state === 'READ'" :size="15" /><Check
              v-else
              :size="15"
            />{{ notification.state === "READ" ? "标为未读" : "标为已读" }}
          </button>
        </article>
      </div></ResourceState
    ><PaginationControls
      :has-next="Boolean(nextToken)"
      :has-previous="tokenStack.length > 0"
      :disabled="loading"
      :label="`第 ${tokenStack.length + 1} 页`"
      @next="nextPage"
      @previous="previousPage"
    />
  </section>
  <div v-if="selectedNotification" class="modal-layer" @click.self="closeDetail">
    <section class="modal-panel panel" role="dialog" aria-modal="true" aria-labelledby="notification-detail-title">
      <div class="panel-header">
        <div>
          <p class="eyebrow">通知详情</p>
          <h2 id="notification-detail-title">{{ selectedNotification.title }}</h2>
        </div>
        <button class="icon-button" type="button" aria-label="关闭" title="关闭" @click="closeDetail">
          <X :size="18" />
        </button>
      </div>
      <div class="panel-body notification-detail-body">
        <div class="notification-detail-status">
          <StatusBadge :value="selectedNotification.state" />
          <span>{{ sourceDomainLabel(selectedNotification.sourceDomain) }} · {{ notificationKindLabel(selectedNotification.notificationKind) }}</span>
        </div>
        <p class="notification-detail-copy">{{ selectedNotification.body }}</p>
        <ErrorNotice v-if="relatedError" :error="relatedError" />
        <button v-if="relatedLabel(selectedNotification)" type="button" class="button"
          :disabled="openingRelated" @click="openRelated(selectedNotification)">
          {{ openingRelated ? "正在打开…" : relatedLabel(selectedNotification) }}
        </button>
        <dl class="notification-detail-facts">
          <div><dt>收到时间</dt><dd>{{ formatDate(selectedNotification.createdAt) }}</dd></div>
          <div v-if="selectedNotification.readAt"><dt>阅读时间</dt><dd>{{ formatDate(selectedNotification.readAt) }}</dd></div>
        </dl>
      </div>
    </section>
  </div>
</template>

<style scoped>
.unread-counter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 32px;
  padding: 0 10px;
  border: 1px solid #cfe1f7;
  border-radius: 6px;
  color: #315c90;
  background: var(--blue-soft);
  font-size: 11px;
  font-weight: 700;
}
.feature-banner {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  color: #986109;
}
.feature-banner--large {
  margin: -5px 0 19px;
  padding: 15px 17px;
  border: 1px solid #f0db9e;
  border-radius: 8px;
  background: var(--amber-soft);
}
.feature-banner--compact {
  margin: 0 0 14px;
  padding: 10px 12px;
  border: 1px solid #cfe1f7;
  border-radius: 7px;
  color: #315c90;
  background: var(--blue-soft);
}
.feature-banner strong,
.feature-banner span {
  display: block;
}
.feature-banner strong {
  font-size: 13px;
}
.feature-banner span {
  margin-top: 4px;
  color: #7e6b43;
  font-size: 11px;
  line-height: 1.5;
}
.notification-list {
  display: grid;
}
.notification-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 17px 20px;
  border-bottom: 1px solid #edf1f2;
}
.notification-row:last-child {
  border-bottom: 0;
}
.notification-row.unread {
  background: #fbfefd;
}
.notification-mark {
  width: 9px;
  flex: 0 0 9px;
  padding-top: 6px;
}
.notification-mark span {
  display: block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: transparent;
}
.notification-row.unread .notification-mark span {
  background: var(--teal);
  box-shadow: 0 0 0 3px var(--teal-soft);
}
.notification-content {
  min-width: 0;
  flex: 1;
}
.notification-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.notification-title strong {
  color: #33464e;
  font-size: 13px;
}
.notification-content p {
  margin: 7px 0;
  color: #4c5c64;
  font-size: 12px;
  line-height: 1.55;
}
.notification-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--muted);
  font-size: 10px;
}
.notification-action {
  flex: 0 0 auto;
  align-self: center;
}
.notification-detail-action {
  flex: 0 0 32px;
  align-self: center;
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
  width: min(520px, 100%);
  max-height: calc(100vh - 40px);
  overflow: auto;
}
.modal-panel .panel-header {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #fff;
}
.notification-detail-body {
  display: grid;
  gap: 16px;
}
.notification-detail-status {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  color: var(--muted);
  font-size: 11px;
}
.notification-detail-copy {
  margin: 0;
  color: #3c4d55;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
}
.notification-detail-facts {
  display: grid;
  gap: 9px;
  margin: 0;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}
.notification-detail-facts div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.notification-detail-facts dt {
  color: var(--muted);
  font-size: 11px;
}
.notification-detail-facts dd {
  margin: 0;
  color: #40515a;
  font-size: 11px;
  text-align: right;
}
@media (max-width: 620px) {
  .notification-row {
    padding: 14px 15px;
    flex-wrap: wrap;
  }
  .notification-action {
    margin-left: 21px;
  }
  .notification-detail-action {
    margin-left: auto;
  }
  .heading-actions {
    align-items: stretch;
  }
}
</style>
