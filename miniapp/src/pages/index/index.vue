<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { onHide, onLoad, onShow, onShareAppMessage } from '@dcloudio/uni-app'
import AppIcon from '../../components/AppIcon.vue'
import AdPopup from '../../components/AdPopup.vue'
import HomeAds from '../../components/HomeAds.vue'
import LocationMap from '../../components/LocationMap.vue'
import { api, consumeTabQuery, go, money, safeLink, type Data, type Place, type Vehicle } from '../../api'
import { getCurrentPlace } from '../../map'
import { parseCoordinate } from '../../map-geometry'
import { devEnabled, pageClass, preferences } from '../../store'
import { claimHomeAd, currentAppVisit } from '../../app-visit'

const home = ref<Data>({ platformName: '预约出行', settings: {}, banners: [], ads: [], vehicles: [] })
const loading = ref(true), error = ref(''), showAd = ref(false), countdown = ref(0), type = ref('RIDE')
const origin = ref<Place | null>(null), destination = ref<Place | null>(null), activeAd = ref<Data>({})
const mapCenter = ref<string | null>(null), userLocation = ref<string | null>(null), locating = ref(false), locationError = ref('')
const pageVisible = ref(false)
let timer: ReturnType<typeof setInterval> | undefined
let locationVersion = 0
let entered = false, automaticOrigin: string | null = null
let alive=true,visible=false,loadVersion=0
let homeVisit=0,requestVisit=0,loadedVisit=0

function stopCountdown() { if (timer) clearInterval(timer); timer = undefined }
function startCountdown() {
  stopCountdown()
  if (!showAd.value || countdown.value <= 0) return
  timer = setInterval(() => { countdown.value = Math.max(0, countdown.value - 1); if (!countdown.value) stopCountdown() }, 1000)
}
function presentAd() {
  if (!visible || showAd.value || loadedVisit !== homeVisit || homeVisit !== currentAppVisit()) return
  const next = home.value.ads?.[0]
  if (!next || !claimHomeAd(homeVisit)) return
  const seconds = Number(next.skipSeconds ?? 3)
  activeAd.value = next
  countdown.value = Number.isFinite(seconds) ? Math.max(0, Math.min(Math.ceil(seconds), 15)) : 3
  showAd.value = true
  startCountdown()
}
async function load() {
  const visit=homeVisit
  if(loading.value&&loadVersion>0&&requestVisit===visit)return
  const version=++loadVersion
  requestVisit=visit
  loading.value = true; error.value = ''
  try {
    const result=await api('/public/home');if(!alive||version!==loadVersion||visit!==currentAppVisit())return;home.value=result
    loadedVisit=visit
    if (home.value.servicePhone) uni.setStorageSync('travel_service_phone', home.value.servicePhone)
    uni.setStorageSync('travel_home', home.value)
    presentAd()
  } catch (e) { if(alive&&version===loadVersion&&visit===currentAppVisit())error.value = e instanceof Error ? e.message : '服务信息加载失败' } finally { if(alive&&version===loadVersion)loading.value = false }
}
function closeAd() {
  showAd.value = false; stopCountdown()
}
function previewAd(item: Data) { stopCountdown(); activeAd.value = item; countdown.value = 0; showAd.value = true }
function adClick() {
  if (!activeAd.value.linkUrl) return
  const link = activeAd.value.linkUrl
  showAd.value = false; stopCountdown(); safeLink(link)
}
function saveInvite(value: unknown) {
  const invite = String(value || '').trim()
  if (invite) uni.setStorageSync('travel_invite', invite)
}
function book(vehicleId?: number) { go('/pages/booking/index?type=' + type.value + '&fromHome=1' + (vehicleId ? '&vehicleId=' + vehicleId : '')) }
function choose(role: string, center?: string) { locationVersion++; locating.value = false; const view = center || mapCenter.value; go('/pages/address/index?role=home_' + role + (view ? '&center=' + encodeURIComponent(view) : '') + (center ? '&picked=1' : '')) }
function moveMap(location: string) { locationVersion++; locating.value = false; mapCenter.value = location }
async function locate(automatic = false) {
  if (locating.value) return
  const version = ++locationVersion
  locating.value = true; locationError.value = ''
  try {
    const place = await getCurrentPlace({ onLocated: location => {
      if (version !== locationVersion) return
      userLocation.value = location; mapCenter.value = location
    } })
    if (version !== locationVersion) return
    userLocation.value = place.location; mapCenter.value = place.location
    if (!automatic || !origin.value || origin.value.location === automaticOrigin) {
      origin.value = place; automaticOrigin = place.location
      uni.setStorageSync('travel_place_home_origin', place)
    }
  } catch (e) { if(version === locationVersion)locationError.value = e instanceof Error ? e.message : '定位未完成，请搜索上车地点' } finally { if(version === locationVersion)locating.value = false }
}
onLoad(options => { saveInvite(options?.inviteCode || options?.scene) })
onShareAppMessage(() => ({ title: '预约出行，邀请您一起安心出发', path: '/pages/index/index', imageUrl: '/static/brand-logo.png' }))
onShow(() => {
  visible=true;pageVisible.value=true
  const visit=currentAppVisit()
  if(homeVisit!==visit){
    homeVisit=visit;loadedVisit=0
    showAd.value=false;activeAd.value={};stopCountdown()
    void load()
  }else presentAd()
  const query = consumeTabQuery('/pages/index/index')
  saveInvite(query.inviteCode || query.scene)
  if (['RIDE', 'CHARTER'].includes(query.type)) type.value = query.type
  const previousOrigin = origin.value?.location
  const savedOrigin = uni.getStorageSync('travel_place_home_origin'), savedDestination = uni.getStorageSync('travel_place_home_destination')
  origin.value = savedOrigin && parseCoordinate(savedOrigin.location) ? savedOrigin : null
  destination.value = savedDestination && parseCoordinate(savedDestination.location) ? savedDestination : null
  const confirmedOnReturn = entered && origin.value && origin.value.location !== previousOrigin
  if (confirmedOnReturn) mapCenter.value = origin.value!.location
  else void locate(true)
  entered = true
  startCountdown()
})
function leavePage(){visible=false;pageVisible.value=false;stopCountdown();locationVersion++;locating.value=false}
onHide(leavePage)
onUnmounted(()=>{alive=false;loadVersion++;leavePage()})
</script>
<template>
  <page-meta :page-style="showAd ? 'overflow:hidden' : ''"/>
  <view class="page home-page" :class="pageClass()">
    <view class="home-map">
      <LocationMap :center="mapCenter" :origin="origin" :destination="destination" :user-location="userLocation" :show-user-location="!origin" :height="preferences.senior ? 236 : 208" :locating="locating" :show-center-pin="false" @update:center="moveMap" @pick="choose('origin',$event)"/>
    </view>
    <view class="map-toolbar">
      <button class="app-button locate-button" :disabled="locating" :loading="locating" @click="locate(false)"><AppIcon v-if="!locating" name="location" :size="18" color="brand"/><text>{{locating?'定位中':'使用当前位置'}}</text></button>
    </view>
    <view v-if="locationError" class="location-error"><text>{{locationError}}</text><button class="app-button plain" @click="choose('origin')">搜索地点</button></view>
    <view class="reservation-panel">
      <view class="tabs"><button class="app-button button-row tab" :class="{active:type==='RIDE'}" @click="type='RIDE'">预约用车</button><button class="app-button button-row tab" :class="{active:type==='CHARTER'}" @click="type='CHARTER'">预约包车</button></view>
      <view class="reservation-body">
        <button class="app-button button-row place-row" @click="choose('origin')"><view class="route-mark start"/><view class="place-main"><text class="label">上车地点</text><text class="place-name" :class="{placeholder:!origin}">{{origin?.name || '您从哪里出发'}}</text></view><AppIcon name="chevron-right" :size="18" color="muted"/></button>
        <button class="app-button button-row place-row" @click="choose('destination')"><view class="route-mark end"/><view class="place-main"><text class="label">下车地点</text><text class="place-name" :class="{placeholder:!destination}">{{destination?.name || '您要去哪里'}}</text></view><AppIcon name="chevron-right" :size="18" color="muted"/></button>
        <text class="booking-note">{{type==='RIDE' ? '下一步选择时间、车型并确认费用' : '下一步填写时长与行程，由客服确认报价'}}</text>
        <button class="app-button primary" @click="book()">{{type==='RIDE' ? '预约用车' : '预约包车'}}</button>
      </view>
    </view>
    <view v-if="loading" class="service-loading">服务信息加载中…</view>
    <view v-else-if="error" class="service-error"><text>{{error}}</text><button class="app-button plain" @click="load">重新加载</button></view>
    <template v-else>
      <button class="app-button button-row notice-row" @click="go('/pages/agreement/index?kind=notice')"><AppIcon name="info" :size="18" color="muted"/><text class="notice-text">{{home.notice || '查看平台公告与服务范围'}}</text><AppIcon name="chevron-right" :size="16" color="muted"/></button>
      <HomeAds v-if="home.banners?.length" :items="home.banners" :active="pageVisible && !showAd" @preview="previewAd" @open="safeLink($event.linkUrl)"/>
      <view class="home-services">
        <view class="section-header"><text class="section-title">服务车型</text><button class="app-button plain" @click="go('/pages/agreement/index?kind=service')">服务说明</button></view>
        <view v-if="!home.vehicles?.length" class="empty"><text>暂无可预约车型</text><button class="app-button secondary" @click="load">重新加载</button></view>
        <button v-for="vehicle in (home.vehicles as Vehicle[])" :key="vehicle.id" class="app-button button-row vehicle-row" @click="book(vehicle.id)">
          <AppIcon name="car" :size="28" color="muted"/><view class="vehicle-main"><text class="vehicle-title">{{vehicle.name}}</text><text class="subtitle">{{vehicle.seats}} 座 · {{vehicle.code}} 型</text></view><view class="vehicle-price"><text>¥{{money(vehicle.baseFare)}}</text><text class="muted">起</text></view><AppIcon name="chevron-right" :size="16" color="muted"/>
        </button>
      </view>
    </template>
    <text v-if="devEnabled" class="dev-label">开发环境 · 微信支付尚未接入</text>
    <AdPopup v-if="showAd" :ad="activeAd" :countdown="countdown" :active="pageVisible" @close="closeAd" @open="adClick"/>
  </view>
</template>
<style scoped>
.home-page .reservation-panel{border-radius:0 0 16px 16px}.home-page .tabs .tab{text-align:center}.home-page .notice-row{min-height:48px;padding-top:8px;padding-bottom:8px;margin-bottom:12px}.home-page .vehicle-row{font-size:15px}.home-page .place-row{text-align:left}.home-page .home-services{border-radius:14px;margin-bottom:12px}.home-page .section-header{min-height:64px}
.home-page{padding:0 16px calc(24px + env(safe-area-inset-bottom));max-width:750px;margin:0 auto}.home-map{margin:0 -16px}.map-toolbar{display:flex;align-items:center;justify-content:flex-end;gap:8px;margin:0 -16px;padding:0 16px;background:#fff;border-bottom:1px solid #eff0f1;min-height:44px}.locate-button{display:flex;align-items:center;gap:4px;font-size:13px;color:#16845b;background:transparent;padding:8px 0;flex-shrink:0}.location-error{display:flex;align-items:center;gap:8px;padding:8px 12px;background:#fff8eb;font-size:13px;color:#735c23;margin:12px 0 0}.location-error>text{flex:1;min-width:0}.location-error .plain{font-size:13px;flex-shrink:0}
.reservation-panel{background:#fff;margin:0 -16px 12px;overflow:hidden}.reservation-body{padding:0 16px 16px}.tabs{border-bottom:1px solid #eff0f1}.tab{min-height:48px;padding:12px 8px}.place-row{display:flex;align-items:center;gap:12px;min-height:64px;border-bottom:1px solid #eff0f1;padding:8px 0}.place-main{flex:1;min-width:0}.place-row .label{font-size:12px;margin-bottom:2px}.place-name{display:block;font-size:16px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.route-mark{width:8px;height:8px;border:2px solid #16845b;border-radius:50%;flex-shrink:0}.route-mark.end{border-color:#646a73;border-radius:2px;background:#646a73}.booking-note{display:block;font-size:13px;color:#646a73;padding:12px 0}.service-loading,.service-error{padding:16px 0;color:#646a73;font-size:13px}.service-error{display:flex;align-items:center;gap:12px}.service-error>text{flex:1}
.notice-row{display:flex;align-items:center;gap:6px;padding:0 0 12px;font-size:13px;color:#646a73}.notice-text{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.home-services{background:#fff;padding:0 16px;margin:0 -16px}.vehicle-row{display:flex;align-items:center;gap:12px;padding:16px 0;border-bottom:1px solid #eff0f1}.vehicle-row:last-child{border-bottom:0}.vehicle-main{flex:1;min-width:0}.vehicle-title{font-size:15px;font-weight:500}.vehicle-price{display:flex;flex-direction:column;text-align:right;gap:2px;font-size:15px;white-space:nowrap}.dev-label{display:block;text-align:center;font-size:12px;color:#646a73;padding:4px 0}
.senior .place-name{font-size:19px}.senior .place-row{min-height:76px}.senior .place-row .label,.senior .locate-button,.senior .booking-note,.senior .notice-row,.senior .dev-label{font-size:16px}.senior .vehicle-title{font-size:18px}.senior .map-toolbar{flex-wrap:wrap;padding-top:8px}.senior .location-error{font-size:16px}.senior .location-error .plain{font-size:16px}</style>
