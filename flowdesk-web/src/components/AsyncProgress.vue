<script setup lang="ts">
/** 展示已提交操作的异步副作用进度，区分请求成功与后台处理完成。 */
import { Clock3 } from "lucide-vue-next";
import type { AsyncProgress } from "@/types/api";
import StatusBadge from "./StatusBadge.vue";
import { asyncEffectLabel } from "@/lib/format";
defineProps<{ effects?: AsyncProgress[] }>();
</script>

<template>
  <div v-if="effects?.length" class="async-progress" aria-live="polite">
    <Clock3 :size="16" /><span>正在更新</span>
    <span
      v-for="effect in effects"
      :key="`${effect.kind}-${effect.relatedRefId}`"
      class="async-effect"
      ><strong>{{ asyncEffectLabel(effect.kind) }}</strong
      ><StatusBadge :value="effect.state" size="small"
    /></span>
  </div>
</template>
