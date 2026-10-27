/** 沿服务端游标读取完整列表，供需要全量候选项的选择器使用。 */
import type { ListResult } from '@/types/api'
/**
 * 沿下一页游标连续读取分页集合并合并全部条目。
 * 记录已经使用的游标，检测到循环时抛错，避免接口异常导致无限查询。
 */
export async function allPages<T>(fetchPage: (token?: string) => Promise<ListResult<T>>): Promise<T[]> {
  const items: T[] = []
  let token: string | undefined
  const seen = new Set<string>()
  do {
    const page = await fetchPage(token)
    items.push(...page.items)
    token = page.meta.page?.nextPageToken || undefined
    // 检测服务端重复游标，避免异常分页让选择器一直请求。
    if (token && seen.has(token)) throw new Error('分页未能继续，请刷新重试。')
    if (token) seen.add(token)
  } while (token)
  return items
}
