import { useEffect, useLayoutEffect, useRef } from 'react'
import { useTranslate } from '../../i18n/index.js'
import { useSearchPanel } from '../search/searchPanelState.js'
import { useSettingsDialog } from '../settings/settingsDialogState.js'
import { closeMobileSider, openMobileSider, useMobileSider } from './mobileSiderState.js'
import { Sider } from './Sider.js'
import styles from './SidebarExtras.module.css'

const overlaySelector = 'dialog[open],[aria-modal="true"],[role="dialog"],[role="alertdialog"],[role="menu"],[role="listbox"]'

function overlayOpen() {
  return Array.from(document.querySelectorAll(overlaySelector)).some(
    element => !element.closest('[hidden],[inert],[aria-hidden="true"],dialog:not([open])')
  )
}

function reserved(target: Element) {
  if (target.closest('input,textarea,select,a,[contenteditable="true"],[role="slider"],video,audio,canvas')) return true
  if (overlayOpen() || !window.getSelection()?.isCollapsed) return true
  for (let el: Element | null = target; el && el !== document.body; el = el.parentElement) {
    if (el.scrollWidth > el.clientWidth + 2 && /auto|scroll/.test(getComputedStyle(el).overflowX)) return true
  }
  return Boolean(useSettingsDialog.getState().stack.length || useSearchPanel.getState().entry)
}

type Swipe = { id: number; x: number; y: number; lastX: number; time: number; velocity: number; open: boolean; dragging: boolean }

export function MobileSider() {
  const t = useTranslate()
  const open = useMobileSider(state => state.open)
  const settingsOpen = useSettingsDialog(state => state.stack.length > 0)
  const searchOpen = useSearchPanel(state => state.entry !== null)
  useLayoutEffect(() => {
    const body = document.body
    const attribute = 'data-tokenseek-drawer-gesture'
    if (open || settingsOpen || searchOpen) {
      body.removeAttribute(attribute)
      return
    }
    const sync = () => body.toggleAttribute(attribute, !overlayOpen())
    sync()
    // Dialogs, menus and listboxes are portalled to the body. Ignore DOM churn inside streamed messages.
    const portals = new MutationObserver(sync)
    portals.observe(body, { childList: true })
    const visibility = new MutationObserver(sync)
    visibility.observe(body, {
      subtree: true, attributes: true, attributeFilter: ['open', 'role', 'aria-modal', 'hidden', 'aria-hidden', 'inert'],
    })
    return () => {
      portals.disconnect()
      visibility.disconnect()
      body.removeAttribute(attribute)
    }
  }, [open, settingsOpen, searchOpen])
  const layer = useRef<HTMLDivElement>(null)
  const sheet = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    if (sheet.current) sheet.current.inert = !open
    if (!open) return
    const main = layer.current?.parentElement?.querySelector('main')
    if (!main) return
    const previous = document.activeElement
    const wasInert = main.inert
    main.inert = true
    sheet.current?.querySelector('button')?.focus({ preventScroll: true })
    return () => {
      main.inert = wasInert
      if (previous instanceof HTMLElement && previous.isConnected && !previous.closest('[inert]')) previous.focus({ preventScroll: true })
    }
  }, [open])
  useEffect(() => {
    const root = layer.current
    const drawer = sheet.current
    if (!root || !drawer) return
    let swipe: Swipe | undefined
    let suppressClickUntil = 0
    const release = () => {
      swipe = undefined
      delete root.dataset.dragging
      root.style.removeProperty('--drawer-offset')
      root.style.removeProperty('--shade')
    }
    const start = (event: TouchEvent) => {
      if (event.touches.length !== 1) { release(); return }
      const target = event.target
      if (!(target instanceof Element) || reserved(target) || (window.visualViewport?.scale ?? 1) !== 1) return
      const open = useMobileSider.getState().open
      if (open ? !root.contains(target) : !target.closest('main')) return
      const { identifier: id, clientX: x, clientY: y } = event.touches[0]
      swipe = { id, x, y, lastX: x, time: event.timeStamp, velocity: 0, open, dragging: false }
    }
    const move = (event: TouchEvent) => {
      if (!swipe) return
      const point = event.touches[0]
      if (event.touches.length !== 1 || point.identifier !== swipe.id || useMobileSider.getState().open !== swipe.open) { release(); return }
      const dx = point.clientX - swipe.x
      const dy = point.clientY - swipe.y
      if (!swipe.dragging) {
        if (Math.max(Math.abs(dx), Math.abs(dy)) < 9) return
        if (Math.abs(dx) < 1.4 * Math.abs(dy) || (swipe.open ? dx > 0 : dx < 0) || !window.getSelection()?.isCollapsed || !event.cancelable) {
          release(); return
        }
        swipe.dragging = true
        root.dataset.dragging = 'true'
      }
      event.preventDefault()
      const width = drawer.offsetWidth || 1
      const offset = Math.max(-width, Math.min(0, (swipe.open ? 0 : -width) + dx))
      root.style.setProperty('--drawer-offset', `${offset}px`)
      root.style.setProperty('--shade', String(.32 * (1 + offset / width)))
      const elapsed = event.timeStamp - swipe.time
      if (elapsed > 0) swipe.velocity = (point.clientX - swipe.lastX) / elapsed
      swipe.lastX = point.clientX
      swipe.time = event.timeStamp
    }
    const end = (event: TouchEvent) => {
      if (swipe?.dragging) {
        const distance = (swipe.lastX - swipe.x) * (swipe.open ? -1 : 1)
        const speed = swipe.velocity * (swipe.open ? -1 : 1)
        if (distance >= drawer.offsetWidth * .35 || (distance > 26 && event.timeStamp - swipe.time < 120 && speed > .5)) {
          if (swipe.open) closeMobileSider(); else openMobileSider()
        }
        if (event.cancelable) event.preventDefault()
        suppressClickUntil = performance.now() + 290
      }
      release()
    }
    const nextPointer = () => { suppressClickUntil = 0 }
    const click = (event: MouseEvent) => {
      if (event.detail && performance.now() < suppressClickUntil) { event.preventDefault(); event.stopImmediatePropagation() }
    }
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && useMobileSider.getState().open && !overlayOpen()) closeMobileSider()
    }
    document.addEventListener('touchstart', start, { passive: true, capture: true })
    document.addEventListener('touchmove', move, { passive: false, capture: true })
    document.addEventListener('touchend', end, { passive: false, capture: true })
    document.addEventListener('touchcancel', release, true)
    document.addEventListener('pointerdown', nextPointer, true)
    document.addEventListener('click', click, true)
    document.addEventListener('keydown', escape, true)
    window.addEventListener('blur', release)
    return () => {
      document.removeEventListener('touchstart', start, true)
      document.removeEventListener('touchmove', move, true)
      document.removeEventListener('touchend', end, true)
      document.removeEventListener('touchcancel', release, true)
      document.removeEventListener('pointerdown', nextPointer, true)
      document.removeEventListener('click', click, true)
      document.removeEventListener('keydown', escape, true)
      window.removeEventListener('blur', release)
    }
  }, [])
  return <div ref={layer} className={styles.layer} data-open={open}>
    <button type="button" className={styles.shade} aria-label={t('collapseSiderbarButton')} tabIndex={-1}
      aria-hidden={!open} onClick={closeMobileSider}/>
    <div ref={sheet} className={styles.sheet} aria-hidden={!open}><Sider mobile/></div>
  </div>
}
