import { useId } from 'react'
import type { ChatModel } from '../../services/api/types.js'
import { FLASH, PRO } from '../../services/chat/models.js'
import { useTranslate } from '../../i18n/index.js'
import styles from './ModelChoice.module.css'

type Props = {
  model: ChatModel
  needsFlash: boolean
  disabled: boolean
  onChange: (model: ChatModel) => void
}

export const ModelChoice = ({ model, needsFlash, disabled, onChange }: Props) => {
  const t = useTranslate()
  const helpId = useId()
  const hint = needsFlash ? t('modelNeedsFlash') : model === PRO ? t('modelProFeatures') : null
  return (
    <div className={styles.choice}>
      <div
        className={styles.line}
        role="group"
        aria-label={t('modelChoice')}
        aria-describedby={hint ? helpId : undefined}
      >
        <span className={styles.label}>{t('modelChoice')}</span>
        <button
          type="button"
          aria-pressed={model === FLASH}
          disabled={disabled}
          onClick={() => onChange(FLASH)}
        >
          <span>Flash</span>
        </button>
        <button
          type="button"
          aria-pressed={model === PRO}
          disabled={disabled || needsFlash}
          onClick={() => onChange(PRO)}
        >
          <span>Pro</span>
        </button>
      </div>
      {hint && <p id={helpId}>{hint}</p>}
    </div>
  )
}
