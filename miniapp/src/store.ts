import { reactive } from 'vue'
export const preferences = reactive({ senior: uni.getStorageSync('travel_senior') === true })
export function setSenior(value: boolean) { preferences.senior = value; uni.setStorageSync('travel_senior', value) }
export function onSeniorChange(event: any) { setSenior(event.detail.value === true) }
export const devEnabled = import.meta.env.VITE_ENABLE_DEV_LOGIN === 'true'
export const pageClass = () => ({ senior: preferences.senior })
