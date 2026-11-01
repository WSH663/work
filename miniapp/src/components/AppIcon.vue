<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{ name: string; size?: number | string; color?: string; label?: string }>(), { size: 20, color: 'muted', label: '' })
const tones: Record<string, string> = { '#1f2329': 'normal', '#646a73': 'muted', '#16845b': 'brand', '#ffffff': 'white', '#fff': 'white', '#d92d20': 'danger' }
const tone = computed(() => tones[props.color.toLowerCase()] || (['normal', 'muted', 'brand', 'white', 'danger'].includes(props.color) ? props.color : 'muted'))
const dimension = computed(() => typeof props.size === 'number' ? `${props.size}px` : props.size)
</script>
<template><image class="app-icon" :src="`/static/icons/${name}-${tone}.png`" mode="aspectFit" :aria-label="label || undefined" :aria-hidden="label ? undefined : true" :style="{ width: dimension, height: dimension }" /></template>
<style scoped>.app-icon{display:inline-block;vertical-align:middle;flex-shrink:0;pointer-events:none}</style>
