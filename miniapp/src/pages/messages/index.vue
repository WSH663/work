<script setup lang="ts">
import { ref } from 'vue'
import { onShow,onHide,onUnload } from '@dcloudio/uni-app'
import AppIcon from '../../components/AppIcon.vue'
import { api,ApiError,dateText,errorToast,go,type Data } from '../../api'
import { pageClass } from '../../store'
const items=ref<Data[]>([]),loading=ref(false),error=ref(''),authRequired=ref(false)
let alive=true,visible=false,requestId=0,loadedSession=''
const pendingRead=new Set<string>(),markedRead=new Set<string>()
function current(token:string){return alive&&visible&&token===String(uni.getStorageSync('travel_token')||'')}
function needLogin(){authRequired.value=true;items.value=[];error.value='';loadedSession='';pendingRead.clear();markedRead.clear()}
async function load(){
  const token=String(uni.getStorageSync('travel_token')||''),version=++requestId
  if(!token){needLogin();loading.value=false;return}
  if(loadedSession!==token){items.value=[];pendingRead.clear();markedRead.clear();loadedSession=token}
  loading.value=true;error.value='';authRequired.value=false
  try{const result=await api<Data[]>('/me/messages');if(current(token)&&version===requestId)items.value=result.map(item=>({...item,readFlag:item.readFlag||markedRead.has(String(item.id))}))}
  catch(e){if(alive&&visible&&version===requestId){if(e instanceof ApiError&&e.status===401&&!uni.getStorageSync('travel_token'))needLogin();else if(current(token))error.value=e instanceof Error?e.message:'消息加载失败'}}
  finally{if(alive&&version===requestId)loading.value=false}
}
async function read(item:Data){
  const token=String(uni.getStorageSync('travel_token')||''),key=String(item.id)
  if(!token){needLogin();return false}
  if(!current(token)||loadedSession!==token)return false
  if(item.readFlag||pendingRead.has(key))return true
  pendingRead.add(key)
  try{await api('/me/messages/'+encodeURIComponent(key)+'/read','POST');if(current(token)&&loadedSession===token){markedRead.add(key);const row=items.value.find(message=>String(message.id)===key);if(row)row.readFlag=true}}
  catch(e){if(alive&&visible){if(e instanceof ApiError&&e.status===401&&!uni.getStorageSync('travel_token'))needLogin();else if(current(token))errorToast(e)}}
  finally{if(loadedSession===token)pendingRead.delete(key)}
  return current(token)&&loadedSession===token
}
async function openOrder(item:Data){if(/^\d+$/.test(String(item.orderId||''))&&await read(item))go('/pages/order/index?id='+encodeURIComponent(item.orderId))}
onShow(()=>{visible=true;void load()});onHide(()=>{visible=false;requestId++;loading.value=false});onUnload(()=>{alive=false;requestId++;pendingRead.clear()})
</script>
<template>
<view class="page" :class="pageClass()">
  <view class="page-heading"><view class="page-heading-main"><text class="page-heading-title">消息通知</text><text class="page-heading-note">预约、报价与接送进度及时掌握</text></view><button class="app-button icon-button" aria-label="刷新消息" :disabled="loading" @click="load"><AppIcon name="refresh" :size="20" color="muted"/></button></view>
  <view v-if="authRequired" class="panel empty"><AppIcon name="message" :size="32" color="muted"/><text class="empty-title">登录后查看消息</text><text class="muted">登录后查看预约、报价、派单与退款进度通知。</text><button class="app-button primary" @click="go('/pages/login/index')">登录 / 注册</button></view>
  <view v-else-if="loading" class="panel empty">正在加载消息…</view>
  <view v-else-if="error" class="panel empty"><text class="empty-title">消息加载失败</text><text>{{error}}</text><button class="app-button secondary" @click="load">重新加载</button></view>
  <view v-else-if="!items.length" class="panel empty"><AppIcon name="message" :size="32" color="muted"/><text class="empty-title">暂无消息</text><text class="muted">预约、报价及派单进度将在这里通知。</text></view>
  <view v-else class="panel"><view v-for="item in items" :key="item.id" class="message-item" @click="read(item)"><view class="row"><text class="message-title">{{item.title}}</text><text class="read-status" :class="{unread:!item.readFlag}">{{item.readFlag?'已读':'未读'}}</text></view><text class="message-content">{{item.content}}</text><view class="row"><text class="muted">{{dateText(item.createdAt)}}</text><button v-if="item.orderId" class="app-button plain" @click.stop="openOrder(item)">查看订单</button><button v-else-if="!item.readFlag" class="app-button plain" @click.stop="read(item)">标记已读</button></view></view></view>
  <text class="notification-note">当前为站内通知。微信订阅消息需配置模板并由您授权。</text>
</view>
</template>
<style scoped>
.messages-toolbar{display:flex;justify-content:space-between;align-items:center;font-size:28rpx;margin-bottom:8rpx}.message-item{padding:32rpx 0;border-bottom:1rpx solid #eff0f1}.message-item:last-child{border-bottom:0}.message-title{font-size:30rpx;font-weight:600;flex:1;word-break:break-all}.read-status{font-size:24rpx;color:#646a73;white-space:nowrap}.read-status.unread{color:#16845b}.message-content{display:block;white-space:pre-wrap;font-size:30rpx;line-height:1.7;margin:16rpx 0;color:#41464d}.notification-note{display:block;font-size:24rpx;line-height:1.6;color:#646a73;padding:8rpx 0}.senior .message-title,.senior .message-content{font-size:36rpx}.senior .read-status,.senior .notification-note{font-size:30rpx}
</style>
