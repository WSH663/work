# 预约出行 · 开源项目

面向无障碍出行场景的预约用车、预约包车及运营管理系统。使用 Java Spring Boot、MySQL、Vue 3 和 uni-app。本文统一维护功能、页面、业务规则、接口与部署说明，更新日期：2026-11-01。

本仓库提供脱敏后的源码和初始化示例，不包含运营数据、账号密码、微信 AppID/AppSecret、地图密钥、原品牌素材或生产域名。请自行配置数据库、管理员初始密码、微信账号及地图服务。部署域名 `api.example.com` 仅为占位；项目沿用仓库已有的 Apache-2.0 许可证，第三方 SDK 和依赖遵循各自许可及服务条款。

## 目录

1. [项目结构与技术栈](#1-项目结构与技术栈)
2. [产品与页面设计](#2-产品与页面设计)
3. [订单与资金规则](#3-订单与资金规则)
4. [广告公告与文档配置](#4-广告公告与文档配置)
5. [本地开发](#5-本地开发)
6. [配置说明](#6-配置说明)
7. [数据库与版本迁移](#7-数据库与版本迁移)
8. [接口约定](#8-接口约定)
9. [构建与部署](#9-构建与部署)
10. [验证与待接入能力](#10-验证与待接入能力)
11. [文件维护与资源来源](#11-文件维护与资源来源)

## 1. 项目结构与技术栈

| 目录 | 职责 | 技术 |
| --- | --- | --- |
| `backend/` | 接口、权限、订单、财务、内容及地图代理 | Java 17+、Spring Boot 3.5.7、JDBC、Flyway、MySQL 8 |
| `admin-web/` | 运营管理后台 | Vue 3、TypeScript、Element Plus、Vite 7 |
| `miniapp/` | 微信小程序及 H5 | uni-app、Vue 3、TypeScript |
| `scripts/` | 本地启动、停止、图标生成 | PowerShell、Node.js |
| `.local/` | 本机运行日志、管理员访问资料 | 不提交到版本库 |

```text
项目根目录/
├─ README.md                         本文，唯一项目主文档
├─ backend/
│  ├─ src/main/java/                  业务代码及 Java 迁移
│  ├─ src/main/resources/
│  │  ├─ application.yml              通用配置
│  │  ├─ db/migration/                Flyway SQL 迁移
│  │  └─ documents/                   三份文档初始化内容
│  ├─ src/test/                       正式业务回归测试
│  ├─ application-local.example.yml   本机配置示例
│  ├─ application-local.yml           自行创建的私有配置，不提交
│  └─ target/travel-backend-0.1.0.jar  唯一后端发布包
├─ admin-web/
│  ├─ src/                            后台页面、组件与接口调用
│  └─ dist/                           后台发布目录
├─ miniapp/
│  ├─ src/                            页面、组件、地图与正式资源
│  ├─ scripts/                        地图与拨号纯逻辑回归
│  └─ dist/
│     ├─ build/mp-weixin/             微信开发者工具导入目录
│     └─ release/h5/                  H5 发布目录
└─ scripts/
   ├─ start-dev.ps1
   ├─ stop-dev.ps1
   └─ generate-icons.mjs
```

## 2. 产品与页面设计

### 2.1 产品定位

主要使用者为轮椅乘客、长者和代约亲友。流程采用提前预约、确认费用、支付后人工派单。司机姓名、电话、车牌及服务开始/完成由运营核实并登记。

### 2.2 用户端页面

底部导航为“首页 / 订单 / 我的”。公开内容可游客浏览；个人资料、推广、消息及提交订单需要 USER 身份。

| 页面路径 | 主要任务与布局 |
| --- | --- |
| `/pages/index/index` | 地图、上下车地点与预约入口优先；用车/包车切换，推荐广告、公告及车型信息靠后 |
| `/pages/address/index?role=origin或destination` | 搜索与地图默认可见；真实定位、附近/常用/最近地址，预览后明确确认地点 |
| `/pages/booking/index?type=RIDE或CHARTER` | 按行程、时间、车型、乘车人顺序填写；展示估价或人工报价说明，再确认提交 |
| `/pages/login/index` | 微信身份登录、手机号授权登录、协议确认、邀请码；首次注册弹出昵称填写/选择 |
| `/pages/orders/index` | 按报价确认、待支付、待出行、服务中、完成、取消退款分组；列表分页及当前操作 |
| `/pages/order/index?id=订单ID` | 状态和下一步操作优先，随后行程、报价、司机、支付退款、日志 |
| `/pages/profile/index` | 资料、微信手机号绑定/换绑、常用资料、客服、长辈模式与退出 |
| `/pages/resources/index?kind=passengers或addresses` | 常用乘车人与地址的查看、新增、删除 |
| `/pages/promotion/index` | 邀请码、带 Logo 的官方小程序码、分享/保存、佣金及提现 |
| `/pages/messages/index` | 站内消息、已读与关联订单跳转 |
| `/pages/agreement/index?kind=service或privacy或terms或notice` | 服务说明、隐私说明、用户协议与公告阅读；文档目录、章节和必要操作 |

首页不设置地区、客服或长辈模式快捷按钮。客服和长辈模式在“我的”；首页与“我的”的服务说明打开同一份文档。

### 2.3 后台页面与权限

| 页面路径 | 职责 | 可访问角色 |
| --- | --- | --- |
| `/login` | 管理员登录 | 未登录 |
| `/dashboard` | 经营指标、待办和近期订单 | ADMIN / OPERATOR / FINANCE |
| `/orders` | 查询、详情、报价、派单、服务和取消处理 | 三种后台角色；操作按职责限制 |
| `/users` | 用户资料、状态及推广关系 | ADMIN / OPERATOR |
| `/vehicles` | 车型、计价及上下线 | ADMIN / OPERATOR |
| `/content` | 首页弹窗与推荐广告 | ADMIN / OPERATOR |
| `/information` | 平台公告 | ADMIN / OPERATOR |
| `/documents` | 服务说明、隐私说明、用户协议 | ADMIN / OPERATOR |
| `/payments` | 支付、退款、资金流水 | ADMIN / FINANCE |
| `/commissions` | 推广佣金及结算记录 | ADMIN / FINANCE |
| `/withdrawals` | 提现审核与付款登记 | ADMIN / FINANCE |
| `/settings` | 平台参数 | ADMIN |
| `/admins` | 管理员、角色、密码与启停 | ADMIN |
| `/logs` | 操作日志 | ADMIN |

ADMIN 可执行运营和财务操作。OPERATOR 不办理财务退款、提现；FINANCE 不报价、派单或改变服务状态。服务端每次请求再次鉴权。

侧栏采用真实待办数字提醒，不把普通未读消息或等待用户的订单当作员工待办：

| 提醒入口 | 计数范围 | 点击数字后的路径 |
| --- | --- | --- |
| 订单与派单 | 预约未过期且可报价的包车、待派单、取消待审核 | `/orders?worklist=operations` |
| 支付与退款 | 已进入待退款的订单 | `/orders?worklist=refunds` |
| 提现审核 | 待审核及审核通过后待登记付款的申请 | `/withdrawals?worklist=todo` |

按账号角色返回和显示提醒；0 隐藏，超过 99 显示 `99+`，读屏仍报告实际数量。普通菜单进入完整列表，数字进入对应待办；筛选、重置、分页保留待办范围，另有查看全部入口。工作台共用同一待办数据，“待用户确认报价/支付”独立显示。真实退款尚未接入时仍保留待退款数量，并在列表解释能力限制。

页面可见时每 30 秒更新，回到前台、切换页面和成功处理业务后自动刷新，侧栏底部可手动刷新。请求失败保留上次统计并标记为旧数据；首次失败显示不可用，不伪装为 0。退出或切换身份清空统计，旧身份响应不能回写。

### 2.4 统一界面规则

- 白色、浅灰色构建层级，品牌绿 `#16845b` 用于主要操作及选中状态；间距以 4/8px 为基准。
- 正文以 15–16px 为主，长辈模式主要正文 18px；常规触控区域至少 44px，长辈模式至少 48px。
- 地图、列表、详情、表单和阅读页各按任务组织，不统一堆叠装饰卡片。
- 统一线性图标；表单有字段校验、提交锁和错误原因；列表有加载、空态、分页或重试。
- 固定操作区避让安全区域，长文本换行。删除、发布、拨号等操作有明确确认。

### 2.5 定位与路线

微信使用原生腾讯地图及 `uni.getLocation({ type: 'gcj02' })`；H5 使用浏览器定位并转换到 GCJ-02，高德静态底图经后端代理。两端优先获取真实设备位置，失败显示中性占位、重试和搜索入口，不把固定城市参考点当作当前位置。

选点页的地图中心预览与最终确认分开。地点提示、逆地理、附近地点和驾车路线由高德接口提供。**计价距离按后端驾车规划路程计算，不按直线距离计算**。开发者工具模拟位置不能作为真机 GPS 精度验收。

## 3. 订单与资金规则

### 3.1 订单主流程

用车：选择行程 → 后端路线估价 → 提交 → 待支付 → 待派单 → 已派单 → 服务中 → 已完成。

包车：提交行程和需求 → 待人工报价 → 待确认报价 → 待支付，之后同用车。

| 状态 | 用户操作 | 运营/财务操作 |
| --- | --- | --- |
| `PENDING_QUOTE` 待人工报价 | 等待、取消 | 运营报价、取消 |
| `PENDING_CONFIRM` 待确认报价 | 查看并确认最新报价、取消 | 运营修改报价、取消 |
| `PENDING_PAYMENT` 待支付 | 支付、取消 | 查询、运营取消 |
| `PENDING_DISPATCH` 待派单 | 查询、按规则申请取消 | 运营派单、人工取消 |
| `DISPATCHED` 已派单 | 联系司机、按规则申请取消 | 运营改派、开始服务、人工取消 |
| `IN_SERVICE` 服务中 | 联系司机或客服 | 运营完成服务，或核实异常后中止 |
| `COMPLETED` 已完成 | 查看、联系客服售后 | 财务按原因办理全额售后退款 |
| `CANCEL_REQUESTED` 取消待审核 | 等待、联系客服 | 运营审核通过/驳回 |
| `CANCELLED` 已取消 | 查看、重新预约 | 查询；未发生付款的终态 |
| `REFUNDING` 待财务退款 | 查看进度、联系客服 | 财务执行全额退款 |
| `REFUNDED` 已退款 | 查看记录、重新预约 | 查询资金流水 |

前端按后端返回的 `actions`、取消规则和当前角色显示可用操作；服务端重新检查状态与归属。

### 3.2 校验与异常处理

- 下单 `requestKey` 与提现请求键用于幂等；同一草稿网络重试复用原键，变更实际输入后再更换，避免重复订单或重复冻结。
- 包车报价使用 `quoteVersion`。用户或后台提交旧版本返回 409；用户确认后不得继续改价，变更需求走取消重订。
- 未付款取消直接进入 `CANCELLED`。已付款且符合配置时限的取消进入 `CANCEL_REQUESTED`，审核通过转 `REFUNDING`，驳回恢复原待派单/已派单状态。
- 临近预约不提供无效的自助取消按钮，转客服协助。服务中只可执行有原因的“中止服务”，转全额退款，不生成完成佣金。
- 预约时间已过的未付订单不可继续报价、确认或支付，取消后重新预约。
- 本期仅全额退款，没有部分退款或取消费。`REFUNDING` 表示等待财务办理，不表示已提交微信退款。
- 历史支付、累计退款和净收分开保留；操作有订单日志、审计和关联站内消息。

### 3.3 佣金与提现

已支付订单完成后生成 `PENDING` 佣金，预计结算时间为完成后 30 天。到期且订单仍已完成、未退款，转 `SETTLED` 并计入可提现；等待期退款转 `REVERSED`。

到期任务默认启动 60 秒后检查，后续间隔 60 秒，每批最多 100 条；停机恢复后补处理。参数为 `app.commission-settlement-initial-delay-ms`、`app.commission-settlement-delay-ms`，默认均为 `60000` 毫秒。预计时间不承诺精确秒级入账。

提现申请冻结余额，审核通过仍需财务实际转账并填写凭证才进入 `PAID`；系统不自动划款。驳回或撤销尚未付款的审核会解冻，已付款不可撤销。已经结算的佣金退款需要足够可用余额冲回；已提现或冻结造成余额不足时会阻止自动退款，独立追偿台账尚未实现。

## 4. 广告公告与文档配置

### 4.1 内容分工

| 后台入口 | 管理对象 | 用户端展示 |
| --- | --- | --- |
| 首页广告 `/content` | `AD` 首页弹窗、`BANNER` 首页推荐位 | 首页局部浮层与推荐区 |
| 平台公告 `/information` | `NOTICE` 通知文章 | 首页入口及公告阅读页 |
| 文档配置 `/documents` | `service`、`privacy`、`terms` 三份整篇文档 | 服务说明、隐私说明、用户协议 |
| 平台配置 `/settings` | 客服电话、服务区域、首页提示、预约取消、佣金提现参数 | 真实业务校验与对应提示 |
| 车型与价格 `/vehicles` | 车型、基础价、里程价、包车价、附加费 | 车型选择和服务端计价 |

首页提示 `settings.notice` 独立维护，发布公告不会自动改写它。编辑说明正文也不会改变真实预约、价格或取消规则。

### 4.2 广告展示与运营

每次冷启动或从后台重新进入产生一次新访问，该次首次到首页展示排序最前的一条已上线弹窗；同一次访问内部切页不重复。加载失败或没有广告时不消耗展示机会。

- 弹窗覆盖首页局部区域，四周可见首页；**用户可随时关闭**。`skipSeconds` 仅为提示，不限制关闭。
- 文字广告不留空媒体区；图片等比完整显示并可预览；视频先展示封面，由用户点击播放，隐藏/离开时暂停或卸载。
- 建议素材：弹窗图片 4:3、首页横幅图片 2.4:1、视频 16:9。后台和前台保留标题、正文与必要跳转，失败可重试。
- 新增默认下线，支持预览、排序、上下线和复制到另一广告位。多个弹窗明确当前/备用关系。
- 素材填写公开 HTTPS 链接，跳转限制为已有站内页面。没有素材上传、存储、视频转码、广告排期或曝光点击统计。

### 4.3 文档编辑发布

服务说明、隐私说明、用户协议各自拥有完整标题、引言、有序章节和页尾，可编辑整篇、新增/删除/排序/停用章节，配置图片、视频、相关页面及真实业务入口。

操作：选择文档 → 编辑与手机预览 → 保存草稿 → 确认发布保存版。保存草稿不影响用户；发布只更新当前这一篇。未保存离开或切换需确认，多人编辑版本冲突返回 409，要求重新加载。

公开接口仅返回发布版和启用章节。文档首次失败无缓存时显示错误和重试；只有同类型先前成功读取的缓存可以兜底，并明确显示缓存提示。文本按纯文本换行，不执行 HTML。

可引用 `{{serviceArea}}`、`{{servicePhone}}`、`{{advanceMinutes}}`、`{{maxAdvanceDays}}`、`{{cancelHours}}`、`{{advanceRule}}`、`{{bookingRangeRule}}`、`{{cancellationRule}}`；读取时替换为当前业务配置。未知或未闭合变量不能保存，URL 不接受模板拼接。

章节 `actions` 对应 `contact`、`profile`、`passengers`、`addresses`、`amapPrivacy`。所有客服和司机电话共用“显示实际号码 → 拨打/取消 → 确认后系统拨号”，取消不发起通话。

当前只保留一份草稿和一份发布版，没有历史多版本回滚、文档审批流或阅读签署统计。

## 5. 本地开发

### 5.1 环境与安装

需要 Java 17+、Maven 3、MySQL 8；Node.js 20.19+ 或 22.12+，分别在两个前端安装锁定依赖：

```powershell
cd admin-web
npm ci
cd ..\miniapp
npm ci
cd ..
```

从示例创建本地配置：

```powershell
Copy-Item backend/application-local.example.yml backend/application-local.yml
Copy-Item admin-web/.env.example admin-web/.env.local
Copy-Item miniapp/.env.example miniapp/.env.local
```

先新建一个空的 UTF-8/utf8mb4 MySQL 8 数据库及专用应用账号，再设置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 和至少 10 位的 `ADMIN_PASSWORD`。这些变量也可放在本地 YAML 对应属性中；真实值只能保存在已被 Git 忽略的私有配置或部署环境中。首次管理员不存在而初始密码为空时，后端会拒绝启动，开发环境也没有固定默认密码。

### 5.2 启动停止

从项目根目录运行，按需选择组件：

```powershell
.\scripts\start-dev.ps1 -Component admin
.\scripts\start-dev.ps1 -Component miniapp
.\scripts\start-dev.ps1 -Component backend
# 全部启动；已有后端包可加 -SkipBuild
.\scripts\start-dev.ps1 -SkipBuild
.\scripts\stop-dev.ps1
```

| 组件 | 地址 |
| --- | --- |
| 管理后台 | `http://localhost:5173` |
| 小程序 H5 开发预览 | `http://localhost:5174` |
| 本地后端健康检查 | `http://localhost:18080/api/health` |

`-Component miniapp` 启动 H5，不是微信编译监听。微信开发在 `miniapp` 执行 `npm run dev:mp-weixin`，由启动它的 HBuilderX/终端停止；上述停止脚本只管理自己记录的进程，不停止其他项目或 MySQL。

开发模式默认连接 **`http://localhost:18080`**；可在相应前端本地环境配置 `VITE_API_BASE_URL`。生产环境文件仅含 `https://api.example.com` 占位，构建发布前必须改成自己的 HTTPS 后端地址。手机访问本地后端需使用电脑可达的局域网地址；构建不会启动应用服务。

## 6. 配置说明

### 6.1 后端

后端通用配置在 `backend/src/main/resources/application.yml`，额外读取当前工作目录的 `application-local.yml` 和 `backend/application-local.yml`（存在时均读取，后列文件的同名属性优先）。部署时把私有配置放在 JAR 旁并从该目录启动。

下表变量是通用配置里的占位符名称。如果私有 YAML 已直接填写同名 Spring 属性（例如 `spring.datasource.password`），再设置 `DB_PASSWORD` 不会自动覆盖该字面量。需要环境变量接管时，私有 YAML 也使用 `${DB_PASSWORD}` 等占位符，或使用 Spring 原生属性变量（如 `SPRING_DATASOURCE_PASSWORD`、`APP_ADMIN_PASSWORD`）；显式命令行参数优先。

| 环境变量 | 作用 |
| --- | --- |
| `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | MySQL 连接，不是后台登录账号 |
| `ADMIN_USERNAME`、`ADMIN_PASSWORD` | 首次创建管理员的初始凭据 |
| `PORT` | 后端监听端口，默认 18080 |
| `CORS_ORIGINS` | 浏览器允许访问接口的完整来源，逗号分隔 |
| `AMAP_WEB_KEY`、`AMAP_MINI_KEY` | 高德服务端接口和微信备用检索 |
| `WECHAT_APP_ID`、`WECHAT_APP_SECRET` | 微信身份、手机号及小程序码服务端调用 |
| `DEV_PAYMENTS_ENABLED` | 开发模拟交易开关；生产还受 profile 限制 |
| `WECHAT_ORDER_TEMPLATE_ID`、`WECHAT_REFUND_TEMPLATE_ID`、`WECHAT_WITHDRAWAL_TEMPLATE_ID` | 订阅消息预留模板配置，当前不自动发送 |

首次管理员不存在时，所有环境都要求显式配置至少 10 位初始密码；已存在账号后，修改初始配置不会重置其密码。账号操作在后台管理员页面完成。请为部署设置独立随机密码，不要沿用测试 fixture。

CORS 来源应含协议、域名及需要时的端口，不带路径；默认只包含四个 localhost/127.0.0.1 开发来源。生产环境请设置自己的前端来源；环境变量会替换整段默认列表。CORS 仅控制浏览器跨域，不负责 DNS、证书、Nginx 或微信合法域名。

### 6.2 前端和微信配置

- `VITE_API_BASE_URL`：后端根地址，不重复添加 `/api`，修改后重新构建。
- `VITE_H5_USE_API_PROXY`：仅控制 H5 开发代理。
- `VITE_AMAP_MINI_KEY`：微信端服务端检索失败时的高德 SDK 备用检索。
- `VITE_ENABLE_DEV_LOGIN`：历史兼容开关，登录页无开发入口，不能替代后端 production 保护。
- 微信 AppID 在 `miniapp/src/manifest.json` 的 `mp-weixin.appid`，开源版留空；在本地填写并与后端一致。AppSecret 仅放后端，不能放 `VITE_` 变量。AppID、地图 key 及真实域名的本地改动不要提交到公开分支。

小程序微信身份 code 与 `getPhoneNumber` code 是两种独立凭证。普通身份登录不会自动获得手机号；用户点击手机号授权按钮后，由后端向微信换取实际授权号码。手机号权限、额度以及隐私声明需在微信公众平台确认。首次昵称使用微信支持的昵称输入交互，不无授权静默读取资料。

微信公众平台 request 合法域名应包含你自己的 HTTPS 后端域名，使用高德 SDK 备用检索时包含 `https://restapi.amap.com`；媒体与相册功能按实际配置 downloadFile/素材域名和权限。上传前验证关闭开发调试后体验版也正常，开发工具或真机调试成功不能替代合法域名校验。

## 7. 数据库与版本迁移

业务库为 `accessible_travel`，字符集 UTF-8/utf8mb4。**初始化和升级以 Flyway 为唯一来源**，无需导入旧整库 SQL。第一次启动建表及默认数据，已有库按未执行版本增量迁移；不要修改已执行版本。

| 版本 | 变更 |
| --- | --- |
| V1 | 基础业务表、开发车型和默认平台参数 |
| V2 | 订单请求键和报价版本 |
| V3 | 站内消息关联订单 |
| V4 | 30 天佣金结算字段 |
| V5 | 提现请求键与重复请求保护 |
| V6 | 审计对象字段扩容 |
| V7 | 微信手机号验证标志、国家码和验证时间 |
| V8 | 广告视频封面和按钮文案 |
| V9 | Java 迁移创建整篇文档配置，初始化草稿/发布版并导入旧 SERVICE |

SQL 迁移在 `backend/src/main/resources/db/migration/`，V9 在 `backend/src/main/java/db/migration/`，初始文档在 `resources/documents/`。V9 保留旧 content、用户、订单和资金记录；读取文档接口不隐式初始化数据。

启动新版 JAR 时，Flyway 按数据库当前版本执行尚未执行的迁移。开源版已将 V1 初始化数据中的品牌、地区和运营信息改为通用示例，因此应使用**新建空库**。不要直接连接旧项目的业务库，也不要通过跳过校验覆盖旧迁移历史。

### 7.1 初始化数据

仓库只保留 8 份 SQL 迁移、Java V9 和默认文档 JSON，不提供用户、订单、推广关系、财务、会话或完整业务数据库快照。首次启动由 Flyway 自动创建表和示例车型、平台设置及文档；管理员由显式配置的初始密码创建。

平台名称、服务地区、客服电话、车型价格及说明需要由部署方审核配置。示例内容不代表已具备当地运营、微信支付或手机号接口权限。`db/backup/**` 即使在本地存在，也被 Git 忽略并从 JAR 资源中排除。

## 8. 接口约定

### 8.1 通用格式

所有接口以 `/api` 为前缀。认证头 `Authorization: Bearer <token>`。常规响应：

```json
{"code":0,"message":"ok","data":{}}
```

错误有对应 HTTP 状态和非零 code；409 表示当前状态/版本冲突，需重新读取。分页结构 `{items,total,page,size}`；金额为元、路线距离为公里，附近地点 distance 为米。时间采用服务时区 Asia/Shanghai 的 ISO 本地格式。`GET /map/static` 成功返回 PNG 二进制，是 JSON 格式的例外。

### 8.2 公共、登录与用户接口

下表路径省略 `/api`；完整字段校验以对应 Controller/Service 为准。

| 接口 | 用途或关键输入 |
| --- | --- |
| `GET /health` | 健康及 DEV/生产模式 |
| `GET /public/home`、`/public/config`、`/public/vehicles` | 首页、公开参数和车型，不返回服务端秘密 |
| `POST /auth/admin/login` | `{username,password}` |
| `POST /auth/wechat/login` | `{code,phoneCode?,inviteCode?,agreementAccepted:true}`；返回 token、user 及首次用户标识 |
| `POST /auth/logout` | 撤销当前 token |
| `GET /me`、`POST /me` | 查看用户、修改昵称；不允许手工覆盖账号手机号 |
| `POST /me/phone` | `{code}`，微信授权绑定或换绑，写入 phoneVerified 等信息 |
| `GET/POST /me/passengers`、`DELETE /me/passengers/{id}` | 常用乘车人 `{name,phone}` |
| `GET/POST /me/addresses`、`DELETE /me/addresses/{id}` | 常用地址 `{label,address,location}` |
| `GET /map/tips?keywords=&city=` | 地点搜索 |
| `GET /map/reverse?location=lng,lat`、`/map/nearby?location=lng,lat` | 逆编码、附近地点 |
| `GET /map/static?location=&zoom=&width=&height=` | H5 静态底图；错误仍返回错误 JSON |
| `POST /quotes/estimate` | `{vehicleId,origin,destination}`，服务端路线与费用 |
| `GET /orders?status=&group=&page=&size=` | 精确状态或业务分组分页 |
| `POST /orders` | `{requestKey?,type,vehicleId,originName,destinationName,origin,destination,appointmentAt,passengerName,passengerPhone,hours?,itinerary?,remark?}` |
| `GET /orders/{id}` | 行程、actions、cancelRules、日志、payments 和资金汇总 |
| `POST /orders/{id}/cancel` | `{reason}` |
| `POST /orders/{id}/accept-quote` | `{quoteVersion}` |
| `POST /orders/{id}/pay` | 当前只有 DEV 模拟支付可用，生产真实能力未接入时明确失败 |
| `GET /me/promotion`、`/me/promotion/qrcode` | 推广余额/关系/记录、带 Logo 的官方小程序码 |
| `POST /me/withdrawals` | `{requestKey?,amount,accountName,accountNo}` |
| `GET /me/messages`、`POST /me/messages/{id}/read` | 站内消息与已读，关联订单仍校验归属 |

`location` 格式为 GCJ-02 的 `lng,lat`。常用乘车人联系方式与账号授权手机号是不同业务字段。开发身份接口仅供隔离联调，生产禁止，用户登录页没有此入口。

### 8.3 后台接口

| 接口 | 操作 |
| --- | --- |
| `GET /admin/dashboard` | 统计与近期订单 |
| `GET /admin/todos` | 按角色返回 `operations?`、`finance?`、`total`；运营含 `pendingQuotes/pendingDispatch/cancelRequests/total`，财务含 `pendingRefunds/pendingWithdrawals/approvedWithdrawals/total` |
| `GET /admin/orders`、`/admin/orders/{id}` | 查询和详情；列表支持 `worklist=operations或refunds`，与状态/搜索/类型取交集 |
| `POST /admin/orders/{id}/quote` | `{amount,remark?,quoteVersion?}` |
| `POST /admin/orders/{id}/dispatch` | `{driverName,driverPhone,plateNumber}`，含改派 |
| `POST /admin/orders/{id}/start`、`/complete` | 开始、完成服务 |
| `POST /admin/orders/{id}/cancel-review` | `{approved,reason?}`，明确布尔决策，驳回须原因 |
| `POST /admin/orders/{id}/cancel`、`/terminate`、`/refund` | `{reason}`，人工取消、中止、财务全额退款 |
| `GET /admin/users`、`PATCH /admin/users/{id}` | 用户分页查询、状态管理 |
| `GET/POST /admin/vehicles`、`PATCH /admin/vehicles/{id}` | 车型和价格 |
| `GET/POST /admin/content`、`PATCH/DELETE /admin/content/{id}` | 广告/公告内容；字段含 kind、title、body、mediaType、mediaUrl、posterUrl、linkUrl、actionText、sortOrder、enabled、skipSeconds |
| `GET /admin/payments`、`/admin/commissions` | 资金/佣金分页查询 |
| `GET /admin/withdrawals` | 提现列表；`worklist=todo` 限定 PENDING/APPROVED，与精确状态取交集 |
| `POST /admin/withdrawals/{id}/review`、`/cancel`、`/paid` | 审核、撤销未付款审核、凭 paymentReference 登记已转账 |
| `GET/POST /admin/settings` | 平台名称/客服/区域/提示、预约/取消、佣金/提现门槛；先校验可预约时间窗口 |
| `GET/POST /admin/admins`、`PATCH /admin/admins/{id}` | 账号、角色、启停、密码 |
| `GET /admin/logs` | 审计分页 |

### 8.4 文档接口与结构

`kind` 为 `service`、`privacy` 或 `terms`。

- `GET /public/documents/{kind}` → `{document,revision,publishedAt}`，变量已解析，仅发布版启用章节。
- `GET /admin/documents/{kind}` → `{kind,draft,published,revision,publishedRevision,updatedAt,publishedAt,variables}`。
- `POST /admin/documents/{kind}`，请求 `{revision,document}`，只保存草稿，revision 增加。
- `POST /admin/documents/{kind}/publish`，请求 `{revision}`，发布已保存版本；过期版本返回 409，重复发布幂等。

```text
Document = {
  title, introduction, footerTitle, footer,
  sections: [{
    id, title, body, mediaType, mediaUrl, posterUrl,
    linkUrl, actions, enabled
  }]
}
```

最多 30 节，每节 body 8000 字，整篇文本 60000 字；title/章节标题/footerTitle 最长 120，introduction 最长 2000，footer 最长 4000，URL 最长 1000。章节 id 为小写字母开头、最多 64 位的小写字母/数字/短横线，整篇唯一；前端锚点加 `doc-section-` 前缀。`mediaType=TEXT/IMAGE/VIDEO`，媒体使用 HTTPS。发布至少有一节启用。后台 ADMIN/OPERATOR 可维护，FINANCE 无权。

## 9. 构建与部署

### 9.1 构建命令和唯一发布路径

```powershell
cd backend
mvn package
cd ..\admin-web
npm run build
cd ..\miniapp
npm run typecheck
npm run build:h5 -- --outDir dist/release/h5
npm run build:mp-weixin
```

| 组件 | 发布产物 |
| --- | --- |
| 后端 | `backend/target/travel-backend-0.1.0.jar` |
| 后台 | `admin-web/dist/` |
| 可选 H5 | `miniapp/dist/release/h5/` |
| 微信小程序 | `miniapp/dist/build/mp-weixin/` |

不使用旧别名 JAR、`dist/build/h5` 或 `dist/dev` 作为发布包。更新前端依赖用 `npm ci`，保留 package-lock.json。重建输出目录时仅清理相应 dist，不能删除源目录或配置。

### 9.2 运行后端

服务器安装 Java 17+、配置 MySQL，上传 JAR 和私有配置。从 JAR 所在目录启动：

```bash
java -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai \
  -jar travel-backend-0.1.0.jar \
  --spring.profiles.active=production \
  --app.dev-payments-enabled=false
```

没有独立的 `application-production.yml`；production profile 由代码控制开发能力保护。正式运行采用宝塔 Java 项目或 systemd 管理进程，监听 18080，Nginx 提供 HTTPS。数据库连接和后台初始账号分开配置。

使用自己的后端域名，例如 `api.example.com`，配置实际证书。反代关键部分为：

```nginx
location / {
    proxy_pass http://127.0.0.1:18080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_connect_timeout 60s;
    proxy_read_timeout 120s;
}
```

不在 proxy_pass 后加 `/api`，避免改写接口路径。健康检查示例为 `https://api.example.com/api/health`；仅健康成功不能证明微信合法域名、登录或支付已正确配置。

### 9.3 发布前端

后台与可选 H5 部署各自静态目录到 HTTPS 站点，前端已使用 hash 路由。静态站点域名与后端不同时，把真实网页完整来源加入 CORS。不要把开发 Vite 服务器当正式站点。

微信开发者工具导入 `miniapp/dist/build/mp-weixin`，确认 AppID、后端地址和合法域名，重新编译后上传体验版。配套更新顺序为：后端及迁移 → 后台静态文件 → 小程序与可选 H5。只替换前端不能获得尚未部署的后端接口。

仓库仅提供源码；运行产物需按上述命令自行构建，服务器与微信发布账号由部署方配置。

## 10. 验证与待接入能力

### 10.1 保留的自动验证

```powershell
cd backend
mvn test
cd ..\miniapp
npm test
npm run typecheck
cd ..\admin-web
npm run build
```

后端保留 16 个测试类，覆盖订单、并发、权限、资金、30 日结算、迁移、地图、微信授权、推广码、文档及后台待办等 86 项检查。小程序 `npm test` 保留地图坐标/投影与客服确认的纯逻辑回归，不访问真实网络、电话或数据库。后端测试使用临时 H2，不修改本地或线上 MySQL。

测试中的手机号、坐标、账户、code 和 token 均为隔离 fixture，不是部署凭据。测试不依赖真实微信、地图账号或生产数据库。真实设备授权、短信/手机号额度及第三方权限需部署方另行验证。

2026-11-01 脱敏版已验证：后端 16 类、86 项测试及 JAR 打包，后台生产构建，小程序类型检查、地图与客服回归、微信包及 H5 构建全部通过。真实微信和地图能力仍需自行配置后在设备上验证。

### 10.2 当前已实现与限制

| 能力 | 当前状态 |
| --- | --- |
| 微信身份、手机号授权登录/绑定、首次昵称 | 已实现；手机号仍依赖微信权限和额度，真机验证授权/拒绝/换绑 |
| 预约、人工报价派单、取消审核、资金记录 | 已实现；支付退款目前仅 DEV 模拟 |
| 微信真实支付和退款 | 验签、回调及真实交易未接入，生产不伪造成功 |
| 订阅消息 | 预留配置/适配入口，当前仅站内消息，填模板 ID 不会自动发送 |
| 官方小程序码、Logo、分享、保存 | 已实现；手机扫码归因、相册权限及浏览器复制支持需实际验证 |
| 佣金、提现 | 30 天后结算、冻结/审核/人工付款登记；无自动转账或独立追偿台账 |
| 内容和文档 | 整篇草稿发布、广告/公告；无素材上传、转码、广告统计排期或历史回滚 |
| 服务区域 | 运营说明，可配置；无行政边界电子围栏 |
| 财务对账 | 有流水和基础统计；无微信账单自动拉取、差异自动对账 |
| 司机与车辆 | 人工登记；无司机端、自动派单、实时轨迹 |
| 账号与隐私 | 无自助注销、统一保留期限/自动删除、导出及隐私工单 |

正式运营前确认真实车型、价格、客服、服务范围、运营主体及协议条款。默认文档和数据需要运营审阅。微信真机还需验证定位精度、原生地图与弹窗层级、视频解码、手机号、扫码、相册、拨号、安全区和长辈字号；H5 测试或编译成功不能替代这些验收。

## 11. 文件维护与资源来源

### 11.1 文件整理原则

- 产品设计、开发、接口、部署和功能边界统一更新本文，不再新增每轮修改记录文档。
- 正式源码、Flyway 迁移、默认文档 JSON、Logo/图标、配置示例、锁文件及必要回归测试保留。
- 数据库导出、证书私钥、账号凭据、微信开发者工具私有配置、截图、日志和构建产物不得进入仓库。
- `.local/`、私有 YAML、`.env`/`.env.local` 等由本地环境管理；公开配置只保留空值或示例。
- `node_modules`、`target` 和 `dist` 不作为源码交付；依赖按 lock 文件安装，运行产物自行构建。

### 11.2 图标和第三方来源

小程序 PNG 线性图标与无文字通用车辆 Logo 由 `scripts/generate-icons.mjs` 中的形状源生成，五种固定色，无远程图标请求。运行生成脚本需 Node 可解析 `sharp`，成品在 `miniapp/src/static/icons/` 与 `miniapp/src/static/brand-logo.png`，无需在正常构建时重新生成。后端推广码使用同一 Logo 资源。

`miniapp/src/vendor/amap-wx.ts` 来自高德官方 AMapWX SDK V1.3.0，仅将 CommonJS 导出适配为 ESM 并添加 TypeScript 检查豁免；SDK 逻辑保持原样。原下载压缩包不参与构建，已清理。官方来源与使用说明：

- [高德微信 SDK 下载说明](https://lbs.amap.com/api/wx/download)
- [原始 AMapWX SDK V1.3.0](https://a.amap.com/lbs/static/zip/AMapWX_SDK_V1.3.0.zip)
- [高德输入提示接口](https://lbs.amap.com/api/wx/guide/get-data/get-inputtips)

地址搜索优先使用服务端 `/api/map/tips`，仅微信端失败时使用 SDK 备用检索；H5 不包含该微信 SDK。使用时遵守供应方许可及商业使用要求，地图自身必要标识按组件保留。
