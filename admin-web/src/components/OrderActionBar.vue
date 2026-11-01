<script setup lang="ts">
import { computed } from 'vue'
export interface OrderAction { kind: string; label: string; type: 'primary' | 'warning' | 'danger' }
const props = defineProps<{ actions: OrderAction[]; busy?: boolean; compact?: boolean }>()
const emit = defineEmits<{ select: [kind: string] }>()
const primary = computed(() => props.actions.find(item => item.kind === 'start') || props.actions.find(item => item.kind === 'complete') || props.actions[0])
const visible = computed(() => props.compact ? primary.value ? [primary.value] : [] : props.actions)
const secondary = computed(() => props.compact ? props.actions.filter(item => item.kind !== primary.value?.kind) : [])
</script>

<template>
  <div class="table-actions">
    <el-button v-for="item in visible" :key="item.kind" link :type="item.type" :disabled="busy" @click="emit('select', item.kind)">{{ item.label }}</el-button>
    <el-dropdown v-if="secondary.length" trigger="click" :disabled="busy" @command="kind => emit('select', String(kind))"><el-button link type="primary" :disabled="busy">更多<AppIcon name="ArrowDown" :size="12"/></el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-for="item in secondary" :key="item.kind" :command="item.kind" :class="{ 'danger-action': item.type === 'danger' }">{{ item.label }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
  </div>
</template>
<style scoped>
.danger-action{color:var(--el-color-danger)}
</style>
