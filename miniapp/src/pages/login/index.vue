<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { api, go } from '../../api'
import FirstLoginNickname from '../../components/FirstLoginNickname.vue'
import { saveWechatSession } from '../../account-session'
import { needsFirstLoginNickname } from '../../nickname'
import { pageClass } from '../../store'
import { createWechatPhonePrompt, isWechatPhonePromptCurrent, isWechatMiniProgram, readWechatPhoneCode, requestWechatLoginCode, supportsWechatPhoneAuthorization, type WechatPhonePrompt } from '../../wechat-phone'

const agreed = ref(false), inviteCode = ref(String(uni.getStorageSync('travel_invite') || ''))
const busy = ref(false), loginMode = ref<'phone' | 'basic' | ''>(''), formError = ref('')
const returnError = ref('')
const configLoading = ref(false), configReady = ref(false), configError = ref(''), wechatConfigured = ref(false)
const phoneSupported = ref(supportsWechatPhoneAuthorization())
const showNicknameSetup = ref(false), nicknameActive = ref(false), authenticatedToken = ref(''), nicknameUserId = ref('')
let alive = true, visible = false, configVersion = 0, loginVersion = 0
let pendingLoginReturn = false, navigating = false, successNotice = '登录成功', successIcon: 'success' | 'none' = 'success'
let phonePrompt: WechatPhonePrompt | null = null
const wechatAvailable = computed(() => configReady.value && wechatConfigured.value && isWechatMiniProgram)
const loginNote = computed(() => {
  if (!isWechatMiniProgram) return '请在微信小程序内完成微信登录和手机号授权。'
  if (configLoading.value) return '正在检查微信登录服务…'
  if (configError.value) return '暂时无法确认微信登录服务，请重新检查后再试。'
  if (!wechatConfigured.value) return '微信登录服务尚未配置，请联系平台或稍后重试。'
  if (!phoneSupported.value) return '当前微信版本不支持手机号授权，请更新微信；您也可先微信登录，稍后绑定。'
  return '手机号由您在微信授权窗口选择。暂不授权时，可先微信登录，之后在“我的”中绑定。'
})
async function loadConfig() {
  if (configLoading.value) return
  const version = ++configVersion
  configLoading.value = true; configError.value = ''; configReady.value = false; wechatConfigured.value = false
  try {
    const config = await api('/public/config')
    if (!alive || version !== configVersion) return
    wechatConfigured.value = config.wechatConfigured === true; configReady.value = true
  } catch (error) {
    if (alive && version === configVersion) configError.value = error instanceof Error ? error.message : '暂时连接不到登录服务'
  } finally { if (alive && version === configVersion) configLoading.value = false }
}
onLoad(options => {
  const invite = String(options?.inviteCode || options?.invite || '').trim()
  if (invite) { inviteCode.value = invite; uni.setStorageSync('travel_invite', invite) }
  void loadConfig()
})
onShow(() => {
  visible = true; nicknameActive.value = true; phoneSupported.value = supportsWechatPhoneAuthorization()
  if (showNicknameSetup.value || pendingLoginReturn) {
    if (!authenticatedSessionCurrent()) invalidateNicknameSession()
    else if (pendingLoginReturn) { continueAfterLogin(); return }
  }
  const prompt = phonePrompt
  if (prompt?.code) { phonePrompt = null; if (isWechatPhonePromptCurrent(prompt, loginVersion)) void login(prompt.code) }
})
onHide(() => {
  visible = false; nicknameActive.value = false
  const prompt = phonePrompt
  // Native WeChat phone verification may temporarily hide this page. A real
  // navigation changes the current page instance and invalidates the prompt.
  if (prompt) setTimeout(() => { if (phonePrompt === prompt && !isWechatPhonePromptCurrent(prompt, loginVersion)) phonePrompt = null }, 0)
  // Keep an authorized request on this page alive during backgrounding. Its
  // result still checks the original session; UI continuation waits for onShow.
})
onUnload(() => { alive = false; nicknameActive.value = false; pendingLoginReturn = false; phonePrompt = null; loginVersion++; configVersion++ })
function authenticatedSessionCurrent() {
  return alive && !!authenticatedToken.value && authenticatedToken.value === uni.getStorageSync('travel_token') && nicknameUserId.value === String(uni.getStorageSync('travel_user')?.id || '')
}
function invalidateNicknameSession() {
  showNicknameSetup.value = false; pendingLoginReturn = false; navigating = false; returnError.value = ''; authenticatedToken.value = ''; nicknameUserId.value = ''
  formError.value = '登录状态已变化，请重新登录后继续。'
}
function continueAfterLogin() {
  if (!alive || navigating) return
  if (!authenticatedSessionCurrent()) { invalidateNicknameSession(); return }
  if (!visible) { pendingLoginReturn = true; return }
  pendingLoginReturn = false; navigating = true; returnError.value = ''; showNicknameSetup.value = false
  uni.showToast({ title: successNotice, icon: successIcon })
  const openProfile = () => {
    if (!authenticatedSessionCurrent()) { invalidateNicknameSession(); return }
    uni.switchTab({ url: '/pages/profile/index', fail: () => {
      if (!alive) return
      if (!authenticatedSessionCurrent()) { invalidateNicknameSession(); return }
      navigating = false; returnError.value = '登录已完成，暂时无法返回页面，请重试。'
    } })
  }
  const pages = getCurrentPages()
  if (pages.length > 1) uni.navigateBack({ fail: openProfile })
  else openProfile()
}
function nicknameSaved() {
  successNotice = '昵称已保存'; successIcon = 'success'; showNicknameSetup.value = false
  continueAfterLogin()
}
function nicknameSkipped() { showNicknameSetup.value = false; continueAfterLogin() }
function clearInvite() { if (busy.value) return; inviteCode.value = ''; uni.removeStorageSync('travel_invite'); formError.value = '' }
function beginPhoneAuthorization() {
  if (busy.value || !agreed.value || !wechatAvailable.value || !phoneSupported.value) return
  formError.value = ''
  phonePrompt = createWechatPhonePrompt(loginVersion)
}
async function authorizePhone(event: unknown) {
  const prompt = phonePrompt
  if (!alive || busy.value) return
  if (!prompt || !isWechatPhonePromptCurrent(prompt, loginVersion)) {
    phonePrompt = null
    formError.value = '手机号授权已中断，请重新点击授权按钮。'; return
  }
  if (!agreed.value) { formError.value = '请先阅读并勾选同意服务协议与隐私说明。'; return }
  try {
    const phoneCode = readWechatPhoneCode(event)
    if (!visible) { prompt.code = phoneCode; return }
    phonePrompt = null; await login(phoneCode)
  } catch (error) { phonePrompt = null; formError.value = error instanceof Error ? error.message : '手机号授权未完成，请重新授权。' }
}
async function login(phoneCode?: string) {
  if (!alive || !visible || busy.value || showNicknameSetup.value || navigating) return
  if (authenticatedSessionCurrent()) { continueAfterLogin(); return }
  formError.value = ''; phonePrompt = null
  if (!agreed.value) { formError.value = '请先阅读并勾选同意服务协议与隐私说明。'; return }
  if (!wechatAvailable.value) { formError.value = loginNote.value; return }
  const version = ++loginVersion, previousSession = String(uni.getStorageSync('travel_token') || '')
  const current = () => alive && version === loginVersion && previousSession === String(uni.getStorageSync('travel_token') || '')
  busy.value = true; loginMode.value = phoneCode ? 'phone' : 'basic'
  try {
    // The login credential and phone authorization credential have separate purposes.
    const code = await requestWechatLoginCode()
    if (!current()) return
    const result = await api('/auth/wechat/login', 'POST', { code, ...(phoneCode ? { phoneCode } : {}), inviteCode: inviteCode.value.trim() || undefined, agreementAccepted: true })
    if (!current()) return
    if (typeof result?.token !== 'string' || !result.token || !result?.user?.id) throw new Error('登录信息不完整，请稍后重试。')
    saveWechatSession(result.token, result.user)
    authenticatedToken.value = result.token; nicknameUserId.value = String(result.user.id)
    successNotice = phoneCode ? result.user.phoneVerified === true ? '登录成功，手机号已绑定' : '已登录，请在“我的”核对手机号' : '登录成功'
    successIcon = phoneCode && result.user.phoneVerified !== true ? 'none' : 'success'
    const promptKey = `travel_nickname_prompted_${result.user.id}`
    if (needsFirstLoginNickname(result.newUser, result.user.nickname, uni.getStorageSync(promptKey) === true)) {
      showNicknameSetup.value = true
      // A full device cache must not interrupt an already successful login.
      try { uni.setStorageSync(promptKey, true) } catch { /* The server flag remains authoritative. */ }
    } else continueAfterLogin()
  } catch (error) {
    if (current()) {
      const message = error instanceof Error ? error.message : '登录未完成，请稍后重试。'
      formError.value = inviteCode.value.trim() && /推广码|邀请码/.test(message) ? `${message} 请修改或清除邀请码后重新登录。` : message
    }
  } finally { if (alive && version === loginVersion) { busy.value = false; loginMode.value = '' } }
}
</script>
<template>
<view class="page login-page" :class="pageClass()">
  <view class="login-heading"><text class="title">登录预约出行</text><text class="muted">登录后可预约车辆、查看订单。</text></view>
  <view class="panel invite-panel"><view class="field"><text class="label">邀请码（选填）</text><input v-model="inviteCode" :disabled="busy" maxlength="32" placeholder="请输入邀请人的邀请码" aria-label="邀请码，选填"/><button v-if="inviteCode" class="app-button plain clear-invite" :disabled="busy" @click="clearInvite">清除邀请码</button></view></view>
  <view class="agreement"><checkbox-group @change="agreed=$event.detail.value.length>0"><label class="agreement-check"><checkbox value="agreed" :checked="agreed" color="#16845b" :disabled="busy"/><text>我已阅读并同意</text></label></checkbox-group><button class="app-button agreement-link" :disabled="busy" @click="go('/pages/agreement/index?kind=terms')">服务协议</button><text>与</text><button class="app-button agreement-link" :disabled="busy" @click="go('/pages/agreement/index?kind=privacy')">隐私说明</button></view>
  <view v-if="formError" class="login-error" role="alert"><text>{{ formError }}</text></view>
  <view v-if="returnError" class="login-error" role="alert"><text>{{ returnError }}</text><button class="app-button plain" @click="continueAfterLogin">继续进入我的</button></view>
  <!-- #ifdef MP-WEIXIN -->
  <button class="app-button primary wechat-phone-login" open-type="getPhoneNumber" :disabled="busy || showNicknameSetup || !!returnError || configLoading || !agreed || !wechatAvailable || !phoneSupported" :loading="loginMode === 'phone'" @click="beginPhoneAuthorization" @getphonenumber="authorizePhone">手机号授权登录</button>
  <!-- #endif -->
  <!-- #ifndef MP-WEIXIN -->
  <button class="app-button primary wechat-phone-login" disabled>请在微信小程序内授权手机号</button>
  <!-- #endif -->
  <button class="app-button secondary wechat-basic-login" :disabled="busy || showNicknameSetup || !!returnError || configLoading || !agreed || !wechatAvailable" :loading="loginMode === 'basic'" @click="login()">微信登录，稍后绑定</button>
  <text class="login-status muted">{{ loginNote }}</text>
  <text class="login-status muted">首次注册后可选择微信昵称，也可稍后在“我的—编辑资料”中设置。</text>
  <view v-if="configError" class="config-error"><text>{{ configError }}</text><button class="app-button plain" :disabled="configLoading" @click="loadConfig">重新检查登录服务</button></view>
  <FirstLoginNickname v-if="showNicknameSetup" :session-token="authenticatedToken" :user-id="nicknameUserId" :active="nicknameActive" @saved="nicknameSaved" @skip="nicknameSkipped" @invalid="invalidateNicknameSession"/>
</view>
</template>
<style scoped>
.login-page{padding-top:48rpx}.login-heading{padding:16rpx 0 40rpx}.login-heading .title{font-size:40rpx;margin-bottom:16rpx}.invite-panel{margin-bottom:16rpx}.clear-invite{margin-top:8rpx}.agreement{display:flex;align-items:center;flex-wrap:wrap;gap:4rpx;font-size:26rpx;color:#41464d;line-height:1.7;padding-bottom:24rpx}.agreement-check{display:flex;align-items:center;min-height:88rpx}.agreement-link{color:#126e4a;min-height:88rpx;display:flex;align-items:center;background:transparent;padding:0 4rpx;margin:0;border-radius:0;font-size:26rpx;font-weight:400}.login-error{padding:20rpx 24rpx;margin-bottom:24rpx;border:1rpx solid #e4b6b2;background:#fff4f3;color:#a12620;border-radius:8rpx;font-size:28rpx;line-height:1.7}.login-status{display:block;margin-top:24rpx;font-size:26rpx;line-height:1.7}.wechat-basic-login{margin-top:20rpx}.wechat-phone-login[disabled]{background:#e2e5e7;color:#646a73;opacity:1}.config-error{margin-top:16rpx;color:#a12620;font-size:26rpx;line-height:1.7}.config-error button{text-align:left}.senior .agreement,.senior .agreement-link,.senior .login-status,.senior .config-error{font-size:32rpx;color:#41464d}.senior .agreement-link{color:#075a35}.senior .login-error{font-size:34rpx}.senior .login-heading .title{font-size:46rpx}.senior .agreement-check,.senior .agreement-link{min-height:96rpx}
</style>
