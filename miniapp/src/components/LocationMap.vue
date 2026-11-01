<script setup lang="ts">
import { computed, getCurrentInstance, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Place } from '../api'
import { staticMapUrl } from '../map'
import { formatCoordinate, mapPoint, parseCoordinate, pointCoordinate, project, unproject, type Pixel } from '../map-geometry'
import AppIcon from './AppIcon.vue'
import { pageClass } from '../store'

const props = withDefaults(defineProps<{
  center?: string | null; origin?: Place | null; destination?: Place | null; selected?: Place | null
  userLocation?: string | null; height?: number; interactive?: boolean; showCenterPin?: boolean; showUserLocation?: boolean; locating?: boolean
}>(), { center: null, height: 250, interactive: true, showCenterPin: true, showUserLocation: true, locating: false })
const emit = defineEmits<{
  (event: 'update:center', location: string): void
  (event: 'move', location: string): void
  (event: 'pick', location: string): void
  (event: 'error', message: string): void
  (event: 'ready'): void
}>()
const instance = getCurrentInstance(), mapId = `travel-map-${instance?.uid || 0}`, wrapperId = `${mapId}-wrapper`
const localCenter = ref<string | null>(parseCoordinate(props.center) ? props.center! : null)
const coordinate = computed(() => parseCoordinate(localCenter.value))
const zoom = ref(15), mapWidth = ref(Math.min(1024, uni.getSystemInfoSync().windowWidth || 375))
let minimumZoom = 1
// #ifdef MP-WEIXIN
minimumZoom = 3
// #endif
const imageWidth = computed(() => Math.max(128, Math.min(1024, Math.round(mapWidth.value))))
const imageHeight = computed(() => Math.max(128, Math.min(1024, Math.round(props.height))))
const retry = ref(0), mapError = ref(''), imageLoading = ref(!!coordinate.value)
// WeChat uses its native Tencent map; H5 keeps the AMap static image.
// Both consume GCJ-02 coordinates independently of the device location source.
let usesNativeMap = false
// #ifdef MP-WEIXIN
usesNativeMap = true
// #endif
const staticMapEnabled = !usesNativeMap
const nativeVisible = ref(true)
const imageUrl = computed(() => staticMapEnabled && coordinate.value && localCenter.value ? `${staticMapUrl(localCenter.value, zoom.value, imageWidth.value, imageHeight.value)}&retry=${retry.value}` : '')
const drag = ref<Pixel>({ x: 0, y: 0 }), imageStyle = computed(() => ({ transform: `translate(${drag.value.x}px,${drag.value.y}px)` }))
let bounds = { left: 0, top: 0, width: mapWidth.value, height: props.height }
let alive = true, start: Pixel | null = null, last: Pixel | null = null, moved = false, pinchDistance = 0, pinchZoom = 15, lastTouchAt = 0
let nativeContext: UniApp.MapContext | undefined
let nativeVersion = 0
let staticElement: HTMLElement | null = null
let resizeObserver: ResizeObserver | undefined
watch(() => props.center, value => {
  const nextCenter = parseCoordinate(value) ? value! : null
  if (nextCenter === localCenter.value) return
  nativeVersion++; nativeContext = undefined
  localCenter.value = nextCenter; nativeVisible.value = true; mapError.value = ''; imageLoading.value = !!nextCenter; resetDrag()
  if (!nextCenter) unbindStaticEvents()
  nextTick(syncMapElements)
})
watch(imageUrl, value => { imageLoading.value = !!value; mapError.value = '' })
watch(() => props.height, () => nextTick(syncMapElements))
function unbindStaticEvents() {
  // #ifdef H5
  staticElement?.removeEventListener('mousedown', mouseDown)
  staticElement?.removeEventListener('touchstart', domTouchStart)
  staticElement?.removeEventListener('touchmove', domTouchMove)
  staticElement?.removeEventListener('touchend', domTouchEnd)
  staticElement?.removeEventListener('touchcancel', resetDrag)
  // #endif
  staticElement = null
}
function syncMapElements() {
  if (!alive) return
  measure()
  // #ifdef H5
  const element = coordinate.value ? document.getElementById(`${mapId}-static`) : null
  if (element !== staticElement) {
    unbindStaticEvents(); staticElement = element
    staticElement?.addEventListener('mousedown', mouseDown)
    staticElement?.addEventListener('touchstart', domTouchStart, { passive: false })
    staticElement?.addEventListener('touchmove', domTouchMove, { passive: false })
    staticElement?.addEventListener('touchend', domTouchEnd, { passive: false })
    staticElement?.addEventListener('touchcancel', resetDrag, { passive: false })
  }
  // #endif
  // #ifdef MP-WEIXIN
  if (coordinate.value && nativeVisible.value && !nativeContext) nativeContext = uni.createMapContext(mapId, instance?.proxy)
  // #endif
}
function measure() {
  if (!alive) return
  // Native DOM coordinates must match the raw DOM pointer coordinates. Uni's
  // normalized events/selector rects subtract the H5 navigation bar height.
  // #ifdef H5
  const element = document.getElementById(wrapperId)
  if (element) {
    const rect = element.getBoundingClientRect()
    if (rect.width > 0) { bounds = { left: rect.left, top: rect.top, width: rect.width, height: rect.height }; mapWidth.value = rect.width }
  }
  // #endif
  // #ifdef MP-WEIXIN
  uni.createSelectorQuery().in(instance?.proxy).select(`#${wrapperId}`).boundingClientRect(result => {
    if (!alive || !result || Array.isArray(result)) return
    const rect = result as { left: number; top: number; width: number; height: number }
    if (rect.width > 0) { bounds = rect; mapWidth.value = rect.width }
  }).exec()
  // #endif
}
onMounted(() => {
  nextTick(syncMapElements)
  // #ifdef H5
  // Uni's event normalization drops MouseEvent.button. Attach native listeners
  // to keep button and viewport coordinates consistent across start/move/end.
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(measure)
    const wrapper = document.getElementById(wrapperId)
    if (wrapper) resizeObserver.observe(wrapper)
  }
  window.addEventListener('resize', measure)
  window.addEventListener('mousemove', mouseMove)
  window.addEventListener('mouseup', mouseUp)
  window.addEventListener('scroll', measure, true)
  // #endif
})
onBeforeUnmount(() => {
  alive = false
  // #ifdef H5
  unbindStaticEvents(); resizeObserver?.disconnect(); resizeObserver = undefined
  window.removeEventListener('resize', measure)
  window.removeEventListener('mousemove', mouseMove)
  window.removeEventListener('mouseup', mouseUp)
  window.removeEventListener('scroll', measure, true)
  // #endif
})
function resetDrag() { start = null; last = null; moved = false; pinchDistance = 0; drag.value = { x: 0, y: 0 } }
function commit(location: string) { if (!coordinate.value || !parseCoordinate(location)) return; localCenter.value = location; emit('update:center', location); emit('move', location) }
function changeZoom(amount: number) { if (!props.interactive || !coordinate.value) return; nativeVersion++; zoom.value = Math.max(minimumZoom, Math.min(17, zoom.value + amount)); resetDrag() }
function loaded() { if (!alive || !coordinate.value || (usesNativeMap && !nativeVisible.value)) return; imageLoading.value = false; mapError.value = ''; emit('ready') }
function failed() {
  if (!alive || !coordinate.value) return
  imageLoading.value = false; mapError.value = '地图暂未加载，可继续搜索地址'
  // #ifdef MP-WEIXIN
  nativeVersion++; nativeVisible.value = false; nativeContext = undefined
  // #endif
  emit('error', mapError.value)
}
function reload() {
  if (!coordinate.value) return
  mapError.value = ''; imageLoading.value = true; retry.value++
  // #ifdef MP-WEIXIN
  // Remount the native map and restore its context without changing provider.
  nativeVersion++; nativeContext = undefined; nativeVisible.value = true
  nextTick(syncMapElements)
  // #endif
}

const points = computed(() => {
  const list: { key: string; title: string; location: string; tone: string; nativeId: number }[] = []
  if (props.origin) list.push({ key: 'origin', title: '上车', location: props.origin.location, tone: 'origin', nativeId: 1 })
  if (props.destination) list.push({ key: 'destination', title: '下车', location: props.destination.location, tone: 'destination', nativeId: 2 })
  if (props.selected) list.push({ key: 'selected', title: '已选', location: props.selected.location, tone: 'selected', nativeId: 3 })
  // Address selection has one active target. Once a target exists, keep the
  // map focused on that target instead of adding a second GPS pin elsewhere;
  // the current GPS value is still shown in the page's current-location row.
  // Home/route maps have no selected target and continue to show the live pin.
  if (props.showUserLocation && props.userLocation && !props.selected) list.push({ key: 'current', title: '当前位置', location: props.userLocation, tone: 'current', nativeId: 4 })
  // The address page can temporarily pass the same GPS point as both
  // `selected` and `userLocation` (the reverse-geocode request resolves after
  // the location callback). Passing both to the native map renders two pins
  // on top of each other. Keep one semantic marker per coordinate and prefer
  // the live-location marker when the values are equal.
  const unique = new Map<string, typeof list[number]>()
  const rank = (key: string) => key === 'origin' || key === 'destination' ? 4 : key === 'current' ? 3 : 2
  for (const point of list) {
    const coordinate = parseCoordinate(point.location)
    if (!coordinate) continue
    const key = `${coordinate.longitude.toFixed(5)},${coordinate.latitude.toFixed(5)}`
    const previous = unique.get(key)
    if (!previous || rank(point.key) > rank(previous.key)) unique.set(key, point)
  }
  return Array.from(unique.values())
})
const displayCenterPin = computed(() => {
  if (!props.showCenterPin || !props.interactive || !coordinate.value) return false
  // A selected/current marker already identifies the map center. The fixed
  // center pin is only useful while the user is dragging to a new point; do
  // not draw it on top of an existing marker.
  const center = coordinate.value
  return !points.value.some(point => {
    const value = parseCoordinate(point.location)
    return !!value && Math.abs(value.longitude - center.longitude) < 0.00015 && Math.abs(value.latitude - center.latitude) < 0.00015
  })
})
const overlayPoints = computed(() => {
  const center = coordinate.value
  if (!center) return []
  return points.value.map(point => {
  const pixel = mapPoint(parseCoordinate(point.location)!, center, zoom.value, imageWidth.value, imageHeight.value)
  const x = pixel.x * mapWidth.value / imageWidth.value + drag.value.x
  const y = pixel.y * props.height / imageHeight.value + drag.value.y
  return { ...point, visible: x >= -12 && x <= mapWidth.value + 12 && y >= -12 && y <= props.height + 12, style: { left: `${x}px`, top: `${y}px` } }
  })
})
const nativeMarkers = computed(() => points.value.map(point => ({
  id: point.nativeId, ...parseCoordinate(point.location)!, width: 28, height: 28,
  iconPath: point.key === 'destination' ? '/static/icons/map-pin-normal.png' : '/static/icons/map-pin-brand.png',
  anchor: { x: 0.5, y: 1 }, callout: { content: point.title, display: 'ALWAYS', color: '#1f2329', bgColor: '#ffffff', fontSize: 13, padding: 5, borderRadius: 4 }
})))

// Static-map pixels use the same Web Mercator projection as the returned image.
function relativePoint(point: Pixel) { return { x: point.x - bounds.left, y: point.y - bounds.top } }
function begin(point: Pixel) { if (!props.interactive || !coordinate.value || mapError.value) return; start = point; last = point; moved = false; drag.value = { x: 0, y: 0 } }
function move(point: Pixel) {
  if (!start || pinchDistance) return
  last = point
  const dx = point.x - start.x, dy = point.y - start.y
  if (Math.abs(dx) + Math.abs(dy) > 5) moved = true
  drag.value = { x: dx, y: dy }
}
function finish(point: Pixel | null) {
  const center = coordinate.value
  if (!start || !props.interactive || !center) { resetDrag(); return }
  const end = point || last || start
  if (moved) {
    const middle = project(center, zoom.value)
    const location = formatCoordinate(unproject({ x: middle.x - (end.x - start.x) * imageWidth.value / mapWidth.value, y: middle.y - (end.y - start.y) * imageHeight.value / props.height }, zoom.value))
    resetDrag(); commit(location)
  } else {
    const relative = relativePoint(end)
    if (relative.x >= 0 && relative.x <= bounds.width && relative.y >= 0 && relative.y <= bounds.height) {
      emit('pick', formatCoordinate(pointCoordinate({ x: relative.x * imageWidth.value / mapWidth.value, y: relative.y * imageHeight.value / props.height }, center, zoom.value, imageWidth.value, imageHeight.value)))
    }
    resetDrag()
  }
}
function mouseDown(event: MouseEvent) { if (Date.now() - lastTouchAt < 600 || event.button !== 0) return; event.preventDefault(); measure(); begin({ x: event.clientX, y: event.clientY }) }
function mouseMove(event: MouseEvent) { if (Date.now() - lastTouchAt < 600) return; if (start && !pinchDistance) move({ x: event.clientX, y: event.clientY }) }
function mouseUp(event: MouseEvent) { if (Date.now() - lastTouchAt < 600) return; if (start) finish({ x: event.clientX, y: event.clientY }) }
type TouchInput = { touches: ArrayLike<{ clientX: number; clientY: number }>; changedTouches: ArrayLike<{ clientX: number; clientY: number }> }
const touchPoint = (touch: { clientX: number; clientY: number }): Pixel => ({ x: touch.clientX, y: touch.clientY })
const distance = (event: TouchInput) => Math.hypot(event.touches[0].clientX - event.touches[1].clientX, event.touches[0].clientY - event.touches[1].clientY)
function touchStart(event: TouchInput) {
  lastTouchAt = Date.now()
  if (!props.interactive || !coordinate.value || mapError.value) return
  if (event.touches.length === 2) { resetDrag(); pinchDistance = distance(event); pinchZoom = zoom.value; return }
  if (event.touches[0]) begin(touchPoint(event.touches[0]))
}
function touchMove(event: TouchInput) {
  if (!coordinate.value) return
  if (pinchDistance && event.touches.length >= 2) { zoom.value = Math.max(minimumZoom, Math.min(17, pinchZoom + Math.round(Math.log2(distance(event) / pinchDistance)))); return }
  if (event.touches[0]) move(touchPoint(event.touches[0]))
}
function touchEnd(event: TouchInput) {
  lastTouchAt = Date.now()
  if (pinchDistance) { if (event.touches.length < 2) resetDrag(); return }
  finish(event.changedTouches[0] ? touchPoint(event.changedTouches[0]) : null)
}
// H5 uses raw DOM touch coordinates so they share the viewport coordinate
// space of getBoundingClientRect; Uni-normalized touch Y excludes navigation.
function domTouchStart(event: TouchEvent) { if (!props.interactive || mapError.value) return; event.preventDefault(); event.stopPropagation(); measure(); touchStart(event) }
function domTouchMove(event: TouchEvent) { if (!props.interactive || mapError.value) return; event.preventDefault(); event.stopPropagation(); touchMove(event) }
function domTouchEnd(event: TouchEvent) { if (!props.interactive || mapError.value) return; event.preventDefault(); event.stopPropagation(); touchEnd(event) }
function nativeTap(event: any) {
  if (!props.interactive || !coordinate.value) return
  const detail = event.detail || {}
  if (Number.isFinite(detail.longitude) && Number.isFinite(detail.latitude)) { nativeVersion++; emit('pick', formatCoordinate(detail)) }
}
function nativeRegion(event: any) {
  const detail = event.detail || {}, type = detail.type || event.type, causedBy = detail.causedBy || event.causedBy
  if (!props.interactive || !coordinate.value) return
  if (type === 'begin') { nativeVersion++; return }
  if (type !== 'end' || !['gesture', 'drag', 'scale'].includes(causedBy)) return
  if (detail.scale) zoom.value = Math.max(minimumZoom, Math.min(17, Math.round(detail.scale)))
  const version = ++nativeVersion
  const center = detail.centerLocation
  if (center && Number.isFinite(center.longitude) && Number.isFinite(center.latitude) && parseCoordinate(`${center.longitude},${center.latitude}`)) {
    commit(formatCoordinate(center)); return
  }
  nativeContext?.getCenterLocation({ success: center => { if (alive && version === nativeVersion) commit(formatCoordinate(center)) } })
}
</script>

<template>
  <view class="location-map" :class="pageClass()">
    <view :id="wrapperId" class="map-viewport" :style="{ height: height + 'px' }">
      <!-- #ifdef MP-WEIXIN -->
      <map v-if="coordinate && nativeVisible" :id="mapId" :key="retry" class="native-map" :latitude="coordinate.latitude" :longitude="coordinate.longitude" :scale="zoom" :min-scale="minimumZoom" :max-scale="17" :markers="nativeMarkers" :enable-scroll="interactive" :enable-zoom="interactive" :enable-rotate="false" :enable-overlooking="false" :show-location="false" @tap="nativeTap" @regionchange="nativeRegion" @updated="loaded" @error="failed" @rendererror="failed">
        <cover-image v-if="displayCenterPin" class="native-center-pin" src="/static/icons/map-pin-brand.png"/>
        <cover-view v-if="interactive" class="native-zoom"><cover-view class="native-zoom-button" @tap.stop="changeZoom(1)">＋</cover-view><cover-view class="native-zoom-button" @tap.stop="changeZoom(-1)">－</cover-view></cover-view>
      </map>
      <!-- #endif -->
      <!-- #ifdef H5 -->
      <view v-if="coordinate && staticMapEnabled" :id="mapId + '-static'" class="static-map" :class="{ interactive, dragging: moved }">
        <image :key="imageUrl" :src="imageUrl" class="map-image" mode="scaleToFill" :style="imageStyle" :draggable="false" @load="loaded" @error="failed"/>
        <template v-for="point in overlayPoints" :key="point.key"><view v-if="point.visible" class="map-marker" :class="point.tone" :style="point.style"><text class="marker-label">{{ point.title }}</text><AppIcon name="map-pin" :size="26" :color="point.tone === 'destination' ? 'normal' : 'brand'"/></view></template>
        <view v-if="displayCenterPin && !mapError" class="center-pin"><AppIcon name="map-pin" :size="30" color="brand"/></view>
      </view>
      <view v-if="coordinate && staticMapEnabled && imageLoading && !mapError" class="map-loading">正在加载地图…</view>
      <view v-if="coordinate && staticMapEnabled && interactive" class="zoom-controls"><button class="app-button zoom-button" aria-label="放大地图" :disabled="zoom >= 17" @click.stop="changeZoom(1)">＋</button><button class="app-button zoom-button" aria-label="缩小地图" :disabled="zoom <= 1" @click.stop="changeZoom(-1)">－</button></view>
      <!-- #endif -->
      <view v-if="!coordinate" class="map-placeholder"><text>{{ locating ? '正在获取当前位置…' : '请开启定位或搜索地点' }}</text></view>
      <view v-if="coordinate && mapError" class="map-error"><text>{{ mapError }}</text><button class="app-button secondary retry-button" @click.stop="reload">重新加载地图</button></view>
    </view>
  </view>
</template>

<style scoped>
.location-map{width:100%;background:#fff}.map-viewport{position:relative;overflow:hidden;background:#edf0ed;isolation:isolate}.native-map,.static-map{width:100%;height:100%;position:relative}.static-map{touch-action:none;user-select:none}.static-map.interactive{cursor:grab}.static-map.dragging{cursor:grabbing}.map-image{display:block;width:100%;height:100%;pointer-events:none}.map-placeholder{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;padding:24px;background:#edf0ed;color:#646a73;font-size:14px;line-height:22px;text-align:center}.map-loading{position:absolute;left:8px;bottom:32px;z-index:3;background:#fff;padding:4px 8px;border-radius:4px;color:#646a73;font-size:12px;pointer-events:none}.zoom-controls,.native-zoom{position:absolute;right:8px;top:48px;z-index:4;border:1px solid #d0d3d6;background:#fff;border-radius:4px;overflow:hidden}.zoom-button{width:44px;min-width:44px;height:44px;min-height:44px;padding:0;background:#fff;color:#1f2329;border-radius:0;font-size:24px;line-height:44px}.zoom-button+.zoom-button{border-top:1px solid #dee0e3}.zoom-button[disabled]{color:#a6abb1;background:#fff}.map-marker{position:absolute;z-index:2;display:flex;flex-direction:column;align-items:center;transform:translate(-50%,-100%);pointer-events:none;white-space:nowrap}.marker-label{font-size:12px;color:#1f2329;line-height:18px;padding:2px 6px;background:#fff;border:1px solid #d0d3d6;border-radius:4px}.current .marker-label{color:#075a35;border-color:#16845b}.center-pin{position:absolute;left:50%;top:50%;transform:translate(-50%,-100%);pointer-events:none;z-index:2}.native-center-pin{position:absolute;left:50%;top:50%;width:30px;height:30px;transform:translate(-50%,-100%)}.native-zoom-button{width:44px;height:44px;line-height:44px;text-align:center;font-size:24px;color:#1f2329;background:#fff;border-bottom:1px solid #dee0e3}.map-error{position:absolute;inset:0;z-index:5;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:12px;padding:20px;background:#f5f6f7;color:#646a73;font-size:14px;text-align:center}.retry-button{font-size:14px;padding:10px 16px;min-height:44px}
/* The component owns the senior class so native style isolation is respected. */
.location-map.senior .map-placeholder{font-size:18px;line-height:28px}
.location-map.senior .zoom-button,.location-map.senior .native-zoom-button{width:48px;min-width:48px;height:48px;min-height:48px;font-size:28px;line-height:48px}
.location-map.senior .marker-label,.location-map.senior .map-loading{font-size:14px;line-height:20px}
.location-map.senior .map-error{font-size:16px}
.location-map.senior .retry-button{font-size:18px;line-height:26px;min-height:48px}
</style>
