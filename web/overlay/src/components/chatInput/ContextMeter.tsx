import { useId, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import type { ChatModel } from '../../services/api/types.js'
import { getVisibleMessages } from '../../services/chat/branches.js'
import {
  contextEstimate,
  estimateDraftTokens,
  lastWindowTokens,
  remainingLabel,
  type ContextEstimate,
} from '../../services/chat/contextWindow.js'
import { useChatStore } from '../../services/chat/store.js'
import { useSettings } from '../../services/settings.js'
import { useTranslate } from '../../i18n/index.js'
import { ConversationUsage } from '../messages/ConversationUsage.js'
import { IconChevronRightOutline14 } from '../base/icons.js'
import pages from '../settings/SettingsPages.module.css'
import styles from './ContextMeter.module.css'

const labels = {
  'zh-CN': {
    back: '返回',
    remaining: '上下文剩余（估算）',
    available: '可用空间',
    detail: '查看对话用量',
    pending: '待统计',
    branchRemaining: '本分支剩余上下文（估算）',
    capacity: '模型窗口',
    last: '最近一次请求',
    draftTokens: '草稿估算',
    total: '模型请求用量',
    streaming: '回答生成中，结束后更新窗口估算。',
    attachment: '草稿含附件，解析后的 token 数待官方返回。',
    history: '当前分支的最后一轮缺少最终用量。',
    draft: '这段草稿无法按中英文字符比例可靠估算。',
    near: '可用上下文偏低。',
    over: '预计已用完此窗口，可缩短草稿或开启新对话。',
  },
  'en-US': {
    back: 'Back',
    remaining: 'Estimated context remaining',
    available: 'Available space',
    detail: 'View conversation usage',
    pending: 'Unknown',
    branchRemaining: 'Estimated context left on this branch',
    capacity: 'Model window',
    last: 'Last request',
    draftTokens: 'Draft estimate',
    total: 'Model request usage',
    streaming: 'The estimate updates when the reply finishes.',
    attachment: 'The draft includes files. Their token count is available after processing.',
    history: 'The latest turn on this branch has no final usage record.',
    draft:
      'This draft cannot be estimated reliably using the Chinese and English character ratios.',
    near: 'Available context is running low.',
    over: 'The estimated window is full. Shorten the draft or start a new chat.',
  },
}

type Props = {
  sessionId: string | null
  model: ChatModel
  text: string
  hasAttachment: boolean
  streaming: boolean
}

export function ContextMeter({ sessionId, model, text, hasAttachment, streaming }: Props) {
  const [open, setOpen] = useState(false)
  const trigger = useRef<HTMLButtonElement>(null)
  const locale = useSettings((state) => state.locale)
  const copy = labels[locale]
  const previous = useChatStore((state) => {
    if (sessionId === null) return 0n
    const session = state.sessionCache[sessionId]
    return session && !streaming ? lastWindowTokens(getVisibleMessages(session)) : null
  })
  const draft = useMemo(() => estimateDraftTokens(text), [text])
  const estimate = contextEstimate(model, previous, draft, hasAttachment, streaming)
  const percent = remainingLabel(estimate)
  const { remaining, limit } = estimate
  const fraction = remaining !== null ? remaining / limit : null
  const description = copy.remaining + ' ' + (remaining === null ? copy.pending : percent)
  return (
    <>
      <button
        type="button"
        ref={trigger}
        className={styles.meter}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={description + ' · ' + copy.detail}
        title={description}
        onClick={() => setOpen(true)}
      >
        <svg className={styles.ring} viewBox="0 0 40 40" aria-hidden="true">
          <circle
            cx="20"
            cy="20"
            r="16.5"
            className={styles.track}
            strokeDasharray={fraction === null ? '2 3' : undefined}
          />
          {fraction !== null && fraction > 0 && (
            <circle
              cx="20"
              cy="20"
              r="16.5"
              pathLength="100"
              className={styles.arc}
              strokeDasharray={fraction * 100 + ' 100'}
              transform="rotate(-90 20 20)"
            />
          )}
        </svg>
        <span aria-hidden="true">{percent}</span>
      </button>
      {open && (
        <ContextDetails
          sessionId={sessionId}
          model={model}
          estimate={estimate}
          onDismiss={() => {
            setOpen(false)
            trigger.current?.focus({ preventScroll: true })
          }}
        />
      )}
    </>
  )
}

function ContextDetails({
  sessionId,
  model,
  estimate,
  onDismiss,
}: {
  sessionId: string | null
  model: ChatModel
  estimate: ContextEstimate
  onDismiss: () => void
}) {
  const locale = useSettings((state) => state.locale)
  const copy = labels[locale]
  const t = useTranslate()
  const session = useChatStore((state) =>
    sessionId === null ? undefined : state.sessionCache[sessionId]
  )
  const title = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  const back = useRef<HTMLButtonElement>(null)
  const dismiss = useRef(onDismiss)
  dismiss.current = onDismiss
  useLayoutEffect(() => {
    const el = dialog.current
    if (!el) return
    el.showModal()
    back.current?.focus({ preventScroll: true })
    const onBack = (event: Event) => {
      event.preventDefault()
      event.stopImmediatePropagation()
      dismiss.current()
    }
    window.addEventListener('tokenseek:back', onBack, true)
    return () => {
      window.removeEventListener('tokenseek:back', onBack, true)
      el.close()
    }
  }, [])
  const { previous, draft, remaining, reason, limit } = estimate
  const count = (value: bigint | number) => value.toLocaleString(locale)
  return createPortal(
    <dialog
      ref={dialog}
      className={styles.page}
      aria-labelledby={title}
      onCancel={(event) => {
        event.preventDefault()
        onDismiss()
      }}
    >
      <header className={pages.bar}>
        <div className={pages.barInner}>
          <button
            type="button"
            ref={back}
            className={pages.back}
            aria-label={copy.back}
            onClick={onDismiss}
          >
            <IconChevronRightOutline14 />
          </button>
          <h1 id={title}>{t('conversationUsage')}</h1>
        </div>
      </header>
      <div className={pages.scroll}>
        <div className={pages.inner}>
          <section className={styles.card}>
            <h2 className={styles.kicker}>{copy.branchRemaining}</h2>
            <p className={styles.figure}>
              {remaining === null ? copy.pending : remainingLabel(estimate)}
            </p>
            <dl className={styles.readings}>
              {remaining !== null && (
                <div>
                  <dt>{copy.available}</dt>
                  <dd>{count(remaining)} tokens</dd>
                </div>
              )}
              <div>
                <dt>{copy.capacity}</dt>
                <dd>
                  {model === 'deepseek-flash' ? 'Flash' : 'Pro'} · {count(limit)} tokens
                </dd>
              </div>
              {previous !== null && previous > 0n && (
                <div>
                  <dt>{copy.last}</dt>
                  <dd>{count(previous)} tokens</dd>
                </div>
              )}
              {draft !== null && draft > 0 && (
                <div>
                  <dt>{copy.draftTokens}</dt>
                  <dd>≈ {count(draft)} tokens</dd>
                </div>
              )}
            </dl>
            {reason && <p className={styles.hint}>{copy[reason]}</p>}
            {remaining !== null && remaining / limit <= 0.05 && (
              <p className={styles.hint}>{remaining === 0 ? copy.over : copy.near}</p>
            )}
          </section>
          <h2 className={styles.heading}>{copy.total}</h2>
          <section className={styles.card}>
            <ConversationUsage
              messages={session?.messages ?? []}
              visible={session ? getVisibleMessages(session) : []}
            />
          </section>
        </div>
      </div>
    </dialog>,
    document.body
  )
}
