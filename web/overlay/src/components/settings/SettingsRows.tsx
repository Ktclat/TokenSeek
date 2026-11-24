import type { ReactNode } from 'react'
import { IconChevronRightOutline14, IconCheckedOutline14 } from '../base/icons.js'
import styles from './SettingsPages.module.css'

export const SettingsRow = ({ label, value, icon, onClick }: {
  label: string; value?: string; icon?: ReactNode; onClick(): void
}) => (
  <button type="button" className={styles.row} onClick={onClick}>
    {icon && <span className={styles.icon} aria-hidden="true">{icon}</span>}
    <span className={styles.label}>{label}</span>
    {value && <span className={styles.value}>{value}</span>}
    <span className={styles.chevron} aria-hidden="true"><IconChevronRightOutline14 /></span>
  </button>
)

export const Options = <T extends string,>({ title, options, selected, onChange }: {
  title: string
  options: readonly { value: T; label: string }[]
  selected: string
  onChange(value: T): void
}) => (
  <div role="radiogroup" aria-label={title} className={styles.group}>
    {options.map(option => (
      <label key={option.value} className={styles.option}>
        <input type="radio" name="settings-choice" value={option.value} checked={selected === option.value}
          onChange={() => onChange(option.value)}
          onClick={() => { if (selected === option.value) onChange(option.value) }} />
        <span className={styles.label}>{option.label}</span>
        {selected === option.value && <span className={styles.check} aria-hidden="true"><IconCheckedOutline14 /></span>}
      </label>
    ))}
  </div>
)
