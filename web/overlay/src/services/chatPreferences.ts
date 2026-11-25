import { create } from 'zustand'
import { getActiveWorkspaceId, profileStorageName } from './profiles.js'

export const fontScales = { compact: 90, standard: 100, large: 115, largest: 130 } as const
export type FontSize = keyof typeof fontScales
export type AnswerStyle = 'standard' | 'brief' | 'detailed'
export type AnswerLanguage = 'auto' | 'zh' | 'en'
export type Personalization = { style: AnswerStyle; language: AnswerLanguage }

declare global {
  interface Window {
    tokenSeekSetWorkspace?: (id: string) => void
    TokenSeekSettings?: { present(screen: string): void; setTextScale(percent: number): void }
  }
}

function read(key: string): string | null {
  try { return localStorage.getItem(key) } catch { return null }
}
function write(key: string, value: string) {
  localStorage.setItem(key, value)
}
const sizeKey = 'tokenseek:font-size'
function loadPersonalization(): Personalization {
  let value: Partial<Personalization> | null = null
  try { value = JSON.parse(read(profileStorageName('tokenseek:answer-preferences')) || 'null') } catch { /* Use defaults. */ }
  return {
    style: value?.style === 'brief' || value?.style === 'detailed' ? value.style : 'standard',
    language: value?.language === 'zh' || value?.language === 'en' ? value.language : 'auto',
  }
}
const savedSize = read(sizeKey)
export const useChatPreferences = create<{ font: FontSize } & Personalization>(() => ({
  font: savedSize && Object.hasOwn(fontScales, savedSize) ? savedSize as FontSize : 'standard',
  ...loadPersonalization(),
}))

export function setFontSize(font: FontSize) {
  write(sizeKey, font)
  useChatPreferences.setState({ font })
  applyFontSize()
}
export function initializeDevicePreferences() {
  window.tokenSeekSetWorkspace?.(getActiveWorkspaceId())
  applyFontSize()
}
function applyFontSize() {
  const percent = fontScales[useChatPreferences.getState().font]
  if (window.TokenSeekSettings) window.TokenSeekSettings.setTextScale(percent)
  else document.documentElement.style.setProperty('--ts-font-scale', String(percent / 100))
}
export function setPersonalization(updates: Partial<Personalization>) {
  const { style, language } = useChatPreferences.getState()
  const next = { style, language, ...updates }
  write(profileStorageName('tokenseek:answer-preferences'), JSON.stringify(next))
  useChatPreferences.setState(next)
}
export function personalizedSystem(base: string) {
  const { style, language } = useChatPreferences.getState()
  const parts = [base]
  if (style === 'brief') parts.push('用户偏好简洁回答，优先给出结论。')
  if (style === 'detailed') parts.push('用户偏好详细回答，请在合适时解释过程并举例。')
  if (language === 'zh') parts.push('用户默认希望用中文回答。')
  if (language === 'en') parts.push('The user prefers answers in English by default.')
  if (parts.length > 1) parts.push('如果用户在当前消息中另有要求，请遵循当前消息。')
  return parts.join('\n')
}
