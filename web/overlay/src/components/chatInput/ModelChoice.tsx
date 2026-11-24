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
  return (
    <div className={styles.choice}>
      <div className={styles.line} role="group" aria-label={t('modelChoice')}>
        <span className={styles.label}>{t('modelChoice')}</span>
        <div className={styles.options}>
          <button
            type="button"
            aria-pressed={model === FLASH}
            disabled={disabled}
            onClick={() => onChange(FLASH)}
          >
            Flash
          </button>
          <button
            type="button"
            aria-pressed={model === PRO}
            disabled={disabled || needsFlash}
            onClick={() => onChange(PRO)}
          >
            Pro
          </button>
        </div>
      </div>
      {(needsFlash || model === PRO) && (
        <p>{t(needsFlash ? 'modelNeedsFlash' : 'modelProFeatures')}</p>
      )}
    </div>
  )
}
