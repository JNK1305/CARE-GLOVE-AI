export type ClinicalRecordType =
  | "NOTE"
  | "MEDICINE_INSTRUCTION"
  | "SUGGESTION"
  | "OBSERVATION"
  | "REPORT"
  | "TEST"
  | "ACTION"
  | "PATIENT_RESPONSE";

export type VitalRangeStatus =
  | "WITHIN_RANGE"
  | "BELOW_RANGE"
  | "ABOVE_RANGE"
  | "CRITICAL"
  | "UNKNOWN";

export type VitalThresholdConfig = {
  min?: number;
  max?: number;
  criticalMin?: number;
  criticalMax?: number;
};

export type ClinicalRecord = {
  id: string;
  patientId: string;
  actorId: string;
  actorName: string;
  actorRole: "admin" | "senior" | "duty";
  type: ClinicalRecordType;
  content?: string;
  medicineName?: string;
  dosage?: string;
  frequency?: string;
  instruction?: string;
  testName?: string;
  result?: string;
  notes?: string;
  hospital?: string;
  updatedAt?: string;
  isInternal?: boolean;
  timestamp: string;
};

export type TimelineEvent = {
  id: string;
  patientId: string;
  type:
    | ClinicalRecordType
    | "ADMISSION"
    | "WARD_ASSIGNMENT"
    | "BED_ASSIGNMENT"
    | "GLOVE_ASSIGNMENT"
    | "DOCTOR_ASSIGNMENT"
    | "MONITORING_STARTED"
    | "ALERT"
    | "NOTIFICATION"
    | "DISCHARGE";
  actor: string;
  description: string;
  timestamp: string;
  severity?: "INFO" | "WARNING" | "CRITICAL";
  metadata?: Record<string, string>;
};

export const clinicalStorageKey = "care-glove-clinical-records";
export const timelineStorageKey = "care-glove-clinical-timeline";

function safeJsonRead<T>(key: string, fallback: T): T {
  if (typeof window === "undefined") return fallback;
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return fallback;
    return JSON.parse(raw) as T;
  } catch (error) {
    console.error(`Unable to read persisted data for "${key}".`, error);
    return fallback;
  }
}

export function loadClinicalRecords(): ClinicalRecord[] {
  const stored = safeJsonRead<Array<ClinicalRecord & { doctorId?: string; doctorName?: string }>>(clinicalStorageKey, []);
  return stored.map((record) => ({
    ...record,
    actorId: record.actorId || record.doctorId || "unknown",
    actorName: record.actorName || record.doctorName || "Care team",
    actorRole: record.actorRole || "senior",
  }));
}

export function loadTimeline(): TimelineEvent[] {
  return safeJsonRead<TimelineEvent[]>(timelineStorageKey, []);
}

export function saveClinicalData(
  records: ClinicalRecord[],
  timeline: TimelineEvent[],
) {
  if (typeof window !== "undefined") {
    localStorage.setItem(clinicalStorageKey, JSON.stringify(records));
    localStorage.setItem(timelineStorageKey, JSON.stringify(timeline));
    window.dispatchEvent(new Event("care-glove-data-updated"));
  }
}

export function timelineFor(record: ClinicalRecord): TimelineEvent {
  const descriptions: Record<ClinicalRecordType, string> = {
    NOTE: "added a patient note",
    MEDICINE_INSTRUCTION: "added a medicine instruction",
    SUGGESTION: "added a patient-specific instruction",
    OBSERVATION: "recorded a staff observation",
    REPORT: "added a staff report",
    TEST: `recorded test: ${record.testName || "manual test"}`,
    ACTION: "recorded an action taken",
    PATIENT_RESPONSE: "recorded a patient response",
  };
  return {
    id: `TL-${record.id}`,
    patientId: record.patientId,
    type: record.type,
    actor: record.actorName,
    description: descriptions[record.type],
    timestamp: record.timestamp,
  };
}

export function recordLabel(type: ClinicalRecordType) {
  return {
    NOTE: "Doctor note",
    MEDICINE_INSTRUCTION: "Medicine instruction",
    SUGGESTION: "Patient suggestion",
    OBSERVATION: "Observation",
    REPORT: "Staff report",
    TEST: "Test result",
    ACTION: "Action taken",
    PATIENT_RESPONSE: "Patient response",
  }[type];
}

export const staffRecordTypes: ClinicalRecordType[] = [
  "OBSERVATION",
  "REPORT",
  "TEST",
  "ACTION",
  "PATIENT_RESPONSE",
];

export function recordText(record: ClinicalRecord) {
  if (record.type === "TEST")
    return `${record.testName}: ${record.result}${record.notes ? ` — ${record.notes}` : ""}`;
  if (record.type === "MEDICINE_INSTRUCTION")
    return `${record.medicineName} — ${record.dosage}${record.frequency ? ` · ${record.frequency}` : ""}${record.instruction ? ` — ${record.instruction}` : ""}`;
  return record.content || "";
}

export function describeVitalRangeStatus(
  value: number | undefined,
  threshold?: VitalThresholdConfig,
): VitalRangeStatus {
  if (typeof value !== "number" || Number.isNaN(value) || !threshold) {
    return "UNKNOWN";
  }

  if (threshold.criticalMin !== undefined && value < threshold.criticalMin) {
    return "CRITICAL";
  }
  if (threshold.criticalMax !== undefined && value > threshold.criticalMax) {
    return "CRITICAL";
  }
  if (threshold.min !== undefined && value < threshold.min) {
    return "BELOW_RANGE";
  }
  if (threshold.max !== undefined && value > threshold.max) {
    return "ABOVE_RANGE";
  }
  if (threshold.min !== undefined || threshold.max !== undefined) {
    return "WITHIN_RANGE";
  }

  return "UNKNOWN";
}

export function vitalRangeLabel(status: VitalRangeStatus) {
  switch (status) {
    case "WITHIN_RANGE":
      return "Within expected range";
    case "BELOW_RANGE":
      return "Below expected range";
    case "ABOVE_RANGE":
      return "Above expected range";
    case "CRITICAL":
      return "Critical";
    default:
      return "Range not configured";
  }
}
