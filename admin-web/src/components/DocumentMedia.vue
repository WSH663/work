<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
const props = defineProps<{ type: string; url: string; poster: string; title: string }>()
const failed = ref(false), posterFailed = ref(false), playing = ref(false), hint = ref(''), video = ref<HTMLVideoElement | null>(null)
let version = 0, alive = true
function safe(value: string) { try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password && !url.hash ? url.href : '' } catch { return '' } }
function stop() { version++; video.value?.pause(); playing.value = false; hint.value = '' }
function mediaError(event: Event) { if (props.type === 'VIDEO' && event.target !== video.value) return; stop(); failed.value = true }
async function play() {
  if (playing.value || !safe(props.url) || failed.value) return
  const current = ++version; playing.value = true; hint.value = ''; await nextTick()
  if (!alive || current !== version) return
  const element = video.value; if (!element) return
  try { await element.play(); if (!alive || current !== version) element.pause() }
  catch (error) { if (!alive || current !== version) return; if (error instanceof Error && error.name === 'NotAllowedError') hint.value = '请点击视频内的播放按钮继续。'; else if (!(error instanceof Error && error.name === 'AbortError')) { stop(); failed.value = true } }
}
watch(() => [props.type, props.url, props.poster], () => { stop(); failed.value = false; posterFailed.value = false })
onBeforeUnmount(() => { alive = false; stop() })
</script>
<template>
  <div v-if="type !== 'TEXT'" class="document-media">
    <div v-if="!safe(url) || failed" class="document-media-error"><AppIcon name="Picture" :size="24"/><span>{{ failed ? '素材加载失败' : '请填写有效的 HTTPS 素材链接' }}</span><el-button v-if="failed" link type="primary" @click="failed = false; posterFailed = false">重新加载素材</el-button></div>
    <img v-else-if="type === 'IMAGE'" :src="safe(url)" :alt="title" @error="mediaError"/>
    <video v-else-if="playing" ref="video" :src="safe(url)" :poster="!posterFailed ? safe(poster) || undefined : undefined" preload="none" controls playsinline @error="mediaError" @play="hint = ''"/>
    <button v-else type="button" class="document-video-cover" aria-label="播放章节视频预览" @click="play"><img v-if="safe(poster) && !posterFailed" :src="safe(poster)" alt="视频封面" @error="posterFailed = true"/><span class="play-symbol" aria-hidden="true"/><span>点击播放</span></button>
    <p v-if="hint" class="play-hint" role="status">{{ hint }}</p>
  </div>
</template>
<style scoped>
.document-media{margin:16px 0;overflow:hidden}.document-media>img{display:block;width:100%;height:auto}.document-media>video{display:block;width:100%;aspect-ratio:16/9;background:#18231d}.document-media-error{min-height:144px;padding:20px;background:#f5f6f7;color:#747c76;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;font-size:13px}.document-video-cover{position:relative;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:10px;width:100%;aspect-ratio:16/9;border:0;background:#18231d;color:#fff;font-size:13px}.document-video-cover img{position:absolute;inset:0;width:100%;height:100%;object-fit:contain}.document-video-cover>span{position:relative;text-shadow:0 1px 2px #000}.play-symbol{width:44px;height:44px;border:1px solid #fff;border-radius:50%;background:#0006}.play-symbol:after{content:'';position:absolute;left:17px;top:12px;border-left:13px solid #fff;border-top:9px solid transparent;border-bottom:9px solid transparent}.play-hint{font-size:12px;color:#606266;line-height:20px;margin:8px 0 0}
</style>
