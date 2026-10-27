package com.flowdesk.iam.model;

import java.util.UUID;

/**
 * 批量查询得到的资源标识与编码对，用于把角色或权限绑定转换为可排序的业务视图。
 */
public record IdCodeRow(UUID id, String code) {
}
