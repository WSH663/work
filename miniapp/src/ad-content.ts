import type { Data } from './api'

export type AdMediaKind = 'TEXT' | 'IMAGE' | 'VIDEO'
export function adMediaKind(item: Data): AdMediaKind {
  return item.mediaUrl && (item.mediaType === 'IMAGE' || item.mediaType === 'VIDEO') ? item.mediaType : 'TEXT'
}
export function adActionText(item: Data): string {
  return String(item.actionText || '').trim().slice(0, 12) || '查看详情'
}
export function adMediaHeight(width: number, viewportHeight: number, placement: 'popup' | 'banner', kind: AdMediaKind): number {
  if (kind === 'TEXT') return 0
  const natural = kind === 'VIDEO' ? width * 9 / 16 : placement === 'popup' ? width * 3 / 4 : width / 2.4
  return Math.round(placement === 'popup' ? Math.min(natural, Math.max(96, viewportHeight * .4)) : natural)
}
