import { useSettings } from '../../services/settings.js'
import { IconChevronRightOutline14 } from '../base/icons.js'
import styles from './AccountFlow.module.css'

export const platformUrls = {
  account: 'https://platform.deepseek.com/',
  login: 'https://platform.deepseek.com/sign_in',
  topUp: 'https://platform.deepseek.com/top_up',
  keys: 'https://platform.deepseek.com/api_keys',
}

const labels = {
  'zh-CN': { keys: 'API 密钥', officialKeys: '创建或管理官方密钥', topUp: '充值' },
  'en-US': { keys: 'API keys', officialKeys: 'Create or manage keys on DeepSeek', topUp: 'Top up' },
}

export const usePlatformLabels = () => useSettings(state => labels[state.locale])

export const OfficialKeysLink = () => {
  const text = usePlatformLabels()
  return (
    <a className={styles.link} href={platformUrls.keys} target="_blank" rel="noopener noreferrer">
      {text.officialKeys}<IconChevronRightOutline14 />
    </a>
  )
}
