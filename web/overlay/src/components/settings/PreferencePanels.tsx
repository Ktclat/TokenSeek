import { useLayoutEffect } from 'react'
import { useTheme } from '../../app/theme.js'
import { setThemePreference, useSettings, type ThemePreference } from '../../services/settings.js'
import { fontScales, setFontSize, setPersonalization, useChatPreferences, type FontSize, type AnswerStyle, type AnswerLanguage } from '../../services/chatPreferences.js'
import { backSettings, pushSettings, type SettingsSection } from './settingsDialogState.js'
import { SettingsRow } from './SettingsRows.js'
import styles from './SettingsPages.module.css'
import prefs from './PreferencePanels.module.css'

const labels = {
  'zh-CN': {
    home: '设置', account: '账户', profile: 'DeepSeek 账户', wallet: '钱包', keys: 'API 密钥', app: '应用',
    theme: '外观', font: '字体大小', personalization: '个性化', general: '通用', data: '数据管理', back: '返回',
    mode: '主题', system: '跟随系统', light: '浅色', dark: '深色', compact: '较小', standard: '标准', large: '较大', largest: '最大',
    preview: '预览', sample: '今天想聊点什么？', answer: '回答风格', brief: '简洁', detailed: '详细', lang: '默认回答语言',
    auto: '跟随提问', zh: '中文', en: '英语',
  },
  'en-US': {
    home: 'Settings', account: 'Account', profile: 'DeepSeek account', wallet: 'Wallet', keys: 'API keys', app: 'App',
    theme: 'Appearance', font: 'Font size', personalization: 'Personalization', general: 'General', data: 'Data management', back: 'Back',
    mode: 'Theme', system: 'System', light: 'Light', dark: 'Dark', compact: 'Small', standard: 'Standard', large: 'Large', largest: 'Extra large',
    preview: 'Preview', sample: 'What would you like to talk about?', answer: 'Response style', brief: 'Concise', detailed: 'Detailed', lang: 'Default response language',
    auto: 'Match my message', zh: 'Chinese', en: 'English',
  },
}
export const usePreferenceLabels = () => useSettings(s => labels[s.locale])

type Panel = 'home' | 'theme' | 'font' | 'personalization'
type Item = { id: string; label: string; value?: string; selected?: boolean }
type Group = { label?: string; items: Item[] }
type Screen = { page: Panel; title: string; back: string; dark: boolean; scale: number; groups: Group[]; sample?: string }

export function PreferencePanels({ page }: { page: Panel }) {
  const t = usePreferenceLabels()
  const theme = useSettings(s => s.themePreference)
  const { font, style, language } = useChatPreferences()
  const actions = new Map<string, () => void>()
  const option = (id: string, label: string, callback: () => void, fields: Partial<Item> = {}): Item => {
    actions.set(id, callback)
    return { id, label, ...fields }
  }
  const link = (to: SettingsSection, label: string, value?: string) => option(to, label, () => pushSettings(to), { value })
  const options = <T extends string>(values: readonly T[], active: T, set: (next: T) => void) =>
    values.map(value => option(value, t[value as keyof typeof t], () => set(value), { selected: value === active }))
  let groups: Group[]
  switch (page) {
    case 'home': groups = [
      { label: t.account, items: [link('account', t.profile), link('wallet', t.wallet), link('keys', t.keys)] },
      { label: t.app, items: [link('theme', t.theme, t[theme]), link('personalization', t.personalization),
        link('general', t.general), link('data', t.data)] },
    ]; break
    case 'theme': groups = [
      { label: t.mode, items: options<ThemePreference>(['system','light','dark'], theme, setThemePreference) },
      { items: [link('font', t.font, t[font])] },
    ]; break
    case 'font': groups = [{ items: options<FontSize>(['compact','standard','large','largest'], font, setFontSize) }]; break
    case 'personalization': groups = [
      { label: t.answer, items: options<AnswerStyle>(['brief','standard','detailed'], style, value => setPersonalization({ style: value })) },
      { label: t.lang, items: options<AnswerLanguage>(['auto','zh','en'], language, value => setPersonalization({ language: value })) },
    ]; break
  }
  const dark = useTheme() === 'dark'
  const screen: Screen = { page, title: t[page], back: t.back, dark, scale: fontScales[font], groups,
    sample: page === 'font' ? t.sample : undefined }
  const description = JSON.stringify(screen)
  useLayoutEffect(() => () => window.TokenSeekSettings?.present(''), [])
  useLayoutEffect(() => {
    const host = window.TokenSeekSettings
    if (!host) return
    const handle = (event: Event) => {
      const detail = (event as CustomEvent<{ page: string; id: string }>).detail
      if (detail?.page !== page) return
      if (detail.id === 'back') backSettings()
      else actions.get(detail.id)?.()
    }
    window.addEventListener('tokenseek:preference-action', handle)
    host.present(description)
    return () => window.removeEventListener('tokenseek:preference-action', handle)
  }, [description])
  return <>
    {screen.sample && <p className={prefs.sample} style={{ fontSize: 16 * screen.scale / 100 }}>{screen.sample}</p>}
    {groups.map((group, i) => <section className={prefs.group} key={group.label ?? i}>
      {group.label && <h2 className={styles.sectionLabel}>{group.label}</h2>}
      <div className={styles.group} role={group.items[0].selected !== undefined ? 'radiogroup' : undefined} aria-label={group.label ?? t[page]}>
        {group.items.map(item => item.selected === undefined
          ? <SettingsRow key={item.id} label={item.label} value={item.value} onClick={actions.get(item.id)!} />
          : <button className={styles.row} role="radio" aria-checked={item.selected} key={item.id} onClick={actions.get(item.id)}>
            <span className={styles.label}>{item.label}</span>{item.selected && <span className={styles.check} aria-hidden="true">✓</span>}
          </button>)}
      </div>
    </section>)}
  </>
}
