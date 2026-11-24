import type { ChatModel } from '../api/types.js'
import type { Message } from './types.js'

export const FLASH: ChatModel = 'deepseek-flash'
export const PRO: ChatModel = 'deepseek-v4-pro'

export const isChatModel = (value: unknown): value is ChatModel => value === FLASH || value === PRO

/** Keep attachments and tool results on the model that can read the original context. */
export const requiresFlashContext = (messages: Message[]) =>
  messages.some((message) =>
    message.role === 'user'
      ? message.files.length > 0
      : message.blocks.some((block) => block.type !== 'text' && block.type !== 'thinking')
  )
