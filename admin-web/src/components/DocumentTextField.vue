<script setup lang="ts">
import { nextTick, ref } from 'vue'
import type { InputInstance } from 'element-plus'
import { documentVariables } from '../lib/documents'
const props = withDefaults(defineProps<{ modelValue: string; label: string; limit: number; rows?: number; required?: boolean; error?: string; disabled?: boolean; variables: Record<string, string>; placeholder?: string }>(), { rows: 1 })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const input = ref<InputInstance>(), opened = ref(false)
let caretStart: number | null = null, caretEnd: number | null = null
function rememberCaret() {
  const element = input.value?.textarea || input.value?.input
  caretStart = element?.selectionStart ?? null; caretEnd = element?.selectionEnd ?? null
}
async function insert(key: string) {
  if (props.disabled) return
  const token = `{{${key}}}`, start = caretStart ?? props.modelValue.length, end = caretEnd ?? start
  const next = props.modelValue.slice(0, start) + token + props.modelValue.slice(end)
  if (next.length > props.limit) return
  emit('update:modelValue', next); opened.value = false
  await nextTick()
  const element = input.value?.textarea || input.value?.input
  element?.focus(); element?.setSelectionRange(start + token.length, start + token.length)
  caretStart = start + token.length; caretEnd = caretStart
}
</script>
<template>
  <el-form-item class="document-text-field" :error="error" :required="required">
    <template #label><span>{{ label }}</span><el-popover v-model:visible="opened" placement="bottom-end" :width="300" trigger="click" :disabled="disabled"><template #reference><el-button type="primary" link :disabled="disabled" @mousedown="rememberCaret">插入平台信息</el-button></template><div class="variable-picker"><strong>跟随平台配置自动更新</strong><p>选择要插入的信息，预览会显示当前内容。</p><button v-for="item in documentVariables" :key="item.key" type="button" :disabled="disabled || modelValue.length + item.key.length + 4 > limit" @click="insert(item.key)"><span>{{ item.label }}</span><small>{{ variables[item.key] || '当前未配置' }}</small></button></div></el-popover></template>
    <el-input ref="input" :model-value="modelValue" :type="rows > 1 ? 'textarea' : 'text'" :rows="rows" :maxlength="limit" show-word-limit :disabled="disabled" :placeholder="placeholder" @update:model-value="value => emit('update:modelValue', value)" @blur="rememberCaret" @click="rememberCaret" @keyup="rememberCaret"/>
  </el-form-item>
</template>
<style scoped>
.document-text-field :deep(.el-form-item__label){display:flex;align-items:center;justify-content:space-between;gap:12px;width:100%;line-height:24px}.document-text-field :deep(.el-form-item__label .el-button){font-size:12px;min-height:24px;padding:0}.variable-picker strong{font-size:14px;font-weight:500}.variable-picker p{font-size:12px;color:#69756b;line-height:20px;margin:6px 0 12px}.variable-picker button{width:100%;display:flex;flex-direction:column;gap:4px;align-items:flex-start;border:0;border-top:1px solid #edf0ed;background:#fff;padding:10px 0;text-align:left;cursor:pointer;line-height:20px}.variable-picker button span{font-size:13px;color:#256142}.variable-picker button small{font-size:12px;color:#69756b;overflow-wrap:anywhere}.variable-picker button:disabled{opacity:.45;cursor:not-allowed}
</style>
