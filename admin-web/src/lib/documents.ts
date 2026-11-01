export const documentKinds = ['service', 'privacy', 'terms'] as const
export type DocumentKind = typeof documentKinds[number]
export interface DocumentSection { id: string; title: string; body: string; mediaType: 'TEXT' | 'IMAGE' | 'VIDEO'; mediaUrl: string; posterUrl: string; linkUrl: string; actions: string[]; enabled: boolean }
export interface ManagedDocument { title: string; introduction: string; footerTitle: string; footer: string; sections: DocumentSection[] }
export interface DocumentState { kind: DocumentKind; draft: ManagedDocument; published: ManagedDocument; revision: number; publishedRevision: number; updatedAt: string; publishedAt: string; variables: Record<string, string> }
export const documentLabels: Record<DocumentKind, string> = { service: '服务说明', privacy: '隐私说明', terms: '用户协议' }
export const documentDescriptions: Record<DocumentKind, string> = {
  service: '首页和“我的”的服务说明共用此篇文档。完整维护正文、章节与相关操作，不再单独追加补充说明。',
  privacy: '用于登录页和“我的”中的隐私说明。维护信息收集、使用、管理方式；微信隐私授权声明仍需在微信平台配置。',
  terms: '用于登录页及说明文档底部的用户协议入口。服务约定与隐私说明分别维护，发布仅影响本篇文档。',
}
export const documentActions = [
  { value: 'contact', label: '联系平台客服', description: '确认后拨打平台客服电话' },
  { value: 'profile', label: '个人资料', description: '进入“我的”查看和管理个人资料' },
  { value: 'passengers', label: '常用乘车人', description: '进入常用乘车人页面，需登录' },
  { value: 'addresses', label: '常用地址', description: '进入常用地址页面，需登录' },
  { value: 'amapPrivacy', label: '复制高德隐私政策链接', description: '复制政策链接，供用户在浏览器打开' },
]
export const documentLinks = [
  { value: '/pages/index/index', label: '首页' }, { value: '/pages/booking/index', label: '预约用车' },
  { value: '/pages/booking/index?type=RIDE', label: '预约用车（兼容入口）' }, { value: '/pages/booking/index?type=CHARTER', label: '预约包车' },
  { value: '/pages/orders/index', label: '我的订单' }, { value: '/pages/profile/index', label: '我的' },
  { value: '/pages/promotion/index', label: '推广与佣金' }, { value: '/pages/messages/index', label: '消息通知' },
  { value: '/pages/agreement/index', label: '服务说明（兼容入口）' }, { value: '/pages/agreement/index?kind=service', label: '服务说明' },
  { value: '/pages/agreement/index?kind=notice', label: '平台公告' }, { value: '/pages/agreement/index?kind=privacy', label: '隐私说明' },
  { value: '/pages/agreement/index?kind=terms', label: '用户协议' },
]
export const documentVariables = [
  { key: 'serviceArea', label: '服务区域' }, { key: 'servicePhone', label: '客服电话' },
  { key: 'advanceRule', label: '提前预约规则' }, { key: 'bookingRangeRule', label: '可预约日期规则' },
  { key: 'cancellationRule', label: '取消规则' }, { key: 'advanceMinutes', label: '提前预约分钟数' },
  { key: 'maxAdvanceDays', label: '可预约天数' }, { key: 'cancelHours', label: '取消提前小时数' },
]
export function renderDocumentText(value: string, variables: Record<string, string>) { return value.replace(/\{\{([a-zA-Z][a-zA-Z0-9]*)\}\}/g, (token, key) => variables[key] ?? token) }
export function copyDocument(value: ManagedDocument): ManagedDocument { return JSON.parse(JSON.stringify(value)) }
export function normalizeDocument(value: ManagedDocument): ManagedDocument {
  return { title: value.title.trim(), introduction: value.introduction.trim(), footerTitle: value.footerTitle.trim(), footer: value.footer.trim(), sections: value.sections.map(s => ({ ...s, id: s.id.trim(), title: s.title.trim(), body: s.body.trim(), mediaUrl: s.mediaType === 'TEXT' ? '' : s.mediaUrl.trim(), posterUrl: s.mediaType === 'VIDEO' ? s.posterUrl.trim() : '', linkUrl: s.linkUrl.trim(), actions: [...s.actions] })) }
}
function https(value: string) { try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password && !url.hash } catch { return false } }
export function validateDocument(doc: ManagedDocument): { message: string; section?: number } | null {
  const texts: { text: string; max: number; label: string; required?: boolean; section?: number }[] = [
    { text: doc.title, max: 120, label: '文档标题', required: true }, { text: doc.introduction, max: 2000, label: '引言' },
    { text: doc.footerTitle, max: 120, label: '页尾标题' }, { text: doc.footer, max: 4000, label: '页尾说明' },
  ]
  if (doc.sections.length < 1 || doc.sections.length > 30) return { message: '文档须包含 1 至 30 个章节' }
  const ids = new Set<string>()
  for (let index = 0; index < doc.sections.length; index++) {
    const s = doc.sections[index]!, issue = (message: string) => ({ message: `第 ${index + 1} 章：${message}`, section: index })
    if (!/^[a-z][a-z0-9-]{0,63}$/.test(s.id) || ids.has(s.id)) return issue('章节标识须唯一，以小写字母开头，仅含小写字母、数字或短横线，最多 64 位')
    ids.add(s.id)
    if (!s.body && s.mediaType === 'TEXT') return issue('请填写正文')
    if (s.mediaType !== 'TEXT' && !https(s.mediaUrl)) return issue('请填写有效的 HTTPS 图片或视频链接')
    if (s.mediaType === 'VIDEO' && s.posterUrl && !https(s.posterUrl)) return issue('视频封面须为有效的 HTTPS 图片链接')
    if ([s.mediaUrl, s.posterUrl, s.linkUrl].some(value => value.length > 1000)) return issue('链接不能超过 1000 个字符')
    if (s.linkUrl && !documentLinks.some(link => link.value === s.linkUrl)) return issue('请选择支持的小程序站内页面')
    if (s.actions.some(action => !documentActions.some(option => option.value === action)) || new Set(s.actions).size !== s.actions.length) return issue('章节操作不支持或重复')
    texts.push({ text: s.title, max: 120, label: '章节标题', required: true, section: index }, { text: s.body, max: 8000, label: '章节正文', section: index })
  }
  for (const field of texts) {
    const prefix = field.section === undefined ? '' : `第 ${field.section + 1} 章：`
    const issue = (message: string) => ({ message: prefix + message, section: field.section })
    if (field.required && !field.text) return issue(`请填写${field.label}`)
    if (field.text.length > field.max) return issue(`${field.label}不能超过 ${field.max} 字`)
    if (field.text.includes('\0')) return issue(`${field.label}包含无效字符`)
    let unknown = ''; const stripped = field.text.replace(/\{\{([a-zA-Z][a-zA-Z0-9]*)\}\}/g, (_, key) => { if (!documentVariables.some(v => v.key === key)) unknown = key; return '' })
    if (unknown) return issue(`${field.label}包含未知变量：${unknown}`)
    if (stripped.includes('{{') || stripped.includes('}}')) return issue(`${field.label}变量格式不正确，请从“插入配置变量”选择`)
  }
  if (texts.reduce((total, field) => total + field.text.length, 0) > 60000) return { message: '整篇文档文字不能超过 60000 字' }
  return null
}
