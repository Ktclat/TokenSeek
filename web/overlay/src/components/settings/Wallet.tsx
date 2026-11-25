import { useTranslate, type TranslationKey } from '../../i18n/index.js'
import { useBalance, type WalletState } from '../../services/walletBalance.js'
import { platformUrls, usePlatformLabels } from './OfficialPlatform.js'
import { Button } from '../base/Button.js'
import { closeSettings, pushSettings } from './settingsDialogState.js'
import styles from './Wallet.module.css'

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
  const labels = usePlatformLabels()
  const balance = useBalance(apiKey)
  return (
    <section className={styles.wallet} aria-label={t('wallet')}>
      <BalanceCard bound={Boolean(apiKey)} {...balance} />
      <div className={styles.actions}>
        <a className={styles.topUp} href={platformUrls.topUp} target="_blank" rel="noopener noreferrer">{labels.topUp}</a>
        {balance.state.status === 'ready' && balance.state.balance.is_available &&
          <Button variant="outlined" onClick={closeSettings}>{t('walletChat')}</Button>}
      </div>
      <p className={styles.hint}>{t('walletAccountHint')}</p>
    </section>
  )
}
