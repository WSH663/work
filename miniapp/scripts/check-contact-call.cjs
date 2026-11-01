// Executes the actual shared API module with isolated uni callbacks. No device calls or network.
const fs = require('node:fs/promises')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const ts = require('typescript')
const root = path.resolve(__dirname, '..')
async function main() {
  const source = await fs.readFile(path.join(root, 'src/api.ts'), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const checks = []
  function setup({ phone = '400-000-0000', configuredPhone = phone, modal = 'cancel', dial = 'success', hold = false, requestFails = false } = {}) {
    const storage = new Map(phone ? [['travel_service_phone', phone]] : [])
    const modals = [], calls = [], toasts = [], requests = []
    const uni = {
      getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value),
      showToast: value => toasts.push(value.title),
      showModal: options => { modals.push(options); if (!hold) modal === 'fail' ? options.fail({ errMsg: 'fixture unavailable' }) : options.success({ confirm: modal === 'confirm', cancel: modal !== 'confirm' }) },
      makePhoneCall: options => { calls.push(options.phoneNumber); if (dial !== 'success') options.fail({ errMsg: dial }); options.complete() },
      request: options => { requests.push(options.url); if (requestFails) options.success({ statusCode: 503, data: { code: 503, message: 'fixture unavailable' } }); else options.success({ statusCode: 200, data: { code: 0, data: { servicePhone: configuredPhone } } }) },
    }
    const exports = {}
    vm.runInNewContext(code, { exports, uni, console, getCurrentPages: () => [], require: name => {
      if (name === './api-base') return { apiUrl: value => 'https://fixture.invalid/api' + value }
      if (name === './account-session') return { clearAccountSession() {} }
      if (name === './api-network-error') return { ApiNetworkError: Error, networkFailureDiagnostic: () => ({ code: 'fixture' }) }
      throw new Error('Unexpected import: ' + name)
    } })
    return { api: exports, modals, calls, toasts, requests }
  }
  {
    const s = setup(); await s.api.callService(); assert.equal(s.modals.length, 1); assert.match(s.modals[0].content, /400-000-0000/); assert.equal(s.modals[0].confirmText, '拨打'); assert.equal(s.modals[0].cancelText, '取消'); assert.equal(s.calls.length, 0); checks.push('客服先显示号码和拨打/取消，取消不触发系统拨号')
  }
  {
    const s = setup({ modal: 'confirm' }); await s.api.callService(); assert.deepEqual(s.calls, ['4000000000']); checks.push('仅确认拨打才发起一次系统通话，并清理号码格式分隔符')
  }
  {
    const s = setup({ hold: true }); const first = s.api.callService(); await s.api.callService(); assert.equal(s.modals.length, 1); s.modals[0].success({ confirm: false }); await first; assert.equal(s.calls.length, 0); checks.push('连续点击不重复弹窗，取消后解除操作锁')
  }
  {
    const s = setup({ phone: '', configuredPhone: '' }); await s.api.callService(); assert.equal(s.modals.length, 0); assert.equal(s.calls.length, 0); assert.match(s.toasts[0], /未配置/); checks.push('电话未配置时提示真实原因，不弹拨号确认')
  }
  {
    const s = setup({ phone: '', configuredPhone: '400-000-0000', modal: 'confirm' }); await s.api.callService(); assert.equal(s.requests.length, 1); assert.deepEqual(s.calls, ['4000000000']); checks.push('无缓存时读取公开配置，确认后使用实际配置号码')
  }
  {
    const s = setup({ modal: 'fail' }); await s.api.callService(); assert.equal(s.calls.length, 0); checks.push('确认弹窗失败时不会直接拨号')
  }
  {
    const s = setup({ modal: 'confirm', dial: 'makePhoneCall:fail cancel' }); await s.api.callService(); assert.equal(s.toasts.length, 0); checks.push('取消系统拨号不显示错误或虚假成功')
  }
  {
    const s = setup({ modal: 'confirm', dial: 'makePhoneCall:fail unavailable' }); await s.api.callService(); assert.match(s.toasts[0], /无法发起通话/); checks.push('系统拨号失败提示可重试，不反馈已接通')
  }
  {
    const s = setup({ phone: '', requestFails: true }); await s.api.callService(); assert.equal(s.calls.length, 0); assert.equal(s.modals.length, 0); assert.match(s.toasts[0], /unavailable/); checks.push('配置读取失败不触发拨号')
  }
  {
    const s = setup(); await s.api.confirmPhoneCall('javascript:bad', '联系司机'); assert.equal(s.calls.length, 0); assert.equal(s.modals.length, 0); checks.push('无效号码被拦截，司机共用相同拨号确认')
  }
  console.log('PASS: contact confirmation regression (' + checks.length + ' checks; no network or system phone calls).')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
