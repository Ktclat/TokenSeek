import { useTranslate } from '../../i18n/index.js'
import { newChat, useChatStore } from '../../services/chat/store.js'
import { IconButton } from '../base/IconButton.js'
import { IconNewChatOutline20 } from '../base/icons.js'

export const NewChatButton = ({ className }: { className?: string }) => {
  const t = useTranslate()
  const importing = useChatStore((state) => state.importingHistory)
  const start = () => {
    if (useChatStore.getState().activeId === null) {
      // The welcome screen already owns the unsent draft.
      document.getElementById('tokenseek-composer')?.focus({ preventScroll: true })
      return
    }
    newChat()
  }
  return (
    <IconButton
      className={className}
      size="xl"
      tone="primary"
      icon={<IconNewChatOutline20 />}
      aria-label={t('newChat')}
      disabled={importing}
      onClick={start}
    />
  )
}
