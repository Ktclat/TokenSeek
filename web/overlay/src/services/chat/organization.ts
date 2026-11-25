import { create } from 'zustand'
import { createId } from '../../utils/id.js'
import { editOrganization, loadOrganization } from './db.js'
import { parseOrganization, type ChatOrganization } from './organizationData.js'

const empty = () => parseOrganization(undefined)
export const useOrganization = create(() => ({ data: empty(), status: 'loading' as 'loading' | 'ready' | 'error' }))
let revision = 0
export async function refreshOrganization() {
  const version = ++revision
  try {
    const data = await loadOrganization()
    if (version === revision) useOrganization.setState({ data: data ?? empty(), status: 'ready' })
  } catch {
    if (version === revision) useOrganization.setState({ status: 'error' })
  }
}

async function update(transform: (data: ChatOrganization) => ChatOrganization) {
  await editOrganization(current => transform(current ?? empty()))
  await refreshOrganization()
}

export const addProject = async (name: string, sessionId?: string) => {
  const id = createId()
  await update(data => ({ ...data,
    projects: [...data.projects, { id, name }],
    assignments: sessionId ? { ...data.assignments, [sessionId]: id } : data.assignments,
  }))
  return id
}
export const renameProject = (id: string, name: string) => update(data => ({ ...data,
  projects: data.projects.map(project => project.id === id ? { ...project, name } : project),
}))
export const removeProject = (id: string) => update(data => ({ ...data,
  projects: data.projects.filter(project => project.id !== id),
  assignments: Object.fromEntries(Object.entries(data.assignments).filter(([, project]) => project !== id)),
}))
export const moveSession = (sessionId: string, projectId: string | null) => update(data => {
  const assignments: Record<string, string> = Object.assign(Object.create(null), data.assignments)
  if (projectId === null) delete assignments[sessionId]
  else {
    if (!data.projects.some(project => project.id === projectId)) throw new Error('Project no longer exists')
    assignments[sessionId] = projectId
  }
  return { ...data, assignments }
})
export const setPinned = (id: string, enabled: boolean) => update(data => ({ ...data,
  pinned: [...(enabled ? [id] : []), ...data.pinned.filter(value => value !== id)],
}))
