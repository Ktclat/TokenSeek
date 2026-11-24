export type SavedKey = { id: string; name: string; suffix: string }
export type KeyRing = { activeId: string | null; entries: SavedKey[] }
export type KeyAction = 'read' | 'add' | 'select' | 'rename' | 'remove'
type Snapshot = KeyRing & { apiKey: string | null }

const inAndroid = () => Boolean(window.TokenSeekSecrets) ||
  window.location.origin === 'https://appassets.androidplatform.net'

// The standalone web preview has no Android Keystore. Hold its keys in memory only.
let previewKeys: { id: string; name: string; secret: string }[] = []
let previewActive: string | null = null
let previewInitialized = false

function preview(action: KeyAction, id: string, name: string, secret: string): Snapshot | null {
  if (!previewInitialized) {
    previewInitialized = true
    try {
      // Existing preview data remains available; this version never writes a secret to localStorage.
      const previous = localStorage.getItem('chat-demo:api-key')
      if (previous) {
        previewActive = 'imported'
        previewKeys.push({ id: previewActive, name: '', secret: previous })
      }
    } catch { /* Storage is optional in the preview. */ }
  }
  const selected = previewKeys.find(entry => entry.id === id)
  switch (action) {
    case 'add': {
      if (!secret || secret.length > 4096 || name.length > 64) return null
      let entry = previewKeys.find(item => item.secret === secret)
      if (!entry) {
        entry = { id: crypto.randomUUID(), name, secret }
        previewKeys.push(entry)
      } else if (name) entry.name = name
      previewActive = entry.id
      break
    }
    case 'select':
      if (id && !selected) return null
      previewActive = id || null
      break
    case 'rename':
      if (!selected || name.length > 64) return null
      selected.name = name
      break
    case 'remove':
      if (!selected) return null
      if (id === 'imported') {
        // Retire an old browser credential only when its owner explicitly removes it.
        try { localStorage.removeItem('chat-demo:api-key') } catch { return null }
      }
      previewKeys = previewKeys.filter(item => item.id !== id)
      if (previewActive === id) previewActive = null
      break
  }
  return {
    activeId: previewActive, apiKey: previewKeys.find(item => item.id === previewActive)?.secret ?? null,
    entries: previewKeys.map(item => ({ id: item.id, name: item.name, suffix: item.secret.slice(-4) })),
  }
}

export function accessKeys(action: KeyAction, id = '', name = '', secret = ''): Snapshot | null {
  if (!inAndroid()) return preview(action, id, name.trim(), secret)
  try {
    const result = window.TokenSeekSecrets?.manageKeys(action, id, name.trim(), secret)
    if (!result) return null
    return JSON.parse(result) as Snapshot
  } catch {
    return null
  }
}

export const isKeyPreview = () => !inAndroid()
