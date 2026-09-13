import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import StatusBadge from './StatusBadge.vue'

describe('StatusBadge', () => {
  it('renders stable status copy and tone', () => {
    const wrapper = mount(StatusBadge, { props: { value: 'RECOVERY_REQUIRED' } })
    expect(wrapper.text()).toContain('等待恢复')
    expect(wrapper.classes()).toContain('status-badge--warning')
  })
})
