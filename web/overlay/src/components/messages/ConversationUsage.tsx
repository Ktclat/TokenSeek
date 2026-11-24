import { useState } from 'react'
import { useTranslate } from '../../i18n/index.js'
import { summarizeUsage } from '../../services/chat/usage.js'
import type { AssistantMessage, Message } from '../../services/chat/types.js'
import { useSettings } from '../../services/settings.js'
import styles from './ConversationUsage.module.css'

export function ConversationUsage({ messages, visible }: { messages: Message[]; visible: Message[] }) {
  const t = useTranslate()
  const locale = useSettings(state => state.locale)
  const [scope, setScope] = useState<'session' | 'reply'>('session')
  const [selectedId, setSelectedId] = useState('')
  const branchReplies = visible.filter((message): message is AssistantMessage => message.role === 'assistant')
  const index = branchReplies.findIndex(reply => reply.id === selectedId)
  const position = index < 0 ? branchReplies.length - 1 : index
  const current = branchReplies[position]
  const replies = messages.filter((message): message is AssistantMessage => message.role === 'assistant')
  if (!replies.length) return null
  const hasCurrent = current?.role === 'assistant'
  const session = scope === 'session' || !hasCurrent
  const conversation = summarizeUsage(replies)
  const usage = session ? conversation : summarizeUsage([current])
  const format = (count: bigint) => count.toLocaleString(locale)
  return (
    <details className={styles.panel}>
      <summary>
        <span>{t('conversationUsage')}</span>
        <span className={styles.value}>
          {conversation.confirmed > 0
            ? t(conversation.incomplete ? 'usagePartialCount' : 'usageTokenCount', { total: format(conversation.total) })
            : t('usageNoFinal')}
        </span>
      </summary>
      {hasCurrent && (
        <div role="group" aria-label={t('usageScope')} className={styles.scopes}>
          <button type="button" aria-pressed={session} onClick={() => setScope('session')}>{t('usageSession')}</button>
          <button type="button" aria-pressed={!session} onClick={() => setScope('reply')}>{t('usageReply')}</button>
        </div>
      )}
      {!session && current && (
        <div className={styles.reply}>
          {branchReplies.length > 1 && (
            <div role="group" className={styles.pager} aria-label={t('usageChooseReply')}>
              <button type="button" aria-label={t('usagePrevious')} disabled={position === 0}
                onClick={() => setSelectedId(branchReplies[position - 1].id)}>‹</button>
              <span aria-live="polite">{t('usageReplyNumber', { n: position + 1, total: branchReplies.length })}</span>
              <button type="button" aria-label={t('usageNext')} disabled={position === branchReplies.length - 1}
                onClick={() => setSelectedId(branchReplies[position + 1].id)}>›</button>
            </div>
          )}
          {current.model && <span>{t('usageRequestModel', { model: current.model })}</span>}
        </div>
      )}
      {usage.confirmed > 0 && (
        <>
          <dl>
            <div><dt>{t('usageTotal')}</dt><dd>{format(usage.total)}</dd></div>
            <div><dt>{t('usageInput')}</dt><dd>{format(usage.input)}</dd></div>
            <div><dt>{t('usageOutput')}</dt><dd>{format(usage.output)}</dd></div>
            <div><dt>{t('usageCached')}</dt><dd>{format(usage.cached)}</dd></div>
          </dl>
          <p>{t('usageFinalCount', { count: usage.confirmed })}</p>
        </>
      )}
      {usage.confirmed === 0 && <p>{t('usageNoFinal')}</p>}
      {usage.incomplete > 0 && <p>{t('usageIncomplete', { count: usage.incomplete })}</p>}
      {usage.snapshots > 0 && <p>{t('usageSnapshots', { total: format(usage.snapshotTokens) })}</p>}
      {session && <p>{t('usageAllBranches')}</p>}
    </details>
  )
}
