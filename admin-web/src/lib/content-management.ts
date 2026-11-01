import type { Row } from './api'

export type ContentKind = 'AD' | 'BANNER' | 'NOTICE' | 'SERVICE'
export const adKinds: ContentKind[] = ['AD', 'BANNER']
export const informationKinds: ContentKind[] = ['NOTICE']
export const isAdvertisement = (kind: string) => kind === 'AD' || kind === 'BANNER'
export const contentDefinitions: Record<ContentKind, { label: string; create: string; location: string; purpose: string; examples: string; size: string; titleHint: string; bodyHint: string; sortHint: string }> = {
  AD: {
    label: '首页弹窗', create: '新增弹窗', location: '小程序首页上方的局部浮层',
    purpose: '每次新进入小程序后，首次到首页展示一条；站内返回不重复，用户可随时关闭。',
    examples: '适合重要活动或阶段性服务推广。普通通知请发布到“平台公告”。',
    size: '图片建议 4:3（1200 × 900 px）；视频 16:9。素材下方独立展示标题、正文和跳转按钮。',
    titleHint: '用一句话说明推广内容，例如“轮椅出行，提前预约”',
    bodyHint: '说明服务亮点、活动条件与参与方式，弹窗完整展示正文。',
    sortHint: '数值越小优先级越高；相同数值按记录编号从小到大。已上线中仅第一条展示，其余为备用。',
  },
  BANNER: {
    label: '首页推荐位', create: '新增推荐', location: '小程序首页预约区域之后的“活动推荐”',
    purpose: '已上线内容按顺序展示，用户左右滑动或点击切换；视频需点击播放。',
    examples: '适合常用服务介绍、预约引导和活动推广，可同时展示多条。',
    size: '图片建议 2.4:1（1200 × 500 px）；视频 16:9。标题和摘要不压在图片上。',
    titleHint: '说明推荐内容，例如“预约包车，了解行程安排”',
    bodyHint: '首页展示摘要，点击内容可阅读全文。文字推荐最多三行摘要，图片和视频推荐最多两行。',
    sortHint: '数值越小越靠前；相同数值按记录编号从小到大，所有已上线推荐均可切换查看。',
  },
  NOTICE: {
    label: '平台公告', create: '新增公告', location: '首页提示入口 → 平台公告 → 公告列表',
    purpose: '发布服务调整、预约安排和平台通知，用户可展开阅读正文。',
    examples: '例如节假日服务安排、临时停运通知、活动规则说明；没有报名、核销等活动管理功能。',
    size: '以可阅读的标题和正文为主，可附图片或视频。首页那句常驻提示单独在“平台配置”维护。',
    titleHint: '写清通知事项，例如“国庆期间预约服务安排”',
    bodyHint: '写清影响范围、时间和处理方式。图片、视频展示在文章标题下方，正文支持换行。',
    sortHint: '数值越小越靠前；相同数值按记录编号从小到大。已发布公告会出现在公告列表。',
  },
  SERVICE: {
    label: '服务说明', create: '新增说明内容', location: '首页 / 我的 → 服务说明（同一个页面）',
    purpose: '维护服务说明页中的轮椅适配、接送点、陪同及行李等补充内容。',
    examples: '首页与“我的”共用这个服务说明页；发布内容加入其中的“平台补充说明”章节，不另建页面。',
    size: '标题与正文独立展示，可附图片或视频。本表仅管理本页补充内容，保留基础正文；预约时限、价格等规则请在对应配置中修改。',
    titleHint: '说明具体事项，例如“轮椅与行李乘车须知”',
    bodyHint: '补充实际服务细节，避免重复或覆盖系统中的预约、计价和取消规则。',
    sortHint: '数值越小越靠前；相同数值按记录编号从小到大。已发布内容依次展示在服务说明页的“平台补充说明”章节。',
  },
}
export function orderedContent(rows: Row[]): Row[] {
  return [...rows].sort((a, b) => Number(a.sortOrder || 0) - Number(b.sortOrder || 0) || Number(a.id) - Number(b.id))
}
export function currentPopup(rows: Row[]): Row | undefined {
  return orderedContent(rows.filter(row => row.kind === 'AD' && Boolean(row.enabled)))[0]
}
export function contentStatus(row: Row, rows: Row[]): string {
  if (!row.enabled) return isAdvertisement(row.kind) ? '已下线' : '未发布'
  if (row.kind === 'AD') return Number(currentPopup(rows)?.id) === Number(row.id) ? '当前展示' : '备用（已上线）'
  return row.kind === 'BANNER' ? '展示中' : '已发布'
}
