<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import AppIcon from '../../components/AppIcon.vue'
import { api, ApiError, callService, consumeTabQuery, confirm, errorToast, go, type Data } from '../../api'
import { clearAccountSession } from '../../account-session'
import { pageClass, preferences, onSeniorChange } from '../../store'
import { createWechatPhonePrompt, isWechatPhonePromptCurrent, isWechatMiniProgram, readWechatPhoneCode, supportsWechatPhoneAuthorization, type WechatPhonePrompt } from '../../wechat-phone'
import { inputNickname, nicknameReviewError, submittedNickname } from '../../nickname'

const me = ref<Data | null>(null), editing = ref(false), nickname = ref(''), busy = ref(false), loading = ref(false), error = ref(''), phoneError = ref('')
const configReady = ref(false), wechatConfigured = ref(false), phoneSupported = ref(supportsWechatPhoneAuthorization()), busyMode = ref<'save' | 'phone' | 'logout' | ''>('')
const nicknameError = ref(''), nicknameRejected = ref(false)
let alive = true, visible = false, requestId = 0, operationVersion = 0, loadedSession = ''
let phonePrompt: WechatPhonePrompt | null = null
const phoneVerified = computed(() => me.value?.phoneVerified === true)
const phoneText = computed(() => me.value?.phone ? `${me.value.phone}${phoneVerified.value ? '（微信已验证）' : '（未验证）'}` : '尚未绑定微信手机号')
const phoneAvailable = computed(() => isWechatMiniProgram && configReady.value && wechatConfigured.value && phoneSupported.value)
const phoneNote = computed(() => !isWechatMiniProgram ? '请在微信小程序内绑定或更换手机号。' : !configReady.value ? '暂未确认微信手机号服务，请重试加载个人信息。' : !wechatConfigured.value ? '微信服务尚未配置，暂时无法绑定手机号。' : !phoneSupported.value ? '当前微信版本不支持手机号授权，请更新微信后重试。' : '请在微信授权窗口选择手机号，绑定结果以微信验证为准。')
function current(token: string) { return alive && token === String(uni.getStorageSync('travel_token') || '') }
function clearView() { me.value = null; editing.value = false; nickname.value = ''; nicknameError.value = ''; nicknameRejected.value = false; error.value = ''; phoneError.value = ''; loadedSession = ''; configReady.value = false; wechatConfigured.value = false; phonePrompt = null }
function failure(e: unknown, token: string) { if (e instanceof ApiError && e.status === 401 && !uni.getStorageSync('travel_token')) clearView(); else if (current(token)) errorToast(e) }
async function load() {
  if (!alive || !visible || busy.value) return
  const token = String(uni.getStorageSync('travel_token') || ''), version = ++requestId
  if (!token) { clearView(); loading.value = false; return }
  if (loadedSession !== token) { clearView(); loadedSession = token }
  loading.value = true; error.value = ''
  try {
    const [userResult, configResult] = await Promise.allSettled([api('/me'), api('/public/config')])
    if (userResult.status === 'rejected') throw userResult.reason
    if (!current(token) || !visible || version !== requestId) return
    configReady.value = configResult.status === 'fulfilled'
    wechatConfigured.value = configResult.status === 'fulfilled' && configResult.value.wechatConfigured === true
    me.value = userResult.value; uni.setStorageSync('travel_user', userResult.value)
    if (!editing.value) nickname.value = userResult.value.nickname || ''
  } catch (e) {
    if (alive && visible && version === requestId) {
      if (e instanceof ApiError && e.status === 401 && !uni.getStorageSync('travel_token')) clearView()
      else if (current(token)) error.value = e instanceof Error ? e.message : '个人信息加载失败'
    }
  } finally { if (alive && version === requestId) loading.value = false }
}
onShow(() => {
  visible = true; phoneSupported.value = supportsWechatPhoneAuthorization(); consumeTabQuery('/pages/profile/index')
  const prompt = phonePrompt
  if (prompt?.code) { phonePrompt = null; if (isWechatPhonePromptCurrent(prompt, operationVersion)) { void completePhoneAuthorization(prompt.code, prompt); return } }
  void load()
})
onHide(() => {
  visible = false; requestId++; loading.value = false
  const prompt = phonePrompt
  if (prompt) setTimeout(() => { if (phonePrompt === prompt && !isWechatPhonePromptCurrent(prompt, operationVersion)) phonePrompt = null }, 0)
})
onUnload(() => { alive = false; requestId++; operationVersion++; phonePrompt = null })
function protectedGo(url: string) { if (!uni.getStorageSync('travel_token')) return go('/pages/login/index'); go(url) }
function openOrders() { protectedGo('/pages/orders/index') }
function edit() { if (busy.value || loading.value) return; if (!me.value) return go('/pages/login/index'); nicknameRejected.value = false; nicknameError.value = ''; nickname.value = me.value.nickname === '微信用户' ? '' : me.value.nickname || ''; editing.value = true }
function focusNickname() { if (!busy.value) { nicknameRejected.value = false; nicknameError.value = '' } }
function changeNickname(event: unknown) {
  const value = inputNickname(event)
  if (typeof value === 'string') nickname.value = value
  nicknameRejected.value = false; nicknameError.value = ''
}
function blurNickname(event: unknown) { const value = inputNickname(event); if (typeof value === 'string' && !nicknameRejected.value) nickname.value = value }
function reviewNickname(event: unknown) {
  if (!editing.value || !visible) return
  const message = nicknameReviewError(event)
  // Review callbacks do not identify the reviewed value. Do not infer that a
  // later success approves the current input or clears an earlier failure.
  if (message) { nicknameRejected.value = true; nicknameError.value = message; nickname.value = '' }
}
function cancelEdit() { if (busy.value) return; editing.value = false; nicknameError.value = ''; nicknameRejected.value = false }
async function save(event: unknown) {
  if (busy.value) return
  const token = String(uni.getStorageSync('travel_token') || '')
  if (!token) { clearView(); return go('/pages/login/index') }
  if (nicknameRejected.value) return
  nicknameError.value = ''
  let selectedNickname: string
  try { selectedNickname = submittedNickname(event) }
  catch (e) { nicknameError.value = e instanceof Error ? e.message : '请重新填写昵称。'; return }
  nickname.value = selectedNickname
  const body = { nickname: selectedNickname }
  const version = ++operationVersion
  busy.value = true; busyMode.value = 'save'; requestId++; loading.value = false
  try {
    const data = await api('/me', 'POST', body)
    if (!current(token) || version !== operationVersion) return
    if (nicknameRejected.value) return
    if (typeof data.nickname !== 'string' || data.nickname !== body.nickname) { nicknameError.value = '服务端尚未确认昵称，请重新加载后核对。'; return }
    me.value = data; nickname.value = data.nickname; uni.setStorageSync('travel_user', data); editing.value = false; error.value = ''; nicknameError.value = ''; if (visible) uni.showToast({ title: '资料已保存', icon: 'success' })
  } catch (e) { if (alive && visible && version === operationVersion) { if (current(token)) nicknameError.value = e instanceof Error ? e.message : '资料保存失败，请重试。'; failure(e, token) } }
  finally { finishOperation(version, token) }
}
function beginPhoneAuthorization() {
  if (busy.value || loading.value || !me.value || !phoneAvailable.value) return
  phoneError.value = ''; phonePrompt = createWechatPhonePrompt(operationVersion)
}
async function bindPhone(event: unknown) {
  const prompt = phonePrompt
  if (!alive || busy.value) return
  if (!prompt || !isWechatPhonePromptCurrent(prompt, operationVersion)) { phonePrompt = null; phoneError.value = '手机号授权已中断，请重新点击授权按钮。'; return }
  if (!prompt.token || uni.getStorageSync('travel_identity_mode') !== 'WECHAT') { phoneError.value = '请先使用微信重新登录，再绑定手机号。'; return }
  let code: string
  try { code = readWechatPhoneCode(event, 'binding') }
  catch (e) { phonePrompt = null; phoneError.value = e instanceof Error ? e.message : '手机号授权未完成，请重试。'; return }
  if (!visible) { prompt.code = code; return }
  phonePrompt = null; await completePhoneAuthorization(code, prompt)
}
async function completePhoneAuthorization(code: string, prompt: WechatPhonePrompt) {
  if (busy.value || !alive || !visible || !isWechatPhonePromptCurrent(prompt, operationVersion)) return
  const version = ++operationVersion, token = prompt.token
  busy.value = true; busyMode.value = 'phone'; requestId++; loading.value = false
  try {
    if (me.value?.phone && !await confirm('更换账号手机号', '继续后，当前账号手机号将替换为本次微信授权选择的号码。已保存的乘车联系人号码不会自动修改。是否继续？')) return
    if (!current(token) || !visible || version !== operationVersion) return
    const data = await api('/me/phone', 'POST', { code })
    if (!current(token) || version !== operationVersion) return
    me.value = data; uni.setStorageSync('travel_user', data); phoneError.value = ''
    if (data.phoneVerified === true) { if (visible) uni.showToast({ title: '手机号已绑定', icon: 'success' }) }
    else phoneError.value = '服务端尚未确认手机号验证，请重新加载后核对。'
  } catch (e) {
    if (alive && visible && version === operationVersion) {
      if (e instanceof ApiError && e.status === 401 && !uni.getStorageSync('travel_token')) clearView()
      else if (current(token)) phoneError.value = e instanceof Error ? e.message : '手机号绑定未完成，请重新授权。'
    }
  } finally { finishOperation(version, token) }
}
function finishOperation(version: number, token: string) {
  if (alive && version === operationVersion) { busy.value = false; busyMode.value = ''; if (visible && !current(token)) void load() }
}
async function logout() {
  if (busy.value) return
  const token = String(uni.getStorageSync('travel_token') || '')
  if (!token) { clearView(); return }
  const version = ++operationVersion
  busy.value = true; busyMode.value = 'logout'; requestId++; loading.value = false
  try {
    if (!await confirm('退出当前账号', '退出后将清除本机账号、选点和待确认提交缓存；订单与常用信息保存在服务器，可重新登录查看。')) return
    if (!current(token) || !visible || version !== operationVersion) return
    await api('/auth/logout', 'POST')
    if (current(token) && version === operationVersion) { clearAccountSession(); clearView(); if (visible) uni.showToast({ title: '已退出登录', icon: 'none' }) }
  } catch (e) { if (alive && visible && version === operationVersion) failure(e, token) }
  finally { finishOperation(version, token) }
}
const accountMenu = [{ icon: 'users', label: '常用乘车人', url: '/pages/resources/index?kind=passengers' }, { icon: 'address', label: '常用地址', url: '/pages/resources/index?kind=addresses' }, { icon: 'wallet', label: '推广与佣金', url: '/pages/promotion/index' }]
</script>
<template>
<view class="page profile-page" :class="pageClass()">
  <view class="page-heading"><view class="page-heading-main"><text class="page-heading-title">我的</text><text class="page-heading-note">管理出行信息，获取平台服务</text></view></view>
  <view v-if="error" class="inline-alert"><text>{{error}}</text><button class="app-button plain" :disabled="loading||busy" @click="load">重试</button></view>
  <view class="user-section">
    <view class="user-avatar"><AppIcon name="user" :size="28" color="muted"/></view>
    <view class="user-main"><text class="user-name">{{loading?'加载中…':me?.nickname||'未登录'}}</text><text class="subtitle">{{me?phoneText:'登录后预约、管理订单'}}</text></view>
    <button class="app-button plain profile-edit" :disabled="busy||loading" @click="edit">{{me?'编辑资料':'登录 / 注册'}}</button>
  </view>
  <form v-if="editing" class="panel profile-form" @submit="save">
    <view class="section-header"><text class="section-title">个人资料</text><button class="app-button icon-button" :disabled="busy" aria-label="关闭资料编辑" @click="cancelEdit"><AppIcon name="close" :size="20" color="muted"/></button></view>
    <view class="field"><text class="label">昵称</text>
      <!-- #ifdef MP-WEIXIN -->
      <input name="nickname" type="nickname" :value="nickname" :disabled="busy" maxlength="60" placeholder="选择微信昵称或自行填写" aria-label="昵称，可选择微信昵称或自行填写" @focus="focusNickname" @input="changeNickname" @blur="blurNickname" @nicknamereview="reviewNickname"/>
      <!-- #endif -->
      <!-- #ifndef MP-WEIXIN -->
      <input name="nickname" type="text" :value="nickname" :disabled="busy" maxlength="60" placeholder="请输入昵称" aria-label="昵称" @input="changeNickname" @blur="changeNickname"/>
      <!-- #endif -->
      <text class="hint">{{ isWechatMiniProgram ? '点按输入框，可选择微信昵称，也可自行填写。' : '昵称用于账号展示，可在此修改。' }}不会更改已保存的乘车人姓名。</text>
      <text v-if="nicknameError" class="phone-error" role="alert">{{nicknameError}}</text>
    </view>
    <view class="form-actions"><button class="app-button secondary" :disabled="busy" @click="cancelEdit">取消</button><button class="app-button primary profile-save" form-type="submit" :disabled="busy" :loading="busyMode==='save'">保存资料</button></view>
  </form>
  <view v-if="me" class="panel phone-panel"><view class="field"><text class="label">账号手机号</text><text class="value">{{phoneText}}</text><text class="hint">{{phoneNote}}</text><text v-if="phoneError" class="phone-error" role="alert">{{phoneError}}</text>
    <!-- #ifdef MP-WEIXIN -->
    <button class="app-button secondary profile-phone" open-type="getPhoneNumber" :disabled="busy||loading||!phoneAvailable" :loading="busyMode==='phone'" @click="beginPhoneAuthorization" @getphonenumber="bindPhone">{{phoneVerified?'微信授权更换手机号':'微信授权绑定手机号'}}</button>
    <!-- #endif -->
    <!-- #ifndef MP-WEIXIN -->
    <button class="app-button secondary profile-phone" disabled>请在微信小程序内授权手机号</button>
    <!-- #endif -->
    <button v-if="!configReady" class="app-button plain" :disabled="busy||loading" @click="load">重新检查微信服务</button>
  </view></view>
  <view class="panel shortcut-panel"><button class="app-button button-row shortcut" @click="openOrders"><AppIcon name="orders" :size="24"/><text>我的订单</text></button><button class="app-button button-row shortcut" @click="protectedGo('/pages/messages/index')"><AppIcon name="message" :size="24"/><text>消息通知</text></button></view>
  <text class="section-caption">出行资料</text><view class="panel"><button v-for="item in accountMenu" :key="item.url" class="app-button button-row menu-row" @click="protectedGo(item.url)"><AppIcon :name="item.icon" :size="20" color="muted"/><text class="menu-label">{{item.label}}</text><AppIcon name="chevron-right" :size="16" color="muted"/></button></view>
  <view class="panel">
    <view class="menu-row"><AppIcon name="accessibility" :size="20" color="muted"/><view class="menu-label"><text>长辈模式</text><text class="menu-sub">更大字号与操作区域</text></view><switch :checked="preferences.senior" color="#16845b" @change="onSeniorChange"/></view>
    <button class="app-button button-row menu-row" @click="callService"><AppIcon name="phone" :size="20" color="muted"/><text class="menu-label">联系客服</text><AppIcon name="chevron-right" :size="16" color="muted"/></button>
    <button class="app-button button-row menu-row" @click="go('/pages/agreement/index?kind=service')"><AppIcon name="help" :size="20" color="muted"/><text class="menu-label">服务说明</text><AppIcon name="chevron-right" :size="16" color="muted"/></button>
    <button class="app-button button-row menu-row" @click="go('/pages/agreement/index?kind=privacy')"><AppIcon name="shield" :size="20" color="muted"/><text class="menu-label">隐私说明</text><AppIcon name="chevron-right" :size="16" color="muted"/></button>
  </view>
  <button v-if="me" class="app-button secondary profile-logout" :disabled="busy" :loading="busyMode==='logout'" @click="logout">退出登录</button>
</view>
</template>
<style scoped>
.profile-page{padding-top:0}.user-section{display:flex;align-items:center;gap:24rpx;padding:32rpx 0;min-height:176rpx}.user-avatar{width:96rpx;height:96rpx;border-radius:8rpx;background:#e9ecef;display:flex;align-items:center;justify-content:center;flex-shrink:0}.user-main{flex:1;min-width:0}.user-name{font-size:34rpx;font-weight:600;display:block;word-break:break-all}.user-section .plain{font-size:26rpx;white-space:nowrap}.shortcut-panel{display:flex;padding:0}.shortcut{flex:1;display:flex;align-items:center;justify-content:center;gap:16rpx;min-height:120rpx;border-right:1rpx solid #eff0f1;font-size:28rpx}.shortcut:last-child{border-right:0}.phone-error{display:block;color:#a12620;font-size:28rpx;line-height:1.7;margin-bottom:20rpx}.phone-panel .field{padding-bottom:32rpx}.profile-phone{width:100%}.senior .user-name{font-size:40rpx}.senior .shortcut,.senior .phone-error{font-size:34rpx}.senior .user-section{flex-wrap:wrap}.senior .user-section .plain{font-size:32rpx}
</style>
