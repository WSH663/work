import { api, type Data, type Place } from './api'
import { apiUrl } from './api-base'
import { formatCoordinate, parseCoordinate, wgs84ToGcj02 } from './map-geometry'
// #ifdef MP-WEIXIN
import { AMapWX } from './vendor/amap-wx'
// #endif

export interface NearbyPlace extends Place { id?: string; distance?: number }
export function reverseLocation(location: string): Promise<Place> {
  if (!parseCoordinate(location)) return Promise.reject(new Error('地图坐标无效，请重新选点'))
  return api<Place>(`/map/reverse?location=${encodeURIComponent(location)}`)
}
export function nearbyPlaces(location: string): Promise<NearbyPlace[]> {
  return api<NearbyPlace[]>(`/map/nearby?location=${encodeURIComponent(location)}`)
}
export function staticMapUrl(location: string, zoom: number, width: number, height: number): string {
  return apiUrl(`/map/static?location=${encodeURIComponent(location)}&zoom=${zoom}&width=${Math.max(128, Math.min(1024, Math.round(width)))}&height=${Math.max(128, Math.min(1024, Math.round(height)))}`)
}
function locationError(message: string): Error {
  if (/denied|deny|auth|permission|拒绝|权限/i.test(message)) return new Error('未获得定位权限，请在系统或微信设置中开启定位权限后重试，也可搜索地点')
  if (/timeout|超时/i.test(message)) return new Error('定位超时，请重试或搜索地址')
  return new Error('暂时无法定位，可搜索地址或在地图上选点')
}
/** Called on page entry or retry; platform permission and privacy prompts remain in control. */
export async function getCurrentPlace(options: { onLocated?: (location: string) => void } = {}): Promise<Place> {
  let location = ''
  // #ifdef H5
  if (!navigator.geolocation) throw new Error('当前浏览器不支持定位，请搜索地址或在地图上选点')
  location = await new Promise<string>((resolve, reject) => navigator.geolocation.getCurrentPosition(
    result => resolve(formatCoordinate(wgs84ToGcj02({ longitude: result.coords.longitude, latitude: result.coords.latitude }))),
    failure => reject(locationError(failure.code === 1 ? 'permission denied' : failure.code === 3 ? 'timeout' : failure.message)),
    { enableHighAccuracy: true, timeout: 12000, maximumAge: 0 }
  ))
  // #endif
  // #ifdef MP-WEIXIN
  location = await new Promise<string>((resolve, reject) => {
    // WeChat's highAccuracyExpireTime is a high-accuracy sampling window, not
    // an overall API timeout. Ignore late callbacks after this request expires.
    let settled = false
    const timer = setTimeout(() => { if (!settled) { settled = true; reject(locationError('timeout')) } }, 12000)
    uni.getLocation({
      type: 'gcj02', isHighAccuracy: true, highAccuracyExpireTime: 5000,
      success: result => {
        if (settled) return
        settled = true; clearTimeout(timer)
        const value = `${result.longitude},${result.latitude}`
        if (!parseCoordinate(value)) { reject(new Error('定位结果无效，请重新获取当前位置')); return }
        resolve(formatCoordinate({ longitude: result.longitude, latitude: result.latitude }))
      },
      fail: failure => { if (!settled) { settled = true; clearTimeout(timer); reject(locationError(failure.errMsg || '')) } }
    })
  })
  // #endif
  if (!parseCoordinate(location)) throw new Error('当前平台无法获取有效定位，请搜索地点')
  // Make the actual GPS coordinate available before the slower address lookup.
  options.onLocated?.(location)
  try {
    const place = await reverseLocation(location)
    // Retain the GPS point; nearby POIs returned by a provider must not move it.
    return { ...place, location }
  } catch {
    // GPS succeeded even when geocoding did not. An empty address makes that
    // distinction explicit to callers and never pretends an address was parsed.
    return { name: '当前位置', address: '', location }
  }
}

export async function addressTips(keywords: string, city: string): Promise<Data[]> {
  try { return await api<Data[]>(`/map/tips?keywords=${encodeURIComponent(keywords)}&city=${encodeURIComponent(city)}`) }
  catch (originalError) {
    // #ifdef MP-WEIXIN
    const miniKey = import.meta.env.VITE_AMAP_MINI_KEY
    if (miniKey) return new Promise<Data[]>((resolve, reject) => {
      const timer = setTimeout(() => reject(originalError), 7000)
      const Constructor = AMapWX as unknown as new (options: { key: string }) => { getInputtips: (options: Data) => void }
      const amap = new Constructor({ key: miniKey })
      amap.getInputtips({ keywords, city,
        success: (data: Data) => { clearTimeout(timer); resolve(data.tips || []) },
        fail: () => { clearTimeout(timer); reject(originalError) }
      })
    })
    // #endif
    throw originalError
  }
}
