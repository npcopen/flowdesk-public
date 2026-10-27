import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({
  workflow: Object.fromEntries(['tasks', 'instances', 'definitions', 'currentDraft', 'versions', 'patchDraft', 'validateDraft', 'publishDraft'].map(k => [k, vi.fn()])),
  users: vi.fn(),
}))
vi.mock('@/api/endpoints', () => ({ workflowApi: api.workflow, iamApi: { users: api.users } }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ can: () => true, canAny: () => true, hasRole: () => true }) }))
vi.mock('vue-router', () => ({ RouterLink: { template: '<a><slot /></a>', props: ['to'] } }))
import ApprovalsPage from './ApprovalsPage.vue'

/**
 * 构造单个工作流资源的 API 测试结果。
 * 提供响应头及元数据，供草稿管理页面按真实结果结构读取。
 */
const one = (data: unknown) => ({ data, headers: new Headers(), meta: {} })
/**
 * 构造工作流集合的分页测试结果。
 * 默认没有下一页，便于控制定义、版本及审批人候选的测试数据。
 */
const list = (items: unknown[] = []) => ({ items, meta: { page: { nextPageToken: null } } })
const draft = { draftId: 'draft-1', definitionId: 'definition-1', status: 'EDITABLE', validationState: 'VALID', lockVersion: 1, condition: { fieldCode: 'SERVICE_RISK_LEVEL', operator: 'EQUALS', valueType: 'STRING', value: 'HIGH' }, level1ApproverUserId: 'approver-a', level2ApproverUserId: 'approver-b' }

beforeEach(() => {
  vi.resetAllMocks()
  api.workflow.tasks.mockResolvedValue(list())
  api.workflow.instances.mockResolvedValue(list())
  api.workflow.definitions.mockResolvedValue(list([{ definitionId: 'definition-1', definitionName: '权限审批' }]))
  api.workflow.currentDraft.mockResolvedValue(one(draft))
  api.workflow.versions.mockResolvedValue(list())
  api.workflow.patchDraft.mockResolvedValue(one({ ...draft, level1ApproverUserId: 'approver-c', validationState: 'UNVALIDATED', lockVersion: 2 }))
  api.workflow.validateDraft.mockResolvedValue(one({ valid: true, validationDigest: 'new-digest', lockVersion: 3 }))
  api.workflow.publishDraft.mockResolvedValue(one({ workflowVersionId: 'version-new' }))
  api.users.mockResolvedValue(list(['a', 'b', 'c'].map(x => ({ userId: `approver-${x}`, displayName: `审批人${x}` }))))
})

it('requires saving and validating changed approvers before publishing that version', async () => {
  const wrapper = mount(ApprovalsPage)
  await flushPromises()
  /**
   * 按可见文本在组件按钮集合中定位目标按钮。
   * 测试用此入口触发草稿保存、校验和发布等用户操作。
   */
  const button = (label: string) => wrapper.findAll('button').find(b => b.text().includes(label))!
  expect(button('发布规则').attributes('disabled')).toBeUndefined()
  await wrapper.get('#level-one').setValue('approver-c')
  expect(button('发布规则').attributes('disabled')).toBeDefined()
  expect(button('校验规则').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('有未保存的修改')
  await button('发布规则').trigger('click')
  expect(api.workflow.publishDraft).not.toHaveBeenCalled()
  await button('保存草稿').trigger('click')
  await flushPromises()
  expect(api.workflow.patchDraft).toHaveBeenCalledWith('draft-1', expect.objectContaining({ level1ApproverUserId: 'approver-c' }), '"lv:1"')
  expect(button('发布规则').attributes('disabled')).toBeDefined()
  await button('校验规则').trigger('click')
  await flushPromises()
  expect(api.workflow.validateDraft).toHaveBeenCalledWith('draft-1', '"lv:2"')
  expect(button('发布规则').attributes('disabled')).toBeUndefined()
  await button('发布规则').trigger('click')
  await flushPromises()
  expect(api.workflow.publishDraft).toHaveBeenCalledWith('draft-1', '"lv:3"')
  wrapper.unmount()
})
