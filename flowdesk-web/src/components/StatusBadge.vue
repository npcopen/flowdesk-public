<script setup lang="ts">
import { computed } from "vue";
import { label } from "@/lib/format";

const props = defineProps<{
  value: string | null | undefined;
  size?: "small" | "normal";
}>();
const tone = computed(() => {
  const value = (props.value || "").toUpperCase();
  if (
    [
      "CLOSED",
      "RESOLVED",
      "RECOVERED",
      "APPROVED",
      "COMPLETED_PASSED",
      "SATISFIED_ON_TIME",
      "ESTABLISHED",
      "VALID",
      "READ",
    ].includes(value)
  )
    return "success";
  if (
    [
      "APPROVAL_REJECTED",
      "REJECTED",
      "COMPLETED_REJECTED",
      "BREACHED",
      "FAILED",
    ].includes(value)
  )
    return "danger";
  if (["IN_PROGRESS", "ASSIGNED", "RUNNING", "REPLAYING"].includes(value))
    return "info";
  if (
    [
      "AWAITING_APPROVAL",
      "PENDING",
      "PENDING_ASSIGNMENT",
      "REQUESTED",
      "RECOVERY_REQUIRED",
      "OPEN",
      "UNREAD",
    ].includes(value)
  )
    return "warning";
  return "neutral";
});
</script>

<template>
  <span
    class="status-badge"
    :class="[
      `status-badge--${tone}`,
      { 'status-badge--small': size === 'small' },
    ]"
    ><span class="status-badge-dot" />{{ label(value) }}</span
  >
</template>
