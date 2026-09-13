<script setup lang="ts">
import { computed, ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import {
  Bell,
  Users,
  Settings2,
  ClipboardCheck,
  LayoutDashboard,
  LogOut,
  Menu,
  Siren,
  Ticket,
  X,
} from "lucide-vue-next";
import { useAuthStore } from "@/stores/auth";
import type { PermissionCode } from "@/types/api";
import { roleLabels } from "@/lib/format";
import { featureFlags } from "@/api/features";

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const open = ref(false);

interface NavItem {
  to: string;
  label: string;
  icon: typeof LayoutDashboard;
  permissions?: PermissionCode[];
}
const navItems: NavItem[] = [
  { to: "/", label: "工作台", icon: LayoutDashboard },
  {
    to: "/tickets",
    label: "工单",
    icon: Ticket,
    permissions: [
      "TICKET_READ_OWN",
      "TICKET_READ_ASSIGNED",
      "TICKET_READ_TENANT",
    ],
  },
  {
    to: "/approvals",
    label: "审批",
    icon: ClipboardCheck,
    permissions: [
      "APPROVAL_READ_ASSIGNED",
      "WORKFLOW_RUNTIME_READ_TENANT",
      "WORKFLOW_READ_TENANT",
    ],
  },
  {
    to: "/alerts",
    label: "告警",
    icon: Siren,
    permissions: ["INCIDENT_READ_TENANT", "ALERT_EVENT_READ_TENANT"],
  },
  {
    to: "/notifications",
    label: "通知",
    icon: Bell,
    permissions: ["NOTIFICATION_READ_SELF"],
  },
  { to: '/settings/users', label: '用户与权限', icon: Users, permissions: ['IAM_USER_READ'] },
  { to: '/settings/catalog', label: '工单类型', icon: Settings2, permissions: ['CATALOG_READ_TENANT'] },
];

const visibleNav = computed(() =>
  navItems.filter((item) => !item.permissions || auth.canAny(item.permissions)),
);
const isLogin = computed(() => route.name === "login");
const roleText = computed(
  () => auth.user?.roles.map((role) => roleLabels[role]).join(" / ") || "",
);
const connectionText = computed(() =>
  featureFlags.fullMock ? "本地数据" : "工作空间",
);

function closeMenu(): void {
  open.value = false;
}
function logout(): void {
  auth.logout();
  closeMenu();
  void router.replace({ name: "login" });
}
</script>

<template>
  <div v-if="isLogin || !auth.isAuthenticated" class="auth-frame">
    <slot />
  </div>
  <div v-else class="app-frame">
    <aside class="sidebar" :class="{ 'sidebar--open': open }">
      <div class="brand">
        <div class="brand-mark" aria-hidden="true">F</div>
        <div>
          <strong>FlowDesk</strong>
          <span>服务台工作空间</span>
        </div>
        <button
          class="icon-button sidebar-close"
          type="button"
          aria-label="关闭导航"
          title="关闭导航"
          @click="closeMenu"
        >
          <X :size="19" />
        </button>
      </div>
      <nav class="primary-nav" aria-label="主导航">
        <RouterLink
          v-for="item in visibleNav"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          :class="{
            active:
              item.to === '/'
                ? route.path === '/'
                : route.path.startsWith(item.to),
          }"
          @click="closeMenu"
        >
          <component :is="item.icon" :size="18" stroke-width="1.8" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-footer">
        <div class="connection-pill">
          <span class="status-dot" :class="featureFlags.fullMock ? 'status-dot--warn' : 'status-dot--neutral'" /> {{ connectionText }}
        </div>
        <div class="security-note">访问权限按账号统一管理</div>
      </div>
    </aside>
    <div
      v-if="open"
      class="sidebar-scrim"
      aria-hidden="true"
      @click="closeMenu"
    />
    <main class="main-shell">
      <header class="topbar">
        <button
          class="icon-button menu-toggle"
          type="button"
          aria-label="打开导航"
          title="打开导航"
          @click="open = true"
        >
          <Menu :size="21" />
        </button>
        <div class="breadcrumb">
          <span>FlowDesk</span><span class="breadcrumb-separator">/</span
          ><strong>{{
            route.meta.title ||
            (route.name === "dashboard" ? "工作台" : String(route.name || ""))
          }}</strong>
        </div>
        <div class="topbar-actions">
          <RouterLink
            v-if="auth.can('NOTIFICATION_READ_SELF')"
            to="/notifications"
            class="topbar-notice"
            aria-label="查看通知"
            title="查看通知"
            ><Bell :size="18"
          /></RouterLink>
          <div class="user-chip">
            <div class="avatar">
              {{ auth.user?.displayName?.slice(0, 1) || "?" }}
            </div>
            <div class="user-chip-copy">
              <strong>{{ auth.user?.displayName }}</strong
              ><span>{{ roleText }}</span>
            </div>
          </div>
          <button
            class="icon-button"
            type="button"
            aria-label="退出登录"
            title="退出登录"
            @click="logout"
          >
            <LogOut :size="18" />
          </button>
        </div>
      </header>
      <section class="content-wrap"><slot /></section>
    </main>
  </div>
</template>
