import { useTranslate } from '../../i18n/index.js'
import { useCurrentProfile } from '../../services/profiles.js'
import { useSettings } from '../../services/settings.js'
import { useBalance } from '../../services/walletBalance.js'
import { IconButton } from '../base/IconButton.js'
import { Menu } from '../base/Menu.js'
import { IconSettingsOutline16, IconUploadOutline14 } from '../base/icons.js'
import { ProfileAvatar } from '../settings/ProfileAvatar.js'
import { AccountIcon } from '../settings/AccountFlow.js'
import { WalletIcon } from '../settings/Wallet.js'
import { openSettings, type SettingsSection } from '../settings/settingsDialogState.js'
import { closeMobileSider } from './mobileSiderState.js'
import { useOrganizationText } from './organizationText.js'
import { SiderIcon } from './SiderIcon.js'
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
    <button type="button" className={styles.accountButton} onClick={() => go('account')}>
      <ProfileAvatar className={styles.avatar} profile={profile} />
      <span className={styles.identity}><strong>{profile.nickname}</strong><small title={balance}>{balance}</small></span>
    </button>
    <Menu options={[
      { key: 'account', label: copy.account, icon: <AccountIcon/>, onSelect: () => go('account') },
      { key: 'wallet', label: t('wallet'), icon: <WalletIcon/>, onSelect: () => go('wallet') },
      { key: 'data', label: copy.importChat, icon: <IconUploadOutline14/>, onSelect: () => go('data') },
      { key: 'settings', label: t('settings'), icon: <IconSettingsOutline16/>, onSelect: () => go('home') },
    ]}>{({ open, toggle }) => <IconButton icon={<SiderIcon name="menu" />} size="xl" aria-label={copy.accountMenu}
      aria-haspopup="menu" aria-expanded={open} onClick={toggle} />}</Menu>
  </footer>
}
