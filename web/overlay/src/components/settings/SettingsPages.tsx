import { useEffect, useLayoutEffect, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useTranslate } from '../../i18n/index.js'
import { regionCodes, getRegionName, provinces } from '../../services/location.js'
import { isChineseBrowser, setLocale, setLocation, setTtsVoice, useSettings } from '../../services/settings.js'
import { IconChevronRightOutline14 } from '../base/icons.js'
import { useIsMobile, useMobileViewport } from '../../app/viewport.js'
import { usePlatformLabels } from './OfficialPlatform.js'
import { AccountFlow, useAccountText } from './AccountFlow.js'
import { Button } from '../base/Button.js'
import { Wallet } from './Wallet.js'
import { ProfileSettings, useProfileText } from './ProfileSettings.js'
import { AccountKey } from './AccountKey.js'
import { backSettings, closeSettings, pushSettings, type SettingsSection } from './settingsDialogState.js'
import { PreferencePanels, usePreferenceLabels } from './PreferencePanels.js'
import { SettingsRow, Options } from './SettingsRows.js'
import { usePageText } from './SettingsPageText.js'
import styles from './SettingsPages.module.css'

export const SettingsPages = ({ page, backDisabled, children }: {
  page: SettingsSection; backDisabled: boolean; children: ReactNode
}) => {
  const t = useTranslate()
  const copy = usePageText()
  const account = useAccountText()
  const profileText = useProfileText()
  const labels = usePlatformLabels()
  const prefs = usePreferenceLabels()
  const titleRef = useRef<HTMLHeadingElement>(null)
  const frameRef = useMobileViewport(useIsMobile())
  const { apiKey, locale, ttsVoice, location } = useSettings()
  const province = provinces.find(item => item.code === location?.province)
  const city = province?.cities.find(item => item.code === location?.city)
  const localProvince = isChineseBrowser()
  const titles: Record<SettingsSection, string> = {
    profiles: profileText.profiles,
    home: t('settings'), account: account.title, wallet: t('wallet'), keys: labels.keys, general: t('settingDialogAsideGeneralOptionLabel'),
    data: t('settingDialogAsideDataOptionLabel'), theme: prefs.theme, font: prefs.font, personalization: prefs.personalization,
    language: t('settingsLocalePreference'), voice: t('settingsTtsVoice'),
    location: t('settingsLocation'), cities: t('settingsLocationCity'),
  }
  const languages = [
    { value: 'zh-CN', label: t('settingsLocaleZhCN') }, { value: 'en-US', label: t('settingsLocaleEnUS') },
  ] as const
  const voices = [
    { value: 'stella', label: t('settingsTtsVoiceStella') }, { value: 'tide', label: t('settingsTtsVoiceTide') },
  ] as const

  useLayoutEffect(() => {
    const chat = document.getElementById('root')
    const previous = document.activeElement
    if (chat) {
      chat.inert = true
      chat.setAttribute('aria-hidden', 'true')
    }
    return () => {
      if (chat) {
        chat.inert = false
        chat.removeAttribute('aria-hidden')
      }
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus({ preventScroll: true })
    }
  }, [])

  useLayoutEffect(() => { titleRef.current?.focus({ preventScroll: true }) }, [page])

  useEffect(() => {
    const onBack = (event: Event) => {
      if (event instanceof KeyboardEvent && event.key !== 'Escape') return
      // Existing destructive confirmation dialogs consume Escape before page navigation.
      if (document.querySelector('[role="dialog"]')) return
      event.preventDefault()
      event.stopImmediatePropagation()
      if (!backDisabled) backSettings()
    }
    window.addEventListener('tokenseek:back', onBack, true)
    window.addEventListener('keydown', onBack, true)
    return () => {
      window.removeEventListener('tokenseek:back', onBack, true)
      window.removeEventListener('keydown', onBack, true)
    }
  }, [backDisabled])

  const renderPage = () => {
    switch (page) {
      case 'home':
      case 'theme':
      case 'font':
      case 'personalization':
        return <PreferencePanels page={page} />
      case 'profiles':
        return <ProfileSettings />
      case 'account':
        return <AccountFlow apiKey={apiKey} />
      case 'wallet':
        return <Wallet key={apiKey ?? ''} apiKey={apiKey} />
      case 'keys':
        return <>
          <AccountKey key={apiKey ?? ''} />
          {apiKey && <div className={styles.keyChat}><Button block onClick={closeSettings}>{t('walletChat')}</Button></div>}
        </>
      case 'data':
        return children
      case 'general':
        return <div className={styles.group}>
          <SettingsRow label={titles.language} value={languages.find(item => item.value === locale)?.label} onClick={() => pushSettings('language')} />
          <SettingsRow label={titles.voice} value={voices.find(item => item.value === ttsVoice)?.label} onClick={() => pushSettings('voice')} />
          <SettingsRow label={titles.location}
            value={city?.name ?? province?.name ?? (location ? getRegionName(location.region, locale) : copy.unset)}
            onClick={() => pushSettings('location')} />
        </div>
      case 'language':
        return <Options title={titles.language} options={languages} selected={locale} onChange={value => {
          setLocale(value)
          backSettings()
        }} />
      case 'voice':
        return <Options title={titles.voice} options={voices} selected={ttsVoice} onChange={value => {
          setTtsVoice(value)
          backSettings()
        }} />
      case 'location': {
        const options = localProvince ? provinces.map(item => ({ value: item.code, label: item.name }))
          : regionCodes.map(code => ({ value: code, label: getRegionName(code, locale) })).sort((a, b) => a.label.localeCompare(b.label, locale))
        return <>
          <p className={styles.hint}>{copy.locationHelp}</p>
          <Options title={titles.location} options={[{ value: '', label: copy.unset }, ...options]}
            selected={(localProvince ? province?.code : location?.region) ?? ''}
            onChange={value => {
              if (!value) { setLocation(null); backSettings(); return }
              if (localProvince) {
                if (value !== province?.code) setLocation({ region: 'CN', province: value })
                pushSettings('cities')
              } else { setLocation({ region: value }); backSettings() }
            }} />
        </>
      }
      case 'cities':
        return <Options title={titles.cities} selected={city?.code ?? ''}
          options={[{ value: '', label: copy.unset }, ...(province?.cities.map(item => ({ value: item.code, label: item.name })) ?? [])]}
          onChange={value => {
            if (province) setLocation({ region: 'CN', province: province.code, city: value || undefined })
            backSettings(2)
          }} />
    }
  }

  return createPortal(
    <div ref={frameRef} data-tokenseek-page={page} className={styles.screen}>
      <header className={styles.bar}>
        <div className={styles.barInner}>
          <button type="button" className={styles.back} aria-label={copy.back} disabled={backDisabled} onClick={() => backSettings()}>
            <IconChevronRightOutline14 />
          </button>
          <h1 ref={titleRef} id="tokenseek-settings-title" tabIndex={-1}>{titles[page]}</h1>
        </div>
      </header>
      <main key={page} aria-labelledby="tokenseek-settings-title" className={styles.scroll}>
        <div className={styles.inner}>{renderPage()}</div>
      </main>
    </div>, document.body
  )
}
