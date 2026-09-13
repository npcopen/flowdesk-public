package com.flowdesk.workflow.service;

import java.util.List;

public record WorkflowPage<T>(List<T> items, String nextPageToken, String sort) {
    public WorkflowPage {
        items = List.copyOf(items);
    }
}
