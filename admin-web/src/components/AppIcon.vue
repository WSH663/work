<script setup lang="ts">
import { computed } from 'vue'
import { Monitor, Tickets, User, Van, Picture, Money, Share, Wallet, Setting, UserFilled, Document, Refresh, Search, Plus, Menu, ArrowDown, ArrowUp, ArrowRight, SwitchButton, OfficeBuilding, View, Edit, Delete, Check, Close, Warning, Upload, Phone, DocumentCopy, Location } from '@element-plus/icons-vue'
const props = withDefaults(defineProps<{ name: string; size?: number }>(), { size: 16 })
const icons: Record<string, any> = { Monitor, Tickets, User, Van, Picture, Money, Share, Wallet, Setting, UserFilled, Document, Refresh, Search, Plus, Menu, ArrowDown, ArrowUp, ArrowRight, SwitchButton, OfficeBuilding, View, Edit, Delete, Check, Close, Warning, Upload, Phone, DocumentCopy, Location }
const icon = computed(() => icons[props.name] || Document)
</script>
<template><el-icon :size="size" aria-hidden="true"><component :is="icon" /></el-icon></template>
