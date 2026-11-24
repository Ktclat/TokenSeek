import { useState } from 'react'
import { useIsMobile } from '../../app/viewport.js'
import { useTranslate } from '../../i18n/index.js'
import { setApiKey } from '../../services/settings.js'
import { Button } from '../base/Button.js'
import { Modal } from '../base/Modal.js'
import { showToast } from '../base/toastState.js'
import { IconRightUpOutline14 } from '../base/icons.js'
import { ApiKeyField, ApiKeyConfirm, useApiKeyEditor } from './ApiKeyField.js'
import { usePageText } from './SettingsPageText.js'
import styles from './SettingsPages.module.css'

export const AccountKey = () => {
  const editor = useApiKeyEditor()
  const t = useTranslate()
  const copy = usePageText()
  const isMobile = useIsMobile()
  const [confirmRemove, setConfirmRemove] = useState(false)
  return (
    <>
      <div className={styles.group}>
        <a className={styles.external} href="https://platform.deepseek.com/api_keys" target="_blank" rel="noopener noreferrer">
          {t('walletKeys')} <IconRightUpOutline14 />
        </a>
      </div>
      {!editor.apiKey && <p className={styles.hint}>{copy.keyHelp}</p>}
      <div className={styles.keyEntry}>
        <ApiKeyField editor={editor} />
        {isMobile && <ApiKeyConfirm editor={editor} />}
      </div>
      {editor.apiKey && <button type="button" className={styles.remove} onClick={() => {
        editor.form.cancelVerification()
        setConfirmRemove(true)
      }}>{t('walletRemove')}</button>}
      {confirmRemove && <Modal title={t('walletRemove')} onClose={() => setConfirmRemove(false)} footer={
        <>
          <Button variant="outlined" autoFocus onClick={() => setConfirmRemove(false)}>{t('cancel')}</Button>
          <Button variant="danger" onClick={() => {
            if (!setApiKey(null)) showToast({ key: 'walletStorageFailed', type: 'error' })
            setConfirmRemove(false)
          }}>{t('walletConfirmRemove')}</Button>
        </>
      }>{copy.removeHelp}</Modal>}
    </>
  )
}
