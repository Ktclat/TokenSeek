import { useRef, useState } from 'react'
import { useSettings } from '../../services/settings.js'
import { Button } from '../base/Button.js'
import { platformUrls } from './OfficialPlatform.js'
import styles from './Wallet.module.css'

export const presets = ['10', '50', '100']
const copy = {
  'zh-CN': {
    title: '余额与充值',
    amount: '充值金额', custom: '自定义金额', unit: '人民币', copy: '复制金额', checkout: '前往官方收银台',
    boundHint: '请在收银台登录此密钥所属的 DeepSeek 账户。',
    signIn: '充值将计入收银台登录的 DeepSeek 账户。', keys: '绑定 API Key', back: '返回',
    invalid: '请输入大于零的金额，最多两位小数。', overflow: '本机输入最多支持 12 位整数。',
    copied: '已复制金额', copyFailed: '请选中金额后手动复制。',
  },
  'en-US': {
    title: 'Balance and top-up',
    amount: 'Top-up amount', custom: 'Custom amount', unit: 'CNY', copy: 'Copy amount', checkout: 'Open official checkout',
    boundHint: 'Use the DeepSeek account that owns this key at checkout.',
    signIn: 'Funds go to the DeepSeek account signed in at checkout.', keys: 'Connect API key', back: 'Back',
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

export function FundingForm({ accountHint }: { accountHint: string }) {
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
    <header className={styles.formHeader}>
      <h2 id="funding-title">{text.amount}</h2>
      <button type="button" className={styles.copy} disabled={!amount} onClick={() => void copyAmount()}>{text.copy}</button>
    </header>
    <label className={styles.field}>
      <input ref={input} type="text" aria-label={text.custom} inputMode="decimal" autoComplete="off" value={raw} placeholder="0.00"
        aria-invalid={Boolean(error)} aria-describedby={error ? 'funding-error' : undefined}
        onChange={event => change(event.target.value)} /><small>{text.unit}</small>
    </label>
    {error && <p id="funding-error" className={styles.invalid} role="alert">{text[error]}</p>}
    <div className={styles.presets}>{presets.map(item =>
      <button type="button" key={item} aria-pressed={amount === item + '.00'} onClick={() => change(item)}>¥{item}</button>)}</div>
    <div className={styles.actions}>
      {amount
        ? <a href={platformUrls.topUp} target="_blank" rel="noopener noreferrer" className={styles.topUp}>{text.checkout}</a>
        : <Button disabled block size="m">{text.checkout}</Button>}
    </div>
    {amount && <p className={styles.hint}>{accountHint}</p>}
    {feedback && <p role="status" className={styles.hint}>{feedback}</p>}
  </section>
}
