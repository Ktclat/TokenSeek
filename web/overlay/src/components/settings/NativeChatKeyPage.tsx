import { useLayoutEffect, useState } from 'react'
import { useTheme } from '../../app/theme.js'
import { fontScales, useChatPreferences } from '../../services/chatPreferences.js'
import { isSwitchingProfile, switchProfile, useCurrentProfile, useProfiles } from '../../services/profiles.js'
import { changeKey, useSettings } from '../../services/settings.js'
import { platformUrls } from './OfficialPlatform.js'
import { useProfileText } from './ProfileSettings.js'
import { backSettings, closeSettings, keyInputFocused, useSettingsDialog } from './settingsDialogState.js'

declare global {
  interface Window { tokenSeekShowChatKeys?: (screen: string) => void }
}

export function NativeChatKeyPage({ present }: { present: (screen: string) => void }) {
  const current = useCurrentProfile()
  const profiles = useProfiles(state => state.profiles)
  const locale = useSettings(state => state.locale)
  const dark = useTheme() === 'dark'
  const scale = useChatPreferences(state => fontScales[state.font])
  const focusNewKey = useSettingsDialog(state => state.focusNewKey)
  const text = useProfileText()
  const [error, setError] = useState('')
  const description = JSON.stringify({
    page: 'keys', profile: current.id, nickname: current.nickname,
    profiles: profiles.map(({ id, nickname, username }) => ({ id, nickname, username })),
    dark, scale, locale, focusNewKey, error,
  })

  useLayoutEffect(() => {
    const handle = (event: Event) => {
      const detail = (event as CustomEvent<{ id?: string; argument?: string }>).detail
      if (!detail || isSwitchingProfile()) return
      setError('')
      switch (detail.id) {
        case 'back': backSettings(); break
        case 'chat': closeSettings(); break
        case 'official': window.open(platformUrls.keys, '_blank', 'noopener,noreferrer'); break
        case 'changed':
          if (detail.argument === current.id && !changeKey('read')) setError(text.keysUnavailable)
          break
        case 'switch':
          if (typeof detail.argument === 'string' && profiles.some(p => p.id === detail.argument)) {
            void switchProfile(detail.argument, 'keys').then(ok => { if (!ok) setError(text.switchError) })
          }
          break
      }
    }
    window.addEventListener('tokenseek:chat-key-action', handle)
    return () => window.removeEventListener('tokenseek:chat-key-action', handle)
  }, [current.id, profiles, text])
  useLayoutEffect(() => () => present(''), [present])
  useLayoutEffect(() => {
    present(description)
    if (focusNewKey) keyInputFocused()
  }, [description, focusNewKey, present])

  // The bridge uses this marker to restore the source screen after official account tasks.
  return <span hidden data-tokenseek-page="keys" />
}
