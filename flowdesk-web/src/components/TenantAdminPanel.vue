<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import {
  KeyRound,
  Plus,
  RefreshCw,
  Save,
  Shield,
  UserRound,
  UsersRound,
} from "lucide-vue-next";
import { iamApi } from "@/api/endpoints";
import { useAuthStore } from "@/stores/auth";
import type { AccessBinding, Permission, Role, UserSummary } from "@/types/api";
import StatusBadge from "./StatusBadge.vue";
import ResourceState from "./ResourceState.vue";
import ErrorNotice from "./ErrorNotice.vue";
import { permissionDisplayLabel, roleLabels } from "@/lib/format";

type Tab = "users" | "roles" | "permissions" | "bindings";
const tab = ref<Tab>("users");
const auth = useAuthStore();
const loading = ref(true);
const error = ref<unknown>(null);
const users = ref<UserSummary[]>([]);
const roles = ref<Role[]>([]);
const permissions = ref<Permission[]>([]);
const selectedUserId = ref("");
const selectedRoleId = ref("");
const userBindings = ref<AccessBinding[]>([]);
const roleBindings = ref<AccessBinding[]>([]);
const actionError = ref<unknown>(null);
const saving = ref(false);
const showUserForm = ref(false);
const showRoleForm = ref(false);
const newUser = ref({
  username: "",
  displayName: "",
  initialPassword: "",
  status: "ACTIVE" as "ACTIVE" | "INACTIVE",
});
const newRole = ref({
  roleCode: "REQUESTER",
  roleName: "",
  status: "ACTIVE" as "ACTIVE" | "INACTIVE",
});

const selectedUser = computed(
  () =>
    users.value.find((user) => user.userId === selectedUserId.value) || null,
);
const selectedRole = computed(
  () =>
    roles.value.find((role) => role.roleId === selectedRoleId.value) || null,
);
const canReadUsers = computed(() => auth.can("IAM_USER_READ"));
const canManageUsers = computed(() => auth.can("IAM_USER_MANAGE"));
const canReadRoles = computed(() => auth.can("IAM_ROLE_READ"));
const canManageRoles = computed(() => auth.can("IAM_ROLE_MANAGE"));
const canReadPermissions = computed(() => auth.can("IAM_PERMISSION_READ"));
const canReadBindings = computed(() => auth.can("IAM_BINDING_READ"));
const canManageBindings = computed(() => auth.can("IAM_BINDING_MANAGE"));
const availableTabs = computed<Tab[]>(() => {
  const result: Tab[] = [];
  if (canReadUsers.value) result.push("users");
  if (canReadRoles.value) result.push("roles");
  if (canReadPermissions.value) result.push("permissions");
  if (canReadBindings.value) result.push("bindings");
  return result;
});

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  users.value = [];
  roles.value = [];
  permissions.value = [];
  try {
    const jobs: Array<{
      key: "users" | "roles" | "permissions";
      promise: Promise<{ items: UserSummary[] | Role[] | Permission[] }>;
    }> = [];
    if (canReadUsers.value)
      jobs.push({
        key: "users",
        promise: iamApi.users({ pageSize: 100, sort: "displayName" }),
      });
    if (canReadRoles.value)
      jobs.push({
        key: "roles",
        promise: iamApi.roles({ pageSize: 100, sort: "roleName" }),
      });
    if (canReadPermissions.value)
      jobs.push({
        key: "permissions",
        promise: iamApi.permissions({ pageSize: 100, sort: "permissionCode" }),
      });
    const results = await Promise.allSettled(jobs.map((job) => job.promise));
    results.forEach((result, index) => {
      if (result.status !== "fulfilled") {
        if (!error.value) error.value = result.reason;
        return;
      }
      const key = jobs[index].key;
      if (key === "users") users.value = result.value.items as UserSummary[];
      if (key === "roles") roles.value = result.value.items as Role[];
      if (key === "permissions")
        permissions.value = result.value.items as Permission[];
    });
    if (!selectedUserId.value)
      selectedUserId.value = users.value[0]?.userId || "";
    if (!selectedRoleId.value)
      selectedRoleId.value = roles.value[0]?.roleId || "";
    if (canReadBindings.value) await loadBindings();
  } catch (reason) {
    error.value = reason;
  } finally {
    loading.value = false;
  }
}

async function loadBindings(): Promise<void> {
  if (!canReadBindings.value || !selectedUserId.value || !selectedRoleId.value) {
    userBindings.value = [];
    roleBindings.value = [];
    return;
  }
  try {
    const [userResult, roleResult] = await Promise.all([
      iamApi.userRoles(selectedUserId.value, {
        pageSize: 100,
        sort: "roleCode",
      }),
      iamApi.rolePermissions(selectedRoleId.value, {
        pageSize: 100,
        sort: "permissionCode",
      }),
    ]);
    userBindings.value = userResult.items;
    roleBindings.value = roleResult.items;
  } catch (reason) {
    actionError.value = reason;
  }
}

watch([selectedUserId, selectedRoleId], () => {
  if (canReadBindings.value) void loadBindings();
});
watch(
  availableTabs,
  (tabs) => {
    if (tabs.length && !tabs.includes(tab.value)) tab.value = tabs[0];
  },
  { immediate: true },
);
onMounted(load);

function etag(version: number): string {
  return `"lv:${version}"`;
}
function hasBinding(
  items: AccessBinding[],
  targetId: string,
): AccessBinding | undefined {
  return items.find((item) => item.targetId === targetId);
}

async function createUser(): Promise<void> {
  if (!canManageUsers.value) return;
  actionError.value = null;
  saving.value = true;
  try {
    await iamApi.createUser(newUser.value);
    showUserForm.value = false;
    newUser.value = {
      username: "",
      displayName: "",
      initialPassword: "",
      status: "ACTIVE",
    };
    await load();
  } catch (reason) {
    actionError.value = reason;
  } finally {
    saving.value = false;
  }
}
async function toggleUserStatus(user: UserSummary): Promise<void> {
  if (!canManageUsers.value) return;
  actionError.value = null;
  saving.value = true;
  try {
    await iamApi.patchUser(
      user.userId,
      { status: user.status === "ACTIVE" ? "INACTIVE" : "ACTIVE" },
      etag(user.lockVersion),
    );
    await load();
  } catch (reason) {
    actionError.value = reason;
  } finally {
    saving.value = false;
  }
}
async function createRole(): Promise<void> {
  if (!canManageRoles.value) return;
  actionError.value = null;
  saving.value = true;
  try {
    await iamApi.createRole(newRole.value);
    showRoleForm.value = false;
    newRole.value = { roleCode: "REQUESTER", roleName: "", status: "ACTIVE" };
    await load();
  } catch (reason) {
    actionError.value = reason;
  } finally {
    saving.value = false;
  }
}
async function toggleRoleStatus(role: Role): Promise<void> {
  if (!canManageRoles.value) return;
  actionError.value = null;
  saving.value = true;
  try {
    await iamApi.patchRole(
      role.roleId,
      { status: role.status === "ACTIVE" ? "INACTIVE" : "ACTIVE" },
      etag(role.lockVersion),
    );
    await load();
  } catch (reason) {
    actionError.value = reason;
  } finally {
    saving.value = false;
  }
}
async function toggleUserRole(role: Role): Promise<void> {
  if (!canManageBindings.value || !selectedUser.value) return;
  actionError.value = null;
  saving.value = true;
  const existing = hasBinding(userBindings.value, role.roleId);
  try {
    await iamApi.putUserRole(
      selectedUser.value.userId,
      role.roleId,
      { state: existing?.state === "GRANTED" ? "REVOKED" : "GRANTED" },
      {
        create: !existing,
        etag: existing ? etag(existing.lockVersion) : undefined,
      },
    );
    await loadBindings();
  } catch (reason) {
    actionError.value = reason;
  } finally {
    saving.value = false;
  }
}
async function toggleRolePermission(permission: Permission): Promise<void> {
  if (!canManageBindings.value || !selectedRole.value) return;
  actionError.value = null;
  saving.value = true;
  const existing = hasBinding(roleBindings.value, permission.permissionId);
  try {
    await iamApi.putRolePermission(
      selectedRole.value.roleId,
      permission.permissionId,
      { state: existing?.state === "GRANTED" ? "REVOKED" : "GRANTED" },
      {
        create: !existing,
        etag: existing ? etag(existing.lockVersion) : undefined,
      },
    );
    await loadBindings();
  } catch (reason) {
    actionError.value = reason;
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <section class="panel admin-panel">
    <div class="admin-tabs" role="tablist" aria-label="组织管理项目">
      <button
        v-if="canReadUsers"
        type="button"
        :class="{ active: tab === 'users' }"
        @click="tab = 'users'"
      >
        <UserRound :size="15" />用户
      </button>
      <button
        v-if="canReadRoles"
        type="button"
        :class="{ active: tab === 'roles' }"
        @click="tab = 'roles'"
      >
        <UsersRound :size="15" />角色
      </button>
      <button
        v-if="canReadPermissions"
        type="button"
        :class="{ active: tab === 'permissions' }"
        @click="tab = 'permissions'"
      >
        <Shield :size="15" />可用操作
      </button>
      <button
        v-if="canReadBindings"
        type="button"
        :class="{ active: tab === 'bindings' }"
        @click="tab = 'bindings'"
      >
        <KeyRound :size="15" />成员授权
      </button>
      <button
        class="admin-refresh"
        type="button"
        aria-label="刷新组织管理"
        title="刷新组织管理"
        :disabled="loading"
        @click="load"
      >
        <RefreshCw :size="15" />
      </button>
    </div>
    <ErrorNotice v-if="error" :error="error" @retry="load" />
    <ErrorNotice
      v-if="actionError"
      :error="actionError"
      @retry="loadBindings"
    />
    <ResourceState
      :loading="loading"
      :empty="!loading && availableTabs.length === 0"
      empty-text="当前账号无组织管理权限"
    >
      <div v-if="tab === 'users'" class="admin-content">
        <div class="admin-toolbar">
          <div><strong>成员账户</strong><span>管理成员和登录状态</span></div>
          <button
            v-if="canManageUsers"
            class="button button--small"
            type="button"
            @click="showUserForm = !showUserForm"
          >
            <Plus :size="14" />新建用户
          </button>
        </div>
        <form
          v-if="showUserForm && canManageUsers"
          class="inline-form"
          @submit.prevent="createUser"
        >
          <input
            v-model.trim="newUser.username"
            required
            placeholder="用户名"
            aria-label="用户名"
          /><input
            v-model.trim="newUser.displayName"
            required
            placeholder="显示名称"
            aria-label="显示名称"
          /><input
            v-model="newUser.initialPassword"
            required
            type="password"
            placeholder="初始密码"
            aria-label="初始密码"
            autocomplete="new-password"
          /><select v-model="newUser.status" aria-label="用户状态">
            <option value="ACTIVE">启用</option>
            <option value="INACTIVE">停用</option></select
          ><button
            class="button button--small"
            type="submit"
            :disabled="saving"
          >
            <Save :size="14" />创建
          </button>
        </form>
        <div class="table-wrap">
          <table class="data-table admin-table">
            <thead>
              <tr>
                <th>用户</th>
                <th>角色</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="user in users"
                :key="user.userId"
                :class="{ selected: selectedUserId === user.userId }"
                @click="selectedUserId = user.userId"
              >
                <td>
                  <strong>{{ user.displayName }}</strong
                  ><small>{{ user.username }}</small>
                </td>
                <td>
                  {{
                    user.roleCodes.map((code) => roleLabels[code]).join("、") ||
                    "未绑定"
                  }}
                </td>
                <td><StatusBadge :value="user.status" size="small" /></td>
                <td>
                  <button
                    v-if="canManageUsers"
                    class="button button--ghost button--small"
                    type="button"
                    @click.stop="toggleUserStatus(user)"
                  >
                    {{ user.status === "ACTIVE" ? "停用" : "启用" }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div v-else-if="tab === 'roles'" class="admin-content">
        <div class="admin-toolbar">
          <div><strong>角色</strong><span>定义成员可以执行的工作</span></div>
          <button
            v-if="canManageRoles"
            class="button button--small"
            type="button"
            @click="showRoleForm = !showRoleForm"
          >
            <Plus :size="14" />新建角色
          </button>
        </div>
        <form
          v-if="showRoleForm && canManageRoles"
          class="inline-form"
          @submit.prevent="createRole"
        >
          <select v-model="newRole.roleCode" aria-label="角色类型">
            <option
              v-for="code in Object.keys(roleLabels)"
              :key="code"
              :value="code"
            >
              {{ roleLabels[code as keyof typeof roleLabels] }}
            </option></select
          ><input
            v-model.trim="newRole.roleName"
            required
            placeholder="角色名称"
            aria-label="角色名称"
          /><select v-model="newRole.status" aria-label="角色状态">
            <option value="ACTIVE">启用</option>
            <option value="INACTIVE">停用</option></select
          ><button
            class="button button--small"
            type="submit"
            :disabled="saving"
          >
            <Save :size="14" />创建
          </button>
        </form>
        <div class="table-wrap">
          <table class="data-table admin-table">
            <thead>
              <tr>
                <th>角色</th>
                <th>自定义名称</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="role in roles"
                :key="role.roleId"
                :class="{ selected: selectedRoleId === role.roleId }"
                @click="selectedRoleId = role.roleId"
              >
                <td>{{ roleLabels[role.roleCode] || "自定义角色" }}</td>
                <td>{{ role.roleName }}</td>
                <td><StatusBadge :value="role.status" size="small" /></td>
                <td>
                  <button
                    v-if="canManageRoles"
                    class="button button--ghost button--small"
                    type="button"
                    @click.stop="toggleRoleStatus(role)"
                  >
                    {{ role.status === "ACTIVE" ? "停用" : "启用" }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div v-else-if="tab === 'permissions'" class="admin-content">
        <div class="admin-toolbar">
            <div><strong>可用操作</strong><span>查看成员可以执行的工作</span></div>
        </div>
        <div class="permission-grid">
          <div
            v-for="permission in permissions"
            :key="permission.permissionId"
            class="permission-row"
          >
            <span>{{
              permissionDisplayLabel(
                permission.permissionCode,
                permission.permissionName,
              )
            }}</span
            ><small>{{ permission.description || "可按角色分配" }}</small>
          </div>
        </div>
      </div>
      <div v-else class="admin-content">
        <div class="binding-selectors">
          <div class="form-field">
            <label for="binding-user">用户</label
            ><select id="binding-user" v-model="selectedUserId">
              <option
                v-for="user in users"
                :key="user.userId"
                :value="user.userId"
              >
                {{ user.displayName }}（{{ user.username }}）
              </option>
            </select>
          </div>
          <div class="form-field">
            <label for="binding-role">角色</label
            ><select id="binding-role" v-model="selectedRoleId">
              <option
                v-for="role in roles"
                :key="role.roleId"
                :value="role.roleId"
              >
                {{ roleLabels[role.roleCode] || role.roleName }}
              </option>
            </select>
          </div>
        </div>
        <div class="binding-columns">
          <div>
            <h3>成员角色 <small>{{ canManageBindings ? "点击切换授权" : "仅可查看" }}</small></h3>
            <button
              v-for="role in roles"
              :key="role.roleId"
              class="binding-row"
              type="button"
              :disabled="saving || !canManageBindings"
              @click="toggleUserRole(role)"
            >
              <span>{{ roleLabels[role.roleCode] || role.roleName }}</span
              ><StatusBadge
                :value="
                  hasBinding(userBindings, role.roleId)?.state || 'REVOKED'
                "
                size="small"
              />
            </button>
          </div>
          <div>
            <h3>角色操作 <small>{{ canManageBindings ? "点击切换授权" : "仅可查看" }}</small></h3>
            <button
              v-for="permission in permissions"
              :key="permission.permissionId"
              class="binding-row"
              type="button"
              :disabled="saving || !canManageBindings"
              @click="toggleRolePermission(permission)"
            >
              <span>{{
                permissionDisplayLabel(
                  permission.permissionCode,
                  permission.permissionName,
                )
              }}</span
              ><StatusBadge
                :value="
                  hasBinding(roleBindings, permission.permissionId)?.state ||
                  'REVOKED'
                "
                size="small"
              />
            </button>
          </div>
        </div>
      </div>
    </ResourceState>
  </section>
</template>

<style scoped>
.admin-tabs {
  display: flex;
  align-items: center;
  gap: 2px;
  overflow-x: auto;
  padding: 8px 12px;
  border-bottom: 1px solid var(--line);
  background: var(--surface-soft);
}
.admin-tabs button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 11px;
  border: 0;
  border-radius: 5px;
  color: var(--muted);
  background: transparent;
  font-size: 12px;
  white-space: nowrap;
}
.admin-tabs button:hover {
  color: var(--teal-dark);
  background: #e8f2f1;
}
.admin-tabs button.active {
  color: var(--teal-dark);
  background: #dcefeb;
  font-weight: 700;
}
.admin-tabs .admin-refresh {
  margin-left: auto;
  width: 32px;
  padding: 0;
  border: 1px solid var(--line);
  background: #fff;
}
.admin-content {
  min-height: 180px;
}
.admin-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px 12px;
}
.admin-toolbar strong,
.admin-toolbar span {
  display: block;
}
.admin-toolbar strong {
  font-size: 13px;
}
.admin-toolbar span {
  margin-top: 3px;
  color: var(--muted);
  font-size: 11px;
}
.inline-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0 18px 14px;
}
.inline-form input,
.inline-form select {
  min-width: 130px;
  flex: 1;
  min-height: 34px;
  padding: 6px 8px;
  border: 1px solid #cbd9dc;
  border-radius: 5px;
  font-size: 12px;
}
.admin-table {
  min-width: 680px;
}
.admin-table tr.selected td {
  background: #f0faf8;
}
.admin-table td strong,
.admin-table td small {
  display: block;
}
.admin-table td small {
  margin-top: 3px;
  color: var(--muted);
  font-size: 11px;
}
.permission-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  margin: 0 18px 18px;
  border: 1px solid var(--line);
  background: var(--line);
}
.permission-row {
  min-width: 0;
  padding: 10px;
  background: #fff;
}
.permission-row .mono {
  display: block;
  overflow: hidden;
  color: var(--teal-dark);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.permission-row small {
  display: block;
  margin-top: 4px;
  color: var(--muted);
  font-size: 10px;
}
.binding-selectors {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 16px 18px 4px;
}
.binding-columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
  padding: 10px 18px 20px;
}
.binding-columns h3 {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin: 0 0 8px;
}
.binding-columns h3 small {
  color: var(--muted);
  font-size: 10px;
  font-weight: 500;
}
.binding-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-height: 34px;
  padding: 0 9px;
  border: 1px solid #e3eaeb;
  border-radius: 5px;
  color: #40515a;
  background: #fff;
  font-size: 11px;
  text-align: left;
}
.binding-row + .binding-row {
  margin-top: 5px;
}
.binding-row:hover:not(:disabled) {
  border-color: #9dc7c3;
  background: #f3fbf9;
}
@media (max-width: 760px) {
  .permission-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    margin: 0 15px 15px;
  }
  .binding-selectors,
  .binding-columns {
    grid-template-columns: 1fr;
    padding-left: 15px;
    padding-right: 15px;
  }
  .admin-toolbar {
    padding-left: 15px;
    padding-right: 15px;
  }
  .inline-form {
    padding-left: 15px;
    padding-right: 15px;
  }
}
</style>
