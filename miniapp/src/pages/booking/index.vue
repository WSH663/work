<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onLoad, onShow,onHide,onUnload } from '@dcloudio/uni-app'
import AppIcon from '../../components/AppIcon.vue'
import { api, ApiError, confirm, errorToast, go, money, requireLogin, type Data, type Place, type Vehicle } from '../../api'
import { devEnabled, pageClass } from '../../store'
import { accountCacheRevision } from '../../account-session'
const type = ref('RIDE'), vehicles = ref<Vehicle[]>([]), selected = ref(0), origin = ref<Place | null>(null), destination = ref<Place | null>(null)
const loading = ref(false), loadError = ref('')
const authError = ref(''), rules = ref<{ advanceMinutes: number; maxAdvanceDays: number } | null>(null), now = ref(Date.now())
type BookingAttempt = { fingerprint: string; key: string; ownerId: string }
const pendingAttempt = ref<BookingAttempt | null>(null), ownerId = ref('')
let alive = true,visible=false, loadVersion = 0, quoteVersion = 0, requestKey = '', requestFingerprint = '', requestOwner = ''
let cacheRevision = accountCacheRevision
const tomorrow = new Date(Date.now() + 86400000)
const localDate = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const date = ref(localDate(tomorrow)), time = ref('09:00'), passengerName = ref(''), passengerPhone = ref(''), hours = ref('4'), itinerary = ref(''), remark = ref(''), estimate = ref<Data | null>(null), quoting = ref(false), submitting = ref(false)
const vehicle = computed(() => vehicles.value[selected.value])
const isCharter = computed(() => type.value === 'CHARTER')
const vehicleNames = computed(() => vehicles.value.map(v => `${v.name}（${v.seats} 座）`))
const earliest = computed(() => new Date(Math.ceil((now.value + (rules.value?.advanceMinutes || 0) * 60000) / 60000) * 60000))
const latest = computed(() => new Date(now.value + (rules.value?.maxAdvanceDays || 0) * 86400000))
const localTime = (d: Date) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
const minimumTime = computed(() => date.value === localDate(earliest.value) ? localTime(earliest.value) : '00:00')
const maximumTime = computed(() => date.value === localDate(latest.value) ? localTime(latest.value) : '23:59')
const pendingRetry = computed(() => !!ownerId.value && pendingAttempt.value?.ownerId === ownerId.value && pendingAttempt.value?.fingerprint === JSON.stringify(payload()))
watch([selected, () => origin.value?.location, () => destination.value?.location], () => { estimate.value = null })
watch(date,()=>{if(time.value<minimumTime.value)time.value=minimumTime.value;if(time.value>maximumTime.value)time.value=maximumTime.value})
watch([type, selected, () => origin.value?.name, () => origin.value?.location, () => destination.value?.name, () => destination.value?.location, date, time, passengerName, passengerPhone, hours, itinerary, remark], () => {
  if (requestKey) { if (uni.getStorageSync('travel_booking_attempt')?.key === requestKey) uni.removeStorageSync('travel_booking_attempt'); if (pendingAttempt.value?.key === requestKey) pendingAttempt.value = null; requestKey = ''; requestFingerprint = ''; requestOwner = '' }
}, { flush: 'sync' })
onLoad(async options => {
  type.value = options?.type === 'CHARTER' ? 'CHARTER' : 'RIDE'; uni.setNavigationBarTitle({ title: isCharter.value ? '预约包车' : '预约用车' })
  if(options?.fromHome==='1') {
    const from = uni.getStorageSync('travel_place_home_origin'), to = uni.getStorageSync('travel_place_home_destination')
    if(from) uni.setStorageSync('travel_place_origin',from); else uni.removeStorageSync('travel_place_origin')
    if(to) uni.setStorageSync('travel_place_destination',to); else uni.removeStorageSync('travel_place_destination')
  } else { uni.removeStorageSync('travel_place_origin'); uni.removeStorageSync('travel_place_destination') }
  origin.value=uni.getStorageSync('travel_place_origin')||null; destination.value=uni.getStorageSync('travel_place_destination')||null
  await loadVehicles(Number(options?.vehicleId)||undefined)
  const token = uni.getStorageSync('travel_token')
  if (token) try { const me = await api('/me'); if (alive && uni.getStorageSync('travel_token') === token) { if (!passengerName.value) passengerName.value = me.nickname || ''; if (!passengerPhone.value && me.phoneVerified === true) passengerPhone.value = me.phone || '' } } catch { syncPrivateDraft() }
})
async function loadVehicles(vehicleId?: number) {
  const version = ++loadVersion; loading.value = true; loadError.value = ''
  try {
    const [cars, config] = await Promise.all([api<Vehicle[]>('/public/vehicles'), api('/public/config')])
    if (!alive || version !== loadVersion) return
    const advanceMinutes = Number(config.advanceMinutes), maxAdvanceDays = Number(config.maxAdvanceDays)
    if (!Number.isFinite(advanceMinutes) || advanceMinutes < 0 || !Number.isFinite(maxAdvanceDays) || maxAdvanceDays < 1) throw new Error('预约规则暂不可用，请稍后重试')
    rules.value = { advanceMinutes, maxAdvanceDays }; now.value = Date.now(); vehicles.value = cars
    if (vehicleId) { const index = cars.findIndex(car => car.id === vehicleId); if (index >= 0) selected.value = index }
    if (vehicle.value) hours.value = String(vehicle.value.minimumHours || 4)
    const chosen = new Date(`${date.value}T${time.value}:00`).getTime()
    if (chosen < earliest.value.getTime() || chosen > latest.value.getTime()) { date.value = localDate(earliest.value); time.value = localTime(earliest.value) }
  } catch (e) { if (alive && version === loadVersion) loadError.value = e instanceof Error ? e.message : '预约信息加载失败' }
  finally { if (alive && version === loadVersion) loading.value = false }
}
onShow(() => { visible=true;syncPrivateDraft();now.value = Date.now(); if (uni.getStorageSync('travel_token')) authError.value = ''; origin.value = uni.getStorageSync('travel_place_origin') || null; destination.value = uni.getStorageSync('travel_place_destination') || null; const me=uni.getStorageSync('travel_user'); const nextOwner=uni.getStorageSync('travel_token')?String(me?.id||''):''; if(ownerId.value!==nextOwner){requestKey='';requestFingerprint='';requestOwner=''} ownerId.value=nextOwner; pendingAttempt.value=readAttempt(); if(me){if(!passengerName.value)passengerName.value=me.nickname||'';if(!passengerPhone.value&&me.phoneVerified===true)passengerPhone.value=me.phone||''} })
onHide(()=>{visible=false})
onUnload(() => { alive = false; loadVersion++; quoteVersion++ })
function selectPlace(role: string) { if (!submitting.value) go('/pages/address/index?role=' + role) }
function syncPrivateDraft() { if(cacheRevision===accountCacheRevision)return;cacheRevision=accountCacheRevision;passengerName.value='';passengerPhone.value='';itinerary.value='';remark.value='';origin.value=null;destination.value=null;estimate.value=null;pendingAttempt.value=null;requestKey='';requestFingerprint='';requestOwner='';quoteVersion++;quoting.value=false }
function showError(error: unknown) { if (error instanceof ApiError && error.status === 401) {syncPrivateDraft();authError.value = '登录已失效，本机出行资料已清除。请重新登录后先核对订单，避免重复预约。'} else errorToast(error) }
async function usePassenger() { if (submitting.value || !requireLogin()) return; const token = uni.getStorageSync('travel_token'); try { const items = await api<Data[]>('/me/passengers'); if (!alive || !visible || uni.getStorageSync('travel_token') !== token) return; if (!items.length) return go('/pages/resources/index?kind=passengers'); uni.showActionSheet({ itemList: items.map(p => `${p.name}  ${p.phone}`), success: r => { if (alive && visible && !submitting.value && uni.getStorageSync('travel_token') === token) { passengerName.value = items[r.tapIndex].name; passengerPhone.value = items[r.tapIndex].phone } } }) } catch (e) { if (alive && visible && (uni.getStorageSync('travel_token') === token || (e instanceof ApiError && e.status === 401 && !uni.getStorageSync('travel_token')))) showError(e) } }
async function quote() {
  if (!origin.value || !destination.value || !vehicle.value) { errorToast(new Error('请先选择起终点和车型')); return null }
  if (quoting.value) return null
  const version = ++quoteVersion, input = { vehicleId: vehicle.value.id, origin: origin.value.location, destination: destination.value.location }; quoting.value = true
  try { const result = await api('/quotes/estimate', 'POST', input); if (alive && version === quoteVersion && vehicle.value?.id === input.vehicleId && origin.value?.location === input.origin && destination.value?.location === input.destination) { estimate.value = result; return result } }
  catch (error) { if (alive && version === quoteVersion) errorToast(error) }
  finally { if (alive && version === quoteVersion) quoting.value = false }
  return null
}
function payload(): Data { return { type: type.value, vehicleId: vehicle.value?.id, originName: origin.value?.name, destinationName: destination.value?.name, origin: origin.value?.location, destination: destination.value?.location, appointmentAt: `${date.value}T${time.value}:00`, passengerName: passengerName.value.trim(), passengerPhone: passengerPhone.value.trim(), ...(isCharter.value ? { hours: Number(hours.value), itinerary: itinerary.value.trim() } : {}), remark: remark.value.trim() } }
function readAttempt(): BookingAttempt | null { const value=uni.getStorageSync('travel_booking_attempt'); return value && typeof value.fingerprint==='string' && typeof value.ownerId==='string' && typeof value.key==='string' && /^[A-Za-z0-9_-]{1,64}$/.test(value.key) ? value : null }
function pendingKeyFor(fingerprint: string): string {
  if (!ownerId.value) return ''
  if (requestKey && requestOwner === ownerId.value && requestFingerprint === fingerprint) return requestKey
  const previous=readAttempt(); pendingAttempt.value=previous
  if (previous?.ownerId !== ownerId.value || previous.fingerprint !== fingerprint) return ''
  requestKey=previous.key;requestFingerprint=fingerprint;requestOwner=ownerId.value;return requestKey
}
function keyFor(fingerprint: string): string {
  const previous=pendingKeyFor(fingerprint);if(previous)return previous
  requestKey = `booking-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 14)}`
  requestFingerprint = fingerprint;requestOwner=ownerId.value;pendingAttempt.value={fingerprint,key:requestKey,ownerId:ownerId.value};uni.setStorageSync('travel_booking_attempt',pendingAttempt.value);return requestKey
}
async function submit() {
  if(submitting.value||quoting.value)return
  if (!requireLogin()) return
  const input = payload(), fingerprint = JSON.stringify(input), token = uni.getStorageSync('travel_token')
  ownerId.value=String(uni.getStorageSync('travel_user')?.id||'')
  const retryKey=pendingKeyFor(fingerprint)
  if(!retryKey){
    if (!rules.value) return errorToast(new Error('请先加载预约规则'))
    now.value = Date.now()
    if (!origin.value || !destination.value || !vehicle.value) return errorToast(new Error('请完整选择起终点和车型'))
    if (!passengerName.value.trim() || !/^1[3-9]\d{9}$/.test(passengerPhone.value)) return errorToast(new Error('请填写乘车人姓名及正确手机号'))
    const appointment = new Date(`${date.value}T${time.value}:00`).getTime()
    if (!Number.isFinite(appointment) || appointment < earliest.value.getTime() || appointment > latest.value.getTime()) return errorToast(new Error(`请至少提前 ${rules.value.advanceMinutes} 分钟预约，最远可预约 ${rules.value.maxAdvanceDays} 天内的行程`))
    if (isCharter.value && (!/^\d+(\.\d{1,2})?$/.test(hours.value) || Number(hours.value) < (vehicle.value.minimumHours || 1) || Number(hours.value) > 240)) return errorToast(new Error(`包车时长请填写 ${vehicle.value.minimumHours || 1} 至 240 小时，最多两位小数`))
    if (isCharter.value && !itinerary.value.trim()) return errorToast(new Error('请填写包车行程和服务需求'))
  }
  submitting.value = true
  try {
    const price = retryKey || isCharter.value ? null : await quote()
    if (!retryKey && !isCharter.value && !price) return
    if (!alive || !visible || token !== uni.getStorageSync('travel_token')) return
    const approved = retryKey ? await confirm('核对上次提交', '这份预约已确认并提交过，但尚未确认结果。将使用原提交标识核对；若上次尚未创建订单，会按平台当前规则重新提交。') : await confirm('核对预约信息', `${input.originName} → ${input.destinationName}\n${date.value} ${time.value} · ${vehicle.value!.name}\n乘车人：${input.passengerName} ${input.passengerPhone}\n${isCharter.value ? '包车费用待人工报价，您确认报价后再支付。' : `预估费用 ¥${money(price?.amount)}，下单将按最新价格计费，请在订单详情核对后支付。`}`)
    if (!approved || !alive || !visible || token !== uni.getStorageSync('travel_token')) return
    if (JSON.stringify(payload()) !== fingerprint) return errorToast(new Error('预约信息已变化，请重新核对后提交'))
    const key = retryKey || keyFor(fingerprint), order = await api('/orders', 'POST', { ...input, requestKey: key })
    if (!alive || !visible || token !== uni.getStorageSync('travel_token')) return
    uni.redirectTo({ url: '/pages/order/index?id=' + order.id, success: () => { if (uni.getStorageSync('travel_booking_attempt')?.key === key) uni.removeStorageSync('travel_booking_attempt'); if(pendingAttempt.value?.key===key)pendingAttempt.value=null;requestKey = ''; requestFingerprint = '';requestOwner='' }, fail: () => errorToast(new Error('订单已创建，请到“订单”查看，勿重复预约')) })
  } catch (e) { if (alive && (token === uni.getStorageSync('travel_token') || (e instanceof ApiError && e.status === 401 && !uni.getStorageSync('travel_token')))) {if(e instanceof ApiError&&e.status<500){if(uni.getStorageSync('travel_booking_attempt')?.key===requestKey)uni.removeStorageSync('travel_booking_attempt');pendingAttempt.value=null;requestKey='';requestFingerprint='';requestOwner=''}showError(e)} }
  finally { if (alive) submitting.value = false }
}
</script>
<template>
<view class="page booking-page" :class="pageClass()">
  <view class="page-heading"><view class="page-heading-main"><text class="page-heading-title">{{isCharter?'预约包车':'预约用车'}}</text><text class="page-heading-note">{{isCharter?'填写行程需求，客服报价后由您确认':'完善乘车信息，核对费用后提交'}}</text></view></view>
  <view v-if="loading" class="panel empty">正在加载车型与预约规则…</view>
  <view v-else-if="loadError" class="panel empty"><text>{{loadError}}</text><button class="app-button secondary" @click="loadVehicles()">重试</button></view>
  <template v-else>
    <view v-if="authError" class="inline-alert"><text>{{authError}}</text><button class="app-button plain" @click="go('/pages/login/index')">重新登录</button></view>
    <view class="panel">
      <view class="section-header"><text class="section-title">行程地点</text><text class="muted">{{isCharter?'包车':'用车'}}</text></view>
      <view class="route-field" @click="selectPlace('origin')"><view class="route-mark"/><view class="route-main"><text class="label">上车地点</text><text class="place-name" :class="{placeholder:!origin}">{{origin?.name||'请选择上车地点'}}</text><text v-if="origin" class="subtitle">{{origin.address}}</text></view><AppIcon name="chevron-right" :size="18" color="muted"/></view>
      <view class="route-field" @click="selectPlace('destination')"><view class="route-mark end"/><view class="route-main"><text class="label">下车地点</text><text class="place-name" :class="{placeholder:!destination}">{{destination?.name||'请选择目的地'}}</text><text v-if="destination" class="subtitle">{{destination.address}}</text></view><AppIcon name="chevron-right" :size="18" color="muted"/></view>
    </view>
    <view class="panel">
      <view class="section-header"><text class="section-title">时间与车型</text></view>
      <view class="picker-row"><text>预约日期</text><picker mode="date" :disabled="submitting" :value="date" :start="localDate(earliest)" :end="localDate(latest)" @change="date=$event.detail.value"><view class="picker-value"><text>{{date}}</text><AppIcon name="chevron-right" :size="16" color="muted"/></view></picker></view>
      <view class="picker-row"><text>预约时间</text><picker mode="time" :disabled="submitting" :value="time" :start="minimumTime" :end="maximumTime" @change="time=$event.detail.value"><view class="picker-value"><text>{{time}}</text><AppIcon name="chevron-right" :size="16" color="muted"/></view></picker></view>
      <text v-if="rules" class="hint">至少提前 {{rules.advanceMinutes}} 分钟预约，可预约 {{rules.maxAdvanceDays}} 天内的行程。</text>
      <view class="field"><text class="label">服务车型</text><picker :disabled="submitting" :range="vehicleNames" :value="selected" @change="selected=Number($event.detail.value)"><view class="row value"><text>{{vehicleNames[selected]||'暂无可预约车型'}}</text><AppIcon name="chevron-right" :size="16" color="muted"/></view></picker><text v-if="vehicle" class="subtitle">{{vehicle.description}}</text></view>
      <view v-if="isCharter" class="field"><text class="label">包车时长（小时）</text><input v-model="hours" :disabled="submitting" type="digit" :placeholder="'至少 '+(vehicle?.minimumHours||1)+' 小时'"/></view>
      <view v-if="isCharter" class="field"><text class="label">行程安排</text><textarea v-model="itinerary" :disabled="submitting" maxlength="1000" placeholder="请说明途经地点、往返安排与服务需求" auto-height/></view>
    </view>
    <view class="panel">
      <view class="section-header"><text class="section-title">乘车人信息</text><button class="app-button plain" :disabled="submitting" @click="usePassenger">常用乘车人</button></view>
      <view class="field"><text class="label">乘车人姓名</text><input v-model="passengerName" :disabled="submitting" maxlength="30" placeholder="请输入实际乘车人姓名"/></view>
      <view class="field"><text class="label">联系电话</text><input v-model="passengerPhone" :disabled="submitting" type="number" maxlength="11" placeholder="请输入 11 位手机号"/></view>
      <view class="field"><text class="label">服务备注（选填）</text><textarea v-model="remark" :disabled="submitting" maxlength="500" placeholder="轮椅类型、陪同人数、上下车协助等" auto-height/></view>
    </view>
    <view class="panel"><view class="panel-body">
      <text v-if="pendingRetry" class="hint pending-submit-note">上次提交尚未确认结果，请先“核对上次提交”，避免重复预约。</text>
      <view v-if="isCharter"><view class="row"><text class="section-title">包车费用</text><text>待人工报价</text></view><text class="subtitle">客服确认报价后，您确认并支付。</text></view>
      <view v-else><view class="row"><view><text class="label">预估费用</text><text class="amount">{{estimate?'¥'+money(estimate.amount):'待估算'}}</text></view><button class="app-button secondary compact" :loading="quoting" :disabled="submitting||quoting||!vehicle" @click="quote">查看费用</button></view><text class="subtitle">{{estimate?'约 '+estimate.distanceKm+' 公里，'+estimate.durationMinutes+' 分钟。下单时由系统重新计价。':'选择起终点和车型后查看费用。'}}</text></view>
    </view></view>
    <text v-if="devEnabled" class="dev-note">开发环境：支付为模拟交易，不产生真实扣费。</text>
    <view class="booking-actions"><view class="booking-total"><text class="muted">{{isCharter?'包车报价':'预估费用'}}</text><text class="submit-price">{{isCharter?'人工确认':estimate?'¥'+money(estimate.amount):'待估算'}}</text></view><button class="app-button primary" :disabled="submitting||quoting||(!pendingRetry&&(!vehicle||!rules))" :loading="submitting" @click="submit">{{submitting?'正在核对…':pendingRetry?'核对上次提交':isCharter?'提交预约':'提交订单'}}</button></view>
    <view class="consent"><text>提交前请核对乘车信息，查看</text><button class="app-button plain consent-link" :disabled="submitting" @click="go('/pages/agreement/index?kind=terms')">服务协议</button></view>
  </template>
</view>
</template>
<style scoped>
.booking-page .consent{display:flex;align-items:center;justify-content:center;flex-wrap:wrap;gap:4rpx}.consent .consent-link{font-size:24rpx;padding:12rpx 4rpx;min-height:88rpx}.senior .consent .consent-link{font-size:30rpx}
.booking-page{padding-bottom:calc(176rpx + env(safe-area-inset-bottom))}.booking-actions{position:fixed;bottom:0;left:0;right:0;background:#fff;border-top:1rpx solid #dee0e3;padding:24rpx 32rpx calc(24rpx + env(safe-area-inset-bottom));display:flex;align-items:center;gap:24rpx;z-index:10}.booking-total{display:flex;flex-direction:column;flex:1}.submit-price{font-size:32rpx;font-weight:600}.booking-actions .primary{min-width:280rpx}.senior .booking-actions .primary{min-width:260rpx;font-size:18px}.senior .submit-price{font-size:36rpx}
.route-field{display:flex;align-items:center;gap:24rpx;min-height:144rpx;padding:24rpx 0;border-bottom:1rpx solid #eff0f1}.route-field:last-child{border-bottom:0}.route-main{flex:1;min-width:0}.route-mark{width:14rpx;height:14rpx;border:4rpx solid #646a73;border-radius:50%;flex-shrink:0}.route-mark.end{background:#646a73;border-radius:2rpx}.place-name{display:block;font-size:30rpx;word-break:break-all}.picker-row{display:flex;align-items:center;justify-content:space-between;min-height:112rpx;border-bottom:1rpx solid #eff0f1;gap:16rpx}.picker-value{display:flex;align-items:center;gap:16rpx;min-height:88rpx}.compact{padding:16rpx 24rpx;min-width:192rpx}.consent{padding:24rpx 0;line-height:1.7;font-size:24rpx;text-align:center;color:#646a73}.text-link{color:#16845b;padding:16rpx 4rpx}.senior .place-name{font-size:36rpx}.senior .consent{font-size:30rpx}
</style>
