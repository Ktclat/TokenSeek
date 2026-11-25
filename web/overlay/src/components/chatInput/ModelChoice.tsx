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
  return (
    <div className={styles.choice}>
      <div
        className={styles.line}
        role="group"
        aria-label={t('modelChoice')}
        aria-describedby={needsFlash ? helpId : undefined}
      >
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
          title={t('modelProFeatures')}
          disabled={disabled || needsFlash}
          onClick={() => onChange(PRO)}
        >
          <span>Pro</span>
        </button>
      </div>
      {needsFlash && <p id={helpId}>{t('modelNeedsFlash')}</p>}
    </div>
  )
}
