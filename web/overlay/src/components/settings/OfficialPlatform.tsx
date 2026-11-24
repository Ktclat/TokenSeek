import { useSettings } from '../../services/settings.js'
import { IconChevronRightOutline14, IconLinkOutline16 } from '../base/icons.js'
import styles from './AccountFlow.module.css'

export const platformUrls = {
  account: 'https://platform.deepseek.com/',
  topUp: 'https://platform.deepseek.com/top_up',
  keys: 'https://platform.deepseek.com/api_keys',
}

const labels = {
  'zh-CN': {
    officialKeys: '官方 API Key 管理', keyActions: '在 DeepSeek 开放平台创建或删除密钥',
    chatKeys: '聊天使用的 API Key', selectKey: '选用已有 API Key',
  },
  'en-US': {
    officialKeys: 'Official API key management', keyActions: 'Create or delete keys on the DeepSeek platform',
    chatKeys: 'API keys for chat', selectKey: 'Use an existing API key',
  },
}

export const usePlatformLabels = () => useSettings(state => labels[state.locale])

export const OfficialKeysLink = ({ shortcut = false, detail = false }: {
  shortcut?: boolean; detail?: boolean
}) => {
  const text = usePlatformLabels()
  return (
    <a className={shortcut ? styles.shortcut : styles.link} href={platformUrls.keys} target="_blank" rel="noopener noreferrer">
      {shortcut && <IconLinkOutline16 />}
      <span className={styles.destination}>
        {text.officialKeys}
        {detail && <small>{text.keyActions}</small>}
      </span>
      <span className={styles.disclosure} aria-hidden="true"><IconChevronRightOutline14 /></span>
    </a>
  )
}
