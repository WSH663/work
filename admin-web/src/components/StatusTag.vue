<script setup lang="ts">
import { statusNames, statusType } from '../lib/api'
defineProps<{ value?: string }>()
</script>
<template><el-tag :type="statusType(value || '')" effect="plain" disable-transitions>{{ statusNames[value || ''] || value || '—' }}</el-tag></template>
