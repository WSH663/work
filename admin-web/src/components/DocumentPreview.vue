<script setup lang="ts">
import { computed, ref } from 'vue'
import { documentActions, renderDocumentText, type ManagedDocument, type DocumentKind } from '../lib/documents'
import DocumentMedia from './DocumentMedia.vue'
const props = defineProps<{ kind: DocumentKind; document: ManagedDocument; variables: Record<string, string>; publishedAt?: string; revision?: number }>()
const previewRoot = ref<HTMLElement | null>(null)
const sections = computed(() => props.document.sections.filter(section => section.enabled))
const text = (value: string) => renderDocumentText(value, props.variables)
function jump(index: number) { previewRoot.value?.querySelector(`[data-preview-section="${index}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
function actionName(value: string) { return documentActions.find(action => action.value === value)?.label || value }
</script>
<template>
  <div class="document-preview-wrap">
    <p class="preview-note">仅预览阅读效果。目录和媒体可操作；相关页面及业务操作仅示意，不执行跳转或拨号。停用章节不展示。</p>
    <div ref="previewRoot" class="document-phone-preview" data-testid="document-phone-preview">
      <div class="phone-navigation"><AppIcon name="ArrowRight" class="back-icon" :size="16"/><strong>{{ text(document.title) || '文档标题' }}</strong><span>•••</span></div>
      <article>
        <header><h2>{{ text(document.title) || '请填写文档标题' }}</h2><p v-if="publishedAt" class="document-version">发布于 {{ publishedAt.replace('T', ' ').slice(0, 16) }} · 版本 {{ revision }}</p><p v-if="document.introduction" class="introduction">{{ text(document.introduction) }}</p></header>
        <nav v-if="sections.length" class="document-toc" aria-label="预览文档目录"><strong>内容目录</strong><button v-for="(section, index) in sections" :key="section.id + index" type="button" @click="jump(index)"><span>{{ index + 1 }}. {{ text(section.title) || '未填写章节标题' }}</span><AppIcon name="ArrowRight" :size="12"/></button></nav>
        <p v-else class="preview-empty">尚无启用的章节。请至少启用一个章节后发布。</p>
        <section v-for="(section, index) in sections" :key="section.id + index" :data-preview-section="index" class="reading-section">
          <h3>{{ index + 1 }}. {{ text(section.title) || '未填写章节标题' }}</h3>
          <DocumentMedia :type="section.mediaType" :url="section.mediaUrl" :poster="section.posterUrl" :title="text(section.title)"/>
          <p v-if="section.body" class="document-body">{{ text(section.body) }}</p>
          <span v-if="section.linkUrl" class="preview-related">查看相关页面<AppIcon name="ArrowRight" :size="13"/></span>
          <div v-if="section.actions.length" class="preview-actions"><span v-for="action in section.actions" :key="action" class="preview-business-action"><AppIcon :name="action === 'contact' ? 'Phone' : 'ArrowRight'" :size="15"/>{{ actionName(action) }}</span></div>
        </section>
        <footer v-if="document.footerTitle || document.footer"><h3 v-if="document.footerTitle">{{ text(document.footerTitle) }}</h3><p v-if="document.footer">{{ text(document.footer) }}</p></footer>
        <div class="preview-document-links"><span v-if="kind !== 'terms'">用户服务协议</span><span v-if="kind !== 'privacy'">隐私说明</span><button type="button" @click="previewRoot?.scrollIntoView({ behavior: 'smooth', block: 'start' })">返回顶部</button></div>
      </article>
    </div>
  </div>
</template>
<style scoped>
.preview-document-links{display:flex;gap:16px;flex-wrap:wrap;justify-content:center;border-top:1px solid #e8ece9;padding-top:20px;margin-top:28px;font-size:12px;line-height:22px;color:#16794d}.preview-document-links button{border:0;background:none;padding:0;color:inherit;font:inherit}
.document-preview-wrap{max-width:480px;margin:0 auto;min-width:0}.preview-note{margin:0 0 16px;color:#6b7370;font-size:13px;line-height:22px}.document-phone-preview{max-width:390px;width:100%;margin:0 auto;background:#fff;border:1px solid #dfe4e0;border-radius:6px;color:#26322b;overflow:hidden;overflow-wrap:anywhere}.phone-navigation{height:48px;padding:0 16px;display:flex;gap:12px;align-items:center;border-bottom:1px solid #eef0ee;font-size:14px}.phone-navigation strong{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:15px;font-weight:500}.back-icon{transform:rotate(180deg);flex-shrink:0}.phone-navigation>span{letter-spacing:2px}article{padding:24px 20px}h2{font-size:22px;line-height:32px;margin:0 0 12px;font-weight:600}.document-version{font-size:11px;line-height:18px;color:#79817c;margin:0 0 12px}.introduction,.document-body,footer p{white-space:pre-wrap;font-size:15px;line-height:26px;margin:0;color:#4a554e}.document-toc{margin-top:24px;padding:16px 0;border-top:1px solid #e8ece9;border-bottom:1px solid #e8ece9}.document-toc>strong{display:block;font-size:14px;font-weight:500;margin-bottom:8px}.document-toc button{display:flex;align-items:center;justify-content:space-between;gap:12px;width:100%;padding:8px 0;min-height:40px;text-align:left;color:#16794d;background:none;border:0;line-height:22px;font-size:14px}.document-toc button span{min-width:0;overflow-wrap:anywhere}.document-toc button>.el-icon{flex-shrink:0}.reading-section{padding-top:28px;scroll-margin-top:20px}h3{font-size:17px;line-height:27px;margin:0 0 12px;font-weight:600}.preview-actions{margin-top:16px;display:flex;flex-direction:column;gap:8px}.preview-business-action{display:flex;align-items:center;gap:8px;font-size:14px;color:#16794d;line-height:24px}.preview-related{display:inline-flex;align-items:center;gap:6px;margin-top:16px;font-size:14px;line-height:24px;color:#16794d}footer{margin-top:28px;padding-top:20px;border-top:1px solid #e8ece9}footer h3{font-size:15px}footer p{font-size:13px;line-height:23px;color:#79817c}.preview-empty{padding:24px 0;font-size:14px;line-height:24px;color:#909399}
</style>
