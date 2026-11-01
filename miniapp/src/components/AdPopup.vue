<script setup lang="ts">
import { getCurrentInstance, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Data } from '../api'
import { adActionText } from '../ad-content'
import AdMedia from './AdMedia.vue'
import AppIcon from './AppIcon.vue'
import { pageClass } from '../store'

const props = withDefaults(defineProps<{ ad: Data; countdown: number; active?: boolean }>(), { active: true })
defineEmits<{ close: []; open: [] }>()
const bodyHeight = ref(240), instance = getCurrentInstance()
let alive = true
function measureContent() {
  nextTick(() => {
    if (!alive) return
    const windowHeight = uni.getSystemInfoSync().windowHeight
    const maximum = Math.max(96, Math.floor(windowHeight * .8) - 60 - (props.ad.linkUrl ? 76 : 20))
    uni.createSelectorQuery().in(instance?.proxy).select('.ad-popup-inner').boundingClientRect(result => {
      if (!alive || !result || Array.isArray(result) || typeof result.height !== 'number') return
      bodyHeight.value = Math.min(maximum, Math.max(80, result.height))
    }).exec()
  })
}
onMounted(() => { measureContent(); uni.onWindowResize(measureContent) })
watch(() => props.ad, measureContent)
watch(() => props.active, active => { if (active) measureContent() })
onBeforeUnmount(() => { alive = false; uni.offWindowResize(measureContent) })
</script>
<template>
  <view class="ad-popup-mask" :class="pageClass()" @touchmove.stop>
    <view class="ad-popup" role="dialog" aria-modal="true" :aria-label="ad.title || '首页广告'" @tap.stop>
      <view class="ad-popup-head">
        <text class="ad-disclosure">广告<text v-if="countdown>0"> · {{countdown}}s</text></text>
        <button class="app-button popup-close" aria-label="关闭广告，继续使用首页" @click="$emit('close')"><text>关闭</text><AppIcon name="close" :size="18" color="muted"/></button>
      </view>
      <scroll-view scroll-y class="ad-popup-body" :style="{height:bodyHeight+'px'}" :show-scrollbar="true">
        <view class="ad-popup-inner">
          <AdMedia :item="ad" placement="popup" :active="active" @resize="measureContent"/>
          <view class="popup-copy">
            <text class="popup-title">{{ad.title}}</text>
            <text v-if="ad.body" class="popup-description">{{ad.body}}</text>
          </view>
        </view>
      </scroll-view>
      <view v-if="ad.linkUrl" class="ad-popup-foot"><button class="app-button primary popup-action" @click="$emit('open')">{{adActionText(ad)}}</button></view>
    </view>
  </view>
</template>
<style scoped>
.ad-popup-mask{position:fixed;inset:0;z-index:1200;background:rgba(0,0,0,.48);display:flex;align-items:center;justify-content:center;padding:24px 24px calc(24px + env(safe-area-inset-bottom))}.ad-popup{width:100%;max-width:360px;max-height:80vh;display:flex;flex-direction:column;background:#fff;border-radius:12px;overflow:hidden;color:#1f2329}.ad-popup-head{display:flex;align-items:center;justify-content:space-between;padding:0 8px 0 20px;min-height:52px;flex-shrink:0}.ad-disclosure{font-size:12px;color:#646a73}.popup-close{display:flex;align-items:center;justify-content:center;gap:6px;min-height:44px;min-width:76px;padding:8px;background:transparent;font-size:13px;color:#646a73}.ad-popup-body{min-height:0;overscroll-behavior:contain}.popup-copy{padding:20px 24px 24px}.popup-title{display:block;font-size:21px;font-weight:600;line-height:1.5;word-break:break-word}.popup-description{display:block;margin-top:12px;white-space:pre-wrap;word-break:break-word;line-height:1.8;font-size:15px;color:#4c535b}.ad-popup-foot{padding:0 24px 24px;flex-shrink:0}.popup-action{width:100%;min-height:48px}.senior .popup-title{font-size:25px}.senior .popup-description{font-size:18px}.senior .popup-close{font-size:16px;min-height:48px}.senior .ad-popup-head{min-height:56px}.senior .ad-disclosure{font-size:14px}@media(max-width:360px){.ad-popup-mask{padding-left:16px;padding-right:16px}.popup-copy{padding:16px 20px 20px}.ad-popup-foot{padding:0 20px 20px}}@media(max-height:600px){.ad-popup{max-height:80vh}.popup-copy{padding-top:16px;padding-bottom:16px}}
</style>
