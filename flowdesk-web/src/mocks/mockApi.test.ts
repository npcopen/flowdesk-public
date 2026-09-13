import { beforeEach, describe, expect, it } from "vitest";
import { mockApi } from "./mockApi";

describe("mock alert bridge", () => {
  beforeEach(async () => {
    mockApi.reset();
    await mockApi.request("/api/v1/auth/login", {
      method: "POST",
      body: {
        tenantCode: "local-tenant",
        username: "admin",
        password: "local-password",
      },
    });
  });

  it("keeps the incident reference in an alert-created ticket timeline", async () => {
    mockApi.triggerAlert("FIRING");

    const incidents = await mockApi.request<any[]>("/api/v1/incidents", {
      method: "GET",
    });
    const incident = incidents.data[0];
    expect(incident?.incidentId).toBeTruthy();

    const tickets = await mockApi.request<any[]>("/api/v1/tickets", {
      method: "GET",
    });
    const ticket = tickets.data.find((item) => item.originType === "INCIDENT");
    expect(ticket?.ticketId).toBeTruthy();
    if (!ticket) return;

    const timeline = await mockApi.request<any[]>(
      `/api/v1/tickets/${ticket.ticketId}/timeline`,
      { method: "GET" },
    );
    expect(timeline.data).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          eventType: "INCIDENT_CREATED",
          relatedRefType: "INCIDENT",
          relatedRefId: incident.incidentId,
        }),
      ]),
    );
  });
});
