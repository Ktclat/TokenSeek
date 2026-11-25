import { useTranslate } from '../../i18n/index.js'
import { useCurrentProfile } from '../../services/profiles.js'
import { useSettings } from '../../services/settings.js'
import { useBalance } from '../../services/walletBalance.js'
import { IconButton } from '../base/IconButton.js'
import { IconSettingsOutline16 } from '../base/icons.js'
import { ProfileAvatar } from '../settings/ProfileAvatar.js'
import { openSettings, type SettingsSection } from '../settings/settingsDialogState.js'
import { closeMobileSider } from './mobileSiderState.js'
import { useOrganizationText } from './organizationText.js'
import styles from './SidebarExtras.module.css'

export function SidebarAccount() {
  const profile = useCurrentProfile()
  const apiKey = useSettings(state => state.apiKey)
  const { state } = useBalance(apiKey)
  const t = useTranslate()
  const copy = useOrganizationText()
  const balance = state.status === 'ready' && state.balance.balance_infos.length
    ? `${copy.balance} ${state.balance.balance_infos.map(info => `${info.currency} ${info.total_balance}`).join(' · ')}`
    : state.status === 'unbound' ? copy.noBalance : state.status === 'loading' ? copy.loadingBalance : copy.balanceUnknown
  const go = (page: SettingsSection) => { closeMobileSider(); openSettings(page) }
  return <footer className={styles.accountBar}>
    <button type="button" className={styles.accountButton} aria-label={[copy.account, profile.nickname, balance].join(' · ')} onClick={() => go('account')}>
      <ProfileAvatar className={styles.avatar} profile={profile} />
      <span className={styles.identity}><strong>{profile.nickname}</strong><small title={balance}>{balance}</small></span>
    </button>
    <IconButton className={styles.settingsButton} icon={<IconSettingsOutline16 />} size="xl" tone="primary"
      aria-label={t('settings')} onClick={() => go('home')} />
  </footer>
}
