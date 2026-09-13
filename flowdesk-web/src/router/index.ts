import {
  createRouter,
  createWebHistory,
  type RouteLocationNormalized,
} from "vue-router";
import type { PermissionCode } from "@/types/api";
import { useAuthStore } from "@/stores/auth";

declare module "vue-router" {
  interface RouteMeta {
    requiresAuth?: boolean;
    permissions?: PermissionCode[];
    anyPermission?: boolean;
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("@/views/LoginPage.vue"),
      meta: { title: "登录" },
    },
    {
      path: "/",
      name: "dashboard",
      component: () => import("@/views/DashboardPage.vue"),
      meta: { requiresAuth: true, title: "工作台" },
    },
    {
      path: "/tickets",
      name: "tickets",
      component: () => import("@/views/TicketsPage.vue"),
      meta: {
        requiresAuth: true,
        title: "工单",
        permissions: [
          "TICKET_READ_OWN",
          "TICKET_READ_ASSIGNED",
          "TICKET_READ_TENANT",
        ],
        anyPermission: true,
      },
    },
    {
      path: "/tickets/:id",
      name: "ticket-detail",
      component: () => import("@/views/TicketDetailPage.vue"),
      meta: {
        requiresAuth: true,
        title: "工单详情",
        permissions: [
          "TICKET_READ_OWN",
          "TICKET_READ_ASSIGNED",
          "TICKET_READ_TENANT",
        ],
        anyPermission: true,
      },
    },
    {
      path: "/approvals",
      name: "approvals",
      component: () => import("@/views/ApprovalsPage.vue"),
      meta: {
        requiresAuth: true,
        title: "审批",
        permissions: [
          "APPROVAL_READ_ASSIGNED",
          "WORKFLOW_RUNTIME_READ_TENANT",
          "WORKFLOW_READ_TENANT",
        ],
        anyPermission: true,
      },
    },
    {
      path: "/approvals/:id",
      name: "approval-detail",
      component: () => import("@/views/ApprovalDetailPage.vue"),
      meta: {
        requiresAuth: true,
        title: "审批详情",
        permissions: [
          "APPROVAL_READ_ASSIGNED",
          "WORKFLOW_RUNTIME_READ_TENANT",
        ],
        anyPermission: true,
      },
    },
    {
      path: "/alerts",
      name: "alerts",
      component: () => import("@/views/AlertsPage.vue"),
      meta: {
        requiresAuth: true,
        title: "告警",
        permissions: ["INCIDENT_READ_TENANT", "ALERT_EVENT_READ_TENANT"],
        anyPermission: true,
      },
    },
    {
      path: "/alerts/:id",
      name: "alert-detail",
      component: () => import("@/views/AlertDetailPage.vue"),
      meta: {
        requiresAuth: true,
        title: "告警详情",
        permissions: ["INCIDENT_READ_TENANT", "ALERT_EVENT_READ_TENANT"],
        anyPermission: true,
      },
    },
    {
      path: "/notifications",
      name: "notifications",
      component: () => import("@/views/NotificationsPage.vue"),
      meta: {
        requiresAuth: true,
        title: "通知",
        permissions: ["NOTIFICATION_READ_SELF"],
      },
    },
    { path: '/settings/users', name: 'users', component: () => import('@/views/UserManagementPage.vue'), meta: { requiresAuth: true, title: '用户与权限', permissions: ['IAM_USER_READ'] } },
    { path: '/settings/catalog', name: 'catalog', component: () => import('@/views/CatalogManagementPage.vue'), meta: { requiresAuth: true, title: '工单类型', permissions: ['CATALOG_READ_TENANT'] } },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});

function isAllowed(
  route: RouteLocationNormalized,
  auth: ReturnType<typeof useAuthStore>,
): boolean {
  const permissions = route.meta.permissions;
  if (!permissions || permissions.length === 0) return true;
  return route.meta.anyPermission
    ? auth.canAny(permissions)
    : permissions.every((permission) => auth.can(permission));
}

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: "login", query: { redirect: to.fullPath } };
  }
  if (to.name === "login" && auth.isAuthenticated) return { name: "dashboard" };
  if (to.meta.requiresAuth && !isAllowed(to, auth))
    return { name: "dashboard" };
  return true;
});

export default router;
