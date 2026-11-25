import { useState } from 'react'
import { initials, type Profile } from '../../services/profiles.js'
import styles from './ProfileSettings.module.css'

export function ProfileAvatar({ profile, className = styles.avatar }: {
  profile: Pick<Profile, 'nickname' | 'avatar'>; className?: string
}) {
  const [failed, setFailed] = useState<string>()
  const photo = profile.avatar
  return <span className={className + ' ' + styles.crop} aria-hidden="true">
    {photo && failed !== photo
      ? <img src={photo} alt="" onError={() => setFailed(photo)} />
      : initials(profile.nickname) || '·'}
  </span>
}
