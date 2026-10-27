package com.flowdesk.iam.model;

/**
 * 资格检查所需的最小事实快照，汇集用户状态、角色状态、绑定状态及用于追踪本次判断的资格版本。
 */
public record EligibilityFacts(String userStatus, String roleStatus, String assignmentState, long qualificationVersion) {
}
