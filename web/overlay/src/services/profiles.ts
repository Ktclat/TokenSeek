import { create } from 'zustand'
import { isProfilePhoto } from './profilePhoto.js'

export type Profile = { id: string; username: string; nickname: string; avatar?: string }
type ProfileDirectory = { active: string; profiles: Profile[] }

const DIRECTORY = 'tokenseek:profiles:v1'
const initial: ProfileDirectory = {
  active: 'default',
  profiles: [{ id: 'default', username: 'TokenSeek', nickname: 'TokenSeek' }],
}

function readDirectory(): ProfileDirectory {
  const stored = localStorage.getItem(DIRECTORY)
  if (stored === null) return initial
  const data: unknown = JSON.parse(stored)
  if (!data || typeof data !== 'object' || !('profiles' in data) || !Array.isArray(data.profiles)
      || !('active' in data) || !data.profiles.some(p => p.id === data.active)
      || !data.profiles.every(p => typeof p.id === 'string' && typeof p.username === 'string' && typeof p.nickname === 'string')) {
    throw new Error('Invalid profile directory')
  }
  const directory = data as ProfileDirectory
  return {
    ...directory,
    profiles: directory.profiles.map(({ avatar, ...p }) => isProfilePhoto(avatar) ? { ...p, avatar } : p),
  }
}

function loadDirectory() {
  try { return readDirectory() } catch { return initial }
}

const directory = loadDirectory()
// Database names and key operations stay with this document, including callbacks finishing during a switch.
const workspaceId = directory.active
export const getActiveWorkspaceId = () => workspaceId
export const profileStorageName = (base: string) => workspaceId === 'default' ? base : base + ':' + workspaceId
export const useProfiles = create<{ profiles: Profile[] }>(() => ({ profiles: directory.profiles }))
export const useCurrentProfile = () => useProfiles(state => state.profiles.find(p => p.id === workspaceId)!)
export const initials = (name: string): string => {
  const words = name.trim().split(/\s+/u)
  const first = Array.from(words[0] ?? '')
  return (words.length > 1 ? first[0] + Array.from(words[words.length - 1])[0] : first.slice(0, 2).join('')).toLocaleUpperCase()
}

type ProfileError = 'duplicate' | 'invalid' | 'storage'
type Result<T> = { value: T } | { error: ProfileError }

export function saveProfile(id: string | null, username: string, nickname: string, avatar?: string | null): Result<Profile> {
  username = username.trim()
  nickname = nickname.trim() || username
  if (!username || username.length > 64 || nickname.length > 64) return { error: 'invalid' }
  try {
    const current = readDirectory()
    const normalized = (s: string) => s.normalize('NFKC').toLowerCase()
    if (current.profiles.some(p => p.id !== id && normalized(p.username) === normalized(username))) return { error: 'duplicate' }
    if (id && !current.profiles.some(p => p.id === id)) return { error: 'storage' }
    const photo = avatar === undefined ? current.profiles.find(p => p.id === id)?.avatar : avatar
    if (photo && !isProfilePhoto(photo)) return { error: 'invalid' }
    const profile = { id: id ?? crypto.randomUUID(), username, nickname, ...(photo ? { avatar: photo } : {}) }
    const profiles = id ? current.profiles.map(p => p.id === id ? profile : p) : [...current.profiles, profile]
    localStorage.setItem(DIRECTORY, JSON.stringify({ ...current, profiles }))
    useProfiles.setState({ profiles })
    return { value: profile }
  } catch { return { error: 'storage' } }
}

let switching = false
export const isSwitchingProfile = () => switching

export async function switchProfile(id: string, page: 'account' | 'keys' = 'account'): Promise<boolean> {
  if (id === workspaceId) return true
  if (switching) return false
  switching = true
  const wasInert = document.body.inert
  document.body.inert = true
  try {
    const { prepareProfileSwitch } = await import('./chat/store.js')
    await prepareProfileSwitch()
    const current = readDirectory()
    if (!current.profiles.some(p => p.id === id)) throw new Error('Unknown profile')
    localStorage.setItem(DIRECTORY, JSON.stringify({ ...current, active: id }))
    window.history.replaceState(null, '', location.pathname + location.search + '#return-official-' + page)
    window.location.reload()
    return true
  } catch {
    switching = false
    document.body.inert = wasInert
    return false
  }
}
