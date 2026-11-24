import { useSettings } from '../../services/settings.js'

const copy = {
  'zh-CN': {
    back: '返回', account: '账户', application: '应用', keys: 'API Key', unbound: '未绑定',
    keyHelp: '在 DeepSeek 开放平台创建 API Key，再将密钥粘贴到本机。', removeHelp: '移除后，需重新绑定才能在本机聊天和查看余额。官方密钥仍可在开放平台管理。',
    unset: '未设置', locationHelp: '可选。提供位置后，模型可以在回答中参考你所在的地区。',
  },
  'en-US': {
    back: 'Back', account: 'Account', application: 'App', keys: 'API Key', unbound: 'Not connected',
    keyHelp: 'Create an API key on the DeepSeek platform, then paste it here.', removeHelp: 'Reconnect a key to chat and view your balance on this device. You can still manage the key on the DeepSeek platform.',
    unset: 'Not set', locationHelp: 'Optional. Set a location to give the model regional context for its answers.',
  },
}

export const usePageText = () => useSettings(state => copy[state.locale])
