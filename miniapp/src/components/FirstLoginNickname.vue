<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { inputNickname, nicknameReviewError, submittedNickname } from '../nickname'
import { pageClass } from '../store'

const props = defineProps<{ sessionToken: string; userId: string; active: boolean }>()
const emit = defineEmits<{ saved: []; skip: []; invalid: [] }>()
const nickname = ref(''), error = ref(''), rejected = ref(false), pending = ref(false)
let alive = true, operationVersion = 0, invalidated = false

function current() {
  const user = uni.getStorageSync('travel_user')
  return alive && !invalidated && Boolean(props.sessionToken) && Boolean(props.userId)
    && String(uni.getStorageSync('travel_token') || '') === props.sessionToken
    && user && typeof user === 'object' && String(user.id) === props.userId
}
function invalidate() {
  if (!alive || invalidated) return
  invalidated = true; operationVersion++; pending.value = false
  emit('invalid')
}
function requireCurrent() { if (current()) return true; invalidate(); return false }
function focusNickname() {
  if (!props.active || pending.value || !requireCurrent()) return
  rejected.value = false; error.value = ''
}
function changeNickname(event: unknown) {
  if (!props.active || pending.value || !requireCurrent()) return
  const value = inputNickname(event)
  if (typeof value === 'string') nickname.value = value
  rejected.value = false; error.value = ''
}
function blurNickname(event: unknown) {
  if (pending.value || !requireCurrent() || rejected.value) return
  const value = inputNickname(event)
  if (typeof value === 'string') nickname.value = value
}
function reviewNickname(event: unknown) {
  if (!current()) { invalidate(); return }
  const message = nicknameReviewError(event)
  // A callback has no value identifier: a later pass must not clear a failure.
  if (message) { rejected.value = true; error.value = message; nickname.value = '' }
}
function skip() {
  if (!props.active || pending.value || !requireCurrent()) return
  emit('skip')
}
async function save(event: unknown) {
  if (!props.active || pending.value || !requireCurrent() || rejected.value) return
  error.value = ''
  let selected: string
  try { selected = submittedNickname(event) }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '请重新填写昵称。'; return }
  nickname.value = selected
  const token = props.sessionToken, userId = props.userId, version = ++operationVersion
  pending.value = true
  try {
    const user = await api('/me', 'POST', { nickname: selected })
    if (!alive || version !== operationVersion) return
    if (props.sessionToken !== token || props.userId !== userId) { invalidate(); return }
    if (!requireCurrent()) return
    if (rejected.value) return
    if (String(user?.id) !== userId || user?.nickname !== selected) {
      error.value = '服务端尚未确认昵称，请重试保存。'; return
    }
    uni.setStorageSync('travel_user', user)
    emit('saved')
  } catch (failure) {
    if (!alive || version !== operationVersion) return
    if (!current()) { invalidate(); return }
    // The API clears only the matching expired account session on HTTP 401.
    if (failure instanceof ApiError && failure.status === 401) { invalidate(); return }
    error.value = failure instanceof Error ? failure.message : '昵称保存失败，请重试。'
  } finally { if (alive && version === operationVersion) pending.value = false }
}
onMounted(() => { requireCurrent() })
watch(() => [props.sessionToken, props.userId], invalidate)
watch(() => props.active, active => { if (active) requireCurrent() })
onUnmounted(() => { alive = false; operationVersion++ })
</script>

<template>
  <view v-if="active" class="nickname-mask" :class="pageClass()" @touchmove.stop>
    <view class="nickname-dialog" role="dialog" aria-modal="true" aria-label="设置昵称" @tap.stop>
      <text class="nickname-title">设置昵称</text>
      <text class="nickname-description">昵称用于账号展示，可在“我的”中修改。</text>
      <form @submit="save">
        <text class="nickname-label">昵称</text>
        <!-- #ifdef MP-WEIXIN -->
        <input class="nickname-input" name="nickname" type="nickname" :value="nickname" :disabled="pending" :focus="active" maxlength="60" :adjust-position="true" :cursor-spacing="24" placeholder="选择微信昵称或自行填写" aria-label="选择微信昵称或自行填写" @focus="focusNickname" @input="changeNickname" @blur="blurNickname" @nicknamereview="reviewNickname"/>
        <!-- #endif -->
        <!-- #ifndef MP-WEIXIN -->
        <input class="nickname-input" name="nickname" type="text" :value="nickname" :disabled="pending" :focus="active" maxlength="60" :adjust-position="true" :cursor-spacing="24" placeholder="请输入昵称" aria-label="昵称" @focus="focusNickname" @input="changeNickname" @blur="blurNickname"/>
        <!-- #endif -->
        <!-- #ifdef MP-WEIXIN -->
        <text class="nickname-hint">点按输入框，可选择微信昵称，也可自行填写。</text>
        <!-- #endif -->
        <text v-if="error" class="nickname-error" role="alert">{{error}}</text>
        <view class="nickname-actions">
          <button class="app-button secondary nickname-button" :disabled="pending" @click="skip">稍后设置</button>
          <button class="app-button primary nickname-button" form-type="submit" :disabled="pending" :loading="pending">保存并继续</button>
        </view>
      </form>
    </view>
  </view>
</template>

<style scoped>
.nickname-mask{position:fixed;inset:0;z-index:1400;box-sizing:border-box;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,.48);padding:24px 24px calc(24px + env(safe-area-inset-bottom))}
.nickname-dialog{box-sizing:border-box;width:100%;max-width:360px;background:#fff;border-radius:8px;padding:24px;color:#1f2329}
.nickname-title{display:block;font-size:20px;font-weight:600;line-height:1.5}.nickname-description{display:block;margin-top:8px;color:#646a73;font-size:14px;line-height:1.6}.nickname-label{display:block;margin:24px 0 8px;font-size:15px;line-height:1.5}
.nickname-input{box-sizing:border-box;width:100%;min-height:48px;height:48px;border:1px solid #c9cdd4;border-radius:4px;padding:0 12px;font-size:16px;line-height:1.5;background:#fff;color:#1f2329}.nickname-hint{display:block;margin-top:8px;font-size:13px;line-height:1.6;color:#646a73}.nickname-error{display:block;margin-top:12px;font-size:14px;line-height:1.6;color:#a12620;word-break:break-word}
.nickname-actions{display:flex;gap:12px;margin-top:24px}.nickname-button{flex:1;min-width:0;min-height:48px;padding:8px;font-size:15px;line-height:1.5}.nickname-button::after{border:0}
.senior .nickname-title{font-size:23px}.senior .nickname-description,.senior .nickname-label,.senior .nickname-error,.senior .nickname-button{font-size:18px}.senior .nickname-input{font-size:20px;min-height:52px;height:52px}.senior .nickname-hint{font-size:16px}
@media(max-width:360px){.nickname-mask{padding-right:16px;padding-left:16px}.nickname-dialog{padding:20px}.nickname-actions{gap:8px}}
</style>
