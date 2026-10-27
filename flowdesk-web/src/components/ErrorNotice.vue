<script setup lang="ts">
/** 统一展示错误、追踪关联标识及重试入口，便于用户反馈和定位。 */
import { computed } from "vue";
import { AlertCircle, RefreshCw } from "lucide-vue-next";
import { errorLabel, isApiClientError } from "@/api/errors";

const props = withDefaults(defineProps<{ error: unknown; compact?: boolean; retryable?: boolean }>(), { retryable: true });
defineEmits<{ retry: [] }>();
const correlation = computed(() =>
  isApiClientError(props.error) ? props.error.correlationId : null,
);
</script>

<template>
  <div
    class="error-notice"
    :class="{ 'error-notice--compact': compact }"
    role="alert"
  >
    <AlertCircle :size="18" />
    <div class="error-notice-copy">
      <strong>{{ errorLabel(error) }}</strong
      ><small v-if="correlation">问题已记录，如需帮助可联系支持人员。</small>
    </div>
    <button
      v-if="retryable"
      class="button button--ghost button--small"
      type="button"
      @click="$emit('retry')"
    >
      <RefreshCw :size="14" />重试
    </button>
  </div>
</template>
