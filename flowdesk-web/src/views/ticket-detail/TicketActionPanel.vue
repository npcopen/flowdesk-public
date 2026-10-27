<script setup lang="ts">
/** 依据页面计算的权限与状态显示工单操作入口。 */
import { Check, CircleAlert, UserRound } from "lucide-vue-next";
import type { TicketActionKind } from "./useTicketActions";

defineProps<{
  canAssign: boolean;
  canAccept: boolean;
  canTransfer: boolean;
  canResolve: boolean;
  canClose: boolean;
  canReopen: boolean;
}>();
const emit = defineEmits<{ open: [kind: TicketActionKind] }>();
</script>

<template>
  <section class="panel action-panel">
    <div class="panel-header">
      <div>
        <h2>处理操作</h2>
        <p>提交后系统会再次确认权限和最新状态。</p>
      </div>
      <CircleAlert :size="18" class="panel-heading-icon" />
    </div>
    <div class="action-buttons">
      <button
        v-if="canAssign"
        class="button"
        type="button"
        @click="emit('open', 'assign')"
      >
        <UserRound :size="15" />分配</button
      ><button
        v-if="canAccept"
        class="button"
        type="button"
        @click="emit('open', 'accept')"
      >
        <Check :size="15" />接单</button
      ><button
        v-if="canTransfer"
        class="button button--secondary"
        type="button"
        @click="emit('open', 'transfer')"
      >
        <UserRound :size="15" />转派</button
      ><button
        v-if="canResolve"
        class="button"
        type="button"
        @click="emit('open', 'resolve')"
      >
        <Check :size="15" />标记已解决</button
      ><button
        v-if="canClose"
        class="button"
        type="button"
        @click="emit('open', 'close')"
      >
        关闭工单</button
      ><button
        v-if="canReopen"
        class="button button--secondary"
        type="button"
        @click="emit('open', 'reopen')"
      >
        重新打开</button
      ><span
        v-if="
          !canAssign &&
          !canAccept &&
          !canTransfer &&
          !canResolve &&
          !canClose &&
          !canReopen
        "
        class="muted action-empty"
        >当前账号没有可执行操作，或工单正在处理/已结束。</span
      >
    </div>
  </section>
</template>

<style scoped>
.panel-heading-icon {
  color: var(--teal);
}
.action-panel .panel-header {
  align-items: center;
}
.action-buttons {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 9px;
  padding: 18px 21px;
}
.action-empty {
  font-size: 12px;
}
@media (max-width: 620px) {
  .action-buttons {
    padding: 15px 16px;
  }
}
</style>
