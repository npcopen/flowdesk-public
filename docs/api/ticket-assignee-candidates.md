# 工单处理人候选 API

`GET /api/v1/users/ticket-assignee-candidates` 由 IAM 提供，浏览器请求同源 `/api/v1`，经 Web 反向代理和 Gateway 转发。工单分派和转派页面使用它选择目标处理人。

## 授权与范围

请求必须携带有效的人类用户 Bearer Token，并具有以下任一权限：

- `TICKET_ASSIGN_TENANT`
- `TICKET_TRANSFER_TENANT`
- `TICKET_TRANSFER_ASSIGNED`

租户只取自验证后的 Token，不接受客户端指定租户。候选固定满足账号 `ACTIVE`、同租户的 `AGENT` 角色 `ACTIVE`、用户与角色绑定为 `GRANTED`，与内部 `AGENT` / `TICKET_ASSIGNEE` 资格检查采用相同定义。当前目标资格依据账号和角色绑定，不额外要求目标持有某个工单操作权限。

此接口不授予 `/users`、用户详情或用户创建等管理权限，也不授予对其他人工单的操作权限。工单提交时仍会校验操作者、工单状态、资源版本及目标的最新资格；查询到候选并不保证后续提交一定成功。

## 查询参数

| 参数 | 含义 |
| --- | --- |
| `pageSize` | 1–100，默认 20 |
| `pageToken` | 上一页响应返回的不透明游标，第一页省略 |
| `sort` | `displayName`、`-displayName`、`username`、`-username`；默认 `displayName` |

不接受 `status`、`roleCode`、`tenantId` 或其他范围覆盖参数。游标绑定租户、候选接口、排序和每页条数，跨范围复用会返回 `400 CURSOR_INVALID`。

## 响应

沿用项目统一响应结构，仅包含候选的三个身份字段，不返回状态、角色、权限、凭据或账号管理版本：

```json
{
  "data": [
    {
      "userId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
      "username": "agent",
      "displayName": "值班工程师"
    }
  ],
  "meta": {
    "correlationId": "00000000-0000-4000-8000-000000000001",
    "page": {
      "pageSize": 1,
      "nextPageToken": null,
      "sort": "displayName"
    }
  }
}
```

无有效身份返回 401；缺少上述权限返回 403；未知、重复或无效查询参数返回 400。没有符合条件的候选时，返回成功响应和空数组。
