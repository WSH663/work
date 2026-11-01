<script setup lang="ts">
import { getCurrentInstance, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Data } from '../api'
import { adActionText, adMediaKind } from '../ad-content'
import { preferences } from '../store'
import AdMedia from './AdMedia.vue'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ items: Data[]; active: boolean }>()
const emit = defineEmits<{ open: [item: Data]; preview: [item: Data] }>()
const current = ref(0), height = ref(320), instance = getCurrentInstance()
let alive = true
function measure() {
  nextTick(() => {
    if (!alive) return
    const index = current.value
    uni.createSelectorQuery().in(instance?.proxy).selectAll('.home-ad-inner').boundingClientRect(rects => {
      if (!alive || current.value !== index || !Array.isArray(rects)) return
      const rect = rects[index]
      if (rect?.height) height.value = Math.ceil(rect.height)
    }).exec()
  })
}
function change(event: { detail: { current: number } }) { current.value = event.detail.current; measure() }
function select(index: number) { current.value = (index + props.items.length) % props.items.length; measure() }
watch(() => props.items, () => { current.value = Math.min(current.value, Math.max(0, props.items.length - 1)); measure() })
watch(() => preferences.senior, measure)
onMounted(() => { measure(); uni.onWindowResize(measure) })
onBeforeUnmount(() => { alive = false; uni.offWindowResize(measure) })
</script>
<template>
  <view v-if="items.length" class="home-ads">
    <view class="home-ads-heading"><text class="home-ads-title">活动推荐</text><view v-if="items.length>1" class="ad-pagination"><button class="app-button ad-page-button" aria-label="上一条广告" @click="select(current-1)"><AppIcon name="chevron-left" :size="18"/></button><text class="ad-page-count">{{current+1}} / {{items.length}}</text><button class="app-button ad-page-button" aria-label="下一条广告" @click="select(current+1)"><AppIcon name="chevron-right" :size="18"/></button></view></view>
    <swiper class="home-ad-swiper" :style="{height:height+'px'}" :current="current" :autoplay="false" :circular="items.length>1" :indicator-dots="false" @change="change">
      <swiper-item v-for="(item,index) in items" :key="item.id">
        <view class="home-ad-inner" :class="{'text-ad':adMediaKind(item)==='TEXT'}">
          <AdMedia :item="item" placement="banner" :active="active && current===index" @resize="measure"/>
          <button class="app-button button-row home-ad-summary" :aria-label="'查看广告内容：'+item.title" @click="emit('preview',item)"><text class="home-ad-headline">{{item.title}}</text><text v-if="item.body" class="home-ad-description">{{item.body}}</text></button>
          <view class="home-ad-footer"><text class="home-ad-disclosure">广告</text><button v-if="item.linkUrl" class="app-button home-ad-action" @click="emit('open',item)"><text>{{adActionText(item)}}</text><AppIcon name="chevron-right" :size="16" color="brand"/></button><button v-else class="app-button home-ad-more" @click="emit('preview',item)">{{adMediaKind(item)==='TEXT'?'阅读全文':'查看内容'}}<AppIcon name="chevron-right" :size="16" color="muted"/></button></view>
        </view>
      </swiper-item>
    </swiper>
  </view>
</template>
<style scoped>
.home-ads{margin-bottom:16px}.home-ads-heading{display:flex;align-items:center;justify-content:space-between;min-height:44px;margin-bottom:8px;gap:12px}.home-ads-title{font-size:16px;font-weight:600}.ad-pagination{display:flex;align-items:center;gap:4px}.ad-page-button{min-height:40px;min-width:40px;display:flex;align-items:center;justify-content:center;padding:8px;background:transparent}.ad-page-count{font-size:12px;color:#646a73;white-space:nowrap;font-variant-numeric:tabular-nums}.home-ad-swiper{width:100%}.home-ad-inner{width:100%;background:#fff;border-radius:8px;overflow:hidden}.home-ad-summary{padding:16px 16px 0;display:block}.home-ad-headline{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;font-size:17px;line-height:1.5;font-weight:600;word-break:break-word}.home-ad-description{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;font-size:14px;line-height:1.65;margin-top:8px;color:#646a73;white-space:pre-wrap;word-break:break-word}.home-ad-footer{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 16px 8px;min-height:56px}.home-ad-disclosure{color:#8f959e;font-size:11px}.home-ad-action,.home-ad-more{display:flex;align-items:center;gap:4px;min-height:40px;padding:8px 0;background:transparent;font-size:14px;color:#16845b}.home-ad-more{color:#646a73}.text-ad .home-ad-summary{padding-top:20px}.text-ad .home-ad-headline{font-size:20px}.text-ad .home-ad-description{-webkit-line-clamp:3}.senior .home-ads-title{font-size:19px}.senior .home-ad-headline,.senior .text-ad .home-ad-headline{font-size:22px}.senior .home-ad-description,.senior .home-ad-action,.senior .home-ad-more{font-size:18px}.senior .home-ad-disclosure,.senior .ad-page-count{font-size:14px}.senior .ad-page-button{min-width:44px;min-height:44px}
</style>
