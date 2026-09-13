import { describe, expect, it } from "vitest";
import {
  ApiClientError,
  isCapabilityUnavailable,
  isOpaqueResourceNotFound,
} from "./errors";

function apiError(status: number, code: string): ApiClientError {
  return new ApiClientError({ status, code, correlationId: "test-correlation" });
}

describe("optional capability error boundaries", () => {
  it("does not hide authorization failures as an outage", () => {
    const forbidden = apiError(403, "ACCESS_DENIED");
    expect(isCapabilityUnavailable(forbidden, "notification")).toBe(false);
    expect(isCapabilityUnavailable(forbidden, "recovery")).toBe(false);
    expect(isCapabilityUnavailable(forbidden, "sla")).toBe(false);
  });

  it("only treats an opaque collection 404 as an unavailable notification route", () => {
    const missing = apiError(404, "RESOURCE_NOT_FOUND");
    expect(isCapabilityUnavailable(missing, "notification")).toBe(true);
    expect(isCapabilityUnavailable(missing, "notification", "mutation")).toBe(false);
    expect(isOpaqueResourceNotFound(missing)).toBe(true);
    expect(isOpaqueResourceNotFound(apiError(404, "NOTIFICATION_RECIPIENT_ONLY"))).toBe(false);
  });

  it("keeps SLA_NOT_STARTED as a valid business state", () => {
    expect(isCapabilityUnavailable(apiError(409, "SLA_NOT_STARTED"), "sla")).toBe(false);
    expect(isCapabilityUnavailable(apiError(404, "RESOURCE_NOT_FOUND"), "sla")).toBe(true);
  });

  it("classifies service failures consistently across optional surfaces", () => {
    const unavailable = apiError(503, "DEPENDENCY_UNAVAILABLE");
    expect(isCapabilityUnavailable(unavailable, "notification")).toBe(true);
    expect(isCapabilityUnavailable(unavailable, "recovery")).toBe(true);
    expect(isCapabilityUnavailable(unavailable, "sla")).toBe(true);
  });
});
