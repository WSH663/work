<script setup lang="ts">defineProps<{ error: string }>(); defineEmits<{ retry: [] }>()</script>
<template><el-alert v-if="error" class="load-error" :title="error" type="error" :closable="false" show-icon><template #default><el-button type="danger" link @click="$emit('retry')">重新加载</el-button></template></el-alert></template>
