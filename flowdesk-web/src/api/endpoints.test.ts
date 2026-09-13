import { afterEach, describe, expect, it, vi } from "vitest";
import { apiClient } from "./client";
import { recoveryApi, ticketApi } from "./endpoints";
import { FeatureUnavailableError } from "./errors";
import { featureFlags } from "./features";

describe("ticket endpoints", () => {
  it("keeps an unavailable SLA read asynchronous", async () => {
    const promise = ticketApi.slaStatus("ticket-id");
    expect(promise).toBeInstanceOf(Promise);
    if (featureFlags.sla === "disabled") {
      await expect(promise).rejects.toBeInstanceOf(FeatureUnavailableError);
    } else {
      await promise.catch(() => undefined);
    }
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("sends the recovery route, reason, ETag and idempotency key", async () => {
    if (featureFlags.recovery !== "api") return;
    const request = vi.spyOn(apiClient, "request").mockResolvedValue({
      data: { recoveryId: "recovery-1", state: "REPLAYING", lockVersion: 2 },
      meta: { correlationId: "c-replay" },
      status: 202,
      headers: new Headers({ ETag: '"lv:2"' }),
    } as never);

    const result = await recoveryApi.replay(
      "WORKFLOW",
      "recovery-1",
      "已完成依赖核查",
      '"lv:1"',
      "replay-key-123456",
    );

    expect(result.status).toBe(202);
    expect(request).toHaveBeenCalledWith(
      "/workflow-message-recoveries/recovery-1/replays",
      {
        method: "POST",
        body: { reason: "已完成依赖核查" },
        ifMatch: '"lv:1"',
        idempotencyKey: "replay-key-123456",
      },
    );
  });

  it("uses the notification read-state route with a version condition", async () => {
    if (featureFlags.notification !== "api") return;
    const request = vi.spyOn(apiClient, "request").mockResolvedValue({
      data: { notificationId: "notification-1", state: "READ", lockVersion: 3 },
      meta: { correlationId: "c-read" },
      status: 200,
      headers: new Headers({ ETag: '"lv:3"' }),
    } as never);

    await (await import("./endpoints")).notificationApi.setReadState(
      "notification-1",
      "READ",
      '"lv:2"',
      "notification-key-1234",
    );

    expect(request).toHaveBeenCalledWith(
      "/notifications/notification-1/read-state",
      {
        method: "PUT",
        body: { state: "READ" },
        ifMatch: '"lv:2"',
        idempotencyKey: "notification-key-1234",
      },
    );
  });
});
