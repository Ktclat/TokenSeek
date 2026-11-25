import { useTranslate } from '../../i18n/index.js'
import { useSettings } from '../../services/settings.js'
import { Button } from '../base/Button.js'
import { closeSettings, pushSettings } from './settingsDialogState.js'
import { ProfileHeader } from './ProfileSettings.js'
import { SettingsRow } from './SettingsRows.js'
import { BalanceSummary } from './Wallet.js'
import { platformUrls } from './OfficialPlatform.js'
import styles from './AccountFlow.module.css'

const accountText = {
  'zh-CN': {
    title: 'DeepSeek 账户', login: '登录 DeepSeek', useKey: '设置聊天密钥',
    signInHelp: '使用 DeepSeek 账户充值和创建 API Key。选用密钥后，即可在这里聊天。',
    currentKey: '当前密钥', viewAccount: '在官方平台查看账户',
  },
  'en-US': {
    title: 'DeepSeek account', login: 'Sign in to DeepSeek', useKey: 'Set up a chat key',
    signInHelp: 'Sign in to your DeepSeek account to top up and create an API key. Select your key here to start chatting.',
    currentKey: 'Current key', viewAccount: 'View account on DeepSeek',
  },
}

export const useAccountText = () => useSettings(state => accountText[state.locale])

export const AccountIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" aria-hidden="true">
    <circle cx="12" cy="8" r="3.7" />
    <path d="M4.8 20c.4-4 3-6.2 7.2-6.2s6.8 2.2 7.2 6.2" />
  </svg>
)

export const AccountFlow = ({ apiKey }: { apiKey: string | null }) => {
  const t = useTranslate()
  const text = useAccountText()
  const active = useSettings(({ keyRing }) => keyRing.entries.find(key => key.id === keyRing.activeId))
  if (!apiKey) {
    return (
      <>
        <ProfileHeader />
        <section className={styles.login}>
          <p>{text.signInHelp}</p>
          <a className={styles.primary} href={platformUrls.login} target="_blank" rel="noopener noreferrer">{text.login}</a>
          <button className={styles.secondary} type="button" onClick={() => pushSettings('keys')}>{text.useKey}</button>
        </section>
      </>
    )
  }
  const selected = [active?.name, '•••• ' + (active?.suffix ?? apiKey.slice(-4))].filter(Boolean).join(' · ')
  return (
    <>
      <ProfileHeader />
      <section className={styles.account}>
        <BalanceSummary apiKey={apiKey} key={apiKey} />
        <div className={styles.identity}>
          <SettingsRow label={text.currentKey} value={selected} onClick={() => pushSettings('keys')} />
        </div>
        <Button block onClick={closeSettings}>{t('walletChat')}</Button>
        <a className={styles.secondary} href={platformUrls.account} target="_blank" rel="noopener noreferrer">{text.viewAccount}</a>
      </section>
    </>
  )
}
