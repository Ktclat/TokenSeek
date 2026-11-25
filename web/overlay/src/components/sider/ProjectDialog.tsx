import { useEffect, useRef, useState } from 'react'
import { useTranslate } from '../../i18n/index.js'
import { addProject, moveSession, removeProject, renameProject, useOrganization } from '../../services/chat/organization.js'
import { maxProjectName, type Project } from '../../services/chat/organizationData.js'
import { removeSession } from '../../services/chat/store.js'
import { Button } from '../base/Button.js'
import { IconCheckOutline16 } from '../base/icons.js'
import { Modal } from '../base/Modal.js'
import { SiderIcon } from './SiderIcon.js'
import { useOrganizationText } from './organizationText.js'
import styles from './SidebarExtras.module.css'

export type SidebarDialog =
  | { kind: 'create'; sessionId?: string }
  | { kind: 'rename' | 'deleteProject'; project: Project }
  | { kind: 'move' | 'deleteSession'; sessionId: string }

export function ProjectDialog({ dialog, close, change, reveal }: {
  dialog: SidebarDialog; close: () => void; change: (next: SidebarDialog) => void; reveal: (id: string) => void
}) {
  const t = useTranslate()
  const copy = useOrganizationText()
  const { projects, assignments } = useOrganization(state => state.data)
  const [name, setName] = useState(dialog.kind === 'rename' ? dialog.project.name : '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(false)
  const pending = useRef(false)
  const title = { create: copy.create, rename: copy.rename, move: copy.move, deleteProject: copy.remove,
    deleteSession: t('deleteSessionModalContentTitle') }[dialog.kind]

  const dismiss = () => { if (!pending.current) close() }
  useEffect(() => {
    const onBack = (event: Event) => {
      event.preventDefault()
      event.stopImmediatePropagation()
      if (!pending.current) close()
    }
    window.addEventListener('tokenseek:back', onBack, true)
    return () => window.removeEventListener('tokenseek:back', onBack, true)
  }, [close])

  async function run(operation: () => Promise<void>) {
    if (pending.current) return
    pending.current = true
    setBusy(true)
    setError(false)
    try { await operation(); close() }
    catch { setError(true); setBusy(false) }
    finally { pending.current = false }
  }
  const save = () => run(async () => {
    switch (dialog.kind) {
      case 'create': reveal(await addProject(name.trim(), dialog.sessionId)); break
      case 'rename': await renameProject(dialog.project.id, name.trim()); break
      case 'deleteProject': await removeProject(dialog.project.id); break
      case 'deleteSession': if (!await removeSession(dialog.sessionId)) throw new Error('Session deletion failed'); break
    }
  })
  const editing = dialog.kind === 'create' || dialog.kind === 'rename'
  const deleting = dialog.kind === 'deleteProject' || dialog.kind === 'deleteSession'
  return <Modal title={title} onClose={dismiss} closable={dialog.kind === 'move'} footer={dialog.kind !== 'move' && <>
    <Button variant="outlined" size="m" onClick={dismiss} disabled={busy}>{t('cancel')}</Button>
    <Button size="m" variant={deleting ? 'danger' : 'primary'} disabled={busy || (editing && !name.trim())} onClick={save}>
      {deleting ? t('delete') : copy.save}
    </Button>
  </>}>
    {editing && <input className={styles.projectInput} autoFocus maxLength={maxProjectName} value={name}
      aria-label={copy.folderName} placeholder={copy.folderName} onChange={e => setName(e.target.value)}
      onFocus={e => e.target.select()} onKeyDown={e => {
        if (e.key === 'Enter' && !e.nativeEvent.isComposing && name.trim()) void save()
      }} />}
    {dialog.kind === 'deleteProject' && <p className={styles.confirmText}><strong>{dialog.project.name}</strong><br/>{copy.keep}</p>}
    {dialog.kind === 'deleteSession' && t('deleteSessionWithFilesDescription')}
    {dialog.kind === 'move' && <div className={styles.projectChoices}>
      {projects.map(p => <button type="button" disabled={busy} key={p.id} onClick={() => run(async () => {
        await moveSession(dialog.sessionId, p.id); reveal(p.id)
      })}><SiderIcon name="folder"/><span>{p.name}</span>{assignments[dialog.sessionId] === p.id && <IconCheckOutline16 />}</button>)}
      <button type="button" disabled={busy} onClick={() => change({ kind: 'create', sessionId: dialog.sessionId })}>
        <SiderIcon name="add"/><span>{copy.createAndMove}</span>
      </button>
    </div>}
    {error && <p role="alert" className={styles.confirmText}>{copy.error}</p>}
  </Modal>
}
