export type DocumentKind = 'service' | 'privacy' | 'terms'
export type DocumentAction = 'contact' | 'profile' | 'passengers' | 'addresses' | 'amapPrivacy'
export interface DocumentSection {
  id: string
  title: string
  body: string
  mediaType: 'TEXT' | 'IMAGE' | 'VIDEO'
  mediaUrl: string
  posterUrl: string
  linkUrl: string
  actions: DocumentAction[]
  enabled: boolean
}
export interface PublicDocument {
  title: string
  introduction: string
  footerTitle: string
  footer: string
  sections: DocumentSection[]
}
export interface PublishedDocument {
  document: PublicDocument
  revision: number
  publishedAt: string
}

const actionDefinitions: Record<DocumentAction, { label: string; icon: string; url?: string }> = {
  contact: { label: '联系平台客服', icon: 'phone' },
  profile: { label: '个人资料', icon: 'user', url: '/pages/profile/index' },
  passengers: { label: '常用乘车人', icon: 'users', url: '/pages/resources/index?kind=passengers' },
  addresses: { label: '常用地址', icon: 'address', url: '/pages/resources/index?kind=addresses' },
  amapPrivacy: { label: '复制高德隐私政策链接', icon: 'copy' },
}
export const documentActionDefinitions = actionDefinitions

export function documentActionItems(actions: DocumentAction[]) {
  return actions.map(kind => ({ kind, ...actionDefinitions[kind] }))
}

export function documentAnchor(id: string) { return `doc-section-${id}` }

/** Validate both network responses and device cache before rendering published content. */
export function parsePublishedDocument(value: unknown): PublishedDocument {
  const data = value as Partial<PublishedDocument> | null
  const source = data?.document
  if (!source || typeof source.title !== 'string' || !source.title.trim()
    || typeof source.introduction !== 'string' || typeof source.footerTitle !== 'string' || typeof source.footer !== 'string'
    || !Array.isArray(source.sections) || !Number.isInteger(data?.revision) || Number(data?.revision) < 1
    || typeof data?.publishedAt !== 'string' || !data.publishedAt.trim()) {
    throw new Error('已发布文档数据不完整，请稍后重试')
  }
  const ids = new Set<string>()
  const sections = source.sections.filter(section => section?.enabled !== false).map(section => {
    if (!section || typeof section.id !== 'string' || !/^[a-z][a-z0-9-]{0,63}$/.test(section.id)
      || ids.has(section.id) || typeof section.title !== 'string' || !section.title.trim()
      || typeof section.body !== 'string' || !['TEXT', 'IMAGE', 'VIDEO'].includes(section.mediaType)
      || !Array.isArray(section.actions)) {
      throw new Error('已发布文档章节不完整，请稍后重试')
    }
    ids.add(section.id)
    return {
      id: section.id, title: section.title, body: section.body, mediaType: section.mediaType,
      mediaUrl: typeof section.mediaUrl === 'string' ? section.mediaUrl : '',
      posterUrl: typeof section.posterUrl === 'string' ? section.posterUrl : '',
      linkUrl: typeof section.linkUrl === 'string' ? section.linkUrl : '',
      actions: [...new Set(section.actions.filter(action => Object.prototype.hasOwnProperty.call(actionDefinitions, action)))],
      enabled: true,
    }
  })
  if (!sections.length) throw new Error('该文档暂未发布有效章节，请稍后重试')
  return {
    document: { title: source.title, introduction: source.introduction, footerTitle: source.footerTitle, footer: source.footer, sections },
    revision: Number(data.revision), publishedAt: data.publishedAt,
  }
}

export function readDocumentCache(kind: DocumentKind): PublishedDocument | null {
  try {
    const value = uni.getStorageSync(`travel_document_${kind}_v1`)
    return value ? parsePublishedDocument(value) : null
  } catch { return null }
}

export function saveDocumentCache(kind: DocumentKind, document: PublishedDocument) {
  try { uni.setStorageSync(`travel_document_${kind}_v1`, document) } catch { /* Device storage failures must not hide a successfully loaded document. */ }
}

export function documentPublishedAt(value: string): string {
  const standard = value.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/)
  return standard ? `${standard[1]} ${standard[2]}` : value
}
