import { describe, expect, it } from "vitest";
import { describeVitalRangeStatus, vitalRangeLabel } from "./clinical";
import { authService, canAccessPatient } from "./services";
import type { Patient } from "./domain";

describe("clinical range logic", () => {
  it("marks normal values within range", () => {
    expect(
      describeVitalRangeStatus(72, { min: 60, max: 100 }),
    ).toBe("WITHIN_RANGE");
    expect(vitalRangeLabel("WITHIN_RANGE")).toBe("Within expected range");
  });

  it("marks below and above range as expected", () => {
    expect(describeVitalRangeStatus(55, { min: 60, max: 100 })).toBe(
      "BELOW_RANGE",
    );
    expect(describeVitalRangeStatus(105, { min: 60, max: 100 })).toBe(
      "ABOVE_RANGE",
    );
  });

  it("marks critical values and invalid values safely", () => {
    expect(describeVitalRangeStatus(20, { min: 60, max: 100, criticalMin: 40 })).toBe(
      "CRITICAL",
    );
    expect(describeVitalRangeStatus(undefined, { min: 60, max: 100 })).toBe(
      "UNKNOWN",
    );
    expect(vitalRangeLabel("CRITICAL")).toBe("Critical");
  });
});

describe("demo auth boundary", () => {
  it("accepts valid demo credentials and rejects invalid configuration", () => {
    expect(() => authService.authenticate("admin", "admin@careglove.demo", "demo")).not.toThrow();
    expect(() => authService.authenticate("admin", "admin@careglove.demo", "")).toThrow(
      "Enter a demo password",
    );
    expect(() => authService.authenticate("admin", "wrong@careglove.demo", "demo")).toThrow(
      "Those credentials do not match the selected workspace.",
    );
  });

  it("restricts senior doctor access to assigned patients", () => {
    const patient: Patient = {
      id: "CG-5001",
      name: "Patient Example",
      age: "42 yrs",
      ward: "ICU",
      bed: "B-04",
      bedId: "ICU:B-04",
      glove: "GLV-ICU-004",
      doctor: "Dr. Amit Sharma",
      doctorId: "sharma",
      status: "Stable",
      bpm: 72,
      temp: 36.9,
      gsr: 3.2,
      authorized: true,
    };

    expect(canAccessPatient("senior", patient, "sharma")).toBe(true);
    expect(canAccessPatient("senior", patient, "patil")).toBe(false);
  });
});

describe("hardware integration and alerts", () => {
  it("migrates legacy CG-1043 patient seed to hardware patient CG-B2CC11AC158A", async () => {
    const { loadPatients } = await import("./services");
    const seed: Patient[] = [
      {
        id: "CG-1043",
        name: "Sneha Kulkarni",
        age: "47 yrs",
        ward: "ICU",
        bed: "B-01",
        bedId: "ICU:B-01",
        glove: "GLV-ICU-001",
        doctor: "Dr. Amit Sharma",
        doctorId: "sharma",
        status: "Watch",
        bpm: 88,
        temp: 37.1,
        gsr: 3.1,
        authorized: true,
      },
    ];
    const patients = loadPatients(seed);
    const p = patients.find((entry) => entry.glove === "GLV-ICU-001");
    expect(p).toBeDefined();
    expect(p?.id).toBe("CG-B2CC11AC158A");
    expect(p?.ward).toBe("ICU");
    expect(p?.bed).toBe("B-01");
  });

  it("syncs and retains multivariate and acknowledged backend alerts without filtering", async () => {
    const { alertService } = await import("./services");
    const backendAlerts = [
      {
        id: "alert-multi-1",
        patientId: "CG-B2CC11AC158A",
        gloveId: "GLV-ICU-001",
        parameter: "MULTIVARIATE",
        value: 117.0,
        threshold: null,
        severity: "CRITICAL" as const,
        timestamp: new Date().toISOString(),
        source: "HARDWARE",
        status: "ACKNOWLEDGED" as const,
      },
      {
        id: "alert-hr-1",
        patientId: "CG-B2CC11AC158A",
        gloveId: "GLV-ICU-001",
        parameter: "heart_rate",
        value: 155.0,
        threshold: 140.0,
        severity: "CRITICAL" as const,
        timestamp: new Date().toISOString(),
        source: "HARDWARE",
        status: "OPEN" as const,
      },
    ];

    alertService.syncBackendAlerts(backendAlerts as any);
    const list = alertService.list();
    expect(list.some((a) => a.id === "alert-multi-1" && a.parameter === "MULTIVARIATE" && a.status === "ACKNOWLEDGED")).toBe(true);
    expect(list.some((a) => a.id === "alert-hr-1" && a.parameter === "heart_rate")).toBe(true);
  });
});

