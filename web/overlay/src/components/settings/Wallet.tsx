import { useLayoutEffect } from 'react'
import { useTheme } from '../../app/theme.js'
import { fontScales, useChatPreferences } from '../../services/chatPreferences.js'
import { useTranslate, type TranslationKey } from '../../i18n/index.js'
import { useBalance, type WalletState } from '../../services/walletBalance.js'
import { platformUrls } from './OfficialPlatform.js'
import { Button } from '../base/Button.js'
import { backSettings, closeSettings, pushSettings } from './settingsDialogState.js'
import { FundingForm, parseAmount, presets, useFundingText } from './Funding.js'
import styles from './Wallet.module.css'

declare global {
  interface Window { TokenSeekFunding?: { present(screen: string): void; checkout?(amount: string): void } }
}

export const WalletIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M19 6V4a1 1 0 0 0-1.2-1L5.6 5.5A2 2 0 0 0 4 7.5V18a2 2 0 0 0 2 2h13a1 1 0 0 0 1-1V8a1 1 0 0 0-1-1H6"/>
    <path d="M20 11h-4a2 2 0 0 0 0 4h4"/>
  </svg>
)

const walletMessage = (state: WalletState): TranslationKey | null => {
  if (state.status === 'unbound') return 'walletUnbound'
  if (state.status === 'loading') return 'walletLoading'
  if (state.status === 'invalid') return 'walletInvalid'
  if (state.status !== 'ready') return 'walletLoadFailed'
  if (!state.balance.balance_infos.length) return 'walletEmpty'
  if (state.balance.balance_infos.every(info => /^-?0+(\.0+)?$/.test(info.total_balance))) return 'walletZero'
  if (!state.balance.is_available) return 'walletUnavailable'
  return null
}

const BalanceCard = ({ bound, state, refresh, summary = false }: {
  bound: boolean; summary?: boolean
} & ReturnType<typeof useBalance>) => {
  const t = useTranslate()
  const message = walletMessage(state)
  return (
    <div className={styles.balance} aria-label={t('walletTotal')}>
      <header>
        <span>{t('walletTotal')}</span>
        {summary ? (
          <button type="button" className={styles.textButton} onClick={() => pushSettings('wallet')}>{t('wallet')}</button>
        ) : bound && <button type="button" className={styles.textButton} disabled={state.status === 'loading'} onClick={refresh}>
          {t('walletRefresh')}
        </button>}
      </header>
      <div aria-live="polite">
        {state.status === 'ready' && state.balance.balance_infos.map(info => (
          <div key={info.currency} className={styles.account}>
            <div className={styles.total}><small>{info.currency}</small> {info.total_balance}</div>
            {!summary && <dl className={styles.parts}>
              <div><dt>{t('walletCredit')}</dt><dd>{info.topped_up_balance}</dd></div>
              <div><dt>{t('walletGrant')}</dt><dd>{info.granted_balance}</dd></div>
            </dl>}
          </div>
        ))}
        {message && <p className={styles.status}>{t(message)}</p>}
      </div>
    </div>
  )
}

export const BalanceSummary = ({ apiKey }: { apiKey: string }) => {
  const balance = useBalance(apiKey)
  return <BalanceCard bound summary {...balance} />
}

export const Wallet = ({ apiKey }: { apiKey: string | null }) => {
  const t = useTranslate()
  const labels = useFundingText()
  const balance = useBalance(apiKey)
  const dark = useTheme() === 'dark'
  const scale = useChatPreferences(s => fontScales[s.font])
  const { state, refresh } = balance
  const message = walletMessage(state)
  const canChat = state.status === 'ready' && state.balance.is_available
  const accountHint = apiKey ? t('walletAccountHint') : labels.signIn
  const native = window.TokenSeekFunding
  const screen = JSON.stringify({
    page: 'wallet', dark, scale, bound: Boolean(apiKey), canChat, status: state.status,
    message: message ? t(message) : '', accountHint, presets,
    balances: state.status === 'ready' ? state.balance.balance_infos.map(row => ({
      currency: row.currency, total: row.total_balance, credit: row.topped_up_balance, grant: row.granted_balance,
    })) : [],
    labels: {
      ...labels, instructions: labels.nativeInstructions, total: t('walletTotal'), refresh: t('walletRefresh'),
      credit: t('walletCredit'), grant: t('walletGrant'), chat: t('walletChat'),
    },
  })

  useLayoutEffect(() => {
    if (!native) return
    const action = (event: Event) => {
      const detail = (event as CustomEvent<{ id?: string; amount?: string }>).detail
      if (!detail) return
      switch (detail.id) {
        case 'back': backSettings(); break
        case 'refresh': if (apiKey) refresh(); break
        case 'keys': pushSettings('keys'); break
        case 'chat': if (canChat) closeSettings(); break
        case 'checkout':
          if (typeof detail.amount === 'string') {
            const amount = parseAmount(detail.amount).amount
            if (!amount) break
            if (native.checkout) native.checkout(amount)
            else window.open(platformUrls.topUp, '_blank', 'noopener,noreferrer')
          }
          break
      }
    }
    window.addEventListener('tokenseek:funding-action', action)
    return () => window.removeEventListener('tokenseek:funding-action', action)
  }, [native, apiKey, refresh, canChat])

  useLayoutEffect(() => { native?.present(screen) }, [native, screen])
  useLayoutEffect(() => () => native?.present(''), [native])

  if (native) return null
  return (
    <section className={styles.wallet} aria-label={labels.title}>
      <BalanceCard bound={Boolean(apiKey)} {...balance} />
      {!apiKey && <Button size="m" variant="outlined" onClick={() => pushSettings('keys')}>{labels.keys}</Button>}
      <FundingForm />
      <p className={styles.hint}>{accountHint}</p>
      {canChat && <Button variant="outlined" onClick={closeSettings}>{t('walletChat')}</Button>}
    </section>
  )
}
