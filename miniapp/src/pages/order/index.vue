<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow, onHide, onUnload, onPullDownRefresh } from '@dcloudio/uni-app'
import AppIcon from '../../components/AppIcon.vue'
import { api, ApiError, callService, confirmPhoneCall, confirm,copyText,dateText, errorToast, go, money, statusLabels, type Data, type Order } from '../../api'
import { devEnabled, pageClass } from '../../store'
const order=ref<Order|null>(null),id=ref(''),loading=ref(false),busy=ref(false),error=ref(''),refreshError=ref(''),authRequired=ref(false)
let alive=true,visible=false,refreshAfterAction=false,requestId=0,loadedSession='',timer:ReturnType<typeof setTimeout>|undefined
const activeStatuses=['PENDING_QUOTE','PENDING_CONFIRM','PENDING_PAYMENT','PENDING_DISPATCH','DISPATCHED','IN_SERVICE','CANCEL_REQUESTED','REFUNDING']
const canCancel=computed(()=>order.value?.actions?.cancel===true&&order.value.cancelRules?.allowed!==false)
const showDriver=computed(()=>!!order.value?.driverName&&['DISPATCHED','IN_SERVICE'].includes(order.value.status))
const finance=computed(()=>order.value?.finance||{totalPaid:Number(order.value?.paidAmount||0),refunded:0,netPaid:Number(order.value?.paidAmount||0)})
const legacyRefunds=computed<Data[]>(()=>order.value?.payments?.length?[]:order.value?.refunds||[])
const showCancellationPanel=computed(()=>!!order.value&&(['CANCEL_REQUESTED','REFUNDING'].includes(order.value.status)||(!order.value.payments?.length&&(legacyRefunds.value.length>0||order.value.status==='REFUNDED'))))
const amountText=computed(()=>order.value?.type==='CHARTER'&&!Number(order.value.quoteVersion)&&!Number(order.value.amount)?order.value.status==='PENDING_QUOTE'?'待人工报价':'未报价':'¥'+money(order.value?.amount))
const descriptions:Record<string,string>={PENDING_QUOTE:'行程已提交，等待客服确认报价。',PENDING_CONFIRM:'平台已报价，请确认费用后支付。',PENDING_PAYMENT:'支付后平台会为您安排车辆。',PENDING_DISPATCH:'订单已支付，等待平台安排车辆。',DISPATCHED:'车辆已安排，请留意司机联系。',IN_SERVICE:'行程服务中，有需要请联系司机或客服。',COMPLETED:'本次服务已完成。',CANCEL_REQUESTED:'取消申请已提交，等待审核。',CANCELLED:'订单已取消。',REFUNDING:'服务已取消，等待财务办理退款。',REFUNDED:'退款已处理，请查看退款记录。'}
const statusDescription=computed(()=>descriptions[order.value?.status||'']||'下拉刷新查看最新进度。')
function copyOrderNo(){if(order.value)copyText(order.value.orderNo,'订单编号已复制')}
function goOrders(){go('/pages/orders/index')}
function stopTimer(){if(timer)clearTimeout(timer);timer=undefined}
function schedule(){stopTimer();if(alive&&visible&&!busy.value&&order.value&&activeStatuses.includes(order.value.status)&&!authRequired.value)timer=setTimeout(()=>{void load(true)},15000)}
function sessionMatches(token:string){return alive&&token===String(uni.getStorageSync('travel_token')||'')}
function needLogin(){authRequired.value=true;order.value=null;error.value='';refreshError.value='';loadedSession='';stopTimer()}
async function load(background=false,allowBusy=false){
  if(!alive||!visible||(busy.value&&!allowBusy)){uni.stopPullDownRefresh();return}
  const token=String(uni.getStorageSync('travel_token')||''),version=++requestId
  if(!token){needLogin();loading.value=false;uni.stopPullDownRefresh();return}
  if(!/^\d+$/.test(id.value)){error.value='订单编号无效，请从订单列表重新打开';return}
  if(loadedSession&&loadedSession!==token)order.value=null
  loadedSession=token;authRequired.value=false;loading.value=true;if(!background)error.value='';refreshError.value='';stopTimer()
  try{const result=await api<Order>('/orders/'+id.value);if(alive&&visible&&version===requestId&&sessionMatches(token)){order.value=result;error.value=''}}
  catch(e){if(alive&&visible&&version===requestId){if(e instanceof ApiError&&e.status===401&&!uni.getStorageSync('travel_token'))needLogin();else if(sessionMatches(token)){const text=e instanceof Error?e.message:'订单加载失败';if(order.value)refreshError.value=text;else error.value=text}}}
  finally{if(alive&&version===requestId){loading.value=false;schedule()}uni.stopPullDownRefresh()}
}
onLoad(options=>{id.value=options?.id||''});onShow(()=>{visible=true;refreshAfterAction=busy.value;if(loadedSession&&loadedSession!==String(uni.getStorageSync('travel_token')||''))order.value=null;void load()});onHide(()=>{visible=false;requestId++;loading.value=false;stopTimer()});onUnload(()=>{alive=false;requestId++;stopTimer()});onPullDownRefresh(()=>load())
async function lockedAction(operation:(token:string)=>Promise<void>){
  if(busy.value||!order.value||authRequired.value)return
  const token=String(uni.getStorageSync('travel_token')||'');if(!token){needLogin();return}
  busy.value=true;requestId++;loading.value=false;stopTimer();refreshError.value=''
  try{await operation(token)}catch(e){if(alive){if(e instanceof ApiError&&e.status===401&&!uni.getStorageSync('travel_token'))needLogin();else if(sessionMatches(token)){errorToast(e);await load(true,true)}}}
  finally{if(alive){busy.value=false;if(visible&&!sessionMatches(token)){order.value=null;refreshAfterAction=false;void load()}else if(visible&&refreshAfterAction){refreshAfterAction=false;void load(true)}else schedule()}}
}
async function action(path:string,data:Data|undefined,token:string,success:string){
  if(!sessionMatches(token))return
  const version=++requestId,result=await api<Order>('/orders/'+id.value+path,'POST',data)
  if(sessionMatches(token)&&visible&&version===requestId){order.value=result;error.value='';uni.showToast({title:success,icon:'none'})}
}
async function accept(){await lockedAction(async token=>{const snapshot=order.value!;if(!snapshot.actions?.acceptQuote)return;if(!await confirm('确认包车报价',`本次服务报价 ¥${money(snapshot.amount)}${snapshot.quoteRemark?'\n'+snapshot.quoteRemark:''}\n确认后可前往支付。`))return;await action('/accept-quote',{quoteVersion:snapshot.quoteVersion},token,'报价已确认，请核对后支付')})}
async function cancellationReason():Promise<string|null>{
  const reasons=['行程计划变更','预约信息有误','已更换出行安排','其他原因']
  const index=await new Promise<number|null>(resolve=>uni.showActionSheet({itemList:reasons,success:result=>resolve(result.tapIndex),fail:()=>resolve(null)}))
  if(index===null)return null
  if(index<reasons.length-1)return reasons[index]
  return new Promise(resolve=>uni.showModal({title:'填写取消原因',editable:true,placeholderText:'请说明取消原因（最多200字）',success:result=>resolve(result.confirm?String(result.content||'').trim().slice(0,200):null),fail:()=>resolve(null)}))
}
async function cancel(){await lockedAction(async token=>{
  if(!canCancel.value){if(order.value?.cancelRules?.reason)errorToast(new Error(order.value.cancelRules.reason));return}
  const reason=await cancellationReason();if(reason===null)return;if(!reason)return errorToast(new Error('请填写真实取消原因'))
  const paid=finance.value.netPaid>0
  if(!await confirm(paid?'申请取消预约':'取消预约',`${reason}\n${paid?'已支付订单需客服审核，审核通过后由财务退款。':'此订单尚未支付，确认后将直接取消。'}`))return
  await action('/cancel',{reason},token,paid?'取消申请已提交，等待审核':'预约已取消')
})}
async function pay(){await lockedAction(async token=>{
  if(!order.value?.actions?.pay)return
  if(devEnabled&&!await confirm('开发模拟支付',`模拟支付 ¥${money(order.value.amount)}，仅生成开发记录，不会产生真实扣费。`))return
  if(!sessionMatches(token))return
  const version=++requestId,result=await api('/orders/'+id.value+'/pay','POST')
  if(!sessionMatches(token)||!visible||version!==requestId)return
  if(result.order)order.value=result.order
  if(result.mode==='DEV'){uni.showToast({title:'模拟支付完成，等待派单',icon:'none',duration:3000});if(!result.order)await load(true,true)}else if(result.mode==='WECHAT'){
// #ifdef MP-WEIXIN
await new Promise<void>((resolve,reject)=>uni.requestPayment({provider:'wxpay',...result,success:()=>resolve(),fail:failure=>reject(new Error(/cancel|取消/i.test(failure.errMsg||'')?'已取消支付，可稍后继续':'支付未完成，正在核对订单状态'))}));await load(true,true)
// #endif
// #ifndef MP-WEIXIN
throw new Error('请在微信小程序内完成支付')
// #endif
}else throw new Error('支付配置暂不可用，请联系客服')})}
function callDriver(){if(!showDriver.value||!order.value?.driverPhone)return;void confirmPhoneCall(order.value.driverPhone,'联系司机')}
</script>
<template>
<view class="page detail-page" :class="pageClass()">
  <view v-if="authRequired" class="panel empty"><text class="empty-title">登录后查看订单</text><text>登录已失效，请重新登录后继续查看或处理此订单。</text><button class="app-button primary" @click="go('/pages/login/index')">重新登录</button></view>
  <view v-else-if="loading&&!order" class="panel empty">正在加载订单…</view>
  <view v-else-if="error" class="panel empty"><text class="empty-title">订单加载失败</text><text>{{error}}</text><button class="app-button secondary" @click="load()">重新加载</button><button class="app-button plain" @click="goOrders">返回订单列表</button></view>
  <template v-else-if="order">
    <view class="status-section"><view class="row"><text class="status-title">{{statusLabels[order.status]||order.status}}</text><button class="app-button icon-button" aria-label="刷新订单" :disabled="loading||busy" @click="load()"><AppIcon name="refresh" :size="20" color="muted"/></button></view><text class="status-desc">{{order.nextStep||statusDescription}}</text><text v-if="order.appointmentExpired&&['PENDING_QUOTE','PENDING_CONFIRM','PENDING_PAYMENT'].includes(order.status)" class="hint">预约时间已过，请取消此订单后重新预约；需要协助可联系客服。</text><view v-if="refreshError" class="refresh-error"><text>{{refreshError}}，当前显示上次获取的订单。</text><button class="app-button plain" :disabled="busy||loading" @click="load(true)">重试刷新</button></view></view>
    <view class="panel">
      <view class="section-header"><text class="section-title">{{order.type==='CHARTER'?'包车行程':'预约行程'}}</text><text class="muted">{{order.vehicleName}}</text></view>
      <view class="journey-time"><AppIcon name="calendar" :size="20" color="muted"/><text>{{dateText(order.appointmentAt)}}</text></view>
      <view class="route-list"><view class="route-line"><view class="route-mark"/><text>{{order.originName}}</text></view><view class="route-line"><view class="route-mark end"/><text>{{order.destinationName}}</text></view></view>
      <view class="details-group"><view class="detail"><text class="label">乘车人</text><text class="detail-value">{{order.passengerName}} · {{order.passengerPhone}}</text></view><view v-if="order.hours" class="detail"><text class="label">包车时长</text><text class="detail-value">{{order.hours}} 小时</text></view><view v-if="order.distanceKm" class="detail"><text class="label">路线里程</text><text class="detail-value">{{order.distanceKm}} 公里</text></view><view v-if="order.itinerary" class="detail"><text class="label">包车行程</text><text class="detail-value">{{order.itinerary}}</text></view><view v-if="order.remark" class="detail"><text class="label">服务备注</text><text class="detail-value">{{order.remark}}</text></view></view>
    </view>
    <view v-if="showDriver" class="panel"><view class="section-header"><text class="section-title">接送安排</text></view><view class="driver-row"><AppIcon name="user" :size="24" color="muted"/><view class="driver-info"><text>{{order.driverName}}</text><text class="subtitle">{{order.plateNumber}}</text></view><button class="app-button call-driver secondary" @click="callDriver"><AppIcon name="phone" :size="18"/><text>联系司机</text></button></view></view>
    <view class="panel"><view class="section-header"><text class="section-title">费用信息</text></view><view class="details-group"><view class="detail"><text class="label">订单金额</text><text class="detail-value fee">{{amountText}}</text></view><view class="detail"><text class="label">累计支付</text><text class="detail-value">¥{{money(finance.totalPaid)}}</text></view><view class="detail"><text class="label">已退款</text><text class="detail-value">¥{{money(finance.refunded)}}</text></view><view class="detail"><text class="label">当前实收</text><text class="detail-value">¥{{money(finance.netPaid)}}</text></view><view v-if="order.quoteRemark" class="detail"><text class="label">报价说明</text><text class="detail-value">{{order.quoteRemark}}</text></view><text v-if="devEnabled" class="hint">开发环境支付与退款为模拟记录，不发生真实资金交易。</text><text v-if="order.paymentUnavailableReason&&order.status==='PENDING_PAYMENT'" class="hint">{{order.paymentUnavailableReason}}</text></view></view>
    <view v-if="order.payments?.length" class="panel"><view class="section-header"><text class="section-title">支付与退款流水</text></view><view v-for="payment in order.payments" :key="payment.id" class="record-row"><view class="row"><text>{{payment.type==='REFUND'?'退款':'支付'}} ¥{{money(payment.amount)}}</text><text class="muted">{{payment.status==='SUCCESS'?'已完成':payment.status==='FAILED'?'失败':'处理中'}}</text></view><text class="subtitle">{{dateText(payment.createdAt)}}{{payment.mode==='DEV'?' · 模拟交易':''}}</text><text v-if="payment.transactionNo" class="subtitle transaction-number">流水号：{{payment.transactionNo}}</text><text v-if="payment.reason" class="subtitle">{{payment.reason}}</text></view></view>
    <view v-if="showCancellationPanel" class="panel"><view class="section-header"><text class="section-title">取消与退款</text></view><view v-if="['CANCEL_REQUESTED','REFUNDING'].includes(order.status)||!legacyRefunds.length" class="panel-body muted">{{order.status==='CANCEL_REQUESTED'?'取消申请已提交，等待平台审核。':order.status==='REFUNDING'?'服务已取消，等待财务办理退款。':'退款已完成。'}}</view><view v-for="refund in legacyRefunds" :key="refund.id" class="record-row"><view class="row"><text>¥{{money(refund.amount)}}</text><text class="muted">{{refund.status==='SUCCESS'?'退款已完成':refund.status==='FAILED'?'退款失败':'退款处理中'}}</text></view><text class="subtitle">{{dateText(refund.createdAt)}}{{refund.mode==='DEV'?' · 模拟退款':''}}</text></view></view>
    <view class="panel"><view class="section-header"><text class="section-title">订单信息</text></view><view class="details-group"><view class="detail"><text class="label">订单编号</text><text class="detail-value order-number">{{order.orderNo}}</text><button class="app-button copy-button" aria-label="复制订单编号" @click="copyOrderNo"><AppIcon name="copy" :size="18" color="muted"/></button></view><view class="detail"><text class="label">下单时间</text><text class="detail-value">{{dateText(order.createdAt)}}</text></view></view></view>
    <view v-if="order.logs?.length" class="panel"><view class="section-header"><text class="section-title">服务记录</text></view><view v-for="log in order.logs" :key="log.id" class="record-row"><text>{{log.content||log.remark||'订单状态已更新'}}</text><text class="subtitle">{{dateText(log.createdAt)}}</text></view></view>
    <view v-if="order.cancelRules?.reason&&['PENDING_QUOTE','PENDING_CONFIRM','PENDING_PAYMENT','PENDING_DISPATCH','DISPATCHED'].includes(order.status)" class="cancel-rules"><text>{{order.cancelRules.reason}}</text><text v-if="order.cancelRules.cutoffAt" class="subtitle">线上取消截止：{{dateText(order.cancelRules.cutoffAt)}}</text></view>
    <button class="app-button secondary service-button" @click="callService"><AppIcon name="phone" :size="18"/><text>联系平台客服</text></button>
    <view class="order-actions"><text class="action-next-step">{{order.nextStep||statusDescription}}</text><view class="action-buttons"><button v-if="canCancel" class="app-button secondary" :disabled="busy" @click="cancel">{{finance.netPaid>0?'申请取消':'取消预约'}}</button><button v-if="order.actions?.acceptQuote" class="app-button primary" :disabled="busy" :loading="busy" @click="accept">确认报价 ¥{{money(order.amount)}}</button><button v-else-if="order.actions?.pay" class="app-button primary" :disabled="busy" :loading="busy" @click="pay">{{devEnabled?'模拟支付':'立即支付'}} ¥{{money(order.amount)}}</button><button v-else-if="!canCancel" class="app-button secondary" @click="goOrders">查看全部订单</button></view></view>
  </template>
</view>
</template>
<style scoped>
.detail-page.page{padding-bottom:calc(248rpx + env(safe-area-inset-bottom))}.detail-page.senior{padding-bottom:calc(304rpx + env(safe-area-inset-bottom))}.refresh-error{display:flex;align-items:center;gap:16rpx;color:#a12620;font-size:26rpx;line-height:1.6;margin-top:16rpx}.refresh-error>text{flex:1}.refresh-error .plain{flex-shrink:0}.cancel-rules{padding:24rpx 0;color:#646a73;font-size:26rpx;line-height:1.7}.transaction-number{word-break:break-all}.detail-page .order-actions{flex-direction:column;gap:16rpx}.action-next-step{font-size:26rpx;color:#646a73;line-height:1.5}.action-buttons{display:flex;gap:16rpx}.action-buttons button{flex:1;min-width:0}.senior .action-next-step,.senior .cancel-rules,.senior .refresh-error{font-size:32rpx}
.detail-page{padding-bottom:calc(168rpx + env(safe-area-inset-bottom))}.status-section{padding:0 0 24rpx}.status-title{font-size:40rpx;font-weight:600}.status-desc{font-size:28rpx;color:#646a73;line-height:1.6;display:block}.section-header>.muted{max-width:52%;text-align:right}.journey-time{display:flex;align-items:center;gap:16rpx;padding:32rpx 0 16rpx;font-weight:500}.route-list{padding:0 0 24rpx;border-bottom:1rpx solid #eff0f1}.route-line{display:flex;align-items:center;gap:20rpx;padding:12rpx 0}.route-mark{width:12rpx;height:12rpx;border:3rpx solid #646a73;border-radius:50%;flex-shrink:0}.route-mark.end{background:#646a73;border-radius:2rpx}.details-group{padding:16rpx 0}.driver-row{display:flex;align-items:center;gap:16rpx;padding:32rpx 0}.driver-info{flex:1}.call-driver{display:flex;align-items:center;gap:16rpx;padding:16rpx;font-size:26rpx}.fee{font-weight:600}.record-row{padding:24rpx 0;border-bottom:1rpx solid #eff0f1;font-size:28rpx;line-height:1.7}.record-row:last-child{border-bottom:0}.copy-button{display:flex;align-items:center;justify-content:center;width:64rpx;min-height:88rpx;padding:8rpx;background:transparent;margin-top:-12rpx}.order-number{font-size:25rpx}.service-button{display:flex;justify-content:center;align-items:center;gap:16rpx}.order-actions{position:fixed;bottom:0;left:0;right:0;z-index:10;background:#fff;border-top:1rpx solid #dee0e3;padding:24rpx 32rpx calc(24rpx + env(safe-area-inset-bottom));display:flex;gap:16rpx}.order-actions button{flex:1;padding:20rpx 16rpx}.senior .status-title{font-size:46rpx}.senior .status-desc,.senior .record-row{font-size:34rpx}.senior .order-number{font-size:30rpx}
</style>
