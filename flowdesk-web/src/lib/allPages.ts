import type { ListResult } from '@/types/api'
export async function allPages<T>(fetchPage: (token?: string) => Promise<ListResult<T>>): Promise<T[]> {
  const items: T[] = []
  let token: string | undefined
  const seen = new Set<string>()
  do {
    const page = await fetchPage(token)
    items.push(...page.items)
    token = page.meta.page?.nextPageToken || undefined
    if (token && seen.has(token)) throw new Error('分页未能继续，请刷新重试。')
    if (token) seen.add(token)
  } while (token)
  return items
}
