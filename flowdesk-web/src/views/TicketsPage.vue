<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import {
  ArrowUpRight,
  FilePlus2,
  Filter,
  RefreshCw,
  Settings2,
  X,
} from "lucide-vue-next";
import { useRoute, useRouter, RouterLink } from "vue-router";
import { catalogApi, ticketApi } from "@/api/endpoints";
import { useAuthStore } from "@/stores/auth";
import type { ServiceCatalogItem, Ticket } from "@/types/api";
import StatusBadge from "@/components/StatusBadge.vue";
import ResourceState from "@/components/ResourceState.vue";
import ErrorNotice from "@/components/ErrorNotice.vue";
import PaginationControls from "@/components/PaginationControls.vue";
import AsyncProgress from "@/components/AsyncProgress.vue";
import { mutationIntent } from "@/lib/mutationIntent";
import { allPages } from "@/lib/allPages";
import { formatDate, originLabel } from "@/lib/format";

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const loading = ref(true);
const error = ref<unknown>(null);
const items = ref<Ticket[]>([]);
const nextToken = ref<string | null>(null);
const tokenStack = ref<string[]>([]);
const statusFilter = ref("");
const scopeFilter = ref("");
const catalogs = ref<ServiceCatalogItem[]>([]);
const catalogError = ref<unknown>(null);
const catalogLoading = ref(false);
const modalOpen = ref(false);
const submitting = ref(false);
const actionError = ref<unknown>(null);
const asyncEffects = ref<AsyncProgress[]>([]);
const createIntent = mutationIntent();
const form = ref({ catalogItemId: "", subject: "", description: "" });

const isAdmin = computed(() => auth.hasRole("SERVICE_DESK_ADMIN"));
const canReadTenantTickets = computed(() => auth.can("TICKET_READ_TENANT"));
const canCreate = computed(() => auth.can("TICKET_CREATE"));
const canReadCatalog = computed(
  () => isAdmin.value && auth.can("CATALOG_READ_TENANT"),
);
const canEditCatalog = computed(
  () => isAdmin.value && auth.can("CATALOG_UPDATE_MINIMAL"),
);
const effectiveScope = computed(() => {
  if (canReadTenantTickets.value) return "tenant";
  if (auth.can("TICKET_READ_ASSIGNED") && auth.can("TICKET_READ_OWN")) return "visible";
  if (auth.can("TICKET_READ_ASSIGNED")) return "assigned";
  if (auth.can("TICKET_READ_OWN")) return "mine";
  return undefined;
});
const activeCatalogs = computed(() =>
  catalogs.value.filter((item) => item.status === "ACTIVE"),
);

async function load(token = ""): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const result = await ticketApi.tickets({
      pageSize: 20,
      pageToken: token || undefined,
      scope: scopeFilter.value || effectiveScope.value,
      status: statusFilter.value || undefined,
      sort: "-updatedAt",
    });
    items.value = result.items;
    nextToken.value = result.meta.page?.nextPageToken ?? null;
  } catch (reason) {
    error.value = reason;
  } finally {
    loading.value = false;
  }
}
async function loadCatalogs(): Promise<void> {
  if (!canCreate.value && !canReadCatalog.value) return;
  catalogLoading.value = true;
  catalogError.value = null;
  try {
    catalogs.value = await allPages(pageToken => catalogApi.items({ pageSize: 100, pageToken, sort: "itemName" }));
    if (!form.value.catalogItemId)
      form.value.catalogItemId = activeCatalogs.value[0]?.catalogItemId || "";
  } catch (reason) {
    catalogError.value = reason;
  } finally {
    catalogLoading.value = false;
  }
}
function resetPaging(): void {
  tokenStack.value = [];
  void load();
}
function nextPage(): void {
  if (!nextToken.value) return;
  tokenStack.value.push(nextToken.value);
  void load(nextToken.value);
}
function previousPage(): void {
  if (tokenStack.value.length < 2) {
    tokenStack.value = [];
    void load();
    return;
  }
  tokenStack.value.pop();
  const token = tokenStack.value[tokenStack.value.length - 1] || "";
  void load(token);
}
function openCreate(): void {
  actionError.value = null;
  asyncEffects.value = [];
  modalOpen.value = true;
  if (!catalogs.value.length) void loadCatalogs();
}
function closeCreate(): void {
  if (!submitting.value) modalOpen.value = false;
}
async function createTicket(): Promise<void> {
  submitting.value = true;
  actionError.value = null;
  try {
    const result = await ticketApi.create({
      catalogItemId: form.value.catalogItemId,
      subject: form.value.subject,
      description: form.value.description,
    }, createIntent.key(form.value));
    createIntent.reset();
    asyncEffects.value = result.meta.asyncEffects || [];
    modalOpen.value = false;
    form.value = {
      catalogItemId: activeCatalogs.value[0]?.catalogItemId || "",
      subject: "",
      description: "",
    };
    await router.push(`/tickets/${result.data.ticketId}`);
  } catch (reason) {
    actionError.value = reason;
  } finally {
    submitting.value = false;
  }
}

const catalogEdits = ref<
  Record<string, { itemName: string; description: string; status: string }>
>({});
function editCatalog(item: ServiceCatalogItem): void {
  catalogEdits.value[item.catalogItemId] = {
    itemName: item.itemName,
    description: item.description || "",
    status: item.status,
  };
}
async function saveCatalog(item: ServiceCatalogItem): Promise<void> {
  if (!canEditCatalog.value) return;
  const edit = catalogEdits.value[item.catalogItemId];
  if (!edit) return;
  actionError.value = null;
  try {
    await catalogApi.patchItem(
      item.catalogItemId,
      {
        itemName: edit.itemName,
        description: edit.description || null,
        status: edit.status as "ACTIVE" | "INACTIVE",
      },
      `"lv:${item.lockVersion}"`,
    );
    delete catalogEdits.value[item.catalogItemId];
    await loadCatalogs();
  } catch (reason) {
    actionError.value = reason;
  }
}

watch([statusFilter, scopeFilter], resetPaging);
watch(
  () => route.query.create,
  (value) => {
    if (value === "1" && canCreate.value) openCreate();
  },
  { immediate: true },
);
onMounted(() => {
  void Promise.all([load(), loadCatalogs()]);
});
</script>

<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">工单处理</p>
      <h1>工单</h1>
      <p class="lede">从提交、分派到关闭，随时掌握每一步进展。</p>
    </div>
    <div class="heading-actions">
      <button v-if="canCreate" class="button" type="button" @click="openCreate">
        <FilePlus2 :size="16" />新建工单</button
      ><button
        class="button button--secondary"
        type="button"
        :disabled="loading"
        @click="load"
      >
        <RefreshCw :size="16" />刷新
      </button>
    </div>
  </div>
  <ErrorNotice
    v-if="actionError && !modalOpen"
    :error="actionError"
    @retry="load"
  />
  <section class="panel list-panel">
    <div class="filter-bar">
      <Filter :size="16" class="filter-icon" />
      <div class="filter-group">
        <label for="ticket-status">状态</label
        ><select
          id="ticket-status"
          v-model="statusFilter"
          class="filter-control"
        >
          <option value="">全部状态</option>
          <option value="PENDING_ASSIGNMENT">待分配</option>
          <option value="ASSIGNED">已分配</option>
          <option value="IN_PROGRESS">处理中</option>
          <option value="AWAITING_APPROVAL">等待审批</option>
          <option value="RESOLVED">已解决</option>
          <option value="CLOSED">已关闭</option>
        </select>
      </div>
      <div v-if="canReadTenantTickets || (auth.can('TICKET_READ_OWN') && auth.can('TICKET_READ_ASSIGNED'))" class="filter-group">
        <label for="ticket-scope">范围</label
        ><select id="ticket-scope" v-model="scopeFilter" class="filter-control">
          <option value="">默认范围</option>
          <option v-if="canReadTenantTickets" value="tenant">全部工单</option>
          <option value="mine">我的工单</option>
          <option value="assigned">分配给我</option>
        </select>
      </div>
      <div class="filter-summary">{{ items.length }} 条当前页记录</div>
    </div>
    <ResourceState
      :loading="loading"
      :error="error"
      :empty="!loading && !error && items.length === 0"
      empty-text="当前范围暂无工单"
      @retry="load"
      ><div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>工单</th>
              <th>请求内容</th>
              <th>来源</th>
              <th>状态</th>
              <th>更新时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ticket in items" :key="ticket.ticketId">
              <td>
                <RouterLink
                  class="link-strong"
                  :to="`/tickets/${ticket.ticketId}`"
                  >{{ ticket.ticketNo }}</RouterLink
                ><small class="table-subtext">可从工单号查看详情</small>
              </td>
              <td>
                <strong class="table-subject">{{ ticket.subject }}</strong
                ><small class="table-subtext">{{ ticket.description }}</small>
              </td>
              <td>{{ originLabel(ticket.originType) }}</td>
              <td><StatusBadge :value="ticket.status" /></td>
              <td>{{ formatDate(ticket.updatedAt) }}</td>
              <td>
                <RouterLink
                  class="icon-link"
                  :to="`/tickets/${ticket.ticketId}`"
                  aria-label="查看工单"
                  title="查看工单"
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

  <section v-if="canReadCatalog" class="panel catalog-panel">
    <div class="panel-header">
      <div>
        <h2>服务目录设置</h2>
        <p>维护可提交的服务项目；审批规则和处理时效由系统统一管理。</p>
      </div>
      <Settings2 :size="19" class="panel-heading-icon" />
    </div>
    <ErrorNotice
      v-if="catalogError"
      :error="catalogError"
      @retry="loadCatalogs"
    /><ResourceState
      :loading="catalogLoading"
      :empty="!catalogLoading && catalogs.length === 0"
      empty-text="暂无服务目录"
      ><div class="catalog-list">
        <div
          v-for="item in catalogs"
          :key="item.catalogItemId"
          class="catalog-row"
        >
          <template v-if="catalogEdits[item.catalogItemId]"
            ><input
              v-model="catalogEdits[item.catalogItemId].itemName"
              aria-label="目录名称"
            /><input
              v-model="catalogEdits[item.catalogItemId].description"
              aria-label="目录描述"
            /><select
              v-model="catalogEdits[item.catalogItemId].status"
              aria-label="目录状态"
            >
              <option value="ACTIVE">启用</option>
              <option value="INACTIVE">停用</option></select
            ><button
              v-if="canEditCatalog"
              class="button button--small"
              type="button"
              @click="saveCatalog(item)"
            >
              保存</button
            ><button
              v-if="canEditCatalog"
              class="button button--secondary button--small"
              type="button"
              @click="delete catalogEdits[item.catalogItemId]"
            >
              取消
            </button></template
          ><template v-else
            ><div class="catalog-copy">
              <strong>{{ item.itemName }}</strong
              ><span
                >{{ item.description || "无描述" }}</span
              >
            </div>
            <StatusBadge :value="item.status" size="small" /><span
              class="catalog-risk"
              >{{
                item.riskLevel === "HIGH" ? "高风险 / 需审批" : "普通请求"
              }}</span
            ><button
              v-if="canEditCatalog"
              class="button button--ghost button--small"
              type="button"
              @click="editCatalog(item)"
            >
              编辑
            </button></template
          >
        </div>
      </div></ResourceState
    >
  </section>

  <div
    v-if="modalOpen"
    class="modal-layer"
    role="presentation"
    @click.self="closeCreate"
  >
    <section
      class="modal-panel panel"
      role="dialog"
      aria-modal="true"
      aria-labelledby="create-ticket-title"
    >
      <div class="panel-header">
        <div>
          <h2 id="create-ticket-title">新建工单</h2>
          <p>可选服务项目和可查看内容会根据当前账号自动确定。</p>
        </div>
        <button
          class="icon-button"
          type="button"
          aria-label="关闭"
          title="关闭"
          @click="closeCreate"
        >
          <X :size="18" />
        </button>
      </div>
      <form class="panel-body" @submit.prevent="createTicket">
        <ErrorNotice
          v-if="actionError"
          :error="actionError"
          @retry="
            () => {
              actionError = null;
            }
          "
        />
        <div class="form-grid">
          <div class="form-field form-field--full">
            <label for="new-ticket-catalog">服务目录</label
            ><select
              id="new-ticket-catalog"
              v-model="form.catalogItemId"
              required
              :disabled="catalogLoading"
            >
              <option value="" disabled>请选择目录</option>
              <option
                v-for="item in activeCatalogs"
                :key="item.catalogItemId"
                :value="item.catalogItemId"
              >
                {{ item.itemName
                }}{{ item.requiresApproval ? " · 两级审批" : "" }}
              </option></select
            ><span class="form-help"
              >高风险服务提交后会进入审批，审批完成前不会分派处理人。</span
            >
          </div>
          <div class="form-field form-field--full">
            <label for="new-ticket-subject">主题</label
            ><input
              id="new-ticket-subject"
              v-model.trim="form.subject"
              required
              maxlength="255"
            />
          </div>
          <div class="form-field form-field--full">
            <label for="new-ticket-description">描述</label
            ><textarea
              id="new-ticket-description"
              v-model.trim="form.description"
              required
              maxlength="10000"
            />
          </div>
        </div>
        <AsyncProgress :effects="asyncEffects" />
        <div class="form-actions">
          <button
            class="button button--secondary"
            type="button"
            :disabled="submitting"
            @click="closeCreate"
          >
            取消</button
          ><button
            class="button"
            type="submit"
            :disabled="submitting || !form.catalogItemId"
          >
            <span v-if="submitting" class="spinner spinner--light" />{{
              submitting ? "提交中…" : "提交工单"
            }}
          </button>
        </div>
      </form>
    </section>
  </div>
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
.table-subtext {
  display: block;
  max-width: 290px;
  margin-top: 4px;
  overflow: hidden;
  color: var(--muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-subject {
  display: block;
  max-width: 350px;
  overflow: hidden;
  color: #33434c;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.icon-link {
  display: inline-grid;
  place-items: center;
  width: 30px;
  height: 30px;
  color: var(--teal);
  border-radius: 5px;
}
.icon-link:hover {
  background: var(--teal-soft);
}
.catalog-panel {
  margin-top: 19px;
}
.panel-heading-icon {
  color: var(--teal);
}
.catalog-list {
  display: grid;
}
.catalog-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 20px;
  border-bottom: 1px solid #edf1f2;
}
.catalog-row:last-child {
  border-bottom: 0;
}
.catalog-copy {
  min-width: 0;
  flex: 1;
}
.catalog-copy strong,
.catalog-copy span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.catalog-copy strong {
  font-size: 13px;
}
.catalog-copy span {
  margin-top: 3px;
  color: var(--muted);
  font-size: 11px;
}
.catalog-risk {
  color: var(--muted);
  font-size: 11px;
  white-space: nowrap;
}
.catalog-row > input,
.catalog-row > select {
  min-width: 0;
  flex: 1;
  min-height: 32px;
  padding: 5px 7px;
  border: 1px solid #cbd9dc;
  border-radius: 5px;
  font-size: 12px;
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
  width: min(640px, 100%);
  max-height: calc(100vh - 40px);
  overflow: auto;
}
.modal-panel .panel-header {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #fff;
}
.modal-panel .panel-body {
  padding-top: 20px;
}
@media (max-width: 760px) {
  .filter-summary {
    width: 100%;
    margin-left: 0;
  }
  .catalog-row {
    align-items: flex-start;
    flex-wrap: wrap;
    padding: 12px 15px;
  }
  .catalog-copy {
    flex-basis: calc(100% - 80px);
  }
  .catalog-risk {
    margin-right: auto;
  }
  .catalog-row > input,
  .catalog-row > select {
    flex-basis: 100%;
  }
  .data-table {
    min-width: 700px;
  }
}
</style>
