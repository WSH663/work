<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad,onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { api,ApiError,errorToast,go,type Data,type Place } from '../../api'
import { pageClass } from '../../store'
import { accountCacheRevision } from '../../account-session'
import AppIcon from '../../components/AppIcon.vue'
import LocationMap from '../../components/LocationMap.vue'
import { addressTips, getCurrentPlace, nearbyPlaces, reverseLocation, type NearbyPlace } from '../../map'
import { formatCoordinate, parseCoordinate } from '../../map-geometry'

const keyword = ref(''), results = ref<Data[]>([]), recent = ref<Place[]>([]), saved = ref<Data[]>([])
const loading = ref(false), searched = ref(false), selectedRole = ref('origin'), searchError = ref('')
const center = ref<string | null>(null), selected = ref<Place | null>(null), current = ref<Place | null>(null)
const nearby = ref<NearbyPlace[]>([]), nearbyLoading = ref(false), nearbyError = ref(''), resolving = ref(false), selectionError = ref(''), locating = ref(false), locationError = ref('')
const locatingSelection = ref(false)
const savedLoading=ref(false),savedError=ref(''),savedAuthRequired=ref(false),confirming=ref(false)
const activeTab = ref('nearby'), tabs = [{ key: 'nearby', label: '附近' }, { key: 'saved', label: '常用' }, { key: 'recent', label: '最近' }]
const roleLabel = computed(() => selectedRole.value.includes('destination') ? '下车地点' : selectedRole.value === 'saved' ? '常用地点' : '上车地点')
const confirmDisabled = computed(() => confirming.value || !selected.value || resolving.value || (locating.value && locatingSelection.value))
let debounce: ReturnType<typeof setTimeout> | undefined
let alive = true,visible=false, searchVersion = 0, selectionVersion = 0, nearbyVersion = 0, locationVersion = 0,savedVersion=0,savedSession=''
let cacheRevision=accountCacheRevision
const message = (error: unknown, fallback: string) => error instanceof Error ? error.message : fallback

onLoad(async options => {
  selectedRole.value = ['origin','destination','home_origin','home_destination','saved'].includes(options?.role||'') ? options!.role! : 'origin'
  const previous = uni.getStorageSync('travel_places')
  recent.value = Array.isArray(previous) ? previous.filter(place => parseCoordinate(place.location)) : []
  let preview = options?.center
  if (preview && !parseCoordinate(preview)) { try { preview = decodeURIComponent(preview) } catch { preview = '' } }
  const existing = uni.getStorageSync('travel_place_' + selectedRole.value)
  if (existing && parseCoordinate(existing.location)) selected.value = existing
  const picked = options?.picked === '1' && !!parseCoordinate(preview)
  // A previously confirmed address is the active target. Keep the map on it
  // while refreshing the device GPS in the background; otherwise the target
  // marker and a new center pin would appear at different places.
  if (!picked && selected.value) {
    center.value = selected.value.location
    void loadNearby(center.value)
  }
  // A point explicitly picked on the home map keeps its preview. Other entry
  // paths start without a map center until real GPS coordinates arrive.
  if (picked && preview) void resolveMap(preview, false)
  void locate(true, picked || !!selected.value)
})
onShow(()=>{visible=true;const previous=uni.getStorageSync('travel_places');recent.value=Array.isArray(previous)?previous.filter(place=>parseCoordinate(place.location)):[];if(cacheRevision!==accountCacheRevision){cacheRevision=accountCacheRevision;selected.value=null;clearSearch();selectionVersion++;resolving.value=false;selectionError.value=''}void loadSaved()});onHide(()=>{visible=false;savedVersion++;savedLoading.value=false})
onUnload(() => { alive = false; if (debounce) clearTimeout(debounce); searchVersion++; selectionVersion++; nearbyVersion++; locationVersion++;savedVersion++ })
async function loadSaved(){
  const token=String(uni.getStorageSync('travel_token')||''),version=++savedVersion
  if(!token){saved.value=[];savedAuthRequired.value=true;savedError.value='';savedLoading.value=false;return}
  if(savedSession!==token){saved.value=[];savedSession=token}
  savedLoading.value=true;savedError.value='';savedAuthRequired.value=false
  const current=()=>alive&&visible&&version===savedVersion&&token===String(uni.getStorageSync('travel_token')||'')
  try{const data=await api<Data[]>('/me/addresses');if(current())saved.value=data.filter(item=>parseCoordinate(item.location))}
  catch(e){if(alive&&visible&&version===savedVersion){if(e instanceof ApiError&&e.status===401&&!uni.getStorageSync('travel_token')){saved.value=[];savedAuthRequired.value=true}else if(current())savedError.value=message(e,'常用地点加载失败')}}
  finally{if(alive&&version===savedVersion)savedLoading.value=false}
}
function clearSearch() { if (debounce) clearTimeout(debounce); keyword.value = ''; results.value = []; searchError.value = ''; searched.value = false; searchVersion++; loading.value = false }
function onInput() {
  // Once the user starts searching, a late automatic location result must not
  // move the map or replace their selection while they type.
  locationVersion++; locating.value = false; locatingSelection.value = false; locationError.value = ''
  searchVersion++; results.value = []; searched.value = false; loading.value = !!keyword.value.trim()
  if (debounce) clearTimeout(debounce)
  debounce = setTimeout(search, 400)
}
async function search() {
  if (debounce) clearTimeout(debounce)
  const version = ++searchVersion, query = keyword.value.trim()
  if (!query) { results.value = []; searched.value = false; searchError.value = ''; loading.value = false; return }
  loading.value = true; searched.value = true; searchError.value = ''
  try { const data = await addressTips(query, ''); if (alive && version === searchVersion) results.value = data.filter(item => parseCoordinate(item.location)) }
  catch (error) { if (alive && version === searchVersion) { results.value = []; searchError.value = message(error, '地址搜索失败，请重试') } }
  finally { if (alive && version === searchVersion) loading.value = false }
}
async function loadNearby(location: string | null) {
  if (!location || !parseCoordinate(location)) return
  const version = ++nearbyVersion
  nearbyLoading.value = true; nearbyError.value = ''
  try { const data = await nearbyPlaces(location); if (alive && version === nearbyVersion) nearby.value = data.filter(item => parseCoordinate(item.location)) }
  catch (error) { if (alive && version === nearbyVersion) { nearby.value = []; nearbyError.value = message(error, '附近地点暂不可用') } }
  finally { if (alive && version === nearbyVersion) nearbyLoading.value = false }
}
async function resolveMap(location: string | null, manual = true) {
  const parsed = parseCoordinate(location)
  if (!parsed) return
  const normalized = formatCoordinate(parsed), version = ++selectionVersion
  if (manual) { locationVersion++; locating.value = false; locatingSelection.value = false; locationError.value = '' }
  center.value = normalized; selected.value = null; resolving.value = true; selectionError.value = ''; activeTab.value = 'nearby'
  void loadNearby(normalized)
  try { const place = await reverseLocation(normalized); if (alive && version === selectionVersion) selected.value = { ...place, location: normalized } }
  catch (error) { if (alive && version === selectionVersion) selectionError.value = message(error, '无法识别此处地址，请重试或选择附近地点') }
  finally { if (alive && version === selectionVersion) resolving.value = false }
}
function choose(item: Data) {
  if (!parseCoordinate(item.location)) return
  selectionVersion++; locationVersion++; resolving.value = false; locating.value = false; locatingSelection.value = false; selectionError.value = ''; locationError.value = ''
  const name = String(item.name || item.label || item.address || '已选地点')
  selected.value = { name, address: typeof item.address === 'string' ? item.address : name, location: formatCoordinate(parseCoordinate(item.location)!), district: item.district }
  center.value = selected.value.location
  // Keep the selected search result visible until explicit confirmation.
  void loadNearby(center.value)
}
async function locate(automatic = false, keepPickedPreview = false) {
  if (locating.value) return
  const version = ++locationVersion
  if (!automatic) { selectionVersion++; resolving.value = false; selectionError.value = '' }
  locating.value = true; locatingSelection.value = !automatic; locationError.value = ''
  let receivedLocation: string | null = null
  try {
    const place = await getCurrentPlace({ onLocated: location => {
      if (!alive || version !== locationVersion || !parseCoordinate(location)) return
      receivedLocation = location
      current.value = { name: '当前位置', address: '', location }
      if (!keepPickedPreview) { center.value = location; void loadNearby(location) }
    } })
    if (!alive || version !== locationVersion) return
    current.value = place
    if (!keepPickedPreview) {
      center.value = place.location
      if (receivedLocation !== place.location) void loadNearby(place.location)
      if (!automatic || (!selected.value && selectedRole.value.endsWith('origin'))) selected.value = place
    }
    if (!automatic) { activeTab.value = 'nearby'; clearSearch() }
  } catch (error) { if (alive && version === locationVersion) locationError.value = message(error, '暂时无法定位，请搜索地址') }
  finally { if (alive && version === locationVersion) { locating.value = false; locatingSelection.value = false } }
}
function confirmPlace() {
  if (confirmDisabled.value || !selected.value) return
  confirming.value=true
  uni.setStorageSync('travel_place_' + selectedRole.value, selected.value)
  uni.setStorageSync('travel_places', [selected.value, ...recent.value.filter(place => place.location !== selected.value!.location)].slice(0, 8))
  uni.navigateBack({fail:()=>{if(alive){confirming.value=false;errorToast(new Error('地点已保存，请返回上一页继续'))}}})
}
function distanceText(distance?: number) { if (typeof distance !== 'number' || !Number.isFinite(distance)) return ''; return distance >= 1000 ? `${(distance / 1000).toFixed(1)}公里` : `${Math.round(distance)}米` }
</script>

<template>
<view class="page address-page" :class="pageClass()">
  <view class="search-panel">
    <view class="search-line"><view class="search-box"><AppIcon name="search" :size="18" color="muted"/><input v-model="keyword" placeholder="搜索地点名称或地址" confirm-type="search" aria-label="搜索地点名称或地址" @input="onInput" @confirm="search"/><button v-if="keyword" class="app-button clear-button" aria-label="清空地址搜索" @click="clearSearch"><AppIcon name="close" :size="16" color="muted"/></button></view></view>
    <view class="location-row"><text class="role-label">选择{{ roleLabel }}</text><button class="app-button location-button" :disabled="locating" @click="locate(false)"><AppIcon name="location" :size="18" color="brand"/><text>{{ locating ? '正在定位…' : '定位到当前位置' }}</text></button></view>
    <view v-if="locationError" class="location-message"><text>{{ locationError }}</text><button class="app-button plain" :disabled="locating" @click="locate(false)">重试定位</button></view>
  </view>
  <LocationMap v-model:center="center" :selected="selected" :user-location="current?.location" :show-user-location="false" :locating="locating" :height="250" @move="resolveMap" @pick="resolveMap"/>
  <view v-if="selectionError" class="selection-error"><text>{{ selectionError }}</text><button class="app-button plain" @click="resolveMap(center)">重新识别</button></view>
  <view v-if="keyword.trim()" class="search-results">
    <view class="list-heading"><text class="section-title">搜索结果</text><text class="muted">点选后在底部确认</text></view>
    <view v-if="loading" class="panel empty">正在搜索地点…</view>
    <view v-else-if="searchError" class="panel empty"><text class="empty-title">搜索未完成</text><text>{{ searchError }}</text><button class="app-button secondary" @click="search">重新搜索</button></view>
    <view v-else class="panel place-list"><button v-for="(item,index) in results" :key="item.id || index" class="app-button place-option" :class="{ selected: selected?.location === item.location }" @click="choose(item)"><AppIcon name="map-pin" :size="20" :color="selected?.location === item.location ? 'brand' : 'muted'"/><view class="place-text"><text class="place-name">{{ item.name }}</text><text class="subtitle">{{ item.district }} {{ item.address }}</text></view><AppIcon v-if="selected?.location === item.location" name="check" :size="20" color="brand"/></button><view v-if="searched && !results.length" class="empty"><text class="empty-title">未找到地点</text><text>请换用完整名称，或拖动地图选点。</text></view></view>
  </view>
  <template v-else>
    <view v-if="current" class="current-place panel"><button class="app-button place-option" :class="{ selected: selected?.location === current.location }" @click="choose(current)"><AppIcon name="location" :size="20" color="brand"/><view class="place-text"><text class="place-name">{{ current.name === '当前位置' ? '当前位置' : '当前位置 · ' + current.name }}</text><text v-if="current.address" class="subtitle">{{ current.address }}</text></view><AppIcon v-if="selected?.location === current.location" name="check" :size="20" color="brand"/></button></view>
    <view class="place-tabs"><button v-for="tab in tabs" :key="tab.key" class="app-button place-tab" :class="{ active: activeTab === tab.key }" @click="activeTab = tab.key">{{ tab.label }}</button></view>
    <view class="panel place-list">
      <template v-if="activeTab === 'nearby'">
        <text v-if="center" class="nearby-hint">当前地图中心附近</text>
        <view v-if="!center" class="empty">{{ locating ? '正在获取当前位置…' : '尚未获取当前位置，可重试定位或搜索地址。' }}</view>
        <view v-else-if="nearbyLoading" class="empty">正在查找附近地点…</view>
        <view v-else-if="nearbyError" class="empty"><text>{{ nearbyError }}</text><button class="app-button secondary" @click="loadNearby(center)">重新查找</button></view>
        <template v-else><button v-for="(item,index) in nearby" :key="item.id || index" class="app-button place-option" :class="{ selected: selected?.location === item.location }" @click="choose(item)"><AppIcon name="map-pin" :size="20" :color="selected?.location === item.location ? 'brand' : 'muted'"/><view class="place-text"><text class="place-name">{{ item.name }}</text><text class="subtitle">{{ item.address }}</text></view><text v-if="distanceText(item.distance)" class="place-distance">{{ distanceText(item.distance) }}</text><AppIcon v-if="selected?.location === item.location" name="check" :size="20" color="brand"/></button><view v-if="!nearby.length" class="empty">附近暂无地点，可搜索名称或在地图上选点。</view></template>
      </template>
      <template v-else-if="activeTab === 'saved'"><view v-if="savedAuthRequired" class="empty"><text class="empty-title">登录后选用常用地点</text><text>您仍可搜索或在地图上选择地点。</text><button class="app-button secondary" @click="go('/pages/login/index')">登录 / 注册</button></view><view v-else-if="savedLoading" class="empty">正在加载常用地点…</view><view v-else-if="savedError" class="empty"><text>{{savedError}}</text><button class="app-button secondary" @click="loadSaved">重新加载</button></view><template v-else><button v-for="item in saved" :key="item.id" class="app-button place-option" :class="{ selected: selected?.location === item.location }" @click="choose(item)"><AppIcon name="address" :size="20" color="muted"/><view class="place-text"><text class="place-name">{{ item.label || item.name }}</text><text class="subtitle">{{ item.address }}</text></view><AppIcon v-if="selected?.location === item.location" name="check" :size="20" color="brand"/></button><view v-if="!saved.length" class="empty"><text>暂无常用地点，可搜索或在地图上选点。</text><button class="app-button plain" @click="go('/pages/resources/index?kind=addresses')">管理常用地址</button></view></template></template>
      <template v-else><button v-for="(item,index) in recent" :key="index" class="app-button place-option" :class="{ selected: selected?.location === item.location }" @click="choose(item)"><AppIcon name="clock" :size="20" color="muted"/><view class="place-text"><text class="place-name">{{ item.name }}</text><text class="subtitle">{{ item.address }}</text></view><AppIcon v-if="selected?.location === item.location" name="check" :size="20" color="brand"/></button><view v-if="!recent.length" class="empty">暂无最近地点，确认选择后会自动保存。</view></template>
    </view>
  </template>
  <view class="confirm-place-bar"><view class="selection-summary"><text class="selection-name">{{ resolving ? '正在识别地图地址…' : selected?.name || '请先选择地点' }}</text><text class="selection-address">{{ selected?.address || (center ? '搜索、拖动或点选地图后，在这里确认' : locating ? '正在获取当前位置，也可手动搜索地址' : '可重试定位，或搜索地址后确认') }}</text></view><button class="app-button primary confirm-place-button" :disabled="confirmDisabled" :loading="confirming" @click="confirmPlace">{{confirming?'正在返回…':'确认'+roleLabel}}</button></view>
</view>
</template>

<style scoped>
.address-page{padding:0 0 calc(148px + env(safe-area-inset-bottom));background:#f5f6f7}.search-panel{background:#fff;padding:12px 16px 0}.search-line{display:flex;align-items:center;gap:8px}.search-box{display:flex;align-items:center;gap:6px;flex:1;background:#f5f6f7;padding:4px 8px;border-radius:4px;min-width:0}.search-box input{flex:1;min-width:0;font-size:14px}.clear-button{width:36px;min-width:36px;min-height:44px;background:transparent;display:flex;align-items:center;justify-content:center;padding:4px;margin:-4px -6px -4px 0}.location-row{display:flex;align-items:center;justify-content:space-between;gap:8px}.role-label{font-size:15px;font-weight:500}.location-button{display:flex;align-items:center;gap:6px;justify-content:flex-start;min-height:48px;background:transparent;padding:8px 0;color:#16845b;font-size:14px}.location-message,.selection-error{display:flex;align-items:center;gap:8px;color:#b3261e;font-size:13px;line-height:20px}.location-message>text,.selection-error>text{flex:1}.location-message .plain,.selection-error .plain{flex-shrink:0;font-size:14px;padding:8px 0;min-height:44px}.selection-error{padding:0 16px;background:#fff}.list-heading{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:12px 16px}.list-heading .section-title{font-size:15px}.list-heading .muted{font-size:12px}.place-list{padding:0 16px;margin:0;border-radius:0}.place-option{display:flex;align-items:flex-start;gap:8px;padding:14px 0;border-bottom:1px solid #eff0f1;min-height:64px;width:100%;background:#fff;text-align:left;border-radius:0;font-weight:400;line-height:22px}.place-option:last-child{border-bottom:0}.place-option.selected{background:#eff8f3}.place-option>.app-icon{margin-top:2px;flex-shrink:0}.place-text{flex:1;min-width:0}.place-name{font-size:15px;display:block;word-break:break-all;color:#1f2329}.place-option .subtitle{font-size:13px;line-height:20px;margin-top:4px;color:#646a73}.place-distance{font-size:12px;line-height:22px;color:#646a73;white-space:nowrap}.place-tabs{display:flex;background:#fff;border-bottom:1px solid #dee0e3;margin-top:8px}.place-tab{flex:1;background:#fff;border-radius:0;font-size:15px;padding:12px 8px;min-height:48px;border-bottom:2px solid transparent}.place-tab.active{color:#16845b;border-bottom-color:#16845b;font-weight:600}.current-place{padding:0 16px;margin:8px 0 0;border-radius:0}.nearby-hint{display:block;padding:12px 0 0;font-size:12px;line-height:18px;color:#646a73}.confirm-place-bar{position:fixed;bottom:0;left:0;right:0;z-index:50;background:#fff;border-top:1px solid #dee0e3;padding:12px 16px calc(12px + env(safe-area-inset-bottom));display:flex;flex-direction:column;gap:12px}.selection-summary{min-width:0}.selection-name{display:block;font-size:15px;line-height:22px;font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.selection-address{display:block;font-size:12px;line-height:18px;color:#646a73;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-top:4px}.confirm-place-button{width:100%;min-height:44px;padding:12px 16px}.senior.address-page{padding-bottom:calc(172px + env(safe-area-inset-bottom))}.senior .place-name,.senior .role-label{font-size:18px}.senior .search-box input{font-size:16px}.senior .location-button,.senior .place-tab,.senior .list-heading .section-title{font-size:16px}.senior .place-option .subtitle,.senior .nearby-hint,.senior .selection-address,.senior .place-distance{font-size:14px;line-height:22px}.senior .selection-name{font-size:18px;line-height:26px}.senior .place-option{min-height:76px;padding:16px 0}.senior .place-tab{min-height:52px}.senior .location-message,.senior .selection-error{font-size:15px}
</style>
