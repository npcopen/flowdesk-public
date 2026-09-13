<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import {
  CheckCircle2,
  CircleAlert,
  Eye,
  FileClock,
  Play,
  RefreshCw,
  X,
} from "lucide-vue-next";
import { recoveryApi } from "@/api/endpoints";
import { featureFlags, featureNotice } from "@/api/features";
import { ApiClientError, isCapabilityUnavailable } from "@/api/errors";
import { useAuthStore } from "@/stores/auth";
import type { MessageRecovery, RecoveryOwner } from "@/types/api";
import StatusBadge from "@/components/StatusBadge.vue";
import ResourceState from "@/components/ResourceState.vue";
import ErrorNotice from "@/components/ErrorNotice.vue";
import PaginationControls from "@/components/PaginationControls.vue";
import {
  formatDate,
  recoveryDirectionLabel,
  recoveryOwnerLabel,
} from "@/lib/format";

const auth = useAuthStore();
const owners: RecoveryOwner[] = ["TICKET", "WORKFLOW", "ALERT", "NOTIFICATION"];

interface RecoveryState {
  items: MessageRecovery[];
  loading: boolean;
  error: unknown;
  unavailable: boolean;
  nextToken: string | null;
  tokenStack: string[];
}

function emptyState(): RecoveryState {
  return {
    items: [],
    loading: false,
    error: null,
    unavailable: false,
    nextToken: null,
    tokenStack: [],
  };
}

const states = reactive<Record<RecoveryOwner, RecoveryState>>({
  TICKET: emptyState(),
  WORKFLOW: emptyState(),
  ALERT: emptyState(),
  NOTIFICATION: emptyState(),
});
const activeOwner = ref<RecoveryOwner>("TICKET");
const canReplay = computed(
  () =>
    auth.hasRole("SERVICE_DESK_ADMIN") && auth.can("RECOVERY_REPLAY_TENANT"),
);
const activeState = computed(() => states[activeOwner.value]);
const actionNotice = ref<string | null>(null);

type ModalMode = "detail" | "replay";
interface RecoveryTarget {
  owner: RecoveryOwner;
  item: MessageRecovery;
  detail: MessageRecovery | null;
  etag: string;
  loading: boolean;
  error: unknown;
  mode: ModalMode;
}

const target = ref<RecoveryTarget | null>(null);
const replayReason = ref("");
const replayError = ref<unknown>(null);
const replayLoading = ref(false);
const replayNotice = ref<string | null>(null);

function ownerLabel(owner: RecoveryOwner): string {
  return recoveryOwnerLabel(owner);
}

function stateDescription(item: MessageRecovery): string {
  if (item.state === "OPEN") return "等待人工处理";
  if (item.state === "REPLAYING") return "正在处理";
  if (item.state === "RESOLVED") return "已完成";
  return "状态待确认";
}

function relevantTime(item: MessageRecovery): string | null {
  return item.nextAttemptAt || item.recoveryRequiredAt;
}

async function loadOwner(
  owner: RecoveryOwner,
  token = "",
  reset = false,
): Promise<void> {
  const state = states[owner];
  state.loading = true;
  state.error = null;
  state.unavailable = false;
  if (reset) {
    state.items = [];
    state.nextToken = null;
    state.tokenStack = [];
  }
  try {
    if (featureFlags.recovery === "disabled") {
      state.unavailable = true;
      return;
    }
    const result = await recoveryApi.list(owner, {
      pageSize: 20,
      pageToken: token || undefined,
      sort: "-recoveryRequiredAt",
    });
    state.items = result.items;
    state.nextToken = result.meta.page?.nextPageToken ?? null;
  } catch (reason) {
    if (isCapabilityUnavailable(reason, "recovery")) {
      state.unavailable = true;
      state.items = [];
      state.nextToken = null;
    } else {
      state.error = reason;
    }
  } finally {
    state.loading = false;
  }
}

async function refreshAll(): Promise<void> {
  actionNotice.value = null;
  await Promise.all(owners.map((owner) => loadOwner(owner, "", true)));
}

function nextPage(owner: RecoveryOwner): void {
  const state = states[owner];
  if (!state.nextToken || state.loading) return;
  state.tokenStack.push(state.nextToken);
  void loadOwner(owner, state.nextToken);
}

function previousPage(owner: RecoveryOwner): void {
  const state = states[owner];
  if (!state.tokenStack.length || state.loading) return;
  state.tokenStack.pop();
  const previous = state.tokenStack[state.tokenStack.length - 1] || "";
  void loadOwner(owner, previous);
}

function pageLabel(owner: RecoveryOwner): string {
  return `第 ${states[owner].tokenStack.length + 1} 页`;
}

function replaceItem(owner: RecoveryOwner, value: MessageRecovery): void {
  const items = states[owner].items;
  const index = items.findIndex((item) => item.recoveryId === value.recoveryId);
  if (index >= 0) items[index] = value;
}

async function openRecovery(
  owner: RecoveryOwner,
  item: MessageRecovery,
  mode: ModalMode,
): Promise<void> {
  target.value = {
    owner,
    item,
    detail: null,
    etag: `"lv:${item.lockVersion}"`,
    loading: true,
    error: null,
    mode,
  };
  replayReason.value = "";
  replayError.value = null;
  replayNotice.value = null;
  try {
    const result = await recoveryApi.get(owner, item.recoveryId);
    if (!target.value) return;
    target.value.detail = result.data;
    target.value.item = result.data;
    target.value.etag = result.headers.get("ETag") || `"lv:${result.data.lockVersion}"`;
  } catch (reason) {
    if (target.value) target.value.error = reason;
  } finally {
    if (target.value) target.value.loading = false;
  }
}

function closeRecovery(): void {
  if (replayLoading.value) return;
  target.value = null;
  replayError.value = null;
  replayNotice.value = null;
}

async function submitReplay(): Promise<void> {
  if (!target.value || !target.value.detail || !canReplay.value) return;
  const reason = replayReason.value.trim();
  if (!reason) {
    replayError.value = new ApiClientError({
      status: 422,
      code: "RECOVERY_REASON_REQUIRED",
    });
    return;
  }
  const current = target.value;
  replayLoading.value = true;
  replayError.value = null;
  replayNotice.value = null;
  try {
    const result = await recoveryApi.replay(
      current.owner,
      current.detail.recoveryId,
      reason,
      current.etag,
    );
    replaceItem(current.owner, result.data);
    current.item = result.data;
    current.detail = result.data;
    current.etag = result.headers.get("ETag") || `"lv:${result.data.lockVersion}"`;
    current.mode = "detail";
    if (result.status === 202) {
      replayNotice.value = "请求已提交，正在等待处理结果。请稍后刷新查看。";
      actionNotice.value = `${ownerLabel(current.owner)}已提交重新处理请求，结果仍在处理中。`;
    } else {
      replayNotice.value = "请求已提交，请刷新确认最新状态。";
      actionNotice.value = `${ownerLabel(current.owner)}已提交重新处理请求。`;
    }
  } catch (reason) {
    replayError.value = reason;
  } finally {
    replayLoading.value = false;
  }
}

function beginReplay(): void {
  if (!target.value || !target.value.detail || !canReplay.value) return;
  target.value.mode = "replay";
  replayReason.value = "";
  replayError.value = null;
  replayNotice.value = null;
}

onMounted(refreshAll);
</script>

<template>
  <section class="panel recovery-panel" data-testid="recovery-panel">
    <div class="panel-header recovery-panel-header">
      <div>
        <h2>更新处理记录</h2>
        <p>查看需要关注的后续更新，并在有权限时提交重新处理请求。</p>
      </div>
      <button
        class="button button--secondary button--small"
        type="button"
        :disabled="activeState.loading"
        @click="refreshAll"
      >
        <RefreshCw :size="15" />刷新
      </button>
    </div>

    <div class="recovery-tabs" role="tablist" aria-label="更新类型">
      <button
        v-for="owner in owners"
        :key="owner"
        class="recovery-tab"
        :class="{ active: activeOwner === owner }"
        type="button"
        role="tab"
        :aria-selected="activeOwner === owner"
        @click="activeOwner = owner"
      >
        {{ ownerLabel(owner) }}
        <span v-if="states[owner].items.length" class="recovery-tab-count">
          {{ states[owner].items.length }}
        </span>
      </button>
    </div>

    <div v-if="featureFlags.recovery === 'mock'" class="feature-banner recovery-local-note">
      <FileClock :size="16" />
      <span>{{ featureNotice(featureFlags.recovery) }}</span>
    </div>
    <div v-if="actionNotice" class="recovery-action-notice" role="status">
      <CheckCircle2 :size="17" />{{ actionNotice }}
    </div>

    <div class="recovery-content">
      <div v-if="activeState.unavailable" class="feature-banner feature-banner--large">
        <CircleAlert :size="18" />
        <div>
          <strong>当前环境暂时无法提供这类更新</strong>
          <span>服务准备好后可再次刷新，当前不会把它当作已完成。</span>
        </div>
      </div>
      <ResourceState
        v-else
        :loading="activeState.loading"
        :error="activeState.error"
        :empty="!activeState.loading && !activeState.error && activeState.items.length === 0"
        empty-text="暂无需要关注的更新"
        @retry="loadOwner(activeOwner, '', true)"
      >
        <div class="recovery-list">
          <article
            v-for="item in activeState.items"
            :key="item.recoveryId"
            class="recovery-row"
            :data-recovery-id="item.recoveryId"
          >
            <div class="recovery-row-main">
              <strong>{{ recoveryDirectionLabel(item.direction) }}</strong>
              <span>{{ stateDescription(item) }}</span>
            </div>
            <div class="recovery-row-facts">
              <StatusBadge :value="item.state" size="small" />
              <span>需要关注 {{ formatDate(item.recoveryRequiredAt) }}</span>
              <span v-if="relevantTime(item) && relevantTime(item) !== item.recoveryRequiredAt">
                下次处理 {{ formatDate(relevantTime(item)) }}
              </span>
              <span>已尝试 {{ item.attemptCount }} 次</span>
            </div>
            <div class="recovery-row-actions">
              <button
                class="button button--ghost button--small"
                type="button"
                @click="openRecovery(activeOwner, item, 'detail')"
              >
                <Eye :size="14" />查看详情
              </button>
              <button
                v-if="canReplay && item.state === 'OPEN'"
                class="button button--secondary button--small"
                type="button"
                @click="openRecovery(activeOwner, item, 'replay')"
              >
                <Play :size="14" />重新处理
              </button>
            </div>
          </article>
        </div>
      </ResourceState>
      <PaginationControls
        v-if="!activeState.unavailable"
        :has-next="Boolean(activeState.nextToken)"
        :has-previous="activeState.tokenStack.length > 0"
        :disabled="activeState.loading"
        :label="pageLabel(activeOwner)"
        @next="nextPage(activeOwner)"
        @previous="previousPage(activeOwner)"
      />
    </div>
  </section>

  <div v-if="target" class="modal-layer" @click.self="closeRecovery">
    <section
      class="modal-panel panel recovery-modal"
      role="dialog"
      aria-modal="true"
      aria-labelledby="recovery-modal-title"
    >
      <div class="panel-header">
        <div>
          <p class="eyebrow">{{ ownerLabel(target.owner) }}</p>
          <h2 id="recovery-modal-title">
            {{ target.mode === "replay" ? "重新处理更新" : "更新详情" }}
          </h2>
        </div>
        <button class="icon-button" type="button" aria-label="关闭" title="关闭" @click="closeRecovery">
          <X :size="18" />
        </button>
      </div>
      <div class="panel-body">
        <div v-if="target.loading" class="resource-state recovery-modal-loading">
          <span class="spinner" />正在加载详情…
        </div>
        <ErrorNotice
          v-else-if="target.error"
          :error="target.error"
          @retry="openRecovery(target.owner, target.item, target.mode)"
        />
        <template v-else-if="target.detail">
          <div class="recovery-detail-status">
            <StatusBadge :value="target.detail.state" />
            <span>{{ stateDescription(target.detail) }}</span>
          </div>
          <dl class="recovery-facts">
            <div><dt>处理方向</dt><dd>{{ recoveryDirectionLabel(target.detail.direction) }}</dd></div>
            <div><dt>需要关注时间</dt><dd>{{ formatDate(target.detail.recoveryRequiredAt) }}</dd></div>
            <div><dt>最近处理时间</dt><dd>{{ formatDate(target.detail.lastReplayedAt) }}</dd></div>
            <div><dt>处理次数</dt><dd>{{ target.detail.attemptCount }}</dd></div>
            <div><dt>人工处理次数</dt><dd>{{ target.detail.replayCount }}</dd></div>
            <div v-if="target.detail.resolvedAt"><dt>完成时间</dt><dd>{{ formatDate(target.detail.resolvedAt) }}</dd></div>
          </dl>
          <div v-if="replayNotice" class="recovery-action-notice" role="status">
            <CheckCircle2 :size="17" />{{ replayNotice }}
          </div>
          <ErrorNotice
            v-if="replayError"
            :error="replayError"
            @retry="replayError = null"
          />
          <form v-if="target.mode === 'replay'" class="recovery-replay-form" @submit.prevent="submitReplay">
            <div class="form-field">
              <label for="recovery-reason">处理说明</label>
              <textarea
                id="recovery-reason"
                v-model="replayReason"
                rows="4"
                maxlength="1000"
                required
                placeholder="请说明重新处理前已完成的核查"
              />
              <span class="form-help">说明会随本次操作记录，最多 1000 个字符。</span>
            </div>
            <div class="form-actions">
              <button class="button button--secondary" type="button" :disabled="replayLoading" @click="target.mode = 'detail'">
                返回详情
              </button>
              <button class="button" type="submit" :disabled="replayLoading">
                <Play :size="15" />{{ replayLoading ? "提交中…" : "提交重新处理" }}
              </button>
            </div>
          </form>
          <div v-else-if="canReplay && target.detail.state === 'OPEN'" class="form-actions">
            <button class="button" type="button" @click="beginReplay">
              <Play :size="15" />重新处理
            </button>
          </div>
        </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
.recovery-panel {
  margin-top: 18px;
}
.recovery-panel-header {
  align-items: center;
}
.recovery-tabs {
  display: flex;
  gap: 2px;
  overflow-x: auto;
  padding: 10px 17px 0;
  border-bottom: 1px solid var(--line);
  background: var(--surface-soft);
}
.recovery-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
  margin-bottom: -1px;
  padding: 0 12px;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--muted);
  background: transparent;
  font-size: 12px;
  white-space: nowrap;
}
.recovery-tab:hover,
.recovery-tab.active {
  color: var(--teal-dark);
}
.recovery-tab.active {
  border-bottom-color: var(--teal);
  font-weight: 700;
}
.recovery-tab-count {
  display: inline-grid;
  place-items: center;
  min-width: 19px;
  height: 19px;
  padding: 0 5px;
  border-radius: 999px;
  color: var(--teal-dark);
  background: var(--teal-soft);
  font-size: 10px;
  font-weight: 700;
}
.recovery-local-note,
.recovery-action-notice {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 14px 17px 0;
  padding: 10px 12px;
  border: 1px solid #cfe1f7;
  border-radius: 7px;
  color: #315c90;
  background: var(--blue-soft);
  font-size: 12px;
  line-height: 1.5;
}
.recovery-action-notice {
  border-color: #bfe3d4;
  color: #16735b;
  background: #eefaf4;
}
.recovery-content {
  min-width: 0;
}
.feature-banner--large {
  margin: 16px 17px;
}
.recovery-list {
  display: grid;
}
.recovery-row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) minmax(260px, 1.8fr) auto;
  align-items: center;
  gap: 14px;
  padding: 14px 20px;
  border-bottom: 1px solid #edf1f2;
}
.recovery-row:last-child {
  border-bottom: 0;
}
.recovery-row-main,
.recovery-row-facts {
  min-width: 0;
}
.recovery-row-main strong,
.recovery-row-main span {
  display: block;
}
.recovery-row-main strong {
  color: var(--teal-dark);
  font-size: 12px;
}
.recovery-row-main span,
.recovery-row-facts span {
  color: var(--muted);
  font-size: 11px;
}
.recovery-row-main span {
  margin-top: 4px;
}
.recovery-row-facts {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
}
.recovery-row-actions {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 6px;
}
.recovery-modal-loading {
  min-height: 120px;
}
.recovery-detail-status {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  font-size: 12px;
}
.recovery-facts {
  display: grid;
  gap: 10px;
  margin: 20px 0 0;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}
.recovery-facts div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.recovery-facts dt {
  color: var(--muted);
  font-size: 11px;
}
.recovery-facts dd {
  margin: 0;
  color: #40515a;
  font-size: 12px;
  text-align: right;
}
.recovery-replay-form {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}
@media (max-width: 900px) {
  .recovery-row {
    grid-template-columns: 1fr;
    align-items: flex-start;
    gap: 9px;
  }
  .recovery-row-actions {
    justify-content: flex-start;
  }
}
@media (max-width: 620px) {
  .recovery-tabs {
    padding-left: 10px;
    padding-right: 10px;
  }
  .recovery-row {
    padding: 13px 15px;
  }
  .recovery-local-note,
  .recovery-action-notice {
    margin-left: 15px;
    margin-right: 15px;
  }
}
</style>
