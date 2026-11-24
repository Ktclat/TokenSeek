import { useTranslate } from '../../i18n/index.js'
import { useSettings } from '../../services/settings.js'
import { Button } from '../base/Button.js'
import { IconChevronRightOutline14 } from '../base/icons.js'
import { closeSettings, pushSettings } from './settingsDialogState.js'
import { SettingsRow } from './SettingsRows.js'
import { BalanceSummary, WalletIcon } from './Wallet.js'
import { OfficialKeysLink, platformUrls, usePlatformLabels } from './OfficialPlatform.js'
import styles from './SettingsPages.module.css'
import accountStyles from './AccountFlow.module.css'

const accountText = {
  'zh-CN': {
    title: 'DeepSeek 账户', platform: '官方开放平台', device: 'TokenSeek 聊天',
    login: '登录 DeepSeek 开放平台',
    signInHint: '登录、充值和密钥管理由 DeepSeek 官方页面办理。请使用同一账户充值和创建密钥。',
    bindHint: '已经有 Key？在本机选用后，就可以聊天和查看对应账户的余额。',
    deviceHint: '聊天和余额查询使用本机选中的 API Key。',
    wallet: '查看钱包',
  },
  'en-US': {
    title: 'DeepSeek account', platform: 'Official platform', device: 'TokenSeek chat',
    login: 'Sign in to the DeepSeek platform',
    signInHint: 'Sign in, top up and manage keys on DeepSeek. Use the same account for payment and key creation.',
    bindHint: 'Already have a key? Select it here to chat and view that account’s balance.',
    deviceHint: 'Chats and balance checks use the key selected on this device.',
    wallet: 'View wallet',
  },
}

export const useAccountText = () => useSettings(state => accountText[state.locale])

export const AccountIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" aria-hidden="true">
    <circle cx="12" cy="8" r="3.7" />
    <path d="M4.8 20c.4-4 3-6.2 7.2-6.2s6.8 2.2 7.2 6.2" />
  </svg>
)

const PlatformLink = ({ href, children }: { href: string; children: string }) => (
  <a className={accountStyles.link} href={href} target="_blank" rel="noopener noreferrer">
    {children}<span className={accountStyles.disclosure} aria-hidden="true"><IconChevronRightOutline14 /></span>
  </a>
)

export const AccountFlow = ({ apiKey }: { apiKey: string | null }) => {
  const t = useTranslate()
  const text = useAccountText()
  const labels = usePlatformLabels()
  return (
    <>
      <section aria-label={text.platform}>
        <h2 className={styles.sectionLabel}>{text.platform}</h2>
        <div className={styles.group}>
          <PlatformLink href={platformUrls.account}>{text.login}</PlatformLink>
          <PlatformLink href={platformUrls.topUp}>{t('walletRecharge')}</PlatformLink>
          <OfficialKeysLink detail />
        </div>
        <p className={accountStyles.hint}>{text.signInHint}</p>
      </section>
      <section aria-label={text.device}>
        <h2 className={styles.sectionLabel}>{text.device}</h2>
        <div className={styles.group}>
          <SettingsRow label={apiKey ? labels.chatKeys : labels.selectKey} value={apiKey ? '•••• ' + apiKey.slice(-4) : undefined}
            onClick={() => pushSettings('keys')} />
          {apiKey && <SettingsRow label={text.wallet} icon={<WalletIcon />} onClick={() => pushSettings('wallet')} />}
        </div>
        <p className={accountStyles.hint}>{apiKey ? text.deviceHint : text.bindHint}</p>
        {apiKey && <>
          <BalanceSummary apiKey={apiKey} key={apiKey} />
          <div className={accountStyles.chat}>
            <Button block onClick={closeSettings}>{t('walletChat')}</Button>
          </div>
        </>}
      </section>
    </>
  )
}
