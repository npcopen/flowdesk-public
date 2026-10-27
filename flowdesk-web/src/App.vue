<script setup lang="ts">
/** 应用根组件：通过页面壳承载当前路由内容。 */
import { RouterView } from "vue-router";
import AppShell from "@/components/AppShell.vue";
</script>

<template>
  <AppShell>
    <RouterView />
  </AppShell>
</template>
