<script setup lang="ts">
/** 只负责发出上一页、下一页事件，游标历史由使用它的页面维护。 */
import { ArrowLeft, ArrowRight } from "lucide-vue-next";
defineProps<{
  hasNext: boolean;
  hasPrevious: boolean;
  disabled?: boolean;
  label?: string;
}>();
defineEmits<{ next: []; previous: [] }>();
</script>

<template>
  <div class="pagination-controls">
    <span>{{ label || "分页结果" }}</span>
    <div class="pagination-buttons">
      <button
        class="icon-button"
        type="button"
        :disabled="disabled || !hasPrevious"
        aria-label="上一页"
        title="上一页"
        @click="$emit('previous')"
      >
        <ArrowLeft :size="16" />
      </button>
      <button
        class="icon-button"
        type="button"
        :disabled="disabled || !hasNext"
        aria-label="下一页"
        title="下一页"
        @click="$emit('next')"
      >
        <ArrowRight :size="16" />
      </button>
    </div>
  </div>
</template>
