import { useRef, useState, type FormEvent } from 'react'
import {
  saveProfile, switchProfile, useCurrentProfile, useProfiles, type Profile,
} from '../../services/profiles.js'
import { avatarTypes, makeProfilePhoto } from '../../services/profilePhoto.js'
import { ProfileAvatar } from './ProfileAvatar.js'
import { useSettings } from '../../services/settings.js'
import { Button } from '../base/Button.js'
import { pushSettings } from './settingsDialogState.js'
import styles from './ProfileSettings.module.css'

const strings = {
  'zh-CN': {
    photo: '选择照片', resetPhoto: '恢复默认头像', photoBusy: '正在处理照片…',
    photoFormat: '请选择可以打开的 JPG、PNG 或 WebP 照片。', photoSize: '请选择 16 MB 以内、最多 6400 万像素的照片。',
    profiles: '本机账户', switchDeviceProfile: '切换本机账户', edit: '编辑资料', username: '用户名',
    nickname: '昵称', nicknameHint: '默认使用用户名', newProfile: '添加账户', active: '当前账户',
    use: '切换', save: '保存', create: '创建并切换', cancel: '取消', busy: '正在切换…',
    help: '新账户的对话、草稿和密钥单独保存。',
    duplicate: '此用户名已在这台设备上使用。', invalid: '请输入 1 至 64 个字符的用户名。',
    storage: '无法保存账户。请检查本机存储后重试。', switchError: '未能切换账户，请稍后重试。',
    otherKeys: '其他账户的密钥', noKeys: '未保存密钥', switchAndManage: '切换到此账户管理',
    keysUnavailable: '暂时无法读取密钥', ownKeys: '当前账户',
  },
  'en-US': {
    photo: 'Choose photo', resetPhoto: 'Use initials', photoBusy: 'Processing photo…',
    photoFormat: 'Choose a readable JPG, PNG, or WebP photo.', photoSize: 'Choose a photo up to 16 MB and 64 megapixels.',
    profiles: 'Device accounts', switchDeviceProfile: 'Switch device account', edit: 'Edit profile', username: 'Username',
    nickname: 'Nickname', nicknameHint: 'Defaults to your username', newProfile: 'Add account', active: 'Current account',
    use: 'Switch', save: 'Save', create: 'Create and switch', cancel: 'Cancel', busy: 'Switching…',
    help: 'The new account keeps its own chats, drafts, and keys.',
    duplicate: 'This username is already in use on this device.', invalid: 'Enter a username of 1–64 characters.',
    storage: 'Could not save this account. Check device storage and try again.', switchError: 'Could not switch accounts. Try again.',
    otherKeys: 'Keys in other accounts', noKeys: 'No saved keys', switchAndManage: 'Switch to manage these keys',
    keysUnavailable: 'Keys are unavailable', ownKeys: 'Current account',
  },
}
export const useProfileText = () => useSettings(state => strings[state.locale])

export function ProfileIdentity({ profile }: { profile: Profile }) {
  return <>
    <ProfileAvatar profile={profile} />
    <span className={styles.identity}><strong>{profile.nickname}</strong>{profile.username !== profile.nickname && <small>@{profile.username}</small>}</span>
  </>
}

export function ProfileHeader() {
  const profile = useCurrentProfile()
  const copy = useProfileText()
  return <div className={styles.header}>
    <ProfileIdentity profile={profile} />
    <button type="button" className={styles.action} onClick={() => pushSettings('profiles')}>{copy.switchDeviceProfile}</button>
  </div>
}

export function ProfileSettings() {
  const profiles = useProfiles(s => s.profiles)
  const current = useCurrentProfile()
  const copy = useProfileText()
  const [editing, setEditing] = useState<'new' | 'current' | null>(null)
  const [username, setUsername] = useState('')
  const [nickname, setNickname] = useState('')
  const [busy, setBusy] = useState(false)
  const [avatar, setAvatar] = useState<string | null>(null)
  const [readingPhoto, setReadingPhoto] = useState(false)
  const photoVersion = useRef(0)
  const picker = useRef<HTMLInputElement>(null)

  const readPhoto = async (file?: File) => {
    if (!file) return
    const version = ++photoVersion.current
    setReadingPhoto(true)
    setError('')
    try {
      const photo = await makeProfilePhoto(file)
      if (photoVersion.current === version) setAvatar(photo)
    } catch (cause) {
      if (photoVersion.current === version) setError(cause instanceof Error && cause.message === 'size' ? copy.photoSize : copy.photoFormat)
    } finally {
      if (photoVersion.current === version) setReadingPhoto(false)
    }
  }
  const [error, setError] = useState('')

  const openForm = (mode: 'new' | 'current') => {
    ++photoVersion.current
    setReadingPhoto(false)
    setAvatar(mode === 'current' ? current.avatar ?? null : null)
    setEditing(mode)
    setError('')
    setUsername(mode === 'new' ? '' : current.username)
    setNickname(mode === 'new' || current.nickname === current.username ? '' : current.nickname)
  }
  const switchTo = async (id: string) => {
    setBusy(true)
    if (!await switchProfile(id)) { setError(copy.switchError); setBusy(false) }
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (readingPhoto || busy) return
    const result = saveProfile(editing === 'current' ? current.id : null, username, nickname, avatar)
    if ('error' in result) { setError(copy[result.error]); return }
    setEditing(null)
    if (result.value.id !== current.id) void switchTo(result.value.id)
  }

  return <section className={styles.panel}>
    <ul className={styles.list}>{profiles.map(profile => <li key={profile.id}>
      <ProfileIdentity profile={profile} />
      {profile.id === current.id
        ? <span className={styles.selected}>{copy.active}</span>
        : <button type="button" className={styles.action} disabled={busy} aria-label={copy.use + ' ' + profile.nickname}
            onClick={() => void switchTo(profile.id)}>{copy.use}</button>}
    </li>)}</ul>
    {error && <p role="alert" className={styles.error}>{error}</p>}
    {busy && <p role="status">{copy.busy}</p>}
    {editing ? <form className={styles.form} onSubmit={submit}>
      {editing === 'new' && <p className={styles.hint}>{copy.help}</p>}
      <div className={styles.photoEditor}>
        <ProfileAvatar profile={{ nickname: nickname.trim() || username.trim(), avatar: avatar ?? undefined }} />
        <input ref={picker} hidden type="file" accept={avatarTypes} aria-label={copy.photo}
          onChange={event => {
            const file = event.target.files?.[0]
            event.target.value = ''
            void readPhoto(file)
          }} />
        <button type="button" className={styles.action} onClick={() => picker.current?.click()}>{copy.photo}</button>
        {avatar && <button type="button" className={styles.action} onClick={() => {
          ++photoVersion.current; setReadingPhoto(false); setAvatar(null); setError('')
        }}>{copy.resetPhoto}</button>}
      </div>
      {readingPhoto && <span role="status">{copy.photoBusy}</span>}
      <label>{copy.username}<input autoFocus required maxLength={64} value={username}
        autoCapitalize="none" spellCheck={false} onChange={event => setUsername(event.target.value)} /></label>
      <label>{copy.nickname}<input maxLength={64} value={nickname} placeholder={copy.nicknameHint}
        onChange={event => setNickname(event.target.value)} /></label>
      <div className={styles.buttons}>
        <Button type="submit" size="m" disabled={!username.trim() || busy || readingPhoto}>{editing === 'new' ? copy.create : copy.save}</Button>
        <Button size="m" variant="outlined" onClick={() => { ++photoVersion.current; setEditing(null); setError('') }}>{copy.cancel}</Button>
      </div>
    </form> : <div className={styles.buttons}>
      <Button size="m" disabled={busy} onClick={() => openForm('new')}>{copy.newProfile}</Button>
      <Button variant="outlined" size="m" disabled={busy} onClick={() => openForm('current')}>{copy.edit}</Button>
    </div>}
  </section>
}
