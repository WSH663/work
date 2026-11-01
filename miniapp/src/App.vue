<script setup lang="ts">
import { onLaunch, onShow } from '@dcloudio/uni-app'
import { initializeAccountSession } from './account-session'
import { beginAppVisit } from './app-visit'
onLaunch(initializeAccountSession)
onShow(beginAppVisit)
</script>
<style lang="scss">
page,body,uni-page-body { background:#f5f6f7; color:#1f2329; font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Microsoft YaHei',sans-serif; font-size:30rpx; line-height:1.5; }
view,text,button,input,textarea,uni-view,uni-text,uni-button,uni-input,uni-textarea { box-sizing:border-box; font-family:inherit; }
button,uni-button { margin:0; padding:20rpx 32rpx; min-height:88rpx; border-radius:8rpx; font-size:30rpx; line-height:1.5; font-weight:500; }
button::after,uni-button::after { border:0; }
.app-button { margin:0; min-height:44px; font-size:15px; line-height:22px; border-radius:8px; font-family:inherit; }
.app-button::after { border:0; }
.app-button[disabled] { cursor:default; }
.app-button:active:not([disabled]) { opacity:.82; }
.app-button:focus-visible { outline:3px solid #88c4a8;outline-offset:3px; }
.page { padding:24rpx 32rpx 48rpx; min-height:100vh; padding-bottom:calc(48rpx + env(safe-area-inset-bottom)); max-width:760px;margin:0 auto; }
.card,.panel { background:#fff; border:1px solid #e9ecef;border-radius:12px; padding:0 32rpx; margin-bottom:24rpx; overflow:hidden; }
.page-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16rpx;padding:12rpx 0 28rpx}.page-heading-main{flex:1;min-width:0}.page-heading-title{display:block;font-size:40rpx;line-height:1.4;font-weight:600;color:#1f2329}.page-heading-note{display:block;font-size:26rpx;line-height:1.7;color:#646a73;margin-top:8rpx}.page-heading .icon-button{flex-shrink:0;margin-top:-8rpx}
.inline-alert{display:flex;align-items:center;gap:16rpx;padding:20rpx 24rpx;border:1px solid #e3d3b4;background:#fffaf0;border-radius:8px;color:#735c23;font-size:26rpx;line-height:1.7;margin-bottom:24rpx}.inline-alert>text{flex:1;min-width:0}.inline-alert .plain{flex-shrink:0}.form-actions{display:flex;gap:16rpx;padding:24rpx 0 32rpx}.form-actions>button{flex:1;min-width:0}.section-caption{display:block;font-size:24rpx;color:#646a73;padding:8rpx 0 16rpx}.button-row{width:100%;border-radius:0;background:transparent;text-align:left;font-weight:400}
.panel-body { padding:32rpx 0; }
.title { display:block; font-size:40rpx; line-height:1.4; font-weight:600; margin-bottom:24rpx; letter-spacing:0; }
.section-title,.section-heading,.form-heading { font-size:32rpx; font-weight:600; line-height:1.5; display:block; margin:0; }
.section-header { display:flex; align-items:center; justify-content:space-between; min-height:104rpx; border-bottom:1rpx solid #eff0f1; gap:16rpx; }
.subtitle,.muted,.hint { color:#646a73; line-height:1.6; font-size:26rpx; }
.subtitle { display:block; margin-top:8rpx; }
.hint { display:block; padding:16rpx 0; }
.row { display:flex; align-items:center; justify-content:space-between; gap:16rpx; }
.column { display:flex; flex-direction:column; gap:16rpx; }
.primary { color:#fff; background:#16845b; }
.secondary { color:#1f2329; background:#fff; border:1rpx solid #d0d3d6; }
.danger { background:#fff; color:#b3261e; border:1rpx solid #d0d3d6; }
.plain { background:transparent; color:#16845b; padding:16rpx 0; min-height:88rpx; font-size:28rpx; border-radius:0; }
.primary[disabled],.secondary[disabled] { opacity:.45; }
.pill { display:inline-block; font-size:26rpx; color:#646a73; white-space:nowrap; background:transparent; padding:0; }
.dev-note { margin:0 0 24rpx; padding:16rpx 24rpx; font-size:24rpx; line-height:1.6; color:#646a73; background:#eff0f1; border-radius:4rpx; }
.field { border-bottom:1rpx solid #eff0f1; padding:24rpx 0; }
.field:last-child { border-bottom:0; }
.label { font-size:26rpx; color:#646a73; display:block; margin-bottom:8rpx; }
input { width:100%; height:64rpx; min-height:64rpx; font-size:30rpx; color:#1f2329; }
textarea { width:100%; font-size:30rpx; line-height:1.6; min-height:160rpx; }
.value { font-size:30rpx; min-height:64rpx; display:flex; align-items:center; line-height:1.6; word-break:break-all; }
.placeholder { color:#8f959e; }
.empty { padding:64rpx 16rpx; text-align:center; color:#646a73; line-height:1.7; }
.empty-title { display:block; color:#1f2329; font-size:30rpx; margin:16rpx 0 8rpx; font-weight:500; }
.empty button { max-width:360rpx; margin:32rpx auto 0; }
.amount { font-size:48rpx; font-weight:600; color:#1f2329; font-variant-numeric:tabular-nums; }
.spaced { margin-top:24rpx; }
.menu-row { padding:24rpx 0; border-bottom:1rpx solid #eff0f1; display:flex; justify-content:space-between; align-items:center; min-height:104rpx; gap:16rpx; }
.menu-row:last-child { border-bottom:0; }
.menu-label { flex:1; min-width:0; }
.menu-sub { display:block; font-size:26rpx; color:#646a73; margin-top:4rpx; line-height:1.6; }
.icon-button { min-width:88rpx; min-height:88rpx; display:flex; align-items:center; justify-content:center; background:transparent; padding:16rpx; }
.tabs { display:flex; width:100%; background:white; border-bottom:1rpx solid #dee0e3; }
.button-row.tab { text-align:center; }
.tab { flex:1; text-align:center; padding:24rpx 8rpx; min-height:96rpx; font-size:30rpx; border-bottom:4rpx solid transparent; white-space:nowrap; }
.tab.active { color:#16845b; border-bottom-color:#16845b; font-weight:600; }
.detail { display:flex; gap:24rpx; padding:16rpx 0; line-height:1.7; }
.detail>.label { flex-shrink:0; width:144rpx; margin:0; padding-top:2rpx; }
.detail>.detail-value { flex:1; min-width:0; word-break:break-all; }
.bottom-actions { background:#fff; border-top:1rpx solid #dee0e3; padding:24rpx 32rpx calc(24rpx + env(safe-area-inset-bottom)); margin:0 -32rpx -48rpx; }
.bottom-actions .row>button { flex:1; }
.page .booking-actions,.page .order-actions,.page .confirm-place-bar { max-width:760px;margin-left:auto;margin-right:auto; }
.senior { font-size:36rpx; color:#1f2329; }
.senior .title { font-size:46rpx; }
.senior .page-heading-title{font-size:46rpx}.senior .page-heading-note,.senior .inline-alert,.senior .section-caption{font-size:32rpx}
.senior .section-title,.senior .section-heading,.senior .form-heading { font-size:38rpx; }
.senior .subtitle,.senior .muted,.senior .label,.senior .hint,.senior .menu-sub { font-size:32rpx; color:#41464d; }
.senior .value,.senior input,.senior textarea { font-size:36rpx; color:#1f2329; min-height:88rpx; }
.senior button,.senior uni-button,.senior .tab { font-size:36rpx; min-height:96rpx; }
.senior .app-button { font-size:18px; line-height:26px; min-height:48px; }
.senior .primary { background:#075a35; }
.senior .pill,.senior .dev-note { font-size:30rpx; }
.senior .menu-row { min-height:112rpx; }
.senior .detail>.label { width:168rpx; }
</style>
