import type {
  MonitoringParameter,
  MonitoringReading,
  MonitoringSample,
  Patient,
} from "./domain";
import { api } from "./api";

export type { MonitoringParameter, MonitoringReading, MonitoringSample };

export type MonitoringSnapshot = {
  latestReadings: Record<string, MonitoringSample>;
  history: Record<string, MonitoringReading[]>;
  deviceStatus: Record<string, DeviceStatus>;
  monitoringStatuses?: Record<string, import("./api").BackendMonitoringStatus>;
  source: "DEMO_SENSOR" | "HARDWARE" | "FIREBASE_RTDB_LIVE" | string;
};

export type MonitoringDataSource = {
  start: () => void;
  stop: () => void;
  subscribe: (listener: (readings: MonitoringReading[]) => void) => () => void;
  getLatestReading: (patientId: string) => MonitoringSample | undefined;
  getHistory: (patientId: string) => MonitoringReading[];
};

export type DeviceStatus =
  | "AVAILABLE"
  | "ASSIGNED"
  | "DEMO ACTIVE"
  | "LIVE / CONNECTED"
  | "STALE"
  | "INSUFFICIENT CONTACT"
  | "MODEL ERROR"
  | "DISCONNECTED"
  | "NO DATA";

const HISTORY_STORAGE_KEY = "care-glove-monitoring-history";
const MAX_PERSISTED_READINGS = 480;
const source = "DEMO_SENSOR" as const;
const parameters: Array<{
  parameter: MonitoringParameter;
  key: "bpm" | "temperature" | "gsr";
}> = [
  { parameter: "BPM", key: "bpm" },
  { parameter: "Temperature", key: "temperature" },
  { parameter: "GSR", key: "gsr" },
];

function readStoredHistory(patientId: string, gloveId: string): MonitoringReading[] {
  if (typeof window === "undefined") return [];
  const raw = window.localStorage.getItem(HISTORY_STORAGE_KEY);
  if (!raw) return [];
  try {
    const stored = JSON.parse(raw) as Record<string, MonitoringReading[]>;
    const readings = Array.isArray(stored[patientId]) ? stored[patientId] : [];
    return readings
      .filter((reading) =>
        reading?.patientId === patientId &&
        reading?.gloveId === gloveId &&
        typeof reading.id === "string" &&
        typeof reading.timestamp === "string" &&
        typeof reading.value === "number" &&
        parameters.some((item) => item.parameter === reading.parameter),
      )
      .slice(-MAX_PERSISTED_READINGS);
  } catch (error) {
    console.error("Unable to read persisted monitoring history.", error);
    throw new Error("Stored monitoring history is invalid.", { cause: error });
  }
}

function persistHistory(history: Map<string, MonitoringReading[]>) {
  if (typeof window === "undefined") return;
  const serialized = Object.fromEntries(
    [...history.entries()].map(([patientId, readings]) => [
      patientId,
      readings.slice(-MAX_PERSISTED_READINGS),
    ]),
  );
  try {
    window.localStorage.setItem(HISTORY_STORAGE_KEY, JSON.stringify(serialized));
  } catch (error) {
    console.error("Unable to persist monitoring history.", error);
    throw new Error("Monitoring history could not be saved.", { cause: error });
  }
}

export class DemoSensorAdapter implements MonitoringDataSource {
  private readonly patients: Patient[];
  private readonly listeners = new Set<(readings: MonitoringReading[]) => void>();
  private readonly latest = new Map<string, MonitoringSample>();
  private readonly history = new Map<string, MonitoringReading[]>();
  private timer: ReturnType<typeof setInterval> | null = null;
  private tick = 0;

  constructor(patients: Patient[]) {
    this.patients = patients.filter(
      (patient) => patient.glove && patient.lifecycle !== "DISCHARGED",
    );
    this.patients.forEach((patient) => {
      const stored = readStoredHistory(patient.id, patient.glove);
      const lastSample = this.sampleFromHistory(patient, stored);
      const sample = lastSample ?? this.createSample(patient, 0);
      this.latest.set(patient.id, sample);
      this.history.set(patient.id, stored);
    });
  }

  private sampleFromHistory(patient: Patient, history: MonitoringReading[]) {
    const timestamps = [...new Set(history.map((reading) => reading.timestamp))].sort();
    const timestamp = timestamps.at(-1);
    if (!timestamp) return undefined;
    const entries = history.filter((reading) => reading.timestamp === timestamp);
    const value = (parameter: MonitoringParameter, fallback: number) =>
      entries.find((reading) => reading.parameter === parameter)?.value ?? fallback;
    return {
      id: `SMP-${patient.id}-${timestamp}`,
      patientId: patient.id,
      gloveId: patient.glove,
      timestamp,
      bpm: value("BPM", patient.bpm),
      temperature: value("Temperature", patient.temp),
      gsr: value("GSR", patient.gsr),
    };
  }

  private createSample(patient: Patient, offset: number): MonitoringSample {
    const phase = this.tick + offset;
    return {
      id: `SMP-${patient.id}-${Date.now()}`,
      patientId: patient.id,
      gloveId: patient.glove,
      timestamp: new Date().toISOString(),
      bpm: Math.round(Math.min(180, Math.max(45, patient.bpm + Math.sin(phase * 0.7) * 3 + Math.cos(phase * 0.21)))),
      temperature: Number(Math.min(41, Math.max(34, patient.temp + Math.sin(phase * 0.18) * 0.08)).toFixed(1)),
      gsr: Number(Math.min(20, Math.max(0.1, patient.gsr + Math.sin(phase * 0.5) * 0.7)).toFixed(1)),
    };
  }

  private toReadings(sample: MonitoringSample): MonitoringReading[] {
    return parameters.map(({ parameter, key }) => ({
      id: `${sample.id}-${parameter}`,
      patientId: sample.patientId,
      gloveId: sample.gloveId,
      parameter,
      value: sample[key],
      timestamp: sample.timestamp,
      source,
    }));
  }

  start() {
    if (this.timer || typeof window === "undefined") return;
    this.timer = setInterval(() => {
      this.tick += 1;
      this.patients.forEach((patient, index) => {
        const sample = this.createSample(patient, index);
        const readings = this.toReadings(sample);
        this.latest.set(patient.id, sample);
        const history = [...(this.history.get(patient.id) ?? []), ...readings].slice(
          -MAX_PERSISTED_READINGS,
        );
        this.history.set(patient.id, history);
        this.listeners.forEach((listener) => listener(readings));
      });
      persistHistory(this.history);
    }, 2200);
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  subscribe(listener: (readings: MonitoringReading[]) => void) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  getLatestReading(patientId: string) {
    return this.latest.get(patientId);
  }

  getHistory(patientId: string) {
    return this.history.get(patientId) ?? [];
  }
}

export class BackendSensorAdapter implements MonitoringDataSource {
  private readonly patients: Patient[];
  private readonly listeners = new Set<(readings: MonitoringReading[]) => void>();
  private readonly latest = new Map<string, MonitoringSample>();
  private readonly history = new Map<string, MonitoringReading[]>();
  private readonly deviceStatus = new Map<string, DeviceStatus>();
  private readonly monitoringStatusMap = new Map<string, import("./api").BackendMonitoringStatus>();
  private timer: ReturnType<typeof setInterval> | null = null;
  private isPolling = false;

  constructor(patients: Patient[]) {
    this.patients = patients.filter(
      (patient) => patient.lifecycle !== "DISCHARGED",
    );
  }

  async pollPatient(patient: Patient) {
    try {
      const status = await api.getMonitoringStatus(patient.id);
      if (status) {
        let devStatus: DeviceStatus = "DISCONNECTED";
        if (status.monitoringStatus === "ACTIVE") {
          devStatus = "LIVE / CONNECTED";
        } else if (status.monitoringStatus === "STALE") {
          devStatus = "STALE";
        } else if (status.monitoringStatus === "INSUFFICIENT_DATA") {
          devStatus = "INSUFFICIENT CONTACT";
        } else if (status.monitoringStatus === "MODEL_ERROR") {
          devStatus = "MODEL ERROR";
        } else if (status.monitoringStatus === "DISCONNECTED") {
          devStatus = status.latestReading ? "STALE" : "DISCONNECTED";
        } else {
          devStatus = status.latestReading ? "STALE" : "ASSIGNED";
        }
        if (patient.glove) {
          this.deviceStatus.set(patient.glove, devStatus);
        }
        this.deviceStatus.set(patient.id, devStatus);
        this.monitoringStatusMap.set(patient.id, status);

        // Load historical vitals from backend
        let loadedHistory: MonitoringReading[] = [];
        try {
          const storedVitals = await api.getVitals(patient.id);
          if (storedVitals && storedVitals.length > 0) {
            for (const v of storedVitals.slice().reverse()) {
              const gloveId = v.deviceId || patient.glove || "GLV-UNKNOWN";
              const src = v.source || "FIREBASE_RTDB_LIVE";
              if (v.heartRate != null) {
                loadedHistory.push({
                  id: `${v.id}-BPM`,
                  patientId: patient.id,
                  gloveId,
                  parameter: "BPM",
                  value: Math.round(v.heartRate),
                  timestamp: v.timestamp,
                  source: src,
                });
              }
              if (v.temperature != null) {
                loadedHistory.push({
                  id: `${v.id}-Temperature`,
                  patientId: patient.id,
                  gloveId,
                  parameter: "Temperature",
                  value: v.temperature,
                  timestamp: v.timestamp,
                  source: src,
                });
              }
              if (v.gsr != null) {
                loadedHistory.push({
                  id: `${v.id}-GSR`,
                  patientId: patient.id,
                  gloveId,
                  parameter: "GSR",
                  value: v.gsr,
                  timestamp: v.timestamp,
                  source: src,
                });
              }
            }
          }
        } catch (error) {
          console.warn(`Could not load vitals for patient ${patient.id}:`, error);
        }

        if (status.latestReading) {
          const r = status.latestReading;
          const gloveId = r.deviceId || patient.glove || "GLV-UNKNOWN";
          const src = r.source || "FIREBASE_RTDB_LIVE";
          const sample: MonitoringSample = {
            id: r.id,
            patientId: patient.id,
            gloveId,
            timestamp: r.timestamp,
            bpm: Math.round(r.heartRate),
            temperature: r.temperature,
            gsr: r.gsr,
            source: src,
            state: r.state,
            signalQuality: r.signalQuality,
            firmwareVersion: r.firmwareVersion,
          };
          this.latest.set(patient.id, sample);

          const latestItems: MonitoringReading[] = [
            {
              id: `${sample.id}-BPM`,
              patientId: patient.id,
              gloveId,
              parameter: "BPM",
              value: sample.bpm,
              timestamp: sample.timestamp,
              source: src,
            },
            {
              id: `${sample.id}-Temperature`,
              patientId: patient.id,
              gloveId,
              parameter: "Temperature",
              value: sample.temperature,
              timestamp: sample.timestamp,
              source: src,
            },
            {
              id: `${sample.id}-GSR`,
              patientId: patient.id,
              gloveId,
              parameter: "GSR",
              value: sample.gsr,
              timestamp: sample.timestamp,
              source: src,
            },
          ];

          if (loadedHistory.length > 0) {
            const hasLatest = loadedHistory.some((item) => item.timestamp === sample.timestamp);
            if (!hasLatest) {
              loadedHistory = [...loadedHistory, ...latestItems];
            }
            this.history.set(patient.id, loadedHistory.slice(-MAX_PERSISTED_READINGS));
          } else {
            const existing = this.history.get(patient.id) ?? [];
            if (existing.length === 0) {
              this.history.set(patient.id, latestItems);
            } else {
              const hasLatest = existing.some((item) => item.timestamp === sample.timestamp);
              if (!hasLatest) {
                this.history.set(patient.id, [...existing, ...latestItems].slice(-MAX_PERSISTED_READINGS));
              }
            }
          }

          this.listeners.forEach((listener) => listener(latestItems));
        } else {
          if (loadedHistory.length > 0) {
            this.history.set(patient.id, loadedHistory.slice(-MAX_PERSISTED_READINGS));
          }
          this.listeners.forEach((listener) =>
            listener([
              {
                id: `status-${patient.id}`,
                patientId: patient.id,
                gloveId: patient.glove || "GLV-UNKNOWN",
                parameter: "BPM",
                value: 0,
                timestamp: new Date().toISOString(),
                source: "BACKEND",
              },
            ])
          );
        }
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      if (msg.includes("401") || msg.includes("Authentication required") || msg.includes("Unauthorized")) {
        this.stop();
        return;
      }
      console.warn(`Failed to poll status for patient ${patient.id}:`, err);
    }
  }

  async poll() {
    if (this.isPolling) return;
    this.isPolling = true;
    try {
      await Promise.all(this.patients.map((patient) => this.pollPatient(patient)));
    } finally {
      this.isPolling = false;
    }
  }

  start() {
    if (this.timer || typeof window === "undefined") return;
    void this.poll();
    this.timer = setInterval(() => {
      void this.poll();
    }, 5000);
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    this.listeners.clear();
  }

  subscribe(listener: (readings: MonitoringReading[]) => void) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  getLatestReading(patientId: string) {
    return this.latest.get(patientId);
  }

  getHistory(patientId: string) {
    return this.history.get(patientId) ?? [];
  }

  getDeviceStatus(gloveId: string): DeviceStatus {
    return this.deviceStatus.get(gloveId) ?? "NO DATA";
  }

  getAllDeviceStatuses(): Record<string, DeviceStatus> {
    return Object.fromEntries(this.deviceStatus.entries());
  }

  getMonitoringStatus(patientId: string): import("./api").BackendMonitoringStatus | undefined {
    return this.monitoringStatusMap.get(patientId);
  }

  getAllMonitoringStatuses(): Record<string, import("./api").BackendMonitoringStatus> {
    return Object.fromEntries(this.monitoringStatusMap.entries());
  }
}

export const monitoringSourceLabel = (source?: string, readingSource?: string) => {
  const s = readingSource || source;
  if (!s) return "—";
  if (s === "FIREBASE_RTDB_LIVE") return "Firebase RTDB (Live)";
  if (s === "FIREBASE_RTDB_HISTORY") return "Firebase RTDB (History)";
  if (s === "DEMO_SENSOR") return "Demo Sensor (Generated)";
  if (s === "HARDWARE") return "Direct Hardware";
  return s;
};
