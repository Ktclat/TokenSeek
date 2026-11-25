import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { isKeyPreview, listProfileKeys, type SavedKey } from '../../services/keyRing.js'
import { changeKey, useSettings } from '../../services/settings.js'
import { switchProfile, useCurrentProfile, useProfiles, type Profile } from '../../services/profiles.js'
import { useProfileText } from './ProfileSettings.js'
import { Button } from '../base/Button.js'
import { keyInputFocused, useSettingsDialog } from './settingsDialogState.js'
import { OfficialKeysLink } from './OfficialPlatform.js'
import { showToast } from '../base/toastState.js'
import { useApiKeyForm } from './useApiKeyForm.js'
import styles from './KeyList.module.css'
import pages from './SettingsPages.module.css'

const text = {
  'zh-CN': {
    keys: '保存在本机的 Key', add: '添加已有 API Key',
    help: '选用一把已有密钥，聊天费用将计入它所属的 DeepSeek 账户。',
    name: '本机备注（可选）', example: '个人账户', key: '官方 API Key', paste: '粘贴 API Key',
    save: '验证并选用', pending: '正在验证…', cancel: '取消', active: '使用中', select: '选用',
    edit: '本机设置', rename: '保存备注', remove: '从本机移除', confirm: '确认移除',
    removeHint: '仅移除这台设备上的密钥。官方密钥仍可在 DeepSeek 开放平台管理。',
    clear: '取消选用', preview: '浏览器预览中新添加的密钥仅在本页打开期间保留。',
  },
  'en-US': {
    keys: 'Keys on this device', add: 'Add an existing API key',
    help: 'Select an existing key to charge chats to its DeepSeek account.',
    name: 'Device label (optional)', example: 'e.g. Personal account', key: 'Official API key', paste: 'Paste API key',
    save: 'Verify and use', pending: 'Verifying…', cancel: 'Cancel', active: 'In use', select: 'Use',
    edit: 'Device settings', rename: 'Save label', remove: 'Remove from device', confirm: 'Confirm removal',
    removeHint: 'This removes the key from this device. You can manage the official key on DeepSeek.',
    clear: 'Deselect', preview: 'New keys in browser preview are kept only while this page is open.',
  },
}

const useCopy = () => useSettings(state => text[state.locale])
const saveFailed = () => showToast({ key: 'walletStorageFailed', type: 'error' })

function NewKey({ cancel, focusRequested }: { cancel?: () => void; focusRequested: boolean }) {
  const copy = useCopy()
  const [name, setName] = useState('')
  const form = useApiKeyForm(cancel)
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (!focusRequested) return
    if (form.verifying) {
      form.cancelVerification()
      return
    }
    if (!input.current) return
    input.current.focus({ preventScroll: true })
    input.current.scrollIntoView({ block: 'nearest' })
    keyInputFocused()
  }, [focusRequested, form.verifying, form.cancelVerification])

  return (
    <form className={styles.form} onSubmit={event => { event.preventDefault(); void form.submit(name.trim()) }}>
      <label>{copy.key}
        <input ref={input} id="official-api-key" type="password" placeholder={copy.paste} maxLength={4096}
          autoComplete="off" autoCapitalize="none" spellCheck={false} disabled={form.verifying}
          value={form.value} onChange={event => form.setValue(event.target.value)} />
      </label>
      <label>{copy.name}
        <input value={name} maxLength={64} placeholder={copy.example} autoComplete="off" disabled={form.verifying}
          onChange={event => setName(event.target.value)} />
      </label>
      {form.error && <p role="alert">{form.error}</p>}
      <div className={styles.actions}>
        <Button size="m" type="submit" disabled={!form.value.trim() || form.verifying}>{form.verifying ? copy.pending : copy.save}</Button>
        {(cancel || form.verifying) && <Button variant="outlined" size="m" onClick={cancel ?? form.cancelVerification}>{copy.cancel}</Button>}
      </div>
    </form>
  )
}

function KeyRow({ entry, active, editing, toggle }: { entry: SavedKey; active: boolean; editing: boolean; toggle(): void }) {
  const copy = useCopy()
  const [name, setName] = useState(entry.name)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const display = entry.name || 'API Key'
  const action = (type: 'select' | 'remove' | 'rename', id: string, label = '') => {
    if (!changeKey(type, id, label)) { saveFailed(); return }
    if (editing) toggle()
  }
  return (
    <li className={styles.item}>
      <div className={styles.row}>
        <div className={styles.identity}><strong>{display}</strong><small>•••• {entry.suffix} {active && <b>· {copy.active}</b>}</small></div>
        {!active && <button type="button" onClick={() => action('select', entry.id)} aria-label={copy.select + ' ' + display + ' ' + entry.suffix}>{copy.select}</button>}
        <button type="button" aria-expanded={editing} aria-label={copy.edit + ' ' + display + ' ' + entry.suffix}
          onClick={() => { setName(entry.name); setConfirmRemove(false); toggle() }}>{copy.edit}</button>
      </div>
      {editing && (
        <div className={styles.form}>
          <label>{copy.name}<input maxLength={64} value={name} onChange={event => setName(event.target.value)} /></label>
          <div className={styles.actions}>
            <Button size="s" variant="outlined" disabled={name.trim() === entry.name} onClick={() => action('rename', entry.id, name)}>{copy.rename}</Button>
            {active && <Button size="s" variant="outlined" onClick={() => action('select', '')}>{copy.clear}</Button>}
            {!confirmRemove && <Button size="s" variant="dangerOutlined" onClick={() => setConfirmRemove(true)}>{copy.remove}</Button>}
          </div>
          {confirmRemove && <>
            <p>{copy.removeHint}</p>
            <div className={styles.actions}>
              <Button size="s" variant="danger" onClick={() => action('remove', entry.id)}>{copy.confirm}</Button>
              <Button size="s" variant="outlined" onClick={() => setConfirmRemove(false)}>{copy.cancel}</Button>
            </div>
          </>}
        </div>
      )}
    </li>
  )
}

function OtherProfileKeys({ profile }: { profile: Profile }) {
  const copy = useProfileText()
  const keyText = useCopy()
  const [open, setOpen] = useState(false)
  const ring = open ? listProfileKeys(profile.id) : null
  const [error, setError] = useState(false)
  return <section className={styles.otherGroup}>
    <button type="button" aria-expanded={open} onClick={() => setOpen(!open)}>
      <span>{profile.nickname}<small>@{profile.username}</small></span>
      <span aria-hidden="true">{open ? '−' : '+'}</span>
    </button>
    {open && <div className={styles.otherList}>
      {!ring && <p>{copy.keysUnavailable}</p>}
      {ring && (ring.entries.length
        ? <ul>{ring.entries.map(key => <li key={key.id}>
            <strong>{key.name || 'API Key'}</strong><small>•••• {key.suffix} {key.id === ring.activeId && '· ' + keyText.active}</small>
          </li>)}</ul>
        : <p>{copy.noKeys}</p>)}
      <Button variant="outlined" size="s" onClick={async () => setError(!await switchProfile(profile.id, 'keys'))}>{copy.switchAndManage}</Button>
      {error && <p role="alert">{copy.switchError}</p>}
    </div>}
  </section>
}

export function AccountKey() {
  const copy = useCopy()
  const ring = useSettings(state => state.keyRing)
  const current = useCurrentProfile()
  const profiles = useProfiles(state => state.profiles)
  const profileText = useProfileText()
  const focusRequested = useSettingsDialog(state => state.focusNewKey)
  const [adding, setAdding] = useState(focusRequested)
  const [editing, setEditing] = useState<string | null>(null)
  useLayoutEffect(() => {
    if (focusRequested) {
      setAdding(true)
      setEditing(null)
    }
  }, [focusRequested])
  return (
    <>
      <div className={styles.platform}><OfficialKeysLink /></div>
      <h2 className={pages.sectionLabel}>{profileText.ownKeys} · {current.nickname}</h2>
      {ring.entries.length > 0 && <>
        <ul className={styles.list}>{ring.entries.map(entry => (
          <KeyRow key={entry.id} entry={entry} active={entry.id === ring.activeId} editing={editing === entry.id}
            toggle={() => { setAdding(false); setEditing(editing === entry.id ? null : entry.id) }} />
        ))}</ul>
      </>}
      {adding || !ring.entries.length
        ? <>
            <p className={pages.hint}>{copy.help}</p>
            <NewKey focusRequested={focusRequested} cancel={ring.entries.length ? () => setAdding(false) : undefined} />
          </>
        : <Button variant="outlined" onClick={() => { setEditing(null); setAdding(true) }}>{copy.add}</Button>}
      {profiles.length > 1 && <>
        <h2 className={styles.otherHeading}>{profileText.otherKeys}</h2>
        {profiles.filter(profile => profile.id !== current.id).map(profile => <OtherProfileKeys key={profile.id} profile={profile} />)}
      </>}
      {isKeyPreview() && <p className={pages.hint}>{copy.preview}</p>}
    </>
  )
}
