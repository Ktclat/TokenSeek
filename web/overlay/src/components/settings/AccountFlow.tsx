import { useTheme } from '../../app/theme.js'
import { useTranslate } from '../../i18n/index.js'
import { fontScales, useChatPreferences } from '../../services/chatPreferences.js'
import { useCurrentProfile } from '../../services/profiles.js'
import { useSettings } from '../../services/settings.js'
import { useBalance } from '../../services/walletBalance.js'
import { IconChevronRightOutline14 } from '../base/icons.js'
import { platformUrls } from './OfficialPlatform.js'
import { ProfileHeader, useProfileText } from './ProfileSettings.js'
import { usePageText } from './SettingsPageText.js'
import { closeSettings, pushSettings } from './settingsDialogState.js'
import { SettingsRow } from './SettingsRows.js'
import { useNativeSettingsScreen, type NativeSettingsGroup } from './useNativeSettingsScreen.js'
import styles from './SettingsPages.module.css'

const accountText = {
  'zh-CN': {
    title: 'DeepSeek 账户', login: '登录 DeepSeek', home: '在官方平台查看账户',
    funding: '余额与充值', keys: 'API 密钥', officialKeys: 'API 密钥管理', chatKey: '聊天使用的密钥',
    noKey: '未选用', unbound: '未绑定密钥', loading: '正在查询…', invalid: '密钥已失效',
    error: '暂时无法查询', empty: '暂无余额明细',
  },
  'en-US': {
    title: 'DeepSeek account', login: 'Sign in to DeepSeek', home: 'View account on DeepSeek',
    funding: 'Balance and top-up', keys: 'API keys', officialKeys: 'Manage platform API keys', chatKey: 'Key for chat',
    noKey: 'None selected', unbound: 'No key connected', loading: 'Checking…', invalid: 'Invalid key',
    error: 'Balance unavailable', empty: 'No balance details',
  },
}

export const useAccountText = () => useSettings(state => accountText[state.locale])

const officialPages = new Map([
  ['login', platformUrls.login], ['officialAccount', platformUrls.account], ['officialKeys', platformUrls.keys],
])

function selectAccountAction(id: string) {
  const href = officialPages.get(id)
  if (href) window.open(href, '_blank', 'noopener,noreferrer')
  else if (id === 'profiles' || id === 'wallet' || id === 'keys') pushSettings(id)
  else if (id === 'chat') closeSettings()
}

export function AccountFlow({ apiKey }: { apiKey: string | null }) {
  const text = useAccountText()
  const t = useTranslate()
  const profile = useCurrentProfile()
  const profileText = useProfileText()
  const { back } = usePageText()
  const dark = useTheme() === 'dark'
  const scale = useChatPreferences(s => fontScales[s.font])
  const active = useSettings(({ keyRing }) => keyRing.entries.find(key => key.id === keyRing.activeId))
  const { state } = useBalance(apiKey)
  const balance = state.status === 'ready'
    ? state.balance.balance_infos.map(info => info.currency + ' ' + info.total_balance).join(' · ') || text.empty
    : text[state.status]
  const selected = apiKey ? [active?.name, '•••• ' + (active?.suffix ?? apiKey.slice(-4))].filter(Boolean).join(' · ') : text.noKey
  const groups: NativeSettingsGroup[] = [
    { label: profileText.profiles, items: [
      { id: 'profiles', label: profileText.switchProfile, value: profile.nickname + ' · @' + profile.username },
    ] },
    { items: [
      { id: 'login', label: text.login },
      ...(apiKey ? [{ id: 'officialAccount', label: text.home }] : []),
    ] },
    { label: t('walletTotal'), items: [{ id: 'wallet', label: text.funding, value: balance }] },
    { label: text.keys, items: [
      { id: 'officialKeys', label: text.officialKeys },
      { id: 'keys', label: text.chatKey, value: selected },
    ] },
    { items: [{ id: 'chat', label: t('walletChat') }] },
  ]
  useNativeSettingsScreen({ page: 'account', title: text.title, back, dark, scale, groups }, selectAccountAction)

  return <>{groups.map(group => <section key={group.items[0].id}>
    {group.label && <h2 className={styles.sectionLabel}>{group.label}</h2>}
    {group.items[0].id === 'profiles' ? <ProfileHeader /> : <div className={styles.group}>
      {group.items.map(item => {
        const href = officialPages.get(item.id)
        return href ? <a className={styles.row} key={item.id} href={href} target="_blank" rel="noopener noreferrer">
          <span className={styles.label}>{item.label}</span>
          <span className={styles.chevron} aria-hidden="true"><IconChevronRightOutline14 /></span>
        </a> : <SettingsRow key={item.id} label={item.label} value={item.value} onClick={() => selectAccountAction(item.id)} />
      })}
    </div>}
  </section>)}</>
}
