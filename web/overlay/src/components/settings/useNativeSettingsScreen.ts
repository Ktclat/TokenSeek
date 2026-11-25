import { useLayoutEffect, useRef } from 'react'
import { backSettings } from './settingsDialogState.js'

export type NativeSettingsGroup = {
  label?: string
  items: { id: string; label: string; value?: string; selected?: boolean }[]
}

type NativeScreen = {
  page: string; title: string; back: string; dark: boolean; scale: number
  groups: NativeSettingsGroup[]; sample?: string; chat?: string
}

export function useNativeSettingsScreen(screen: NativeScreen, onAction: (id: string) => void) {
  const action = useRef(onAction)
  const page = screen.page
  const description = JSON.stringify(screen)

  useLayoutEffect(() => { action.current = onAction })
  useLayoutEffect(() => () => window.TokenSeekSettings?.present(''), [])
  useLayoutEffect(() => {
    if (!window.TokenSeekSettings) return
    const handle = (event: Event) => {
      const detail = (event as CustomEvent<{ page?: string; id?: string }>).detail
      if (detail?.page !== page || typeof detail.id !== 'string') return
      if (detail.id === 'back') backSettings()
      else action.current(detail.id)
    }
    window.addEventListener('tokenseek:preference-action', handle)
    return () => window.removeEventListener('tokenseek:preference-action', handle)
  }, [page])
  useLayoutEffect(() => { window.TokenSeekSettings?.present(description) }, [description])
}
