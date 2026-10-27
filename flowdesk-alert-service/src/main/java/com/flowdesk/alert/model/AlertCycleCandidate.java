package com.flowdesk.alert.model;
import java.util.*;
import java.time.Instant;

/**
 * 待协调告警周期的候选快照，携带租户、故障指纹和恢复时间；后台协调器据此重新读取持久化事实。
 */
public record AlertCycleCandidate(UUID tenant, String fingerprint, Instant recoveredAt) {}
