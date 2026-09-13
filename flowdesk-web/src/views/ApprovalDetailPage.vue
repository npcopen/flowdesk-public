<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ArrowLeft, Check, RefreshCw, X, XCircle } from "lucide-vue-next";
import { useRoute, useRouter } from "vue-router";
import { iamApi, ticketApi, workflowApi } from "@/api/endpoints";
import { useAuthStore } from "@/stores/auth";
import { isOpaqueResourceNotFound } from "@/api/errors";
import type { ApprovalTask, AsyncProgress as AsyncProgressItem, Ticket, WorkflowInstance, UserSummary } from "@/types/api";
import StatusBadge from "@/components/StatusBadge.vue";
import ResourceState from "@/components/ResourceState.vue";
import ErrorNotice from "@/components/ErrorNotice.vue";
import AsyncProgress from "@/components/AsyncProgress.vue";
import { formatDate } from "@/lib/format";

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const taskId = computed(() => String(route.params.id));
const loading = ref(true);
const error = ref<unknown>(null);
const actionError = ref<unknown>(null);
const task = ref<ApprovalTask | null>(null);
const instance = ref<WorkflowInstance | null>(null);
const ticket = ref<(Pick<Ticket, "ticketId" | "ticketNo" | "subject" | "description" | "status" | "requesterUserId" | "createdAt"> & { catalogName?: string | null }) | null>(null);
const ticketError = ref<unknown>(null);
const requesterIdentity = ref<{username:string;displayName:string}|null>(null);
const taskEtag = ref("");
const decision = ref<"approve" | "reject" | null>(null);
const reason = ref("");
const saving = ref(false);
const effects = ref<AsyncProgressItem[]>([]);
const instanceEtag = ref("");
const handoff = ref(false);
const handoffSaving = ref(false);
const handoffError = ref<unknown>(null);
const handoffCandidates = ref<UserSummary[]>([]);
const handoffCandidatesLoading = ref(false);
const handoffForm = ref({ levelNo: 1, approverUserId: "", reason: "" });
const handoffKey = ref("");
const changes = ref<Awaited<ReturnType<typeof workflowApi.approverChanges>>['data']>([]);
const changesError = ref<unknown>(null);
const canHandoff = computed(() => auth.can("APPROVAL_REASSIGN_TENANT") && instance.value?.status === "RUNNING");
async function beginHandoff(): Promise<void> {
  handoff.value = true;
  handoffError.value = null;
  handoffCandidates.value = [];
  handoffCandidatesLoading.value = true;
  handoffKey.value = crypto.randomUUID();
  handoffForm.value = { levelNo: instance.value?.currentLevel ?? 1, approverUserId: "", reason: "" };
  try {
    const candidates: UserSummary[] = [];
    let token: string | undefined;
    do {
      const page = await iamApi.users({ status: "ACTIVE", roleCode: "APPROVER", pageSize: 100, sort: "displayName", pageToken: token });
      candidates.push(...page.items);
      token = page.meta.page?.nextPageToken ?? undefined;
    } while (token);
    handoffCandidates.value = candidates;
  } catch (reasonValue) { handoffError.value = reasonValue; }
  finally { handoffCandidatesLoading.value = false; }
}
async function submitHandoff(): Promise<void> {
  if (!instance.value || !canHandoff.value || handoffSaving.value || handoffCandidatesLoading.value) return;
  handoffSaving.value = true;
  handoffError.value = null;
  try {
    await workflowApi.changeApprover(instance.value.workflowInstanceId, handoffForm.value, instanceEtag.value, handoffKey.value);
    handoff.value = false;
    await load();
  } catch (reasonValue) { handoffError.value = reasonValue; }
  finally { handoffSaving.value = false; }
}
const canDecide = computed(
  () =>
    auth.can("APPROVAL_DECIDE_ASSIGNED") && task.value?.status === "PENDING"
    && task.value.approverUserId === auth.user?.userId && Boolean(ticket.value) && !ticketError.value && !loading.value,
);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  ticketError.value = null;
  task.value = null;
  instance.value = null;
  ticket.value = null;
  requesterIdentity.value = null;
  try {
    const result = await workflowApi.task(taskId.value);
    task.value = result.data;
    taskEtag.value =
      result.headers.get("ETag") || `"lv:${result.data.lockVersion}"`;
    const instanceResult = await workflowApi.instance(
      result.data.workflowInstanceId,
    );
    instance.value = instanceResult.data;
    instanceEtag.value = instanceResult.headers.get("ETag") || `"lv:${instanceResult.data.lockVersion}"`;
    changesError.value = null;
    try { changes.value = (await workflowApi.approverChanges(instanceResult.data.workflowInstanceId)).data; }
    catch (reasonValue) { changes.value = []; changesError.value = reasonValue; }
    try {
      ticket.value = auth.can("APPROVAL_READ_ASSIGNED") && task.value.approverUserId === auth.user?.userId
        ? (await ticketApi.approvalContext(instanceResult.data.ticketId, task.value.approvalTaskId)).data
        : (await ticketApi.ticket(instanceResult.data.ticketId)).data;
    } catch (reasonValue) {
      // A generic 404 is the contract's opaque visibility result. Other
      // failures must remain visible so a missing dependency is not mistaken
      // for a ticket that simply does not exist.
      if (!isOpaqueResourceNotFound(reasonValue)) {
        ticketError.value = reasonValue;
      }
    }
    if(ticket.value?.requesterUserId) try { requesterIdentity.value = (await iamApi.identity(ticket.value.requesterUserId)).data; } catch { requesterIdentity.value=null; }
  } catch (reasonValue) {
    error.value = reasonValue;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
function begin(kind: "approve" | "reject"): void {
  decision.value = kind;
  reason.value = "";
  actionError.value = null;
}
function cancel(): void {
  if (!saving.value) decision.value = null;
}
async function submit(): Promise<void> {
  if (!task.value || !decision.value || !canDecide.value) return;
  if (decision.value === "reject" && !reason.value.trim()) return;
  saving.value = true;
  actionError.value = null;
  try {
    const result =
      decision.value === "approve"
        ? await workflowApi.approve(
            task.value.approvalTaskId,
            reason.value.trim() || null,
            taskEtag.value,
          )
        : await workflowApi.reject(
            task.value.approvalTaskId,
            reason.value.trim(),
            taskEtag.value,
          );
    effects.value = result.meta.asyncEffects || [];
    decision.value = null;
    await load();
  } catch (reasonValue) {
    actionError.value = reasonValue;
  } finally {
    saving.value = false;
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
      <ArrowLeft :size="15" />返回审批列表
    </button>
  </div>
  <ResourceState
    :loading="loading"
    :error="error"
    :empty="!loading && !error && !task"
    empty-text="审批任务不可见"
    @retry="load"
    ><template v-if="task"
      ><div class="page-heading detail-heading">
        <div>
          <p class="eyebrow">审批任务</p>
          <h1>第 {{ task.levelNo }} 级审批</h1>
          <p class="lede">
            创建于 {{ formatDate(task.createdAt) }} · 已指定审批人
          </p>
        </div>
        <div class="heading-actions">
          <button v-if="canHandoff" class="button button--secondary" type="button" @click="beginHandoff">交接审批人</button>
          <StatusBadge :value="task.status" /><button
            class="button button--secondary"
            type="button"
            :disabled="loading"
            @click="load"
          >
            <RefreshCw :size="15" />刷新
          </button>
        </div>
      </div>
      <AsyncProgress :effects="effects" /><ErrorNotice
        v-if="actionError && !decision"
        :error="actionError"
        @retry="load"
      />
      <div class="approval-layout">
        <section class="panel">
          <div class="panel-header">
            <div>
              <h2>申请信息</h2>
              <p>审批进度与工单状态分别更新，请以最新状态为准。</p>
            </div>
          </div>
          <div class="panel-body">
            <ErrorNotice v-if="ticketError" :error="ticketError" @retry="load" />
            <div v-else-if="ticket" class="ticket-context">
              <strong class="link-strong">{{ ticket.ticketNo }} · {{ ticket.subject }}</strong>
              <p>申请人：<strong v-if="requesterIdentity">{{ requesterIdentity.displayName }}（{{ requesterIdentity.username }}）</strong><span v-else>身份名称暂不可用 · 用户标识 {{ ticket.requesterUserId }}</span></p>
              <p>{{ ticket.description }}</p>
              <p v-if="ticket.catalogName" class="muted">申请事项：{{ ticket.catalogName }}</p>
              <p class="muted">提交于 {{ formatDate(ticket.createdAt) }}</p>
              <div class="context-facts">
                <span
                  >工单状态
                  <strong
                    ><StatusBadge
                      :value="ticket.status"
                      size="small" /></strong></span
                ><span
                  >申请状态
                  <strong><StatusBadge :value="instance?.status || 'PENDING'" size="small" /></strong></span
                >
              </div>
            </div>
            <div v-else class="muted">申请信息尚未加载，请刷新后再作出审批决定。</div>
            <div v-if="instance" class="instance-context">
              <div>
                <span>审批进度</span
                ><StatusBadge :value="instance.status" size="small" />
              </div>
              <div>
                <span>适用条件</span
                ><strong>{{
                  instance.conditionResult ? "符合高风险条件" : "不符合条件"
                }}</strong>
              </div>
              <div>
                <span>当前级别</span
                ><strong>{{
                  instance.currentLevel
                    ? `第 ${instance.currentLevel} 级`
                    : "已结束"
                }}</strong>
              </div>
            </div>
          </div>
        </section>
        <section class="panel decision-panel">
          <div class="panel-header">
            <div>
              <h2>审批决定</h2>
              <p>提交后，系统会继续更新后续审批进度。</p>
            </div>
            <Check
              v-if="task.status === 'APPROVED'"
              :size="19"
              class="decision-icon decision-icon--good"
            /><XCircle
              v-else-if="task.status === 'REJECTED'"
              :size="19"
              class="decision-icon decision-icon--bad"
            />
          </div>
          <div class="panel-body">
            <div v-if="task.status !== 'PENDING'" class="terminal-decision">
              <StatusBadge :value="task.status" />
              <p>{{ task.decisionReason || "未填写决定原因。" }}</p>
              <time>{{ formatDate(task.decidedAt) }}</time>
            </div>
            <div v-else-if="!canDecide" class="muted">
              {{ !ticket || ticketError ? '申请信息尚未加载，暂时不能提交决定。' : '当前账号不是该任务的指定审批人，不能提交决定。' }}
            </div>
            <div v-else class="decision-buttons">
              <button class="button" type="button" @click="begin('approve')">
                <Check :size="15" />同意</button
              ><button
                class="button button--danger"
                type="button"
                @click="begin('reject')"
              >
                <XCircle :size="15" />拒绝
              </button>
            </div>
          </div>
        </section>
        <section v-if="changes.length || changesError" class="panel">
          <div class="panel-header"><h2>审批交接记录</h2></div>
          <div class="panel-body">
            <ErrorNotice v-if="changesError" :error="changesError" @retry="load" />
            <div v-for="change in changes" :key="change.changeId">
              <strong>第 {{ change.levelNo }} 级审批人已更换</strong>
              <p>{{ change.reason }}</p><time>{{ formatDate(change.changedAt) }}</time>
            </div>
          </div>
        </section>
      </div></template
    ></ResourceState
  >
  <div v-if="handoff" class="modal-layer">
    <section class="modal-panel panel" role="dialog" aria-modal="true" aria-labelledby="handoff-title">
      <div class="panel-header"><div><h2 id="handoff-title">交接审批人</h2><p>保留已完成的决定；到达交接级别后，接替人员会收到待办通知。</p></div></div>
      <form class="panel-body" @submit.prevent="submitHandoff">
        <ErrorNotice v-if="handoffError" :error="handoffError" />
        <div class="form-field"><label for="handoff-level">交接级别</label>
          <select id="handoff-level" v-model.number="handoffForm.levelNo" :disabled="handoffSaving" required>
            <option v-if="instance?.currentLevel === 1" :value="1">第一级</option><option :value="2">第二级</option>
          </select></div>
        <div class="form-field"><label for="handoff-user">接替人员</label>
          <select id="handoff-user" v-model="handoffForm.approverUserId" :disabled="handoffSaving || handoffCandidatesLoading" required>
            <option value="">请选择另一位审批人</option><option v-for="candidate in handoffCandidates" :key="candidate.userId" :value="candidate.userId">{{ candidate.displayName }}（{{ candidate.username }}）</option>
          </select></div>
        <div class="form-field"><label for="handoff-reason">交接原因</label><textarea id="handoff-reason" v-model.trim="handoffForm.reason" :disabled="handoffSaving" maxlength="1000" required /></div>
        <div class="form-actions"><button class="button button--secondary" type="button" :disabled="handoffSaving" @click="handoff = false">取消</button>
          <button class="button" type="submit" :disabled="handoffSaving || handoffCandidatesLoading || !handoffForm.approverUserId || !handoffForm.reason">{{ handoffSaving ? '交接中…' : '确认交接' }}</button></div>
      </form>
    </section>
  </div>
  <div v-if="decision" class="modal-layer" @click.self="cancel">
    <section class="modal-panel panel" role="dialog" aria-modal="true">
      <div class="panel-header">
        <div>
          <h2>{{ decision === "approve" ? "确认同意" : "确认拒绝" }}</h2>
          <p>原因会记录在审批历史中，提交后不能修改。</p>
        </div>
        <button
          class="icon-button"
          type="button"
          aria-label="关闭"
          title="关闭"
          @click="cancel"
        >
          <X :size="18" />
        </button>
      </div>
      <form class="panel-body" @submit.prevent="submit">
        <ErrorNotice
          v-if="actionError"
          :error="actionError"
          @retry="
            () => {
              actionError = null;
            }
          "
        />
        <div class="form-field">
          <label for="decision-reason"
            >审批原因<span v-if="decision === 'reject'">（必填）</span></label
          ><textarea
            id="decision-reason"
            v-model.trim="reason"
            :required="decision === 'reject'"
            maxlength="1000"
          />
        </div>
        <div class="form-actions">
          <button
            class="button button--secondary"
            type="button"
            :disabled="saving"
            @click="cancel"
          >
            取消</button
          ><button
            class="button"
            :class="{ 'button--danger': decision === 'reject' }"
            type="submit"
            :disabled="saving || (decision === 'reject' && !reason.trim())"
          >
            <span v-if="saving" class="spinner spinner--light" />{{
              saving ? "提交中…" : "提交决定"
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
.approval-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(280px, 0.65fr);
  gap: 18px;
}
.mono {
  overflow-wrap: anywhere;
}
.panel-header .mono {
  color: var(--muted);
}
.ticket-context > p {
  margin: 12px 0 16px;
  color: #40515a;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.context-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  padding-top: 14px;
  border-top: 1px solid #edf1f2;
  color: var(--muted);
  font-size: 11px;
}
.context-facts span {
  display: flex;
  align-items: center;
  gap: 7px;
}
.context-facts strong {
  color: var(--ink);
  font-weight: 600;
}
.instance-context {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}
.instance-context > div {
  display: grid;
  gap: 6px;
}
.instance-context span {
  color: var(--muted);
  font-size: 11px;
}
.instance-context strong {
  color: #34464f;
  font-size: 12px;
}
.decision-icon {
  color: var(--muted);
}
.decision-icon--good {
  color: #1b8066;
}
.decision-icon--bad {
  color: var(--red);
}
.decision-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
}
.terminal-decision {
  display: grid;
  gap: 10px;
}
.terminal-decision p {
  margin: 0;
  color: #40515a;
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
}
.terminal-decision time {
  color: var(--muted);
  font-size: 11px;
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
  width: min(500px, 100%);
  max-height: calc(100vh - 40px);
  overflow: auto;
}
.modal-panel .panel-header {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #fff;
}
@media (max-width: 760px) {
  .approval-layout {
    grid-template-columns: 1fr;
  }
  .instance-context {
    grid-template-columns: 1fr;
  }
}
</style>
