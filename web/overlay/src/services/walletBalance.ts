import { useEffect, useSyncExternalStore } from 'react'
import { getBalance } from './api/balance.js'
import { classifyApiError } from './api/errors.js'

export type WalletState =
  | { status: 'unbound' | 'loading' | 'invalid' | 'error' }
  | { status: 'ready'; balance: Awaited<ReturnType<typeof getBalance>> }

const unbound: WalletState = { status: 'unbound' }
const loading: WalletState = { status: 'loading' }
const listeners = new Set<() => void>()
let activeKey: string | null = null
let snapshot: WalletState = unbound
let controller: AbortController | undefined
let users = 0
let timer: ReturnType<typeof setTimeout>

const subscribe = (fn: () => void) => {
  listeners.add(fn)
  return () => { listeners.delete(fn) }
}
function publish(next: WalletState) {
  snapshot = next
  listeners.forEach(fn => fn())
}
function refresh() {
  const key = activeKey
  controller?.abort()
  if (!key) return
  const request = new AbortController()
  controller = request
  publish(loading)
  const timeout = setTimeout(() => request.abort(), 15000)
  void getBalance(key, request.signal)
    .then(balance => { if (controller === request) publish({ status: 'ready', balance }) })
    .catch(error => {
      if (controller === request) publish({ status: classifyApiError(error) === 'unauthorized' ? 'invalid' : 'error' })
    })
    .finally(() => clearTimeout(timeout))
}
function onReturn() {
  if (document.visibilityState !== 'visible' || !users) return
  clearTimeout(timer)
  timer = setTimeout(refresh, 100)
}

export function useBalance(apiKey: string | null) {
  const state = useSyncExternalStore(subscribe, () => apiKey ? (apiKey === activeKey ? snapshot : loading) : unbound)
  useEffect(() => {
    if (!apiKey) return
    if (++users === 1) {
      window.addEventListener('focus', onReturn)
      window.addEventListener('pageshow', onReturn)
      window.addEventListener('tokenseek:resume', onReturn)
      document.addEventListener('visibilitychange', onReturn)
    }
    if (apiKey !== activeKey) { activeKey = apiKey; refresh() }
    return () => {
      if (--users !== 0) return
      window.removeEventListener('focus', onReturn)
      window.removeEventListener('pageshow', onReturn)
      window.removeEventListener('tokenseek:resume', onReturn)
      document.removeEventListener('visibilitychange', onReturn)
      clearTimeout(timer)
      controller?.abort()
      controller = undefined
      activeKey = null
      snapshot = unbound
    }
  }, [apiKey])
  return { state, refresh }
}
