import { useEffect, useState } from 'react'
import { useTranslate, type TranslationKey } from '../../i18n/index.js'
import { getBalance } from '../../services/api/balance.js'
import { classifyApiError } from '../../services/api/errors.js'
import { setApiKey } from '../../services/settings.js'
import { Button } from '../base/Button.js'
import { showToast } from '../base/toastState.js'
import { closeSettings } from './settingsDialogState.js'
import styles from './Wallet.module.css'

export const WalletIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M19 6V4a1 1 0 0 0-1.2-1L5.6 5.5A2 2 0 0 0 4 7.5V18a2 2 0 0 0 2 2h13a1 1 0 0 0 1-1V8a1 1 0 0 0-1-1H6"/>
    <path d="M20 11h-4a2 2 0 0 0 0 4h4"/>
  </svg>
)

type WalletState =
  | { status: 'unbound' | 'loading' | 'invalid' | 'error' }
  | { status: 'ready'; balance: Awaited<ReturnType<typeof getBalance>> }

function useBalance(apiKey: string | null) {
  const [version, setVersion] = useState(0)
  const [state, setState] = useState<WalletState>({ status: apiKey ? 'loading' : 'unbound' })
  const refresh = () => setVersion(count => count + 1)

  useEffect(() => {
    if (!apiKey) return
    let timer: ReturnType<typeof setTimeout>
    const onReturn = () => {
      if (document.visibilityState !== 'visible') return
      clearTimeout(timer)
      timer = setTimeout(refresh, 100)
    }
    window.addEventListener('focus', onReturn)
    window.addEventListener('pageshow', onReturn)
    window.addEventListener('tokenseek:resume', onReturn)
    document.addEventListener('visibilitychange', onReturn)
    return () => {
      clearTimeout(timer)
      window.removeEventListener('focus', onReturn)
      window.removeEventListener('pageshow', onReturn)
      window.removeEventListener('tokenseek:resume', onReturn)
      document.removeEventListener('visibilitychange', onReturn)
    }
  }, [apiKey])

  useEffect(() => {
    if (!apiKey) return
    const controller = new AbortController()
    let active = true
    setState({ status: 'loading' })
    const timeout = setTimeout(() => controller.abort(), 15000)
    getBalance(apiKey, controller.signal)
      .then(balance => {
        if (active) setState({ status: 'ready', balance })
      })
      .catch((error: unknown) => {
        if (active) setState({ status: classifyApiError(error) === 'unauthorized' ? 'invalid' : 'error' })
      })
      .finally(() => clearTimeout(timeout))
    return () => {
      active = false
      clearTimeout(timeout)
      controller.abort()
    }
  }, [apiKey, version])

  return { state, refresh }
}

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

export const Wallet = ({ apiKey }: { apiKey: string | null }) => {
  const t = useTranslate()
  const { state, refresh } = useBalance(apiKey)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const message = walletMessage(state)
  const remove = () => {
    if (!confirmRemove) {
      setConfirmRemove(true)
    } else if (!setApiKey(null)) {
      showToast({ key: 'walletStorageFailed', type: 'error' })
    }
  }
  return (
    <section className={styles.wallet} aria-label={t('walletTitle')}>
      <div className={styles.balance}>
        <header>
          <span>{t('walletTotal')}</span>
          {apiKey && <button type="button" className={styles.textButton} disabled={state.status === 'loading'} onClick={refresh}>
            {t('walletRefresh')}
          </button>}
        </header>
        <div aria-live="polite">
          {state.status === 'ready' && state.balance.balance_infos.map(info => (
            <div key={info.currency} className={styles.account}>
              <div className={styles.total}><small>{info.currency}</small> {info.total_balance}</div>
              <dl className={styles.parts}>
                <div><dt>{t('walletCredit')}</dt><dd>{info.topped_up_balance}</dd></div>
                <div><dt>{t('walletGrant')}</dt><dd>{info.granted_balance}</dd></div>
              </dl>
            </div>
          ))}
          {message && <p className={styles.status}>{t(message)}</p>}
        </div>
      </div>
      <div className={styles.actions}>
        <a className={styles.topUp} href="https://platform.deepseek.com/top_up" target="_blank" rel="noopener noreferrer">{t('walletRecharge')} <span aria-hidden="true">↗</span></a>
        {state.status === 'ready' && state.balance.is_available &&
          <Button variant="outlined" onClick={closeSettings}>{t('walletChat')}</Button>}
      </div>
      <p className={styles.hint}>{t('walletAccountHint')}</p>
      <div className={styles.keyLinks}>
        <a href="https://platform.deepseek.com/api_keys" target="_blank" rel="noopener noreferrer">{t('walletKeys')} <span aria-hidden="true">↗</span></a>
        {apiKey && <button type="button" className={styles.textButton} onClick={remove}>{t(confirmRemove ? 'walletConfirmRemove' : 'walletRemove')}</button>}
        {confirmRemove && <button type="button" className={styles.textButton} onClick={() => setConfirmRemove(false)}>{t('cancel')}</button>}
      </div>
    </section>
  )
}
