package com.flowdesk.workflow.service;

import java.util.List;

/**
 * 工作流列表的分页结果，携带下一页游标和实际采用的排序规则。
 */
public record WorkflowPage<T>(List<T> items, String nextPageToken, String sort) {
    /**
     * 防御性复制分页条目，避免调用方修改原集合后改变已经返回的结果快照。
     */
    public WorkflowPage {
        items = List.copyOf(items);
    }
}
