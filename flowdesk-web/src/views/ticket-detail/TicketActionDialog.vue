<script setup lang="ts">
/** 收集工单操作需要的处理人或原因，通过事件交由页面执行提交。 */
import { X } from "lucide-vue-next";
import ErrorNotice from "@/components/ErrorNotice.vue";
import type { UserIdentity } from "@/types/api";
import type { TicketActionKind } from "./useTicketActions";

defineProps<{
  actionKind: TicketActionKind | null;
  actionTitle: string;
  actionLoading: boolean;
  actionError: unknown;
  candidates: UserIdentity[];
}>();
const actionText = defineModel<string>("actionText", { required: true });
const selectedAssignee = defineModel<string>("selectedAssignee", { required: true });
const emit = defineEmits<{ close: []; submit: []; "clear-error": [] }>();
</script>

<template>
  <div v-if="actionKind" class="modal-layer" @click.self="emit('close')">
    <section
      class="modal-panel panel"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="`action-title-${actionKind}`"
    >
      <div class="panel-header">
        <div>
          <h2 :id="`action-title-${actionKind}`">{{ actionTitle }}</h2>
          <p>提交后系统会再次确认权限和最新状态。</p>
        </div>
        <button
          class="icon-button"
          type="button"
          aria-label="关闭"
          title="关闭"
          @click="emit('close')"
        >
          <X :size="18" />
        </button>
      </div>
      <form class="panel-body" @submit.prevent="emit('submit')">
        <ErrorNotice
          v-if="actionError"
          :error="actionError"
          @retry="emit('clear-error')"
        />
        <div
          v-if="actionKind === 'assign' || actionKind === 'transfer'"
          class="form-field"
        >
          <label for="action-assignee">处理人</label
          ><select id="action-assignee" v-model="selectedAssignee" required>
            <option
              v-for="candidate in candidates"
              :key="candidate.userId"
              :value="candidate.userId"
            >
              {{ candidate.displayName }}（{{ candidate.username }}）
            </option></select
          ><span v-if="!candidates.length" class="form-help"
            >暂时没有可用的处理人，请稍后重试。</span
          >
        </div>
        <div
          v-if="['transfer', 'resolve', 'close', 'reopen'].includes(actionKind)"
          class="form-field"
        >
          <label for="action-reason"
            >{{ actionKind === "resolve" ? "解决摘要" : "原因"
            }}<span v-if="actionKind !== 'close'">（必填）</span></label
          ><textarea
            id="action-reason"
            v-model.trim="actionText"
            :required="actionKind !== 'close'"
            maxlength="1000"
          />
        </div>
        <div class="form-actions">
          <button
            class="button button--secondary"
            type="button"
            :disabled="actionLoading"
            @click="emit('close')"
          >
            取消</button
          ><button
            class="button"
            type="submit"
            :disabled="
              actionLoading ||
              ((actionKind === 'assign' || actionKind === 'transfer') &&
                !selectedAssignee)
            "
          >
            <span v-if="actionLoading" class="spinner spinner--light" />{{
              actionLoading ? "提交中…" : "确认操作"
            }}
          </button>
        </div>
      </form>
    </section>
  </div>
</template>

<style scoped>
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
  width: min(540px, 100%);
  max-height: calc(100vh - 40px);
  overflow: auto;
}
.modal-panel .panel-header {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #fff;
}
</style>
