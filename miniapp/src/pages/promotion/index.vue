<script setup lang="ts">
import { computed,ref,watch } from 'vue'
import { onShow,onHide,onUnload,onShareAppMessage } from '@dcloudio/uni-app'
import { api,ApiError,confirm,copyText,dateText,errorToast,go,money,requireLogin,type Data } from '../../api'
import { devEnabled, pageClass } from '../../store'
import AppIcon from '../../components/AppIcon.vue'
declare const wx: { env: { USER_DATA_PATH: string }; getFileSystemManager(): { writeFile(options: { filePath: string; data: string; encoding: 'base64'; success: () => void; fail: (error: unknown) => void }): void; unlink(options: { filePath: string; success?: () => void; fail?: () => void }): void } }
const promotion=ref<Data|null>(null),loading=ref(false),error=ref(''),tab=ref('commissions'),showForm=ref(false),amount=ref(''),accountName=ref(''),accountNo=ref(''),busy=ref(false),qrLoading=ref(false),qrError=ref(''),qrImage=ref(''),qrSaving=ref(false)
const authRequired=ref(false),balanceFresh=ref(false),ownerId=ref(''),albumPermissionDenied=ref(false)
type WithdrawalAttempt={fingerprint:string;key:string;ownerId:string}
const attempt=ref<WithdrawalAttempt|null>(null)
let alive=true,visible=false,requestId=0,qrRequestId=0,loadedSession='',requestKey='',requestFingerprint='',requestOwner=''
const minimum=computed(()=>Number(promotion.value?.withdrawalMinimum||0))
const pendingRetry=computed(()=>!!ownerId.value&&attempt.value?.ownerId===ownerId.value&&attempt.value?.fingerprint===JSON.stringify(withdrawalBody()))
const tabs=[{key:'commissions',name:'佣金明细'},{key:'relations',name:'邀请关系'},{key:'withdrawals',name:'提现记录'}]
const labels:Record<string,string>={PENDING:'待审核',APPROVED:'已审核待付款',REJECTED:'已拒绝',PAID:'已付款',SETTLED:'已结算',FROZEN:'冻结中',REVERSED:'已退回',PENDING_REVIEW:'待审核'}
const commissionLabels:Record<string,string>={PENDING:'待结算',SETTLED:'已结算',REVERSED:'已撤销／冲回',FROZEN:'冻结中'}
function recordStatus(status:string){return (tab.value==='commissions'?commissionLabels:labels)[status]||status||'已记录'}
function current(token:string){return alive&&token===String(uni.getStorageSync('travel_token')||'')}
function resetQr(){qrRequestId++;qrLoading.value=false;qrError.value='';qrImage.value='';albumPermissionDenied.value=false}
function needLogin(){authRequired.value=true;promotion.value=null;balanceFresh.value=false;error.value='';amount.value='';accountName.value='';accountNo.value='';showForm.value=false;attempt.value=null;requestKey='';requestFingerprint='';requestOwner='';resetQr()}
function clearAttempt(){if(uni.getStorageSync('travel_withdrawal_attempt')?.key===requestKey)uni.removeStorageSync('travel_withdrawal_attempt');if(attempt.value?.key===requestKey)attempt.value=null;requestKey='';requestFingerprint='';requestOwner=''}
function readAttempt():WithdrawalAttempt|null{const value=uni.getStorageSync('travel_withdrawal_attempt');return value&&typeof value.ownerId==='string'&&typeof value.fingerprint==='string'&&typeof value.key==='string'&&/^[A-Za-z0-9_-]{1,64}$/.test(value.key)?value:null}
function withdrawalBody(){return {amount:Number(amount.value),accountName:accountName.value.trim(),accountNo:accountNo.value.trim()}}
function pendingKeyFor(fingerprint:string){if(!ownerId.value)return '';if(requestKey&&requestOwner===ownerId.value&&requestFingerprint===fingerprint)return requestKey;const previous=readAttempt();attempt.value=previous;if(previous?.ownerId!==ownerId.value||previous.fingerprint!==fingerprint)return '';requestKey=previous.key;requestFingerprint=fingerprint;requestOwner=ownerId.value;return requestKey}
function keyFor(fingerprint:string){const previous=pendingKeyFor(fingerprint);if(previous)return previous;requestKey=`withdrawal-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,14)}`;requestFingerprint=fingerprint;requestOwner=ownerId.value;attempt.value={fingerprint,key:requestKey,ownerId:ownerId.value};uni.setStorageSync('travel_withdrawal_attempt',attempt.value);return requestKey}
watch([amount,accountName,accountNo],()=>{if(requestKey)clearAttempt()},{flush:'sync'})
async function load(allowBusy=false){
  if(!alive||!visible||(busy.value&&!allowBusy))return
  const token=String(uni.getStorageSync('travel_token')||''),version=++requestId
  if(!token){needLogin();loading.value=false;return}
  if(loadedSession!==token){promotion.value=null;balanceFresh.value=false;resetQr();loadedSession=token}
  loading.value=true;error.value='';authRequired.value=false
  try{const result=await api('/me/promotion');if(current(token)&&visible&&version===requestId){promotion.value=result;balanceFresh.value=true;void loadQr(token)}}
  catch(e){if(alive&&visible&&version===requestId){if(e instanceof ApiError&&e.status===401&&!uni.getStorageSync('travel_token'))needLogin();else if(current(token))error.value=e instanceof Error?e.message:'推广信息加载失败'}}
  finally{if(alive&&version===requestId)loading.value=false}
}
async function loadQr(token = String(uni.getStorageSync('travel_token') || '')) {
  if (!alive || !visible || qrLoading.value || !token || !promotion.value || qrImage.value) return
  const version=++qrRequestId
  qrLoading.value = true; qrError.value = ''
  try {
    const result = await api<Data>('/me/promotion/qrcode')
    if(!current(token)||!visible||version!==qrRequestId)return
    if (typeof result?.image==='string'&&/^data:image\/png;base64,[A-Za-z0-9+/=\r\n]+$/.test(result.image)) qrImage.value = result.image
    else qrError.value = '二维码内容暂不可用，请使用邀请码或微信转发。'
  } catch (e) {
    if(!alive||!visible||version!==qrRequestId)return
    if (e instanceof ApiError && e.status === 401 && !uni.getStorageSync('travel_token')) { needLogin(); return }
    if(current(token))qrError.value = e instanceof Error ? e.message : '二维码暂时生成失败，请稍后重试'
  }
  finally { if(alive&&version===qrRequestId)qrLoading.value = false }
}
onShow(()=>{visible=true;const token=String(uni.getStorageSync('travel_token')||''),nextOwner=token?String(uni.getStorageSync('travel_user')?.id||''):'';if(loadedSession&&loadedSession!==token){requestKey='';requestFingerprint='';requestOwner='';amount.value='';accountName.value='';accountNo.value='';showForm.value=false}ownerId.value=nextOwner;attempt.value=readAttempt();void load()});onHide(()=>{visible=false;requestId++;qrRequestId++;loading.value=false;qrLoading.value=false});onUnload(()=>{alive=false;requestId++;qrRequestId++})
function sharePath(){
  const inviteCode=String(promotion.value?.inviteCode||'').trim()
  return inviteCode?'/pages/index/index?inviteCode='+encodeURIComponent(inviteCode):'/pages/index/index'
}

onShareAppMessage(()=>({title:'预约出行，邀请您一起安心出发',path:sharePath(),imageUrl:'/static/brand-logo.png'}))
function copy(){copyText(String(promotion.value?.inviteCode||''),'邀请码已复制')}
function copyLink(){
  // #ifdef H5
  if(promotion.value?.inviteCode)copyText(window.location.origin+window.location.pathname+'#/pages/index/index?inviteCode='+encodeURIComponent(promotion.value.inviteCode),'邀请链接已复制')
  // #endif
}
function qrBase64(){
  const value=String(qrImage.value||'');const comma=value.indexOf(',')
  return comma>=0?value.slice(comma+1):value
}
async function copyQr(){
  if(!qrImage.value)return errorToast(new Error('二维码还未生成，请先检查二维码'))
  // Browsers can copy the actual PNG. Mini-programs do not expose image clipboard
  // access, so copy the invite code as the reliable shareable fallback.
  // #ifdef H5
  try{
    const clipboard=(navigator as Navigator & {clipboard?:Clipboard}).clipboard
    const ClipboardItemCtor=(globalThis as typeof globalThis & {ClipboardItem?:typeof ClipboardItem}).ClipboardItem
    if(clipboard?.write&&ClipboardItemCtor){const blob=await fetch(qrImage.value).then(response=>response.blob());await clipboard.write([new ClipboardItemCtor({'image/png':blob})]);uni.showToast({title:'二维码已复制',icon:'none'});return}
  }catch(_){/* fall through to invite code */}
  // #endif
  copyText(String(promotion.value?.inviteCode||''),'邀请码已复制，可在微信中使用')
}
async function saveQr(){
  if(qrSaving.value)return
  if(!qrImage.value)return errorToast(new Error('二维码还未生成，请先检查二维码'))
  qrSaving.value=true
  let temporaryFile=''
  try{
    // #ifdef H5
    const blob=await fetch(qrImage.value).then(response=>response.blob());const url=URL.createObjectURL(blob);const anchor=document.createElement('a');anchor.href=url;anchor.download='预约出行-推广二维码.png';anchor.click();setTimeout(()=>URL.revokeObjectURL(url),1000);uni.showToast({title:'二维码已下载',icon:'none'});return
    // #endif
    // #ifdef MP-WEIXIN
    if(typeof wx==='undefined'||!wx.getFileSystemManager||!wx.env?.USER_DATA_PATH)throw new Error('当前微信版本不支持保存二维码')
    const settings=await new Promise<UniNamespace.GetSettingSuccessResult>((resolve,reject)=>uni.getSetting({success:resolve,fail:reject}))
    if(settings.authSetting?.['scope.writePhotosAlbum']===false){albumPermissionDenied.value=true;throw new Error('请先开启相册权限，再保存二维码')}
    const fileManager=wx.getFileSystemManager()
    temporaryFile=`${wx.env.USER_DATA_PATH}/promotion-qr-${String(ownerId.value||'current').replace(/[^A-Za-z0-9_-]/g,'')}.png`
    await new Promise<void>((resolve,reject)=>fileManager.writeFile({filePath:temporaryFile,data:qrBase64(),encoding:'base64',success:()=>resolve(),fail:reject}))
    await new Promise<void>((resolve,reject)=>uni.saveImageToPhotosAlbum({filePath:temporaryFile,success:()=>resolve(),fail:reject}))
    albumPermissionDenied.value=false
    uni.showToast({title:'二维码已保存到相册',icon:'none'})
    return
    // #endif
  }catch(e){
    const message=e instanceof Error?e.message:String((e as {errMsg?:string})?.errMsg||'')
    if(albumPermissionDenied.value||/auth deny|authorize|permission|权限|system deny/i.test(message)){albumPermissionDenied.value=true;errorToast(new Error('未获得相册权限，请点击下方“开启相册权限”后重试'))}
    else if(/cancel|取消/i.test(message))errorToast(new Error('已取消保存二维码'))
    else errorToast(new Error(message||'二维码保存失败，请长按图片保存'))
  }
  finally{
    // #ifdef MP-WEIXIN
    if(temporaryFile&&typeof wx!=='undefined')await new Promise<void>(resolve=>wx.getFileSystemManager().unlink({filePath:temporaryFile,success:resolve,fail:resolve}))
    // #endif
    qrSaving.value=false
  }
}
function openAlbumSettings(){uni.openSetting({success:result=>{albumPermissionDenied.value=!result.authSetting?.['scope.writePhotosAlbum'];uni.showToast({title:albumPermissionDenied.value?'请开启“保存到相册”权限':'相册权限已开启，请重新保存',icon:'none'})},fail:()=>errorToast(new Error('暂时无法打开权限设置，请在微信小程序设置中允许保存到相册'))})}
function qrImageError(){qrImage.value='';qrError.value='小程序码图片暂时无法显示，请点击“检查二维码”重试。'}
async function withdraw(){
  if(busy.value||!requireLogin())return
  const token=String(uni.getStorageSync('travel_token')||'');ownerId.value=String(uni.getStorageSync('travel_user')?.id||'')
  const body=withdrawalBody(),fingerprint=JSON.stringify(body),retryKey=pendingKeyFor(fingerprint)
  if(!retryKey){if(!balanceFresh.value)return errorToast(new Error('请先刷新余额后申请提现'));if(!promotion.value||Number(promotion.value.availableAmount)<=0)return errorToast(new Error('暂无可提现金额，请等待佣金结算'));if(!/^\d+(\.\d{1,2})?$/.test(amount.value)||body.amount<=0||body.amount>Number(promotion.value.availableAmount))return errorToast(new Error('请输入不超过可提现金额的有效金额'));if(body.amount<minimum.value)return errorToast(new Error(`最低提现金额为 ¥${money(minimum.value)}`));if(!body.accountName||!body.accountNo)return errorToast(new Error('请填写收款人和收款账号'))}
  busy.value=true;requestId++;loading.value=false
  try{
    const approved=await confirm(retryKey?'核对上次提现申请':'确认提现申请',retryKey?'上次申请尚未确认结果，将使用原提交标识核对。若上次未创建，会按当前余额及提现规则提交。':`提现金额 ¥${money(body.amount)}\n收款人：${body.accountName}\n收款账号尾号：${body.accountNo.slice(-4)}\n请确认信息无误，提交后等待平台审核。`)
    if(!approved||!current(token))return
    if(JSON.stringify(withdrawalBody())!==fingerprint)return errorToast(new Error('提现信息已变化，请重新核对'))
    const key=retryKey||keyFor(fingerprint),result=await api('/me/withdrawals','POST',{...body,requestKey:key})
    if(!current(token))return
    if(promotion.value?.withdrawals&&result?.id)promotion.value.withdrawals=[result,...promotion.value.withdrawals.filter((item:Data)=>item.id!==result.id)]
    clearAttempt();showForm.value=false;amount.value='';accountName.value='';accountNo.value='';tab.value='withdrawals';balanceFresh.value=false
    if(visible){uni.showToast({title:retryKey?'已核对申请，请查看记录':'申请已提交',icon:'none'});await load(true)}
  }catch(e){if(alive){if(e instanceof ApiError&&e.status<500){clearAttempt();if(e.status===401&&!uni.getStorageSync('travel_token'))needLogin();else if(current(token)){errorToast(e);balanceFresh.value=false;await load(true)}}else if(current(token))errorToast(new Error('申请结果尚未确认，请核对上次申请，避免重复提现'))}}
  finally{if(alive){busy.value=false;if(visible&&!current(token))void load()}}
}
</script>
<template>
<view class="page promotion-page" :class="pageClass()">
  <view class="page-heading"><view class="page-heading-main"><text class="page-heading-title">推广与佣金</text><text class="page-heading-note">查看邀请关系、结算进度与提现记录</text></view><button class="app-button icon-button" aria-label="刷新推广信息" :disabled="loading||busy" @click="load()"><AppIcon name="refresh" :size="20" color="muted"/></button></view>
  <view v-if="authRequired" class="panel empty"><text class="empty-title">登录后查看推广信息</text><text>登录后可查看专属邀请码、佣金与提现记录。</text><button class="app-button primary" @click="go('/pages/login/index')">登录 / 注册</button></view>
  <view v-else-if="loading&&!promotion" class="panel empty">正在加载推广信息…</view>
  <view v-else-if="error&&!promotion" class="panel empty"><text class="empty-title">推广信息加载失败</text><text>{{error}}</text><button class="app-button secondary" @click="load()">重新加载</button></view>
  <template v-else-if="promotion">
    <view v-if="error" class="inline-alert"><text>{{error}}，当前显示上次获取的内容。</text><button class="app-button plain" :disabled="loading||busy" @click="load()">重试</button></view>
    <view class="panel balance-panel"><view class="panel-body"><text class="label">可提现金额（元）</text><view class="balance-line"><text class="balance">{{money(promotion.availableAmount)}}</text><button class="app-button primary withdraw-button" :disabled="busy" @click="showForm=!showForm">{{showForm?'收起':'提现'}}</button></view><view class="balance-totals"><view><text class="muted">待结算</text><text class="total-number">¥{{money(promotion.pendingAmount)}}</text></view><view><text class="muted">提现冻结</text><text class="total-number">¥{{money(promotion.frozenAmount)}}</text></view><view><text class="muted">累计佣金</text><text class="total-number">¥{{money(promotion.totalCommission)}}</text></view></view><text class="hint settlement-rule">订单完成30天后且未退款转可提现，期间退款不结算。</text></view></view>
    <view v-if="showForm" class="panel withdrawal-form"><view class="section-header"><text class="section-title">提现申请</text><button class="app-button icon-button" aria-label="收起提现申请" :disabled="busy" @click="showForm=false"><AppIcon name="close" :size="20" color="muted"/></button></view><view class="field"><text class="label">提现金额（元）</text><input v-model="amount" :disabled="busy" type="digit" maxlength="12" placeholder="请输入提现金额，最多两位小数"/></view><view class="field"><text class="label">收款人姓名</text><input v-model="accountName" :disabled="busy" maxlength="30" placeholder="请输入真实收款人姓名"/></view><view class="field"><text class="label">收款账号</text><input v-model="accountNo" :disabled="busy" maxlength="100" placeholder="请输入与平台确认的收款账号"/></view><text class="hint">平台审核后登记付款{{minimum>0?'，最低提现金额 ¥'+money(minimum):''}}。</text><text v-if="pendingRetry" class="hint pending-withdrawal-note">上次申请尚未确认结果，请先“核对上次申请”，避免重复提现。</text><text v-else-if="Number(promotion.availableAmount)<=0" class="hint">暂无可提现金额，待结算佣金满足条件后可申请提现。</text><text v-else-if="!balanceFresh" class="hint">余额信息尚未更新，请刷新后申请提现。</text><view class="form-actions"><button class="app-button secondary" :disabled="busy" @click="showForm=false">收起</button><button class="app-button primary withdrawal-submit" :loading="busy" :disabled="busy||(!pendingRetry&&(!balanceFresh||Number(promotion.availableAmount)<=0))" @click="withdraw">{{pendingRetry?'核对上次申请':Number(promotion.availableAmount)<=0?'暂无可提现金额':'提交提现申请'}}</button></view></view>
    <view class="panel invitation-panel">
      <view class="section-header"><text class="section-title">我的邀请</text></view>
      <view class="invitation-row"><view><text class="label">专属邀请码</text><text class="invite-code">{{promotion.inviteCode}}</text></view><button class="app-button icon-button" aria-label="复制邀请码" @click="copy"><AppIcon name="copy" :size="20" color="muted"/></button></view>
      <view class="invite-actions"><button class="app-button secondary" @click="copy">复制邀请码</button><!-- #ifdef MP-WEIXIN --><button class="app-button secondary" open-type="share">分享邀请</button><!-- #endif --><!-- #ifndef MP-WEIXIN --><button class="app-button secondary" @click="copyLink">复制邀请链接</button><!-- #endif --></view>
      <text class="hint">好友通过邀请注册，或填写邀请码后建立推广关系。</text>
      <view class="qrcode-section">
        <view class="qrcode-heading"><view class="qrcode-heading-main"><image class="qrcode-brand-logo" src="/static/brand-logo.png" mode="aspectFit"/><view><text class="qrcode-title">微信小程序码</text><text class="qrcode-note">好友扫码即可进入小程序，自动带上你的推广关系</text></view></view></view>
        <view class="qrcode-content">
          <image v-if="qrImage" class="qrcode-image" :src="qrImage" mode="aspectFit" show-menu-by-longpress @error="qrImageError" />
          <view v-else-if="qrLoading" class="qrcode-placeholder"><text class="qrcode-placeholder-title">正在生成小程序码</text><text class="hint">请稍候…</text></view>
          <view v-else class="qrcode-placeholder"><text class="qrcode-placeholder-title">暂未生成小程序码</text><text class="hint">当前可复制邀请码或邀请链接，微信端也可直接转发。微信资料配置完成后点击“检查二维码”即可生成。</text><text v-if="qrError" class="qrcode-error">{{qrError}}</text><button class="app-button plain qrcode-retry" :disabled="qrLoading" @click="loadQr()">检查二维码</button></view>
        </view>
        <view v-if="qrImage" class="qrcode-actions">
          <!-- #ifdef H5 --><button class="app-button secondary" @click="copyQr"><AppIcon name="copy" :size="18" color="muted"/><text>复制二维码</text></button><!-- #endif -->
          <!-- #ifdef MP-WEIXIN --><button class="app-button secondary" @click="copyQr"><AppIcon name="copy" :size="18" color="muted"/><text>复制邀请码</text></button><!-- #endif -->
          <button class="app-button primary" :loading="qrSaving" :disabled="qrSaving" @click="saveQr"><text>{{qrSaving?'保存中…':'下载二维码'}}</text></button>
        </view>
        <!-- #ifdef H5 --><text v-if="qrImage" class="qrcode-save-note">可复制二维码图片，或下载 PNG 文件保存。</text><!-- #endif -->
        <!-- #ifdef MP-WEIXIN --><text v-if="qrImage" class="qrcode-save-note">可长按二维码或点击“下载二维码”保存到相册；“复制邀请码”用于把推广关系分享给好友。</text><!-- #endif -->
        <!-- #ifdef MP-WEIXIN --><view v-if="albumPermissionDenied" class="album-permission-alert"><text>保存二维码需要相册权限，请开启后重试。如仍失败，请检查手机系统中的微信相册权限。</text><button class="app-button plain" :disabled="qrSaving" @click="openAlbumSettings">开启相册权限</button></view><!-- #endif -->
      </view>
    </view>
    <view class="detail-tabs"><button v-for="item in tabs" :key="item.key" class="app-button button-row tab" :class="{active:tab===item.key}" @click="tab=item.key">{{item.name}}</button></view>
    <view class="panel"><view v-if="!promotion[tab]?.length" class="empty"><text class="empty-title">暂无{{tabs.find(item=>item.key===tab)?.name}}</text><text>{{tab==='commissions'?'有效推广订单完成后，将在这里记录结算进度':tab==='relations'?'分享专属邀请码，建立邀请关系后可在这里查看':'提交提现申请后，可在这里查看审核与付款进度'}}</text></view><view v-for="item in promotion[tab]" :key="item.id" class="record-row"><template v-if="tab==='relations'"><text>{{item.nickname||item.userNickname||'受邀用户'}}<text v-if="item.phone"> · {{item.phone}}</text></text><text class="subtitle">{{dateText(item.createdAt)}}</text></template><template v-else><view class="row"><text class="record-amount">{{tab==='commissions'&&item.status!=='REVERSED'?'+':''}}¥{{money(item.amount)}}</text><text class="muted">{{recordStatus(item.status)}}</text></view><text class="subtitle">{{tab==='commissions'?'订单 '+(item.orderNo||item.orderId||'—'):item.accountName||'提现申请'}} · {{dateText(item.createdAt)}}</text><text v-if="tab==='commissions'&&item.status==='PENDING'" class="subtitle">{{item.eligibleAt?'预计结算时间：'+dateText(item.eligibleAt):'订单完成30天后且未退款可结算'}}</text><text v-if="item.remark" class="subtitle">{{item.remark}}</text><text v-if="item.paymentReference" class="subtitle">付款流水：{{item.paymentReference}}</text></template></view></view>
    <text v-if="devEnabled" class="dev-note">开发环境佣金与提现仅供流程测试，不发生真实付款。</text>
  </template>
</view>
</template>
<style scoped>
.album-permission-alert{margin-top:16rpx;padding:20rpx 24rpx;background:#fff8ed;font-size:24rpx;line-height:1.6;color:#8a5c22}.album-permission-alert button{margin-top:8rpx;padding:12rpx 0;min-height:72rpx;color:#16845b}.senior .album-permission-alert{font-size:30rpx}
.balance-panel .balance-totals>view{width:33.333%;min-width:0}.balance-totals .muted{font-size:24rpx}.settlement-rule{margin-top:24rpx}.senior .balance-totals{flex-wrap:wrap;gap:24rpx}.senior .balance-totals>view{width:calc(50% - 12rpx)}.senior .balance-totals .muted{font-size:30rpx}
.balance-line{display:flex;align-items:center;justify-content:space-between;gap:16rpx}.balance{font-size:56rpx;font-weight:600;line-height:1.4;font-variant-numeric:tabular-nums}.withdraw-button{padding:16rpx 32rpx;min-width:160rpx}.balance-totals{display:flex;border-top:1rpx solid #eff0f1;margin-top:32rpx;padding-top:24rpx}.balance-totals>view{width:50%}.total-number{display:block;font-size:30rpx;margin-top:8rpx}.form-submit{margin:16rpx 0 32rpx}.invitation-row{display:flex;align-items:center;justify-content:space-between;padding:24rpx 0 16rpx}.invite-code{font-size:36rpx;font-weight:500;letter-spacing:2rpx}.invite-actions{display:flex;gap:16rpx}.invite-actions button{flex:1;font-size:28rpx;padding:16rpx 8rpx}.detail-tabs{display:flex;margin-bottom:0;background:#fff;border-bottom:1rpx solid #dee0e3;border-radius:8rpx 8rpx 0 0}.detail-tabs+.panel{border-radius:0 0 8rpx 8rpx}.record-row{padding:28rpx 0;border-bottom:1rpx solid #eff0f1;font-size:28rpx;line-height:1.6}.record-row:last-child{border-bottom:0}.record-amount{font-size:32rpx;font-weight:500}.senior .invite-actions button,.senior .record-row{font-size:32rpx}.senior .invite-code{font-size:42rpx}
.qrcode-section{margin-top:32rpx;padding-top:28rpx;border-top:1rpx solid #eff0f1}.qrcode-heading{display:flex;align-items:flex-start;justify-content:space-between}.qrcode-heading-main{display:flex;align-items:center;gap:16rpx}.qrcode-brand-logo{width:56rpx;height:56rpx;border-radius:8rpx;background:#fff}.qrcode-title{display:block;font-size:30rpx;font-weight:500;color:#1f2329}.qrcode-note{display:block;margin-top:8rpx;font-size:24rpx;line-height:1.5;color:#7b818a}.qrcode-content{display:flex;justify-content:center;align-items:center;min-height:320rpx;margin-top:20rpx;background:#f7f8f9;border:1rpx solid #e5e7eb;border-radius:6rpx}.qrcode-image{width:300rpx;height:300rpx;padding:12rpx;background:#fff}.qrcode-placeholder{display:flex;align-items:center;justify-content:center;width:100%;min-height:260rpx;padding:32rpx;box-sizing:border-box;text-align:center;flex-direction:column}.qrcode-placeholder-title{font-size:28rpx;color:#30343b}.qrcode-placeholder .hint{max-width:520rpx;margin-top:12rpx;line-height:1.6}.qrcode-error{margin-top:12rpx;font-size:24rpx;line-height:1.5;color:#c45656}.qrcode-retry{margin-top:20rpx;min-width:180rpx}.qrcode-actions{display:flex;gap:16rpx;margin-top:20rpx}.qrcode-actions button{flex:1;display:flex;align-items:center;justify-content:center;gap:8rpx}.qrcode-save-note{display:block;margin-top:12rpx;font-size:22rpx;line-height:1.5;color:#7b818a}.senior .qrcode-title,.senior .qrcode-placeholder-title{font-size:34rpx}.senior .qrcode-note,.senior .qrcode-placeholder .hint,.senior .qrcode-error,.senior .qrcode-save-note{font-size:30rpx}
</style>
