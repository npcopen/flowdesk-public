<script setup lang="ts">
/** 为页面资源提供统一的加载、失败、空数据和正常内容展示。 */
defineProps<{
  loading?: boolean;
  empty?: boolean;
  error?: unknown;
  emptyText?: string;
}>();
defineEmits<{ retry: [] }>();
</script>

<template>
  <div
    v-if="loading"
    class="resource-state resource-state--loading"
    aria-live="polite"
  >
    <span class="spinner" /><span>正在加载…</span>
  </div>
  <ErrorNotice v-else-if="error" :error="error" @retry="$emit('retry')" />
  <div v-else-if="empty" class="resource-state resource-state--empty">
    <div class="empty-mark">—</div>
    <strong>{{ emptyText || "暂无记录" }}</strong
    ><span>有新的信息时会显示在这里。</span>
  </div>
  <slot v-else />
</template>

<script lang="ts">
import ErrorNotice from "./ErrorNotice.vue";
export default { components: { ErrorNotice } };
</script>
