export function inputNickname(event: unknown): string | undefined {
  const value = (event as { detail?: { value?: unknown } } | null)?.detail?.value
  return typeof value === 'string' ? value : undefined
}

/** Native form data includes WeChat's nickname selection even when input did not fire. */
export function submittedNickname(event: unknown): string {
  const value = (event as { detail?: { value?: { nickname?: unknown } } } | null)?.detail?.value?.nickname
  if (typeof value !== 'string') throw new Error('未读取到昵称，请重新填写后保存。')
  const nickname = value.trim()
  if (!nickname) throw new Error('请选择微信昵称或输入昵称。')
  if (nickname.length > 60) throw new Error('昵称最多60个字符，请缩短后保存。')
  if (/[\u0000-\u001f\u007f]/.test(nickname)) throw new Error('昵称不能包含换行或控制字符。')
  return nickname
}

export function nicknameReviewError(event: unknown): string {
  const detail = (event as { detail?: { pass?: unknown; timeout?: unknown } } | null)?.detail
  if (detail?.timeout === true) return '微信昵称检查超时，请重新填写后保存。'
  return detail?.pass === true ? '' : '昵称未通过微信检查，请修改后保存。'
}

export function needsFirstLoginNickname(newUser: unknown, nickname: unknown, alreadyPrompted: boolean): boolean {
  if (typeof newUser === 'boolean') return newUser
  // Compatibility with a deployed backend that predates the registration flag.
  return newUser === undefined && nickname === '微信用户' && !alreadyPrompted
}
