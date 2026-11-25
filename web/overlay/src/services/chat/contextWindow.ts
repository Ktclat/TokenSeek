import type { ChatModel } from '../api/types.js'
import { isTokenUsage } from '../api/types.js'
import type { Message } from './types.js'
import { getUsageRuns } from './usage.js'

// Official /models context_window counts input and output together.
// https://api-docs.deepseek.com/api/list-models/
export const contextWindows: Record<ChatModel, number> = {
  'deepseek-flash': 1_048_576,
  'deepseek-v4-pro': 1_048_576,
}

/** A continuation contains previous requests too. Only its latest final snapshot describes that turn. */
export function lastWindowTokens(visible: Message[]): bigint | null {
  if (visible.length === 0) return 0n
  const last = visible[visible.length - 1]
  if (last.role !== 'assistant' || (last.status !== 'done' && last.status !== 'stopped'))
    return null
  const run = getUsageRuns(last).at(-1)
  if (run?.kind !== 'final' || !isTokenUsage(run.usage)) return null
  const usage = run.usage
  return (
    BigInt(usage.input_tokens) +
    BigInt(usage.cache_read_input_tokens) +
    BigInt(usage.cache_creation_input_tokens) +
    BigInt(usage.output_tokens)
  )
}

// Rough ratios from https://api-docs.deepseek.com/quick_start/token_usage/.
// Text stays on the device. Other scripts and file contents have no reliable conversion here.
export function estimateDraftTokens(text: string): number | null {
  let tenths = 0
  for (const char of text.trim()) {
    if (char.charCodeAt(0) < 128) tenths += 3
    else if (/[\p{Script=Han}\u3000-\u303f\uff01-\uff60]/u.test(char)) tenths += 6
    else return null
  }
  return Math.ceil(tenths / 10)
}

export type ContextEstimate = {
  limit: number
  previous: bigint | null
  draft: number | null
  remaining: number | null
  reason: 'streaming' | 'attachment' | 'history' | 'draft' | null
}

export function contextEstimate(
  model: ChatModel,
  previous: bigint | null,
  draft: number | null,
  hasAttachment: boolean,
  streaming: boolean
): ContextEstimate {
  const limit = contextWindows[model]
  const reason = streaming
    ? 'streaming'
    : hasAttachment
      ? 'attachment'
      : previous === null
        ? 'history'
        : draft === null
          ? 'draft'
          : null
  let remaining: number | null = null
  if (!reason && previous !== null && draft !== null) {
    const available = BigInt(limit) - previous - BigInt(draft)
    remaining = available > 0n ? Number(available) : 0
  }
  return { limit, previous, draft, remaining, reason }
}

export function remainingLabel({ remaining, limit }: ContextEstimate): string {
  if (remaining === null) return '—'
  const percentage = (remaining * 100) / limit
  if (percentage > 99 && percentage < 100) return '>99%'
  if (percentage > 0 && percentage < 1) return '<1%'
  return Math.round(percentage) + '%'
}
