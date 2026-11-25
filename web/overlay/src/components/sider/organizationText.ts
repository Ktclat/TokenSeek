import { useSettings } from '../../services/settings.js'

const labels = {
  'zh-CN': {
    projects: '项目', recent: '最近对话', pinned: '置顶', pin: '置顶对话', unpin: '取消置顶',
    folderName: '项目名称', create: '新建项目', rename: '重命名项目', remove: '删除项目',
    keep: '项目中的对话会保留。', move: '移入项目', moveOut: '移出项目',
    createAndMove: '新建项目并移入', empty: '暂无对话', save: '保存', exportChat: '导出会话 ZIP',
    error: '操作未完成，请重试。', loadError: '项目与置顶未能载入', account: '账户',
    noBalance: '余额未连接', balanceUnknown: '余额暂不可用', loadingBalance: '正在查询余额…', balance: '余额',
  },
  'en-US': {
    projects: 'Projects', recent: 'Recent chats', pinned: 'Pinned', pin: 'Pin chat', unpin: 'Unpin chat',
    folderName: 'Project name', create: 'New project', rename: 'Rename project', remove: 'Delete project',
    keep: 'Conversations in this project will be kept.', move: 'Move to project', moveOut: 'Remove from project',
    createAndMove: 'Create project and move', empty: 'No chats yet', save: 'Save', exportChat: 'Export chat ZIP',
    error: 'Couldn’t finish. Try again.', loadError: 'Couldn’t load projects and pins', account: 'Account',
    noBalance: 'Balance not connected', balanceUnknown: 'Balance unavailable', loadingBalance: 'Checking balance…', balance: 'Balance',
  },
}
export const useOrganizationText = () => useSettings(state => labels[state.locale])
