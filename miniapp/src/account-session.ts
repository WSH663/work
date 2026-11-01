const identityKeys = ['travel_token', 'travel_user', 'travel_identity_mode']
const privateKeys = ['travel_invite', 'travel_places', 'travel_booking_attempt', 'travel_withdrawal_attempt']
export let accountCacheRevision = 0

export function clearAccountSession(): void {
  for (const key of identityKeys) uni.removeStorageSync(key)
  clearAccountCache()
}

export function clearAccountCache(): void {
  accountCacheRevision++
  for (const key of privateKeys) uni.removeStorageSync(key)
  for (const key of uni.getStorageInfoSync().keys) {
    if (key.startsWith('travel_place_') || key.startsWith('travel_tab_query_')) uni.removeStorageSync(key)
  }
}

export function initializeAccountSession(): void {
  const token = uni.getStorageSync('travel_token')
  const user = uni.getStorageSync('travel_user')
  const mode = uni.getStorageSync('travel_identity_mode')
  // Development identities and orphaned profiles must not become WeChat accounts.
  if ((token || user || mode) && (!token || !user?.id || mode !== 'WECHAT')) clearAccountSession()
}

export function saveWechatSession(token: string, user: Record<string, unknown>): void {
  const previousUser = uni.getStorageSync('travel_user')
  if (previousUser?.id && String(previousUser.id) !== String(user.id)) clearAccountCache()
  uni.setStorageSync('travel_token', token)
  uni.setStorageSync('travel_user', user)
  uni.setStorageSync('travel_identity_mode', 'WECHAT')
  uni.removeStorageSync('travel_invite')
}
