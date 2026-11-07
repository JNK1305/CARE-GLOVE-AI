import { describe, expect, it, vi, beforeEach } from "vitest";
import { BackendSensorAdapter, monitoringSourceLabel } from "./monitoring";
import { api } from "./api";
import type { Patient } from "./domain";

describe("monitoringSourceLabel", () => {
  it("formats source names accurately", () => {
    expect(monitoringSourceLabel("FIREBASE_RTDB_LIVE")).toBe("Firebase RTDB (Live)");
    expect(monitoringSourceLabel("FIREBASE_RTDB_HISTORY")).toBe("Firebase RTDB (History)");
    expect(monitoringSourceLabel("SIMULATED")).toBe("SIMULATED");
    expect(monitoringSourceLabel("HARDWARE")).toBe("Direct Hardware");
  });
});

describe("BackendSensorAdapter", () => {
  const testPatient: Patient = {
    id: "CG-B2CC11AC158A",
    name: "Sneha Kulkarni",
    age: "58 yrs",
    ward: "ICU",
    bed: "B-01",
    bedId: "ICU:B-01",
    glove: "GLV-ICU-001",
    doctor: "Dr. Ananya Roy",
    doctorId: "roy",
    status: "Critical",
    bpm: 72,
    temp: 36.5,
    gsr: 2.0,
    authorized: true,
  };

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("maps genuine backend monitoring status with high precision without truncation", async () => {
    const mockStatus = {
      patientId: "CG-B2CC11AC158A",
      deviceId: "GLV-ICU-001",
      monitoringStatus: "STALE",
      healthState: "CRITICAL",
      severity: "CRITICAL",
      latestReading: {
        id: "rec-123",
        patientId: "CG-B2CC11AC158A",
        deviceId: "GLV-ICU-001",
        timestamp: "2026-10-22T08:11:15.274Z",
        heartRate: 48.0,
        temperature: 35.8125,
        gsr: 0.47887,
        source: "FIREBASE_RTDB_LIVE",
        receivedAt: "2026-10-22T08:11:15.274Z",
        state: "PROCESSED",
      },
      evaluatedAt: "2026-10-22T08:11:20.000Z",
    };

    const mockVitals = [
      {
        id: "rec-123",
        patientId: "CG-B2CC11AC158A",
        deviceId: "GLV-ICU-001",
        timestamp: "2026-10-22T08:11:15.274Z",
        heartRate: 48.0,
        temperature: 35.8125,
        gsr: 0.47887,
        source: "FIREBASE_RTDB_LIVE",
      },
      {
        id: "rec-122",
        patientId: "CG-B2CC11AC158A",
        deviceId: "GLV-ICU-001",
        timestamp: "2026-10-21T17:55:00.916Z",
        heartRate: 96.0,
        temperature: 35.75,
        gsr: 0.1309,
        source: "FIREBASE_RTDB_LIVE",
      },
    ];

    vi.spyOn(api, "getMonitoringStatus").mockResolvedValue(mockStatus as any);
    vi.spyOn(api, "getVitals").mockResolvedValue(mockVitals as any);

    const adapter = new BackendSensorAdapter([testPatient]);
    await adapter.poll();

    const reading = adapter.getLatestReading("CG-B2CC11AC158A");
    expect(reading).toBeDefined();
    expect(reading?.bpm).toBe(48.0);
    expect(reading?.temperature).toBe(35.8125); // Unrounded exact float
    expect(reading?.gsr).toBe(0.47887);
    expect(reading?.source).toBe("FIREBASE_RTDB_LIVE");
    expect(adapter.getDeviceStatus("CG-B2CC11AC158A")).toBe("STALE");

    const history = adapter.getHistory("CG-B2CC11AC158A");
    expect(history.length).toBeGreaterThan(0);
    const tempReading = history.find((h) => h.parameter === "Temperature" && h.value === 35.75);
    expect(tempReading).toBeDefined();
    expect(tempReading?.source).toBe("FIREBASE_RTDB_LIVE");

    const gsrReading = history.find((h) => h.parameter === "GSR" && h.value === 0.1309);
    expect(gsrReading).toBeDefined();
  });

  it("does not fabricate mock data when backend returns no reading", async () => {
    const emptyPatient: Patient = {
      ...testPatient,
      id: "CG-EMPTY",
      glove: "GLV-EMPTY",
    };

    vi.spyOn(api, "getMonitoringStatus").mockResolvedValue({
      patientId: "CG-EMPTY",
      deviceId: null,
      monitoringStatus: "DISCONNECTED",
      latestReading: null,
    } as any);
    vi.spyOn(api, "getVitals").mockResolvedValue([]);

    const adapter = new BackendSensorAdapter([emptyPatient]);
    await adapter.poll();

    expect(adapter.getLatestReading("CG-EMPTY")).toBeUndefined();
    expect(adapter.getHistory("CG-EMPTY")).toEqual([]);
    expect(adapter.getDeviceStatus("CG-EMPTY")).toBe("DISCONNECTED");
  });

  it("halts polling if backend returns an authentication failure", async () => {
    vi.spyOn(api, "getMonitoringStatus").mockRejectedValue(
      new Error("HTTP 401: Invalid or expired bearer token.")
    );

    const adapter = new BackendSensorAdapter([testPatient]);
    const stopSpy = vi.spyOn(adapter, "stop");
    await adapter.poll();

    expect(stopSpy).toHaveBeenCalled();
  });
});

describe("JWT session protection", () => {
  it("preserves genuine JWT when authService.login is invoked", async () => {
    const { authService } = await import("./services");
    const validJwt = "header.payload.signature";
    authService.saveSession({
      id: "admin-1",
      name: "Admin",
      role: "admin",
      token: validJwt,
    });

    const res = authService.login("admin");
    expect(res.token).toBe(validJwt);
  });
});

