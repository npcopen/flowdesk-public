<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  ArrowUpRight,
  CheckCircle2,
  FileCog,
  RefreshCw,
  ShieldCheck,
  XCircle,
} from "lucide-vue-next";
import { RouterLink } from "vue-router";
import { iamApi, workflowApi } from "@/api/endpoints";
import { useAuthStore } from "@/stores/auth";
import type {
  ApprovalTask,
  UserSummary,
  WorkflowDefinition,
  WorkflowDraft,
  WorkflowInstance,
  WorkflowValidation,
  WorkflowVersion,
} from "@/types/api";
import StatusBadge from "@/components/StatusBadge.vue";
import ResourceState from "@/components/ResourceState.vue";
import ErrorNotice from "@/components/ErrorNotice.vue";
import PaginationControls from "@/components/PaginationControls.vue";
import { formatDate } from "@/lib/format";

const auth = useAuthStore();
const loading = ref(true);
const error = ref<unknown>(null);
const actionError = ref<unknown>(null);
const tasks = ref<ApprovalTask[]>([]);
const instances = ref<WorkflowInstance[]>([]);
const nextToken = ref<string | null>(null);
const tokenStack = ref<string[]>([]);
const definitions = ref<WorkflowDefinition[]>([]);
const selectedDefinitionId = ref("");
const draft = ref<WorkflowDraft | null>(null);
const versions = ref<WorkflowVersion[]>([]);
const candidates = ref<UserSummary[]>([]);
const workflowLoading = ref(false);
const workflowSaving = ref(false);
const validation = ref<WorkflowValidation | null>(null);
const draftForm = ref({
  value: "HIGH",
  level1ApproverUserId: "",
  level2ApproverUserId: "",
});
const canManage = computed(
  () => auth.hasRole("SERVICE_DESK_ADMIN") && auth.can("WORKFLOW_READ_TENANT"),
);
const canReadRuntime = computed(() =>
  auth.canAny(["APPROVAL_READ_ASSIGNED", "WORKFLOW_RUNTIME_READ_TENANT"]),
);
const canEditWorkflow = computed(
  () => canManage.value && auth.can("WORKFLOW_DRAFT_MANAGE"),
);
const canPublishWorkflow = computed(
  () => canManage.value && auth.can("WORKFLOW_PUBLISH"),
);
const canDecide = computed(() => auth.can("APPROVAL_DECIDE_ASSIGNED"));
const draftDirty = computed(() => Boolean(draft.value && (draftForm.value.value !== draft.value.condition.value || draftForm.value.level1ApproverUserId !== draft.value.level1ApproverUserId || draftForm.value.level2ApproverUserId !== draft.value.level2ApproverUserId)));
const approversDiffer = computed(() => Boolean(draftForm.value.level1ApproverUserId && draftForm.value.level2ApproverUserId
  && draftForm.value.level1ApproverUserId !== draftForm.value.level2ApproverUserId));

async function load(token = ""): Promise<void> {
  loading.value = true;
  error.value = null;
  if (!canReadRuntime.value) {
    tasks.value = [];
    instances.value = [];
    nextToken.value = null;
    loading.value = false;
    return;
  }
  try {
    const [taskResult, instanceResult] = await Promise.all([
      workflowApi.tasks({
        pageSize: 20,
        pageToken: token || undefined,
        sort: "-createdAt",
      }),
      workflowApi.instances({ pageSize: 20, sort: "-startedAt" }),
    ]);
    tasks.value = taskResult.items;
    instances.value = instanceResult.items;
    nextToken.value = taskResult.meta.page?.nextPageToken ?? null;
  } catch (reason) {
    error.value = reason;
  } finally {
    loading.value = false;
  }
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
    void load();
    return;
  }
  tokenStack.value.pop();
  void load(tokenStack.value[tokenStack.value.length - 1] || "");
}
async function loadWorkflow(): Promise<void> {
  if (!canManage.value) return;
  workflowLoading.value = true;
  actionError.value = null;
  try {
    definitions.value = (
      await workflowApi.definitions({ pageSize: 100, sort: "definitionName" })
    ).items;
    selectedDefinitionId.value ||= definitions.value[0]?.definitionId || "";
    if (selectedDefinitionId.value) {
      const [draftResult, versionResult, candidateResult] =
        await Promise.allSettled([
          workflowApi.currentDraft(selectedDefinitionId.value),
          workflowApi.versions(selectedDefinitionId.value, {
            pageSize: 100,
            sort: "-versionNo",
          }),
          iamApi.users({
            status: "ACTIVE",
            roleCode: "APPROVER",
            pageSize: 100,
            sort: "displayName",
          }),
        ]);
      draft.value =
        draftResult.status === "fulfilled" ? draftResult.value.data : null;
      versions.value =
        versionResult.status === "fulfilled" ? versionResult.value.items : [];
      candidates.value =
        candidateResult.status === "fulfilled"
          ? candidateResult.value.items
          : [];
      if (draft.value) {
        draftForm.value = {
          value: draft.value.condition.value,
          level1ApproverUserId: draft.value.level1ApproverUserId,
          level2ApproverUserId: draft.value.level2ApproverUserId,
        };
      } else {
        draftForm.value.level1ApproverUserId ||=
          candidates.value[0]?.userId || "";
        draftForm.value.level2ApproverUserId ||=
          candidates.value[1]?.userId || "";
      }
    }
  } catch (reason) {
    actionError.value = reason;
  } finally {
    workflowLoading.value = false;
  }
}
async function saveDraft(): Promise<void> {
  if (!selectedDefinitionId.value || !canEditWorkflow.value || !approversDiffer.value) return;
  workflowSaving.value = true;
  actionError.value = null;
  validation.value = null;
  const body = {
    condition: {
      fieldCode: "SERVICE_RISK_LEVEL",
      operator: "EQUALS",
      valueType: "STRING",
      value: draftForm.value.value,
    },
    level1ApproverUserId: draftForm.value.level1ApproverUserId,
    level2ApproverUserId: draftForm.value.level2ApproverUserId,
  };
  try {
    if (draft.value)
      draft.value = (
        await workflowApi.patchDraft(
          draft.value.draftId,
          body,
          `"lv:${draft.value.lockVersion}"`,
        )
      ).data;
    else
      draft.value = (
        await workflowApi.createDraft(selectedDefinitionId.value, body)
      ).data;
  } catch (reason) {
    actionError.value = reason;
  } finally {
    workflowSaving.value = false;
  }
}
async function validateDraft(): Promise<void> {
  if (!draft.value || !canEditWorkflow.value || draftDirty.value) return;
  workflowSaving.value = true;
  actionError.value = null;
  try {
    validation.value = (
      await workflowApi.validateDraft(
        draft.value.draftId,
        `"lv:${draft.value.lockVersion}"`,
      )
    ).data;
    draft.value = {
      ...draft.value,
      validationState: validation.value.valid ? "VALID" : "UNVALIDATED",
      validationDigest: validation.value.validationDigest,
      lockVersion: validation.value.lockVersion,
    };
  } catch (reason) {
    actionError.value = reason;
  } finally {
    workflowSaving.value = false;
  }
}
async function publishDraft(): Promise<void> {
  if (!draft.value || !canPublishWorkflow.value || draftDirty.value) return;
  workflowSaving.value = true;
  actionError.value = null;
  try {
    await workflowApi.publishDraft(
      draft.value.draftId,
      `"lv:${draft.value.lockVersion}"`,
    );
    await loadWorkflow();
  } catch (reason) {
    actionError.value = reason;
  } finally {
    workflowSaving.value = false;
  }
}
onMounted(() => {
  void Promise.all([load(), loadWorkflow()]);
});
</script>

<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">审批进度</p>
      <h1>审批</h1>
      <p class="lede">每项申请都会按设定顺序处理，最终结果会同步回工单。</p>
    </div>
    <div class="heading-actions">
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
  <ErrorNotice v-if="error" :error="error" @retry="load" /><ErrorNotice
    v-if="actionError"
    :error="actionError"
    @retry="loadWorkflow"
  />
  <section class="panel list-panel">
    <div class="panel-header">
      <div>
        <h2>审批任务</h2>
        <p>
          {{
            canDecide
              ? "可处理的申请会显示在这里。"
              : "当前账号只能查看分配给自己的申请。"
          }}
        </p>
      </div>
      <ShieldCheck :size="19" class="panel-heading-icon" />
    </div>
    <ResourceState
      :loading="loading"
      :error="null"
      :empty="!loading && !error && tasks.length === 0"
      empty-text="暂无审批任务"
      ><div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>申请</th>
              <th>级别</th>
              <th>状态</th>
              <th>创建时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(task, index) in tasks" :key="task.approvalTaskId">
              <td>
                <RouterLink
                  class="link-strong"
                  :to="`/approvals/${task.approvalTaskId}`"
                  >第 {{ index + 1 }} 项申请</RouterLink
                >
              </td>
              <td>第 {{ task.levelNo }} 级</td>
              <td><StatusBadge :value="task.status" /></td>
              <td>{{ formatDate(task.createdAt) }}</td>
              <td>
                <RouterLink
                  class="icon-link"
                  :to="`/approvals/${task.approvalTaskId}`"
                  aria-label="查看审批"
                  title="查看审批"
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
  <section class="panel instances-panel">
    <div class="panel-header">
      <div>
        <h2>审批进度</h2>
        <p>查看正在处理和已完成的申请。</p>
      </div>
      <span class="muted">{{ instances.length }} 项申请</span>
    </div>
    <ResourceState
      :loading="false"
      :empty="instances.length === 0"
      empty-text="暂无审批进度"
      ><div class="instance-list">
        <div
          v-for="(instance, index) in instances"
          :key="instance.workflowInstanceId"
          class="instance-row"
        >
          <div>
            <strong>第 {{ index + 1 }} 项申请</strong
            ><span
              >开始于 {{ formatDate(instance.startedAt) }}</span
            >
          </div>
          <div class="instance-meta">
            <StatusBadge :value="instance.status" size="small" /><span
              v-if="instance.currentLevel"
              >当前第 {{ instance.currentLevel }} 级</span
            >
          </div>
        </div>
      </div></ResourceState
    >
  </section>
  <section v-if="canManage" class="panel workflow-admin">
    <div class="panel-header">
      <div>
        <h2>审批规则设置</h2>
        <p>为高风险服务设置最多两级审批人。</p>
      </div>
      <FileCog :size="19" class="panel-heading-icon" />
    </div>
    <div class="panel-body">
      <ResourceState
        :loading="workflowLoading"
        :empty="definitions.length === 0"
        empty-text="暂无可管理的审批规则"
        ><div class="form-grid">
          <div class="form-field">
            <label for="definition">规则</label
            ><select
              id="definition"
              v-model="selectedDefinitionId"
              @change="loadWorkflow"
            >
              <option
                v-for="definition in definitions"
                :key="definition.definitionId"
                :value="definition.definitionId"
              >
                {{ definition.definitionName }}
              </option>
            </select>
          </div>
          <div class="form-field">
            <label for="condition-value">适用风险</label
            ><select id="condition-value" v-model="draftForm.value">
              <option value="HIGH">高风险</option>
              <option value="NORMAL">普通风险</option>
            </select>
          </div>
          <div class="form-field">
            <label for="level-one">一级审批人</label
            ><select id="level-one" v-model="draftForm.level1ApproverUserId">
              <option
                v-for="candidate in candidates"
                :key="candidate.userId"
                :value="candidate.userId"
              >
                {{ candidate.displayName }}
              </option>
            </select>
          </div>
          <div class="form-field">
            <label for="level-two">二级审批人</label
            ><select id="level-two" v-model="draftForm.level2ApproverUserId">
              <option
                v-for="candidate in candidates"
                :key="candidate.userId"
                :value="candidate.userId"
                :disabled="candidate.userId === draftForm.level1ApproverUserId"
              >
                {{ candidate.displayName }}
              </option>
            </select>
          </div>
        </div>
        <p v-if="!approversDiffer" class="validation-bad">两级审批需要两位不同的审批人，请分别选择。</p>
        <p v-if="draftDirty" class="validation-bad">有未保存的修改，请先保存草稿，再校验和发布。</p>
        <p class="muted">发布后，请在“工单类型”中选择新版本并保存，之后的新申请会使用该版本。</p>
        <div class="workflow-admin-status">
          <div>
            <span>当前草稿</span
            ><strong>{{
              draft ? "已创建草稿" : "尚未创建"
            }}</strong>
          </div>
          <StatusBadge
            v-if="draft"
            :value="draft.validationState"
            size="small"
          /><span
            v-if="validation"
            :class="validation.valid ? 'validation-ok' : 'validation-bad'"
            >{{ validation.valid ? "规则校验通过" : "规则校验未通过" }}</span
          >
        </div>
        <div class="form-actions">
          <button
            v-if="canEditWorkflow"
            class="button button--secondary"
            type="button"
            :disabled="workflowSaving || !approversDiffer"
            @click="saveDraft"
          >
            {{ draft ? "保存草稿" : "创建草稿" }}</button
          ><button
            v-if="draft && canEditWorkflow"
            class="button button--secondary"
            type="button"
            :disabled="workflowSaving || draftDirty"
            @click="validateDraft"
          >
            <CheckCircle2 :size="15" />校验规则</button
          ><button
            v-if="draft && canPublishWorkflow"
            class="button"
            type="button"
            :disabled="workflowSaving || draftDirty || draft.validationState !== 'VALID'"
            @click="publishDraft"
          >
            <CheckCircle2 :size="15" />发布规则
          </button>
        </div>
        <div v-if="versions.length" class="version-list">
          <div class="version-heading"><strong>已发布规则</strong></div>
          <div
            v-for="version in versions"
            :key="version.workflowVersionId"
            class="version-row"
          >
              <span>{{ version.condition.value === "HIGH" ? "高风险申请" : "普通申请" }}</span
            ><span>{{ formatDate(version.publishedAt) }}</span>
          </div>
        </div></ResourceState
      >
    </div>
  </section>
</template>

<style scoped>
.panel-heading-icon {
  color: var(--teal);
}
.instances-panel,
.workflow-admin {
  margin-top: 18px;
}
.instance-list {
  display: grid;
}
.instance-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 20px;
  border-bottom: 1px solid #edf1f2;
}
.instance-row:last-child {
  border-bottom: 0;
}
.instance-row strong,
.instance-row span {
  display: block;
}
.instance-row strong {
  color: var(--teal-dark);
  font-size: 12px;
}
.instance-row span {
  margin-top: 4px;
  color: var(--muted);
  font-size: 11px;
}
.instance-meta {
  display: flex;
  align-items: flex-end;
  flex-direction: column;
  gap: 5px;
}
.instance-meta span {
  margin: 0;
  white-space: nowrap;
}
.workflow-admin .panel-body {
  padding-top: 20px;
}
.workflow-admin-status {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 18px;
  padding: 11px 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--surface-soft);
}
.workflow-admin-status span,
.workflow-admin-status strong {
  display: block;
}
.workflow-admin-status span {
  color: var(--muted);
  font-size: 11px;
}
.workflow-admin-status strong {
  margin-top: 3px;
  font-size: 12px;
}
.workflow-admin-status > div {
  margin-right: auto;
}
.validation-ok {
  color: #16735b !important;
}
.validation-bad {
  color: var(--red) !important;
}
.version-list {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}
.version-heading,
.version-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.version-heading {
  margin-bottom: 7px;
  font-size: 12px;
}
.version-heading small {
  color: var(--muted);
  font-size: 10px;
}
.version-row {
  padding: 7px 0;
  color: #4a5b63;
  font-size: 11px;
  border-bottom: 1px solid #edf1f2;
}
.version-row:last-child {
  border-bottom: 0;
}
@media (max-width: 620px) {
  .instance-row {
    align-items: flex-start;
    flex-direction: column;
    padding: 12px 15px;
  }
  .instance-meta {
    align-items: flex-start;
    flex-direction: row;
  }
}
</style>
