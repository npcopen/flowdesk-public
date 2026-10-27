package com.flowdesk.alert.model;
import java.util.*;
import java.time.Instant;

/**
 * 核对已有发件箱恢复记录的最小快照，包含原业务意图和当前状态，供唯一键竞争后的幂等判断。
 */
public record AlertOutboxRecovery(byte[] intent, String state) {}
