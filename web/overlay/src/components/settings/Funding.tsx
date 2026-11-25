import { useRef, useState } from 'react'
import { useSettings } from '../../services/settings.js'
import { Button } from '../base/Button.js'
import { platformUrls } from './OfficialPlatform.js'
import styles from './Wallet.module.css'

export const presets = ['10', '50', '100']
const copy = {
  'zh-CN': {
    title: '余额与充值', nativeInstructions: '在官方收银台点「填入」或粘贴金额，核对后付款。',
    amount: '充值金额（人民币）', custom: '自定义金额', unit: '元', copy: '复制金额', checkout: '前往官方收银台',
    instructions: '可先复制金额，在 DeepSeek 官方收银台粘贴并确认付款。',
    signIn: '请在收银台登录要充值的 DeepSeek 账户。', keys: '绑定 API Key', back: '返回',
    invalid: '请输入大于零的金额，最多两位小数。', overflow: '本机输入最多支持 12 位整数。',
    copied: '已复制金额', copyFailed: '请选中金额后手动复制。',
  },
  'en-US': {
    title: 'Balance and top-up', nativeInstructions: 'At DeepSeek checkout, tap Fill or paste the amount, then review before paying.',
    amount: 'Top-up amount (CNY)', custom: 'Custom amount', unit: 'CNY', copy: 'Copy amount', checkout: 'Open official checkout',
    instructions: 'Copy the amount to paste and confirm at DeepSeek checkout.',
    signIn: 'At checkout, sign in to the DeepSeek account you want to top up.', keys: 'Connect API key', back: 'Back',
    invalid: 'Enter a positive amount with up to two decimal places.', overflow: 'This field supports up to 12 whole-number digits.',
    copied: 'Amount copied', copyFailed: 'Select the amount to copy it manually.',
  },
}
export const useFundingText = () => useSettings(s => copy[s.locale])

// String arithmetic preserves cents and never rounds or accepts scientific notation.
export function parseAmount(raw: string): { amount?: string; error?: 'invalid' | 'overflow' } {
  if (!raw.trim()) return {}
  if (raw.length > 64) return { error: 'overflow' }
  const value = raw.trim().normalize('NFKC')
  if (!/^(?:\d+(?:\.\d{0,2})?|\.\d{1,2})$/.test(value)) return { error: 'invalid' }
  const [integer, cents = ''] = value.split('.')
  const whole = integer.replace(/^0+/, '') || '0'
  if (whole.length > 12) return { error: 'overflow' }
  const amount = whole + '.' + cents.padEnd(2, '0')
  return amount === '0.00' ? { error: 'invalid' } : { amount }
}

export function FundingForm() {
  const text = useFundingText()
  const [raw, setRaw] = useState('')
  const [feedback, setFeedback] = useState('')
  const input = useRef<HTMLInputElement>(null)
  const { amount, error } = parseAmount(raw)
  const change = (value: string) => { setRaw(value); setFeedback('') }
  const copyAmount = async () => {
    if (!amount) return
    try {
      await navigator.clipboard.writeText(amount)
      setFeedback(text.copied)
    } catch {
      input.current?.select()
      setFeedback(text.copyFailed)
    }
  }
  return <section className={styles.funding} aria-labelledby="funding-title">
    <h2 id="funding-title">{text.amount}</h2>
    <div className={styles.presets}>{presets.map(item =>
      <button type="button" key={item} aria-pressed={amount === item + '.00'} onClick={() => change(item)}>¥{item}</button>)}</div>
    <label className={styles.field}>
      <span>{text.custom}</span>
      <span><input ref={input} type="text" inputMode="decimal" autoComplete="off" value={raw} placeholder="0.00"
        aria-invalid={Boolean(error)} aria-describedby={error ? 'funding-error' : undefined}
        onChange={event => change(event.target.value)} /><small>{text.unit}</small></span>
    </label>
    {error && <p id="funding-error" className={styles.invalid} role="alert">{text[error]}</p>}
    <p className={styles.hint}>{text.instructions}</p>
    <div className={styles.actions}>
      {amount
        ? <a href={platformUrls.topUp} target="_blank" rel="noopener noreferrer" className={styles.topUp}>{text.checkout}</a>
        : <Button disabled size="m">{text.checkout}</Button>}
      <Button variant="outlined" size="m" disabled={!amount} onClick={() => void copyAmount()}>{text.copy}</Button>
    </div>
    {feedback && <p role="status" className={styles.hint}>{feedback}</p>}
  </section>
}
