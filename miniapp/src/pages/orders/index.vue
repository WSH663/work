<script setup lang="ts">
import { ref } from 'vue'
import { onShow,onHide,onUnload,onPullDownRefresh,onReachBottom } from '@dcloudio/uni-app'
import AppIcon from '../../components/AppIcon.vue'
import { api,ApiError,consumeTabQuery,dateText,go,money,statusLabels,type Order } from '../../api'
import { pageClass } from '../../store'
const items=ref<Order[]>([]),loading=ref(false),authenticated=ref(false),error=ref(''),filter=ref(''),group=ref(''),page=ref(0),total=ref(0)
let requestId=0,alive=true,visible=false,loadedSession='',failedReset=true
const groups=[{key:'',label:'全部'},{key:'awaitingConfirmation',label:'报价确认'},{key:'awaitingPayment',label:'待支付'},{key:'upcoming',label:'待出行'},{key:'inService',label:'服务中'},{key:'completed',label:'已完成'},{key:'cancellation',label:'取消退款'}]
const filters=[{key:'',label:'全部'},{key:'PENDING_QUOTE',label:'待报价'},{key:'PENDING_CONFIRM',label:'待确认'},{key:'PENDING_PAYMENT',label:'待支付'},{key:'PENDING_DISPATCH',label:'待派单'},{key:'DISPATCHED',label:'已派单'},{key:'IN_SERVICE',label:'服务中'},{key:'COMPLETED',label:'已完成'},{key:'REFUNDING',label:'待退款'},{key:'REFUNDED',label:'已退款'},{key:'CANCEL_REQUESTED',label:'取消审核'},{key:'CANCELLED',label:'已取消'}]
async function load(reset=true){
  const token=String(uni.getStorageSync('travel_token')||'')
  authenticated.value=!!token
  if(!token){requestId++;items.value=[];total.value=0;page.value=0;loading.value=false;error.value='';loadedSession='';uni.stopPullDownRefresh();return}
  if(loading.value&&!reset)return
  if(loadedSession!==token){reset=true;items.value=[];total.value=0;page.value=0;loadedSession=token}
  const version=++requestId,requestedPage=reset?1:page.value+1;loading.value=true;error.value=''
  const valid=()=>alive&&visible&&version===requestId&&token===uni.getStorageSync('travel_token')
  try{const result=await api<{items:Order[];total:number}>('/orders?'+(filter.value?'status='+encodeURIComponent(filter.value):'group='+encodeURIComponent(group.value))+'&page='+requestedPage+'&size=15');if(!valid())return;items.value=reset?result.items:[...items.value,...result.items.filter(item=>!items.value.some(existing=>existing.id===item.id))];total.value=result.total;page.value=requestedPage}
  catch(e){if(alive&&visible&&version===requestId){if(e instanceof ApiError&&e.status===401&&!uni.getStorageSync('travel_token')){authenticated.value=false;items.value=[];total.value=0;page.value=0;loadedSession=''}else if(valid()){failedReset=reset;error.value=e instanceof Error?e.message:'订单加载失败'}}}
  finally{if(alive&&version===requestId)loading.value=false;uni.stopPullDownRefresh()}
}
function select(key:string){filter.value=key;group.value='';items.value=[];total.value=0;load()}
function selectGroup(key:string){group.value=key;filter.value='';items.value=[];total.value=0;load()}
function amountText(order:Order){return order.type==='CHARTER'&&!Number(order.quoteVersion)&&!Number(order.amount)?order.status==='PENDING_QUOTE'?'待报价':'未报价':'¥'+money(order.amount)}
onShow(()=>{visible=true;const query=consumeTabQuery('/pages/orders/index'),previousFilter=filter.value,previousGroup=group.value;if(query.status!==undefined&&filters.some(item=>item.key===query.status)){filter.value=query.status;group.value=''}else if(query.group!==undefined&&groups.some(item=>item.key===query.group)){group.value=query.group;filter.value=''}if(previousFilter!==filter.value||previousGroup!==group.value){requestId++;items.value=[];total.value=0;page.value=0;loading.value=false;error.value=''}load()});onHide(()=>{visible=false;requestId++;loading.value=false});onUnload(()=>{alive=false;requestId++});onPullDownRefresh(()=>load());onReachBottom(()=>{if(items.value.length<total.value)load(false)})
</script>
<template>
<view class="page orders-page" :class="pageClass()">
  <view class="page-heading"><view class="page-heading-main"><text class="page-heading-title">我的订单</text><text class="page-heading-note">查看预约、接送安排与费用进度</text></view><button class="app-button icon-button" aria-label="刷新订单列表" :disabled="loading" @click="load()"><AppIcon name="refresh" :size="20" color="muted"/></button></view>
  <scroll-view scroll-x class="filter-scroll"><view class="filter-line"><button v-for="item in groups" :key="item.key" class="app-button button-row filter" :class="{active:!filter&&group===item.key}" @click="selectGroup(item.key)">{{item.label}}</button></view></scroll-view>
  <view class="status-picker"><text class="muted">精确状态</text><picker :range="filters.map(item=>item.label)" :value="Math.max(0,filters.findIndex(item=>item.key===filter))" @change="select(filters[Number($event.detail.value)].key)"><view class="status-picker-value">{{filter?filters.find(item=>item.key===filter)?.label:'全部状态'}}<AppIcon name="chevron-down" :size="16"/></view></picker></view>
  <view class="list-summary"><text>{{filter?filters.find(item=>item.key===filter)?.label:groups.find(item=>item.key===group)?.label}}订单</text><text class="muted">{{total}} 笔</text></view>
  <view v-if="!authenticated" class="panel empty"><AppIcon name="orders" :size="32" color="muted"/><text class="empty-title">登录后查看订单</text><text class="muted">查看预约、派单与退款进度</text><button class="app-button primary" @click="go('/pages/login/index')">登录 / 注册</button></view>
  <view v-else-if="error&&!items.length" class="panel empty"><text class="empty-title">订单加载失败</text><text class="muted">{{error}}</text><button class="app-button secondary" @click="load()">重新加载</button></view>
  <view v-else-if="!items.length&&!loading" class="panel empty"><AppIcon name="orders" :size="32" color="muted"/><text class="empty-title">暂无{{filter||group?'此类':''}}订单</text><button class="app-button primary" @click="go('/pages/booking/index?type=RIDE')">预约用车</button></view>
  <view v-for="order in authenticated?items:[]" :key="order.id" class="panel order-panel">
    <view class="order-head" @click="go('/pages/order/index?id='+order.id)"><view class="row"><AppIcon name="car" :size="18" color="muted"/><text>{{order.type==='CHARTER'?'预约包车':'预约用车'}}</text></view><text :class="['order-status',{'needs-action':['PENDING_CONFIRM','PENDING_PAYMENT'].includes(order.status)}]">{{statusLabels[order.status]||order.status}}</text></view>
    <view class="order-body" @click="go('/pages/order/index?id='+order.id)">
      <text class="order-time">{{dateText(order.appointmentAt)}}</text>
      <view class="route"><view class="route-mark"/><text>{{order.originName}}</text></view><view class="route"><view class="route-mark end"/><text>{{order.destinationName}}</text></view>
      <text class="subtitle">{{order.vehicleName}}{{order.hours?' · '+order.hours+' 小时':''}}</text>
      <text v-if="order.nextStep" class="subtitle">{{order.nextStep}}</text>
    </view>
    <view class="order-foot"><text class="order-amount">{{amountText(order)}}</text><button class="app-button" :class="['next-button',order.actions?.acceptQuote||order.actions?.pay?'primary':'secondary']" @click="go('/pages/order/index?id='+order.id)">{{order.actions?.acceptQuote?'确认报价':order.actions?.pay?'去支付':'查看详情'}}</button></view>
  </view>
  <view v-if="authenticated&&error&&items.length" class="panel empty"><text>{{error}}</text><button class="app-button secondary" @click="load(failedReset)">重试加载</button></view>
  <view v-if="loading" class="empty">正在加载订单…</view>
  <button v-else-if="items.length<total&&!error" class="app-button plain load-more" @click="load(false)">加载更多</button>
  <text v-else-if="items.length" class="end-note">共 {{total}} 笔订单</text>
</view>
</template>
<style scoped>
.orders-page .filter{width:auto;flex-shrink:0;text-align:center;background:#fff;border-radius:0}.orders-page .filter-scroll{border-top:1px solid #e9ecef}.orders-page .order-status{white-space:nowrap}.orders-page .order-panel{border-radius:14px}
.status-picker{display:flex;align-items:center;justify-content:space-between;gap:16rpx;padding-top:16rpx}.status-picker-value{display:flex;align-items:center;gap:12rpx;min-height:88rpx;font-size:28rpx}.senior .status-picker-value{font-size:34rpx;min-height:96rpx}
.orders-page{padding-top:0}.filter-scroll{width:calc(100% + 64rpx);margin:0 -32rpx;background:#fff;border-bottom:1rpx solid #dee0e3;white-space:nowrap}.filter-line{display:inline-flex;padding:0 24rpx}.filter{padding:28rpx 24rpx;font-size:28rpx;min-height:104rpx;border-bottom:4rpx solid transparent;color:#646a73}.filter.active{color:#16845b;border-bottom-color:#16845b;font-weight:600}.list-summary{display:flex;justify-content:space-between;padding:24rpx 0;font-size:28rpx}.order-head{display:flex;align-items:center;justify-content:space-between;border-bottom:1rpx solid #eff0f1;min-height:96rpx;font-size:28rpx;gap:16rpx}.order-status{color:#646a73;font-size:26rpx}.order-status.needs-action{color:#16845b}.order-body{padding:24rpx 0}.order-time{font-weight:500;font-size:30rpx;display:block;margin-bottom:16rpx}.route{display:flex;align-items:center;gap:20rpx;padding:8rpx 0;font-size:30rpx}.route-mark{width:12rpx;height:12rpx;border:3rpx solid #646a73;border-radius:50%;flex-shrink:0}.route-mark.end{border-radius:2rpx;background:#646a73}.order-foot{display:flex;justify-content:space-between;align-items:center;padding:24rpx 0;border-top:1rpx solid #eff0f1;gap:16rpx}.order-amount{font-size:34rpx;font-weight:500}.next-button{display:flex;align-items:center;justify-content:center;flex-shrink:0;white-space:nowrap;padding:16rpx 24rpx;font-size:28rpx;line-height:1.5;min-height:88rpx}.end-note{display:block;text-align:center;padding:16rpx;color:#646a73;font-size:24rpx}.load-more{width:100%}.senior .filter,.senior .order-head,.senior .order-status{font-size:32rpx}.senior .order-time,.senior .route{font-size:36rpx}.senior .next-button{font-size:32rpx;line-height:1.5}
</style>
