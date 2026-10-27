<script setup lang="ts">
/** 提交组织与用户凭据，登录后返回允许的应用内目标页面。 */
import { computed, ref } from "vue";
import {
  ArrowRight,
  Eye,
  EyeOff,
  LockKeyhole,
  ShieldCheck,
} from "lucide-vue-next";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { errorLabel } from "@/api/errors";
import { featureFlags } from "@/api/features";

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const tenantCode = ref("");
const username = ref("");
const password = ref("");
const showPassword = ref(false);
const submitting = ref(false);
const error = ref<unknown>(null);

const modeLabel = computed(() =>
  featureFlags.fullMock ? "本地工作空间" : "安全登录",
);

/**
 * 提交组织、用户名和密码完成登录。
 * 成功后跳转有效的站内目标地址，结束时清理密码输入并展示失败反馈。
 */
async function submit(): Promise<void> {
  submitting.value = true;
  error.value = null;
  try {
    await auth.login({
      tenantCode: tenantCode.value,
      username: username.value,
      password: password.value,
    });
    const redirect =
      typeof route.query.redirect === "string" &&
      route.query.redirect.startsWith("/")
        ? route.query.redirect
        : "/";
    await router.replace(redirect);
  } catch (reason) {
    error.value = reason;
  } finally {
    password.value = "";
    submitting.value = false;
  }
}
</script>

<template>
  <main class="login-layout">
    <section class="login-aside">
      <div class="login-aside-inner">
        <div class="brand brand--login">
          <div class="brand-mark">F</div>
          <div><strong>FlowDesk</strong><span>服务台工作空间</span></div>
        </div>
        <div class="login-intro">
          <p class="eyebrow">清晰协作，安心处理</p>
          <h1>让每一个请求都有下一步。</h1>
          <p>
            在同一个工作空间里处理工单、审批和运行告警。数据访问遵循组织权限与访问边界。
          </p>
        </div>
        <div class="login-assurance">
          <ShieldCheck :size="18" /><span
            >账号信息仅用于本次登录，不会保存在浏览器中</span
          >
        </div>
      </div>
    </section>
    <section class="login-card-wrap">
      <div class="login-card panel">
        <div class="login-card-header">
          <div>
            <p class="eyebrow">{{ modeLabel }}</p>
            <h2>登录工作空间</h2>
            <p>使用组织代码与账号信息继续。</p>
          </div>
          <LockKeyhole :size="23" class="login-lock" />
        </div>
        <form class="login-form" @submit.prevent="submit">
          <div class="form-field">
            <label for="tenant-code">组织代码</label
            ><input
              id="tenant-code"
              v-model.trim="tenantCode"
              required
              autocomplete="organization"
              data-testid="tenant-code"
            />
          </div>
          <div class="form-field">
            <label for="username">用户名</label
            ><input
              id="username"
              v-model.trim="username"
              required
              autocomplete="username"
              data-testid="username"
            />
          </div>
          <div class="form-field">
            <label for="password">密码</label>
            <div class="password-input">
              <input
                id="password"
                v-model="password"
                required
                :type="showPassword ? 'text' : 'password'"
                autocomplete="current-password"
                data-testid="password"
              /><button
                class="icon-button"
                type="button"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :title="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <EyeOff v-if="showPassword" :size="17" /><Eye
                  v-else
                  :size="17"
                />
              </button>
            </div>
          </div>
          <div v-if="error" class="login-error" role="alert">
            {{ errorLabel(error) }}
          </div>
          <button
            class="button login-submit"
            type="submit"
            :disabled="submitting"
          >
            <span v-if="submitting" class="spinner spinner--light" />{{
              submitting ? "正在验证…" : "进入工作台"
            }}<ArrowRight v-if="!submitting" :size="16" />
          </button>
        </form>
        <p v-if="featureFlags.fullMock" class="local-mode-note">
          当前显示本地数据，通知和后续处理功能暂不可用。
        </p>
        <p class="login-footnote">
          登录状态只在当前页面保留；登出或刷新页面即清除。
        </p>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(420px, 0.9fr);
  width: min(1180px, 100%);
  min-height: min(720px, calc(100vh - 48px));
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 24px 70px rgba(20, 55, 62, 0.12);
}
.login-aside {
  display: flex;
  align-items: stretch;
  padding: 46px;
  color: #e8f6f4;
  background: #123b40;
}
.login-aside-inner {
  display: flex;
  flex-direction: column;
  width: 100%;
}
.brand--login {
  padding: 0;
}
.login-intro {
  max-width: 460px;
  margin: auto 0;
}
.login-intro h1 {
  max-width: 420px;
  margin-bottom: 14px;
  color: #fff;
  font-size: 44px;
}
.login-intro p:not(.eyebrow) {
  max-width: 430px;
  color: #b9d9d5;
  font-size: 15px;
  line-height: 1.7;
}
.login-assurance {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #a5d8d1;
  font-size: 12px;
}
.login-card-wrap {
  display: grid;
  place-items: center;
  padding: 32px;
  background: #fbfdfd;
}
.login-card {
  width: 100%;
  max-width: 440px;
  border: 0;
  box-shadow: none;
}
.login-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 25px;
}
.login-card-header h2 {
  margin-bottom: 6px;
  font-size: 25px;
}
.login-card-header p:not(.eyebrow) {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}
.login-lock {
  color: var(--teal);
}
.login-form {
  display: grid;
  gap: 16px;
}
.password-input {
  position: relative;
}
.password-input input {
  padding-right: 43px;
}
.password-input .icon-button {
  position: absolute;
  right: 3px;
  top: 2px;
}
.login-submit {
  width: 100%;
  min-height: 44px;
  margin-top: 4px;
}
.spinner--light {
  border-color: rgba(255, 255, 255, 0.38);
  border-top-color: #fff;
}
.login-error {
  padding: 10px 12px;
  border: 1px solid #f1c2bc;
  border-radius: 6px;
  color: var(--red);
  background: var(--red-soft);
  font-size: 12px;
}
.local-mode-note {
  margin: 20px 0 0;
  padding: 10px 12px;
  border: 1px solid #d9e8e5;
  border-radius: 6px;
  color: #4f686c;
  background: #f2faf8;
  font-size: 11px;
  line-height: 1.5;
}
.login-footnote {
  margin: 22px 0 0;
  color: #88969d;
  font-size: 11px;
  line-height: 1.55;
}
@media (max-width: 800px) {
  .login-layout {
    display: block;
    min-height: 0;
  }
  .login-aside {
    min-height: 220px;
    padding: 26px 24px;
  }
  .login-intro {
    margin: 38px 0 0;
  }
  .login-intro h1 {
    font-size: 30px;
  }
  .login-intro p:not(.eyebrow) {
    display: none;
  }
  .login-assurance {
    margin-top: 24px;
  }
  .login-card-wrap {
    padding: 28px 20px 32px;
  }
  .login-card-header h2 {
    font-size: 22px;
  }
}
@media (max-width: 420px) {
  .auth-frame {
    padding: 0;
  }
  .login-aside {
    padding: 22px 18px;
  }
  .login-card-wrap {
    padding: 24px 17px 30px;
  }
}
</style>
