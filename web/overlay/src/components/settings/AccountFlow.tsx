import { useTranslate } from '../../i18n/index.js'
import { useSettings } from '../../services/settings.js'
import { Button } from '../base/Button.js'
import { IconRightUpOutline14 } from '../base/icons.js'
import { closeSettings, pushSettings } from './settingsDialogState.js'
import { SettingsRow } from './SettingsRows.js'
import { BalanceSummary, WalletIcon } from './Wallet.js'
import styles from './SettingsPages.module.css'
import accountStyles from './AccountFlow.module.css'

const accountText = {
  'zh-CN': {
    title: 'DeepSeek 账户', platform: '官方开放平台', device: '本机 API 授权',
    login: '登录 DeepSeek 开放平台', bind: '绑定 API Key', manage: '管理本机 API Key',
    signInHint: '请在官方页面查看登录账号，并使用同一账号充值和创建密钥。',
    bindHint: '在官方页面复制新建的 API Key，返回这里绑定，即可用该账户聊天。',
    deviceHint: '聊天和余额查询使用本机选中的 API Key。',
    wallet: '查看钱包',
  },
  'en-US': {
    title: 'DeepSeek account', platform: 'Official platform', device: 'API access on this device',
    login: 'Sign in to the DeepSeek platform', bind: 'Connect an API key', manage: 'Manage keys on this device',
    signInHint: 'Check the signed-in account on the official platform. Use the same account to top up and create your key.',
    bindHint: 'Copy your new key from the platform, then connect it here to chat with your account.',
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

const official = {
  platform: 'https://platform.deepseek.com/',
  topUp: 'https://platform.deepseek.com/top_up',
  keys: 'https://platform.deepseek.com/api_keys',
}

const PlatformLink = ({ href, children, onClick }: { href: string; children: string; onClick?(): void }) => (
  <a className={accountStyles.link} href={href} target="_blank" rel="noopener noreferrer" onClick={onClick}>
    {children}<IconRightUpOutline14 />
  </a>
)

export const AccountFlow = ({ apiKey }: { apiKey: string | null }) => {
  const t = useTranslate()
  const text = useAccountText()
  // The browser owns the platform session. Only an official API key authorizes device requests.
  const platform = (
    <section aria-label={text.platform}>
      <h2 className={styles.sectionLabel}>{text.platform}</h2>
      <div className={styles.group}>
        <PlatformLink href={official.platform}>{text.login}</PlatformLink>
        <PlatformLink href={official.topUp}>{t('walletRecharge')}</PlatformLink>
        <PlatformLink href={official.keys} onClick={() => pushSettings('keys')}>{t('walletKeys')}</PlatformLink>
      </div>
      <p className={accountStyles.hint}>{text.signInHint}</p>
    </section>
  )
  return (
    <>
      {!apiKey && platform}
      <section aria-label={text.device}>
        <h2 className={styles.sectionLabel}>{text.device}</h2>
        <div className={styles.group}>
          <SettingsRow label={apiKey ? text.manage : text.bind} value={apiKey ? '•••• ' + apiKey.slice(-4) : undefined}
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
      {apiKey && platform}
    </>
  )
}
