package com.flowdesk.ticket.service;

import java.util.List;

/** 基于排序键的分页结果，下一页令牌由服务端编码，客户端只负责原样传回。 */
public record PageResult<T>(List<T> items, String nextPageToken, String sort) {
}
