package com.flowdesk.iam.service;

import java.util.List;

/**
 * 基于排序键的分页结果，携带不可由调用方自行构造的下一页令牌。
 */
public record PageResult<T>(List<T> items, String nextPageToken, String sort) {
}
