<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { api,ApiError,confirm,errorToast,go,requireLogin,type Data,type Place } from '../../api'
import { parseCoordinate } from '../../map-geometry'
import { pageClass } from '../../store'
import AppIcon from '../../components/AppIcon.vue'
const error=ref('')
const kind=ref('passengers'),items=ref<Data[]>([]),loading=ref(false),adding=ref(false),name=ref(''),phone=ref(''),label=ref(''),place=ref<Place|null>(null),busy=ref(false)
const authRequired=ref(false)
let alive=true,visible=false,requestId=0,loadedSession=''
function current(token:string){return alive&&token===String(uni.getStorageSync('travel_token')||'')}
function needLogin(){authRequired.value=true;items.value=[];error.value='';loading.value=false;clearForm()}
function clearForm(){adding.value=false;name.value='';phone.value='';label.value='';place.value=null;uni.removeStorageSync('travel_place_saved')}
onLoad(options=>{kind.value=options?.kind==='addresses'?'addresses':'passengers';uni.setNavigationBarTitle({title:kind.value==='addresses'?'常用地址':'常用乘车人'});uni.removeStorageSync('travel_place_saved')})
onShow(()=>{visible=true;const token=String(uni.getStorageSync('travel_token')||'');if(loadedSession&&loadedSession!==token)clearForm();place.value=uni.getStorageSync('travel_place_saved')||null;void load()});onHide(()=>{visible=false;requestId++;loading.value=false});onUnload(()=>{alive=false;requestId++})
async function load(allowBusy=false){
  if(!alive||!visible||(busy.value&&!allowBusy))return
  const token=String(uni.getStorageSync('travel_token')||''),version=++requestId
  if(!token){needLogin();return}
  if(loadedSession!==token){items.value=[];loadedSession=token}
  loading.value=true;error.value='';authRequired.value=false
  try{const data=await api<Data[]>('/me/'+kind.value);if(current(token)&&visible&&version===requestId)items.value=data}
  catch(e){if(alive&&visible&&version===requestId){if(e instanceof ApiError&&e.status===401&&!uni.getStorageSync('travel_token'))needLogin();else if(current(token))error.value=e instanceof Error?e.message:'常用信息加载失败'}}
  finally{if(alive&&version===requestId)loading.value=false}
}
function toggleAdd(){if(busy.value||!requireLogin())return;adding.value=!adding.value}
function choosePlace(){if(!busy.value)go('/pages/address/index?role=saved')}
async function save(){
  if(busy.value||!requireLogin())return
  const token=String(uni.getStorageSync('travel_token')||'');let payload:Data
  if(kind.value==='passengers'){if(!name.value.trim()||!/^1[3-9]\d{9}$/.test(phone.value.trim()))return errorToast(new Error('请填写姓名及正确手机号'));payload={name:name.value.trim(),phone:phone.value.trim()}}
  else{if(!label.value.trim()||!place.value||!parseCoordinate(place.value.location))return errorToast(new Error('请填写地址名称并在地图选择地点'));payload={label:label.value.trim(),address:[place.value.name,place.value.address].filter(Boolean).join(' '),location:place.value.location}}
  busy.value=true;requestId++;loading.value=false;error.value=''
  try{const result=await api('/me/'+kind.value,'POST',payload);if(current(token)){items.value=[result,...items.value.filter(item=>item.id!==result.id)];clearForm();if(visible){uni.showToast({title:'已添加',icon:'success'});await load(true)}}}
  catch(e){if(alive){if(e instanceof ApiError&&e.status===401&&!uni.getStorageSync('travel_token'))needLogin();else if(current(token))errorToast(e)}}
  finally{if(alive){busy.value=false;if(visible&&!current(token))void load()}}
}
async function remove(item:Data){
  if(busy.value||!requireLogin())return
  const token=String(uni.getStorageSync('travel_token')||'');busy.value=true;requestId++;loading.value=false
  try{if(!await confirm('删除常用信息','确认删除“'+(item.name||item.label)+'”？'))return;if(!current(token))return;await api('/me/'+kind.value+'/'+item.id,'DELETE');if(current(token)){items.value=items.value.filter(row=>row.id!==item.id);if(visible){uni.showToast({title:'已删除',icon:'none'});await load(true)}}}
  catch(e){if(alive){if(e instanceof ApiError&&e.status===401&&!uni.getStorageSync('travel_token'))needLogin();else if(current(token)){errorToast(e);await load(true)}}}
  finally{if(alive){busy.value=false;if(visible&&!current(token))void load()}}
}
</script>
<template>
<view class="page resources-page" :class="pageClass()">
  <view class="page-heading"><view class="page-heading-main"><text class="page-heading-title">{{kind==='passengers'?'常用乘车人':'常用地址'}}</text><text class="page-heading-note">{{kind==='passengers'?'提前保存乘车人，预约时直接选用':'保存常用地点，下次出行更便捷'}}</text></view><button class="app-button plain add-button" :disabled="busy" @click="toggleAdd"><AppIcon :name="adding?'close':'plus'" :size="18" color="brand"/><text>{{adding?'收起':'添加'}}</text></button></view>
  <view v-if="authRequired" class="panel empty"><text class="empty-title">登录后管理常用信息</text><text>登录后可添加乘车人和地址，供预约时选用。</text><button class="app-button primary" @click="go('/pages/login/index')">登录 / 注册</button></view>
  <view v-if="adding&&!authRequired" class="panel">
    <view class="section-header"><text class="section-title">{{kind==='passengers'?'添加乘车人':'添加地址'}}</text></view>
    <template v-if="kind==='passengers'"><view class="field"><text class="label">姓名</text><input v-model="name" :disabled="busy" maxlength="30" placeholder="请输入乘车人姓名"/></view><view class="field"><text class="label">联系电话</text><input v-model="phone" :disabled="busy" type="number" maxlength="11" placeholder="请输入 11 位手机号"/></view></template>
    <template v-else><view class="field"><text class="label">地址名称</text><input v-model="label" :disabled="busy" maxlength="30" placeholder="例如：家、医院"/></view><button class="app-button button-row field resource-place" :disabled="busy" @click="choosePlace"><text class="label">地点</text><view class="row value"><text :class="{placeholder:!place}">{{place?.name||'搜索并选择地点'}}</text><AppIcon name="chevron-right" :size="18" color="muted"/></view><text v-if="place" class="subtitle">{{place.address}}</text></button></template>
    <view class="form-actions"><button class="app-button secondary" :disabled="busy" @click="clearForm">取消</button><button class="app-button primary resource-save" :disabled="busy" :loading="busy" @click="save">保存</button></view>
  </view>
  <view v-if="!authRequired&&loading&&!items.length" class="panel empty">正在加载…</view>
  <view v-else-if="!authRequired&&error&&!items.length" class="panel empty"><text class="empty-title">信息加载失败</text><text>{{error}}</text><button class="app-button secondary" :disabled="busy" @click="load()">重新加载</button></view>
  <template v-else-if="!authRequired"><view v-if="error" class="inline-alert"><text>{{error}}，当前显示上次获取的内容。</text><button class="app-button plain" :disabled="busy||loading" @click="load()">重试</button></view><text v-if="items.length" class="section-caption">已保存 {{items.length}} {{kind==='passengers'?'位乘车人':'个地点'}}</text><view class="panel">
    <view v-if="!items.length" class="empty"><AppIcon :name="kind==='passengers'?'users':'address'" :size="32" color="muted"/><text class="empty-title">暂无{{kind==='passengers'?'常用乘车人':'常用地址'}}</text><text class="muted">添加后可在预约时直接选用。</text></view>
    <view v-for="item in items" :key="item.id" class="resource-row"><AppIcon :name="kind==='passengers'?'user':'map-pin'" :size="20" color="muted"/><view class="resource-main"><text class="resource-name">{{item.name||item.label}}</text><text class="subtitle">{{item.phone||item.address}}</text></view><button class="app-button delete-button" :aria-label="'删除'+(item.name||item.label)" :disabled="busy" @click="remove(item)"><AppIcon name="trash" :size="20" color="muted"/></button></view>
  </view></template>
</view>
</template>
<style scoped>
.resource-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16rpx;margin-bottom:16rpx;min-height:88rpx;font-size:30rpx}.add-button{display:flex;align-items:center;gap:12rpx}.form-save{margin:24rpx 0 32rpx}.resource-row{display:flex;align-items:flex-start;gap:16rpx;padding:28rpx 0;border-bottom:1rpx solid #eff0f1}.resource-row:last-child{border-bottom:0}.resource-main{flex:1;min-width:0}.resource-name{font-size:30rpx;font-weight:500;display:block}.delete-button{display:flex;align-items:center;justify-content:center;width:88rpx;min-height:88rpx;padding:16rpx;background:transparent;margin-top:-16rpx;margin-right:-16rpx}.senior .resource-name{font-size:36rpx}
</style>
