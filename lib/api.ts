import type {
  Alert,
  Bed,
  Doctor,
  Glove,
  Notification,
  Patient,
  Report,
  Role,
  User,
  Ward,
  WardName,
} from "./domain";
import type { ClinicalRecord, TimelineEvent } from "./clinical";

export const API_BASE =
  process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export type BackendMonitoringStatus = {
  patientId: string;
  deviceId: string;
  monitoringStatus: "ACTIVE" | "STALE" | "DISCONNECTED" | "INSUFFICIENT_DATA" | "MODEL_ERROR" | "READY";
  healthState: "NORMAL" | "WARNING" | "CRITICAL";
  severity: "NORMAL" | "WARNING" | "CRITICAL";
  latestReading?: {
    id: string;
    patientId: string;
    deviceId: string;
    timestamp: string;
    heartRate: number;
    gsr: number;
    temperature: number;
    state?: string;
    source: "DEMO_SENSOR" | "HARDWARE" | "SIMULATED" | "FIREBASE_RTDB_LIVE" | "FIREBASE_RTDB_HISTORY" | string;
    signalQuality?: number;
    firmwareVersion?: string;
  } | null;
  windowStats?: {
    readingCount: number;
    meanHeartRate: number | null;
    meanGsr: number | null;
    meanTemperature: number | null;
  } | null;
  latestPrediction?: {
    severity: "NORMAL" | "WARNING" | "CRITICAL" | "SENSOR_UNAVAILABLE";
    state: "NORMAL" | "MONITOR" | "POTENTIAL_ANOMALY";
    classifierVersion: string;
    confidence: number | null;
    anomalyScore: number | null;
    reason: string;
    timestamp: string;
  } | null;
  evaluatedAt: string;
  activeAlertId: string | null;
  message: string;
};

export type SystemStatus = {
  backendStatus: string;
  databaseStatus: string;
  monitoringIntervalSeconds: number;
  defaultIntervalSeconds: number;
  requiredFeatures: string[];
};

export type FirebaseStatusResponse = {
  enabled: boolean;
  connected: boolean;
  deviceState: "CONNECTED" | "STALE" | "DISCONNECTED" | "INVALID_DATA";
  databaseUrl: string;
  sensorDataPath: string;
  sensorHistoryPath: string;
  pollIntervalSeconds: number;
  freshnessWindowSeconds: number;
  defaultDeviceId: string;
  defaultPatientId: string;
  lastPollTime: string | null;
  lastSuccessfulPollTime: string | null;
  lastValidMeasurementTime: string | null;
  lastPayloadTimestamp: number | null;
  totalReadingsIngested: number;
  lastError: string | null;
};

function getAuthToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem("care-glove-session");
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    const token = parsed?.token;
    if (!token || typeof token !== "string") return null;
    // Genuine signed backend JWT has 3 parts separated by dots
    if (token.split(".").length !== 3) {
      return null;
    }
    return token;
  } catch {
    return null;
  }
}

function isPublicEndpoint(path: string): boolean {
  return (
    path === "/api/auth/login" ||
    path.startsWith("/api/health") ||
    path.startsWith("/api/system/") ||
    path.startsWith("/actuator/")
  );
}

async function request<T>(
  path: string,
  options: Parameters<typeof fetch>[1] = {}
): Promise<T> {
  const token = getAuthToken();
  const headers = new Headers(options.headers || {});
  headers.set("Content-Type", "application/json");
  if (token && !headers.has("Authorization") && path !== "/api/auth/login") {
    headers.set("Authorization", `Bearer ${token}`);
  }

  if (!token && !isPublicEndpoint(path)) {
    throw new Error(`Authentication required: No valid session token available for protected endpoint ${path}.`);
  }

  const url = `${API_BASE}${path}`;
  const response = await fetch(url, {
    ...options,
    headers,
  });

  if (!response.ok) {
    let errorMessage = `HTTP ${response.status}: ${response.statusText}`;
    try {
      const errorJson = await response.json();
      if (errorJson?.error) {
        errorMessage = errorJson.error;
      } else if (errorJson?.message) {
        errorMessage = errorJson.message;
      }
    } catch {
      // Keep default error message if body is not JSON
    }

    if (response.status === 401 && path !== "/api/auth/login") {
      if (typeof window !== "undefined") {
        window.localStorage.removeItem("care-glove-session");
        window.dispatchEvent(
          new CustomEvent("care-glove-auth-expired", {
            detail: { path, message: errorMessage },
          })
        );
      }
    }

    throw new Error(errorMessage);
  }

  if (response.status === 204) {
    return undefined as unknown as T;
  }

  return response.json() as Promise<T>;
}

export const api = {
  async isBackendReachable(): Promise<boolean> {
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 2000);
      const res = await fetch(`${API_BASE}/api/health`, {
        signal: controller.signal,
      });
      clearTimeout(timeoutId);
      return res.ok;
    } catch {
      return false;
    }
  },

  async getSystemStatus(): Promise<SystemStatus> {
    return request<SystemStatus>("/api/system/status");
  },

  async login(
    role: Role,
    email: string,
    password: string
  ): Promise<{ token: string; user: User }> {
    return request<{ token: string; user: User }>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ role, email, password }),
    });
  },

  async logout(): Promise<void> {
    return request<void>("/api/auth/logout", {
      method: "POST",
    });
  },

  async getMe(): Promise<User> {
    return request<User>("/api/auth/me");
  },

  async getPatients(): Promise<Patient[]> {
    return request<Patient[]>("/api/patients");
  },

  async getPatient(id: string): Promise<Patient> {
    return request<Patient>(`/api/patients/${encodeURIComponent(id)}`);
  },

  async createPatient(
    patient: Omit<Patient, "id">
  ): Promise<Patient> {
    return request<Patient>("/api/patients", {
      method: "POST",
      body: JSON.stringify(patient),
    });
  },

  async updatePatient(
    id: string,
    patch: Partial<Patient>
  ): Promise<Patient> {
    return request<Patient>(`/api/patients/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    });
  },

  async getVitals(patientId: string): Promise<
    Array<{
      id: string;
      patientId: string;
      deviceId: string;
      timestamp: string;
      heartRate: number;
      gsr: number;
      temperature: number;
      source: "DEMO_SENSOR" | "HARDWARE" | "SIMULATED" | "FIREBASE_RTDB_LIVE" | "FIREBASE_RTDB_HISTORY" | string;
      signalQuality?: number;
      firmwareVersion?: string;
    }>
  > {
    return request(`/api/patients/${encodeURIComponent(patientId)}/vitals`);
  },

  async getMonitoringStatus(
    patientId: string
  ): Promise<BackendMonitoringStatus> {
    return request<BackendMonitoringStatus>(
      `/api/patients/${encodeURIComponent(patientId)}/monitoring-status`
    );
  },

  async ingestReading(payload: {
    patientId: string;
    deviceId: string;
    timestamp: string;
    heartRate: number;
    gsr: number;
    temperature: number;
    source?: "HARDWARE" | "DEMO_SENSOR" | "SIMULATED" | "FIREBASE_RTDB_LIVE" | "FIREBASE_RTDB_HISTORY" | string;
    signalQuality?: number;
    firmwareVersion?: string;
  }): Promise<{
    readingId: string;
    patientId: string;
    deviceId: string;
    timestamp: string;
    processingStatus: string;
    message: string;
    reading: unknown;
  }> {
    return request("/api/v1/vitals/ingest", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  async getAlerts(patientId: string): Promise<Alert[]> {
    return request<Alert[]>(`/api/patients/${encodeURIComponent(patientId)}/alerts`);
  },

  async getAllAlerts(): Promise<Alert[]> {
    return request<Alert[]>("/api/alerts");
  },

  async updateAlertStatus(
    alertId: string,
    status: "OPEN" | "ACKNOWLEDGED" | "RESOLVED"
  ): Promise<Alert> {
    return request<Alert>(
      `/api/alerts/${encodeURIComponent(alertId)}?status=${encodeURIComponent(status)}`,
      {
        method: "PATCH",
        body: JSON.stringify({ status }),
      }
    );
  },

  async getNotifications(): Promise<Notification[]> {
    return request<Notification[]>("/api/notifications");
  },

  async markNotificationRead(notificationId: string): Promise<Notification> {
    return request<Notification>(
      `/api/notifications/${encodeURIComponent(notificationId)}`,
      {
        method: "PATCH",
        body: JSON.stringify({ read: true }),
      }
    );
  },

  async getJourney(patientId: string): Promise<TimelineEvent[]> {
    return request<TimelineEvent[]>(
      `/api/patients/${encodeURIComponent(patientId)}/journey`
    );
  },

  async getClinicalRecords(patientId: string): Promise<ClinicalRecord[]> {
    return request<ClinicalRecord[]>(
      `/api/patients/${encodeURIComponent(patientId)}/clinical-records`
    );
  },

  async createClinicalRecord(
    patientId: string,
    record: Omit<ClinicalRecord, "id" | "patientId" | "actorId" | "actorName" | "actorRole" | "timestamp">
  ): Promise<ClinicalRecord> {
    return request<ClinicalRecord>(
      `/api/patients/${encodeURIComponent(patientId)}/clinical-records`,
      {
        method: "POST",
        body: JSON.stringify(record),
      }
    );
  },

  async updateClinicalRecord(
    patientId: string,
    recordId: string,
    content: string
  ): Promise<ClinicalRecord> {
    return request<ClinicalRecord>(
      `/api/patients/${encodeURIComponent(patientId)}/clinical-records/${encodeURIComponent(recordId)}`,
      {
        method: "PUT",
        body: JSON.stringify({ content }),
      }
    );
  },

  async getDoctors(): Promise<Doctor[]> {
    return request<Doctor[]>("/api/doctors");
  },

  async createDoctor(payload: {
    name: string;
    email: string;
    hospital: string;
    specialty?: string;
    contact?: string;
    employeeId?: string;
    password?: string;
  }): Promise<Doctor> {
    return request<Doctor>("/api/doctors", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  async updateDoctor(
    doctorId: string,
    patch: {
      name?: string;
      specialty?: string;
      contact?: string;
      employeeId?: string;
      active?: boolean;
    }
  ): Promise<Doctor> {
    return request<Doctor>(`/api/doctors/${encodeURIComponent(doctorId)}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    });
  },

  async getReports(): Promise<Report[]> {
    return request<Report[]>("/api/reports");
  },

  async createReport(patientId: string): Promise<Report> {
    return request<Report>(
      `/api/patients/${encodeURIComponent(patientId)}/reports`,
      {
        method: "POST",
      }
    );
  },

  async getWards(): Promise<Ward[]> {
    return request<Ward[]>("/api/wards");
  },

  async getBeds(wardId: WardName): Promise<Bed[]> {
    return request<Bed[]>(`/api/wards/${encodeURIComponent(wardId)}/beds`);
  },

  async getGloves(): Promise<Glove[]> {
    return request<Glove[]>("/api/gloves");
  },

  async getFirebaseStatus(): Promise<FirebaseStatusResponse> {
    return request<FirebaseStatusResponse>("/api/system/firebase-status");
  },

  async syncFirebase(): Promise<{ success: boolean; readingsIngested: number; error: string | null }> {
    return request("/api/system/firebase-sync", { method: "POST" });
  },
};
