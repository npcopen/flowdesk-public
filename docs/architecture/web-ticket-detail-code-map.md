# 前端工单详情代码阅读地图

入口是 `flowdesk-web/src/views/TicketDetailPage.vue`，页面专用实现集中在同级 `ticket-detail` 目录。路由仍指向原页面，无新增 API 或工具配置。

| 文件 | 职责 |
| --- | --- |
| `TicketDetailPage.vue` | 创建页面状态，注册首次加载，编排加载/错误状态、标题、请求摘要和各业务面板；连接子组件事件与页面操作。 |
| `ticket-detail/useTicketDetail.ts` | 保存详情与 ETag，计算当前账号的操作权限；加载评论、时间线、SLA、审批启动状态、处理人候选及告警关联，保留各区域的降级和错误范围。 |
| `ticket-detail/useTicketActions.ts` | 保存操作对话框状态、原因、处理人选择、评论草稿、评论幂等意图和异步副作用；提交分配、接单、转派、解决、关闭、重开或评论，成功后刷新详情。 |
| `ticket-detail/TicketActionPanel.vue` | 按页面传入的权限展示可执行操作，发出所选操作类型。 |
| `ticket-detail/TicketActionDialog.vue` | 展示操作表单、候选、提交状态与操作错误；通过双向模型同步表单值，通过事件请求提交、关闭或清除错误。 |
| `ticket-detail/TicketActivityPanels.vue` | 展示评论和业务时间线，编辑页面持有的评论草稿，发出评论提交或重试事件。 |
| `ticket-detail/TicketStatusPanels.vue` | 展示处理时效及历史轮次、审批进度和关联告警；保留 `aside` 根节点和直系面板的响应式布局。 |

## 状态与事件边界

- 两个 composable 只在页面的 `setup` 中创建。`ResourceState` 加载时会卸载内容插槽，评论草稿、未确定结果的 `commentIntent`、操作状态和异步副作用仍属于页面生命周期。
- 子组件负责展示与表单交互，不直接调用 API。所有 `retry` 事件回到同一个无参数 `load()`；评论提交和操作成功后也调用此入口。
- 操作对话框继续位于 `ResourceState` 之外。模态层关闭保护、必填校验、原因去除首尾空白、候选默认选择和按钮禁用条件保持原有规则。
- `useTicketDetail` 与 `useTicketActions` 共用原来的 `actionError` 引用；加载前清除旧操作错误，局部 SLA、流程和候选错误仍按既有分支进入该提示。评论、时间线及告警关联错误保留各自面板范围。

## 必须保留的行为

- 详情响应先克隆再进入响应式状态。写入使用响应中的 ETag，缺失时使用 `"lv:<lockVersion>"`；接口请求体和刷新时机不变。
- 转派和其他操作保持原有权限、角色、所属关系、工单来源及状态限制。处理人候选仅在可分配或可转派时读取，继续通过 `allPages` 拉取完整候选列表。
- 评论请求使用去除首尾空白后的正文。相同工单与正文在结果不确定时重用同一幂等键，读取刷新不清除该意图；只有评论写入成功后重置意图并清空草稿。
- 评论、时间线、SLA 和审批状态继续使用 `Promise.allSettled`，局部失败不覆盖工单主体。`SLA_NOT_STARTED`、功能不可用和不可见资源等原有分类保持不变。
- 告警关联优先使用时间线中的不可变引用；仅旧记录缺少引用时继续使用既有摘要回退。不把关联查询错误转成其他业务入口。
- 子组件保留原 DOM 元素、文案、控件 ID 和可访问名称。样式随对应组件迁移，保持 `scoped`、原选择器与 900/620 像素断点，尤其保留 `.detail-side > .panel:last-child` 的结构。

## 回归入口

- `src/views/TicketTransfer.test.ts`：普通处理人转派、后续页候选、权限控制和 ETag。
- `src/views/TicketDetailPage.test.ts`：评论失败后的同意图重试、成功后新意图、草稿跨刷新保留、局部读取降级和告警时间线关联。
- `src/views/ListRefresh.test.ts`：列表刷新事件不作为分页 token；该页面拆分不改变列表入口。
- 继续执行前端单元测试、TypeScript 检查和构建；拆分不调整 package 或工具配置。
