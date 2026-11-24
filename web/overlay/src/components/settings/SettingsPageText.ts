import { useSettings } from '../../services/settings.js'

const copy = {
  'zh-CN': {
    back: '返回', account: '账户', application: '应用', unbound: '未绑定',
    unset: '未设置', locationHelp: '可选。提供位置后，模型可以在回答中参考你所在的地区。',
  },
  'en-US': {
    back: 'Back', account: 'Account', application: 'App', unbound: 'Not connected',
    unset: 'Not set', locationHelp: 'Optional. Set a location to give the model regional context for its answers.',
  },
}

export const usePageText = () => useSettings(state => copy[state.locale])
