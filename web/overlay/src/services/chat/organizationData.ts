export type Project = { id: string; name: string }
export type ChatOrganization = {
  projects: Project[]
  assignments: Record<string, string>
  pinned: string[]
}

export const maxProjectName = 80
const record = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value)
const nonempty = (value: unknown): value is string => typeof value === 'string' && value.length > 0

/** Older archives have no organization. Ignore references to sessions omitted from a partial export. */
export function parseOrganization(raw: unknown, sessionIds?: ReadonlySet<string>): ChatOrganization {
  const assignments: Record<string, string> = Object.create(null)
  if (raw === undefined) return { projects: [], assignments, pinned: [] }
  if (!record(raw) || !Array.isArray(raw.projects) || !record(raw.assignments) || !Array.isArray(raw.pinned)) {
    throw new TypeError('Invalid chat organization')
  }
  const ids = new Set<string>()
  const projects = raw.projects.map((p: unknown) => {
    if (!record(p) || !nonempty(p.id) || !nonempty(p.name) || !p.name.trim() || p.name.length > maxProjectName || ids.has(p.id)) {
      throw new TypeError('Invalid project')
    }
    ids.add(p.id)
    return { id: p.id, name: p.name.trim() }
  })
  for (const [session, project] of Object.entries(raw.assignments)) {
    if (!session || !nonempty(project) || !ids.has(project)) throw new TypeError('Invalid project assignment')
    if (!sessionIds || sessionIds.has(session)) assignments[session] = project
  }
  const pinned = new Set<string>()
  for (const id of raw.pinned) {
    if (!nonempty(id)) throw new TypeError('Invalid pinned conversation')
    if (!sessionIds || sessionIds.has(id)) pinned.add(id)
  }
  return { projects, assignments, pinned: [...pinned] }
}

export function withoutSession(data: ChatOrganization, id: string): ChatOrganization {
  const assignments = Object.fromEntries(Object.entries(data.assignments).filter(([session]) => session !== id))
  return { ...data, assignments, pinned: data.pinned.filter(session => session !== id) }
}
