import { isTokenUsage, type MessageUsage } from '../api/types.js'
import type { AssistantMessage } from './types.js'

export type UsageRun =
  | { kind: 'missing' }
  | { kind: 'initial' | 'final' | 'legacy'; usage: MessageUsage }

/** Earlier builds preserved at most the latest snapshot, even for a continued reply. */
export function getUsageRuns(reply: AssistantMessage): UsageRun[] {
  if (reply.usageRuns) return reply.usageRuns
  if (isTokenUsage(reply.usage)) return [{ kind: 'legacy', usage: reply.usage }]
  return reply.error?.kind === 'upload' ? [] : [{ kind: 'missing' }]
}

export function beginUsageRun(reply: AssistantMessage): AssistantMessage {
  return { ...reply, usage: undefined, usageRuns: [...getUsageRuns(reply), { kind: 'missing' }] }
}

/** A message_delta is cumulative for this request. It supersedes its start and any earlier delta. */
export function recordUsage(
  reply: AssistantMessage, usage: MessageUsage | undefined, kind: 'initial' | 'final'
): AssistantMessage {
  if (!usage) return reply
  const runs = reply.usageRuns ?? []
  if (kind === 'initial' && runs.at(-1)?.kind === 'final') return reply
  return { ...reply, usage, usageRuns: [...runs.slice(0, -1), { kind, usage }] }
}

export function readUsageRun(value: unknown): UsageRun | null {
  if (!value || typeof value !== 'object' || !('kind' in value)) return null
  if (value.kind === 'missing') return { kind: 'missing' }
  if (!['initial', 'final', 'legacy'].includes(String(value.kind))) return null
  if (!('usage' in value) || !isTokenUsage(value.usage)) return null
  const { input_tokens, cache_creation_input_tokens, cache_read_input_tokens, output_tokens } = value.usage
  return {
    kind: value.kind as 'initial' | 'final' | 'legacy',
    usage: { input_tokens, cache_creation_input_tokens, cache_read_input_tokens, output_tokens },
  }
}

export function summarizeUsage(replies: AssistantMessage[]) {
  let input = 0n
  let output = 0n
  let cached = 0n
  let confirmed = 0
  let incomplete = 0
  let snapshots = 0
  let snapshotTokens = 0n
  for (const reply of replies) {
    for (const run of getUsageRuns(reply)) {
      if (run.kind !== 'final') incomplete++
      if (run.kind === 'missing') continue
      const counts = run.usage
      const hits = BigInt(counts.cache_read_input_tokens)
      const inputTokens = BigInt(counts.input_tokens) + BigInt(counts.cache_creation_input_tokens) + hits
      const outputTokens = BigInt(counts.output_tokens)
      if (run.kind === 'final') {
        confirmed++
        input += inputTokens
        output += outputTokens
        cached += hits
      } else {
        snapshots++
        snapshotTokens += inputTokens + outputTokens
      }
    }
  }
  return { confirmed, incomplete, snapshots, snapshotTokens, input, output, cached, total: input + output }
}
