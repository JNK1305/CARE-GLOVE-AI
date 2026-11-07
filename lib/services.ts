import type {
  Alert,
  Bed,
  DemoSession,
  Glove,
  MonitoringParameter,
  MonitoringReading,
  MonitoringSample,
  Notification,
  Patient,
  Report,
  Role,
  SeniorDoctorAssignment,
  TestResult,
  User,
  Ward,
  WardName,
} from "./domain";
import { DemoSensorAdapter, type MonitoringDataSource } from "./monitoring";
import {
  type ClinicalRecord,
  type TimelineEvent,
  loadClinicalRecords,
  loadTimeline,
  saveClinicalData,
  timelineFor,
} from "./clinical";

const keys = {
  patients: "care-glove-patients",
  session: "care-glove-session",
  readings: "care-glove-monitoring-history",
  alerts: "care-glove-alerts",
  alertRecovery: "care-glove-alert-recovery",
  notifications: "care-glove-notifications",
  reports: "care-glove-reports",
  tests: "care-glove-tests",
  gloves: "care-glove-glove-state",
};

const bedsPerWard: Record<WardName, string[]> = {
  ICU: ["B-01", "B-02", "B-03", "B-04", "B-05", "B-06", "B-07", "B-08", "B-09", "B-10", "B-11", "B-12"],
  NICU: ["B-01", "B-02", "B-03", "B-04", "B-05", "B-06", "B-07", "B-08", "B-09", "B-10", "B-11", "B-12"],
  "Special Ward": ["B-01", "B-02", "B-03", "B-04", "B-05", "B-06", "B-07", "B-08", "B-09", "B-10", "B-11", "B-12"],
  "General Ward": ["B-01", "B-02", "B-03", "B-04", "B-05", "B-06", "B-07", "B-08", "B-09", "B-10", "B-11", "B-12"],
};

export const wards: Ward[] = (Object.keys(bedsPerWard) as WardName[]).map((name) => ({ id: name, name }));

const prefixForWard: Record<WardName, string> = {
  ICU: "ICU",
  NICU: "NICU",
  "Special Ward": "SPL",
  "General Ward": "GEN",
};

const doctors: User[] = [
  { id: "sharma", name: "Dr. Amit Sharma", role: "senior" },
  { id: "patil", name: "Dr. Priya Patil", role: "senior" },
];
const NORMAL_READINGS_TO_RESOLVE_ALERT = 5;
const monitoringParameters = new Set<MonitoringParameter>(["BPM", "Temperature", "GSR"]);

const memoryStore = new Map<string, string>();

function read<T>(key: string, fallback: T): T {
  let raw: string | null = null;
  if (typeof window !== "undefined" && window.localStorage) {
    try {
      raw = window.localStorage.getItem(key);
    } catch {
      raw = memoryStore.get(key) || null;
    }
  } else {
    raw = memoryStore.get(key) || null;
  }
  if (!raw) return fallback;
  try {
    return JSON.parse(raw) as T;
  } catch (error) {
    console.error(`Unable to read persisted CARE-GLOVE data "${key}".`, error);
    return fallback;
  }
}

function write<T>(key: string, value: T, notify = true) {
  const jsonStr = JSON.stringify(value);
  memoryStore.set(key, jsonStr);
  if (typeof window !== "undefined" && window.localStorage) {
    try {
      window.localStorage.setItem(key, jsonStr);
      if (notify) {
        window.dispatchEvent(
          new CustomEvent("care-glove-data-updated", { detail: { key } }),
        );
      }
    } catch (error) {
      console.error(`Unable to persist CARE-GLOVE data "${key}".`, error);
      throw new Error(`Could not save ${key}. Check available browser storage.`, { cause: error });
    }
  }
}

function emitTimeline(events: TimelineEvent[]) {
  if (!events.length) return;
  const current = loadTimeline();
  const ids = new Set(current.map((event) => event.id));
  const next = [...events.filter((event) => !ids.has(event.id)), ...current];
  saveClinicalData(loadClinicalRecords(), next);
}

type StoredPatient = Omit<Patient, "bedId"> & { bedId?: string; spo2?: number };

function patientWithStableBed(patient: StoredPatient): Patient {
  const current = { ...patient };
  delete current.spo2;
  const bedId = current.bedId || `${current.ward}:${current.bed}`;
  const glove = current.glove || gloveIdFor(current.ward, current.bed);
  return { ...current, bedId, glove };
}

export function gloveIdFor(ward: WardName, bed: string) {
  const number = bed.replace(/^B-/, "").padStart(3, "0");
  return `GLV-${prefixForWard[ward]}-${number}`;
}

export function loadPatients(seed: Patient[]): Patient[] {
  const stored = read<StoredPatient[]>(keys.patients, []);
  const sourceList = stored.length > 0 ? stored : seed;
  let migrated = false;
  let patients = sourceList.map(patientWithStableBed).map((patient) => {
    if (patient.id === "CG-1043") {
      migrated = true;
      return {
        ...patient,
        id: "CG-B2CC11AC158A",
        glove: "GLV-ICU-001",
        ward: "ICU" as WardName,
        bed: "B-01",
        bedId: "ICU:B-01",
      };
    }
    return patient;
  });
  if (migrated || !stored.length || stored.some((patient) => "spo2" in patient)) {
    write(keys.patients, patients, false);
  }
  return patients;
}

export function savePatients(patients: Patient[]) {
  const active = patients.filter((patient) => patient.lifecycle !== "DISCHARGED");
  const assignments = active.map((patient) => `${patient.ward}:${patient.bed}`);
  if (new Set(assignments).size !== assignments.length) throw new Error("A bed can only be assigned to one active patient.");
  const gloves = active.map((patient) => patient.glove);
  if (new Set(gloves).size !== gloves.length) throw new Error("A glove can only be assigned to one active patient.");
  write(keys.patients, patients);
  syncGloveState(patients);
}

function syncGloveState(patients: Patient[]) {
  const gloves: Glove[] = [];
  for (const ward of wards) {
    for (const label of bedsPerWard[ward.id]) {
      const bedId = `${ward.id}:${label}`;
      const gloveId = gloveIdFor(ward.id, label);
      const patient = patients.find((candidate) => candidate.bedId === bedId && candidate.lifecycle !== "DISCHARGED");
      gloves.push({
        id: gloveId,
        bedId,
        wardId: ward.id,
        status: !patient ? "AVAILABLE" : patient.monitoringStatus === "ACTIVE" ? "DEMO ACTIVE" : "ASSIGNED",
      });
    }
  }
  write(keys.gloves, gloves);
}

export function getBeds(patients?: Patient[]): Bed[] {
  void patients;
  return wards.flatMap((ward) => bedsPerWard[ward.id].map((label) => ({
    id: `${ward.id}:${label}`,
    wardId: ward.id,
    label,
    gloveId: gloveIdFor(ward.id, label),
  })));
}

export function getGloves(patients: Patient[]): Glove[] {
  const stored = read<Glove[]>(keys.gloves, []);
  const byId = new Map(stored.map((glove) => [glove.id, glove]));
  return getBeds(patients).map((bed) => {
    const patient = patients.find((entry) => entry.bedId === bed.id && entry.lifecycle !== "DISCHARGED");
    const previous = byId.get(bed.gloveId);
    return {
      id: bed.gloveId,
      bedId: bed.id,
      wardId: bed.wardId,
      status: patient ? "DEMO ACTIVE" : "AVAILABLE",
      maintenanceNote: previous?.maintenanceNote,
    };
  });
}

export function availableBeds(patients: Patient[], ward: WardName): Bed[] {
  const occupied = new Set(patients.filter((patient) => patient.lifecycle !== "DISCHARGED").map((patient) => patient.bedId));
  return getBeds(patients).filter((bed) => bed.wardId === ward && !occupied.has(bed.id));
}

export function createPatient(patients: Patient[], patient: Patient): Patient[] {
  if (patients.some((entry) => entry.id.toLowerCase() === patient.id.toLowerCase())) throw new Error("Patient ID already exists.");
  if (!availableBeds(patients, patient.ward).some((bed) => bed.id === patient.bedId)) throw new Error("That bed is no longer available.");
  const expectedGlove = gloveIdFor(patient.ward, patient.bed);
  if (patient.glove !== expectedGlove) throw new Error("The selected bed is not linked to that glove.");
  const next = [...patients, patient];
  savePatients(next);
  const timestamp = new Date().toISOString();
  emitTimeline([
    { id: `admission:${patient.id}`, patientId: patient.id, type: "ADMISSION", actor: "Meera Kulkarni", description: `${patient.name} admitted`, timestamp },
    { id: `ward:${patient.id}:${timestamp}`, patientId: patient.id, type: "WARD_ASSIGNMENT", actor: "Meera Kulkarni", description: `Assigned to ${patient.ward}`, timestamp },
    { id: `bed:${patient.id}:${timestamp}`, patientId: patient.id, type: "BED_ASSIGNMENT", actor: "Meera Kulkarni", description: `Bed ${patient.bed} assigned`, timestamp },
    { id: `glove:${patient.id}:${timestamp}`, patientId: patient.id, type: "GLOVE_ASSIGNMENT", actor: "Meera Kulkarni", description: `Glove ${patient.glove} assigned`, timestamp },
    { id: `doctor:${patient.id}:${timestamp}`, patientId: patient.id, type: "DOCTOR_ASSIGNMENT", actor: "Meera Kulkarni", description: `${patient.doctor} assigned`, timestamp },
  ]);
  return next;
}

export function updatePatient(patients: Patient[], id: string, update: Partial<Patient>): Patient[] {
  const original = patients.find((patient) => patient.id === id);
  if (!original) throw new Error("Patient was not found.");
  const updated = patientWithStableBed({ ...original, ...update });
  if (updated.bedId !== original.bedId || updated.glove !== original.glove) {
    if (!availableBeds(patients.filter((patient) => patient.id !== id), updated.ward).some((bed) => bed.id === updated.bedId)) throw new Error("That bed is already assigned.");
    if (updated.glove !== gloveIdFor(updated.ward, updated.bed)) throw new Error("The bed and glove assignment do not match.");
  }
  const next = patients.map((patient) => patient.id === id ? updated : patient);
  savePatients(next);
  const timestamp = new Date().toISOString();
  const changes: TimelineEvent[] = [];
  if (updated.ward !== original.ward) changes.push({ id: `ward:${id}:${timestamp}`, patientId: id, type: "WARD_ASSIGNMENT", actor: "Meera Kulkarni", description: `Reassigned to ${updated.ward}`, timestamp });
  if (updated.bedId !== original.bedId) changes.push({ id: `bed:${id}:${timestamp}`, patientId: id, type: "BED_ASSIGNMENT", actor: "Meera Kulkarni", description: `Reassigned to bed ${updated.bed}`, timestamp });
  if (updated.glove !== original.glove) changes.push({ id: `glove:${id}:${timestamp}`, patientId: id, type: "GLOVE_ASSIGNMENT", actor: "Meera Kulkarni", description: `Reassigned glove ${updated.glove}`, timestamp });
  if (updated.doctorId !== original.doctorId) changes.push({ id: `doctor:${id}:${timestamp}`, patientId: id, type: "DOCTOR_ASSIGNMENT", actor: "Meera Kulkarni", description: `Assigned to ${updated.doctor}`, timestamp });
  emitTimeline(changes);
  return next;
}

export function dischargePatient(patients: Patient[], id: string): Patient[] {
  const patient = patients.find((entry) => entry.id === id);
  if (!patient) throw new Error("Patient was not found.");
  const next = patients.map((entry) => entry.id === id ? { ...entry, lifecycle: "DISCHARGED" as const, monitoringStatus: "DISCHARGED" as const } : entry);
  savePatients(next);
  const timestamp = new Date().toISOString();
  emitTimeline([{ id: `discharge:${id}:${timestamp}`, patientId: id, type: "DISCHARGE", actor: "Meera Kulkarni", description: `${patient.name} discharged; bed ${patient.bed} and glove ${patient.glove} released`, timestamp }]);
  return next;
}

export type AuthDemoUser = DemoSession & { token?: string };

export const authService = {
  authenticate(role: Role, email: string, password: string) {
    const credentials: Record<Role, string> = {
      admin: "admin@careglove.demo",
      senior: "doctor@careglove.demo",
      duty: "staff@careglove.demo",
    };
    if (!email.trim()) {
      throw new Error("Enter an email address to continue.");
    }
    if (!password.trim()) {
      throw new Error("Enter a demo password to continue.");
    }
    if (email.trim().toLowerCase() !== credentials[role]) {
      throw new Error("Those credentials do not match the selected workspace.");
    }
  },
  login(role: Role, sessionData?: AuthDemoUser): AuthDemoUser {
    if (sessionData) {
      write(keys.session, sessionData);
      return sessionData;
    }
    const existing = read<AuthDemoUser | null>(keys.session, null);
    if (existing?.token && existing.token.split(".").length === 3) {
      return existing;
    }
    const users: Record<Role, AuthDemoUser> = {
      admin: { id: "admin-demo", name: "Care Glove Admin", role, token: "demo-admin-session" },
      senior: { id: "sharma", name: "Dr. Amit Sharma", role, token: "demo-senior-session" },
      duty: { id: "duty-demo", name: "Duty Staff", role, token: "demo-duty-session" },
    };
    const session = users[role];
    write(keys.session, session);
    return session;
  },
  saveSession(session: AuthDemoUser) {
    write(keys.session, session);
  },
  restore(): AuthDemoUser | null {
    const session = read<AuthDemoUser | null>(keys.session, null);
    if (session?.role === "senior" && session.id === "senior-demo") {
      const migrated = { ...session, id: "sharma", name: "Dr. Amit Sharma" };
      write(keys.session, migrated, false);
      return migrated;
    }
    return session;
  },
  logout() {
    if (typeof window !== "undefined") window.localStorage.removeItem(keys.session);
    if (typeof window !== "undefined") window.dispatchEvent(new Event("care-glove-data-updated"));
  },
};

export function ensurePatientTimeline(patients: Patient[]) {
  const existing = loadTimeline();
  const patientIds = new Set(
    existing
      .filter((event) => event.type === "ADMISSION")
      .map((event) => event.patientId),
  );
  const timestamp = new Date().toISOString();
  const events: TimelineEvent[] = [];
  for (const patient of patients) {
    if (patientIds.has(patient.id)) continue;
    events.push(
      { id: `admission:${patient.id}`, patientId: patient.id, type: "ADMISSION", actor: "CARE HOSPITAL", description: `${patient.name} admission record initialized`, timestamp },
      { id: `ward:${patient.id}`, patientId: patient.id, type: "WARD_ASSIGNMENT", actor: "CARE HOSPITAL", description: `Assigned to ${patient.ward}`, timestamp },
      { id: `bed:${patient.id}`, patientId: patient.id, type: "BED_ASSIGNMENT", actor: "CARE HOSPITAL", description: `Bed ${patient.bed} assigned`, timestamp },
      { id: `glove:${patient.id}`, patientId: patient.id, type: "GLOVE_ASSIGNMENT", actor: "CARE HOSPITAL", description: `Glove ${patient.glove} assigned`, timestamp },
      { id: `doctor:${patient.id}`, patientId: patient.id, type: "DOCTOR_ASSIGNMENT", actor: "CARE HOSPITAL", description: `${patient.doctor} assigned`, timestamp },
    );
  }
  emitTimeline(events);
}

export function canAccessPatient(role: Role, patient: Patient, userId?: string) {
  if (role === "admin") return true;
  if (role === "senior") {
    return Boolean(userId) && patient.doctorId === userId && patient.lifecycle !== "DISCHARGED";
  }
  return patient.authorized && patient.lifecycle !== "DISCHARGED";
}

export function patientsVisibleToRole(role: Role, patients: Patient[], userId?: string) {
  return patients.filter((patient) => canAccessPatient(role, patient, userId));
}

export function getPatientAssignments(patients: Patient[]): SeniorDoctorAssignment[] {
  return patients.map((patient) => ({ patientId: patient.id, doctorId: patient.doctorId, doctorName: patient.doctor }));
}

export const alertService = {
  list(): Alert[] {
    const stored = read<Array<Alert & { status?: Alert["status"]; source?: Alert["source"] }>>(keys.alerts, []);
    return stored.map((alert) => ({
      ...alert,
      status: alert.status || "OPEN",
      source: alert.source || "DEMO_SENSOR",
      gloveId: alert.gloveId || "",
    }));
  },
  evaluate(sample: MonitoringSample, source: MonitoringReading["source"]) {
    const rules: Array<{ parameter: MonitoringParameter; value: number; threshold: number; severity: Alert["severity"]; abnormal: boolean }> = [
      { parameter: "BPM", value: sample.bpm, threshold: 50, severity: "WARNING", abnormal: sample.bpm < 50 },
      { parameter: "BPM", value: sample.bpm, threshold: 120, severity: "WARNING", abnormal: sample.bpm > 120 },
      { parameter: "Temperature", value: sample.temperature, threshold: 38, severity: "WARNING", abnormal: sample.temperature > 38 },
      { parameter: "Temperature", value: sample.temperature, threshold: 35.5, severity: "WARNING", abnormal: sample.temperature < 35.5 },
      { parameter: "GSR", value: sample.gsr, threshold: 10, severity: "WARNING", abnormal: sample.gsr > 10 },
    ];
    const alerts = this.list();
    const recovery = read<Record<string, number>>(keys.alertRecovery, {});
    let changed = false;
    let recoveryChanged = false;
    const parameters = [...new Set(rules.map((rule) => rule.parameter))];
    for (const parameter of parameters) {
      const rule = rules.find((candidate) => candidate.parameter === parameter && candidate.abnormal);
      const existing = alerts.find((alert) => alert.patientId === sample.patientId && alert.parameter === parameter && alert.status === "OPEN");
      const recoveryId = `${sample.patientId}:${parameter}`;
      if (!rule) {
        if (existing) {
          const normalReadings = (recovery[recoveryId] || 0) + 1;
          if (normalReadings < NORMAL_READINGS_TO_RESOLVE_ALERT) {
            recovery[recoveryId] = normalReadings;
            recoveryChanged ||= true;
          } else {
            existing.status = "RESOLVED";
            delete recovery[recoveryId];
            changed ||= true;
            recoveryChanged ||= true;
          }
        } else if (recoveryId in recovery) {
          delete recovery[recoveryId];
          recoveryChanged ||= true;
        }
        continue;
      }
      if (recoveryId in recovery) {
        delete recovery[recoveryId];
        recoveryChanged ||= true;
      }
      if (existing && (existing.severity === "CRITICAL" || existing.severity === rule.severity)) continue;
      if (existing && existing.status !== "RESOLVED") {
        existing.status = "RESOLVED";
        changed ||= true;
      }
      const id = `ALT-${sample.patientId}-${parameter}-${sample.timestamp}`;
      const alert: Alert = {
        id,
        patientId: sample.patientId,
        gloveId: sample.gloveId,
        parameter,
        value: rule.value,
        threshold: rule.threshold,
        severity: rule.severity,
        timestamp: sample.timestamp,
        source,
        status: "OPEN",
      };
      alerts.unshift(alert);
      changed ||= true;
      const notifications = read<Notification[]>(keys.notifications, []);
      notifications.unshift({ id: `NTF-${id}`, alertId: id, patientId: alert.patientId, severity: alert.severity, parameter: alert.parameter, value: alert.value, timestamp: alert.timestamp, read: false });
      write(keys.notifications, notifications, false);
      emitTimeline([
        { id: `alert:${id}`, patientId: alert.patientId, type: "ALERT", actor: source, description: `${alert.severity} ${alert.parameter} alert: ${alert.value}`, timestamp: alert.timestamp, severity: alert.severity, metadata: { alertId: alert.id, gloveId: alert.gloveId } },
        { id: `notification:NTF-${id}`, patientId: alert.patientId, type: "NOTIFICATION", actor: "CARE-GLOVE alert service", description: `Notification created for ${alert.severity} ${alert.parameter} alert`, timestamp: alert.timestamp, metadata: { notificationId: `NTF-${id}`, alertId: alert.id } },
      ]);
    }
    if (changed) write(keys.alerts, alerts);
    if (recoveryChanged) write(keys.alertRecovery, recovery, false);
  },
  syncBackendAlerts(backendAlerts: Alert[]) {
    if (!backendAlerts) return;
    const current = this.list();
    const map = new Map(current.map((a) => [a.id, a]));
    let changed = false;
    for (const b of backendAlerts) {
      const existing = map.get(b.id);
      if (
        !existing ||
        existing.status !== b.status ||
        existing.value !== b.value ||
        existing.timestamp !== b.timestamp ||
        existing.severity !== b.severity ||
        existing.threshold !== b.threshold ||
        existing.explanation !== b.explanation
      ) {
        map.set(b.id, b);
        changed = true;
      }
    }
    if (changed || (backendAlerts.length === 0 && current.length > 0)) {
      write(keys.alerts, Array.from(map.values()), true);
    }
  },
};

export const notificationService = {
  list(): Notification[] {
    return read<Notification[]>(keys.notifications, []);
  },
  unreadCount() {
    return this.list().filter((notification) => !notification.read).length;
  },
  markRead(id: string) {
    write(keys.notifications, this.list().map((notification) => notification.id === id ? { ...notification, read: true } : notification), true);
  },
  markAllRead(patientIds: Set<string>) {
    write(keys.notifications, this.list().map((notification) => patientIds.has(notification.patientId) ? { ...notification, read: true } : notification), true);
  },
  syncBackendNotifications(backendNotifications: Notification[]) {
    if (!backendNotifications || !backendNotifications.length) return;
    const current = this.list();
    const map = new Map(current.map((n) => [n.id, n]));
    let changed = false;
    for (const b of backendNotifications) {
      if (!map.has(b.id) || map.get(b.id)?.read !== b.read) {
        map.set(b.id, b);
        changed = true;
      }
    }
    if (changed) {
      write(keys.notifications, Array.from(map.values()), true);
    }
  },
};

export const clinicalService = {
  list() {
    return loadClinicalRecords();
  },
  create(record: ClinicalRecord) {
    if (!record.patientId || !record.actorId || !record.content?.trim() && record.type !== "TEST" && record.type !== "MEDICINE_INSTRUCTION") throw new Error("Complete the required clinical record fields.");
    if (record.actorRole === "duty" && !["OBSERVATION", "REPORT", "TEST", "ACTION", "PATIENT_RESPONSE"].includes(record.type)) throw new Error("On-duty staff cannot create doctor instructions.");
    if (record.actorRole === "senior" && !doctors.some((doctor) => doctor.id === record.actorId)) throw new Error("The current user is not an authorized Senior Doctor.");
    if (record.type === "TEST" && (!record.testName?.trim() || !record.result?.trim())) throw new Error("Test name and result are required.");
    if (record.type === "MEDICINE_INSTRUCTION" && (!record.medicineName?.trim() || !record.dosage?.trim())) throw new Error("Medicine name and dosage are required.");
    const records = loadClinicalRecords();
    if (records.some((item) => item.id === record.id)) throw new Error("Clinical record ID already exists.");
    const timeline = loadTimeline();
    saveClinicalData([record, ...records], [timelineFor(record), ...timeline]);
    if (record.type === "REPORT") {
      this.createReport({ id: `RPT-${record.id}`, patientId: record.patientId, authorId: record.actorId, authorName: record.actorName, timestamp: record.timestamp, title: "Staff report", content: record.content || "" });
    }
    if (record.type === "TEST") {
      const tests = read<TestResult[]>(keys.tests, []);
      write(keys.tests, [{ id: `TST-${record.id}`, patientId: record.patientId, authorId: record.actorId, authorName: record.actorName, timestamp: record.timestamp, testName: record.testName || "", result: record.result || "" }, ...tests]);
    }
    if (typeof window !== "undefined") window.dispatchEvent(new Event("care-glove-data-updated"));
    return record;
  },
  createReport(report: Report) {
    const reports = read<Report[]>(keys.reports, []);
    if (reports.some((item) => item.id === report.id)) throw new Error("Report ID already exists.");
    write(keys.reports, [report, ...reports]);
    emitTimeline([{ id: `report:${report.id}`, patientId: report.patientId, type: "REPORT", actor: report.authorName, description: `Created report: ${report.title}`, timestamp: report.timestamp, metadata: { reportId: report.id } }]);
    return report;
  },
  reports(): Report[] {
    return read<Report[]>(keys.reports, []);
  },
  tests(): TestResult[] {
    return read<TestResult[]>(keys.tests, []);
  },
};

export function getTimeline(patientId: string): TimelineEvent[] {
  return loadTimeline()
    .filter((event) => event.patientId === patientId)
    .sort((a, b) => a.timestamp.localeCompare(b.timestamp) || a.id.localeCompare(b.id));
}

export function startMonitoringTimeline(patient: Patient, actor = "DEMO_SENSOR") {
  const timeline = loadTimeline();
  const id = `monitoring:${patient.id}`;
  if (timeline.some((event) => event.id === id)) return;
  emitTimeline([{ id, patientId: patient.id, type: "MONITORING_STARTED", actor, description: `Monitoring started · ${patient.glove}`, timestamp: new Date().toISOString() }]);
}

export function listAlertsForPatient(alerts: Alert[], patientIds: Set<string>) {
  return alerts.filter((alert) => patientIds.has(alert.patientId));
}

export function listNotificationsForPatients(notifications: Notification[], patientIds: Set<string>) {
  return notifications.filter((notification) => patientIds.has(notification.patientId));
}

export function exportReport(report: Report) {
  if (typeof window === "undefined") throw new Error("Report download is only available in a browser.");
  const content = [
    "CARE-GLOVE AI — Patient Report",
    `Report ID: ${report.id}`,
    `Patient ID: ${report.patientId}`,
    `Title: ${report.title}`,
    `Author: ${report.authorName}`,
    `Timestamp: ${new Date(report.timestamp).toLocaleString()}`,
    "",
    report.content,
  ].join("\r\n");
  const url = URL.createObjectURL(new Blob([content], { type: "text/plain;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = `${report.id}.txt`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export const serviceStorageKeys = keys;

export const patientService = {
  list: loadPatients,
  getById: (patients: Patient[], id: string) =>
    patients.find((patient) => patient.id === id),
  create: createPatient,
  update: updatePatient,
  discharge: dischargePatient,
  assignments: getPatientAssignments,
};

export const wardService = {
  list: () => wards,
  beds: getBeds,
};

export const bedService = {
  list: getBeds,
  available: availableBeds,
};

export const gloveService = {
  list: getGloves,
};

export const monitoringService = {
  createDataSource(patients: Patient[]): MonitoringDataSource {
    return new DemoSensorAdapter(patients);
  },
};

export const reportService = {
  create: clinicalService.createReport,
  list: clinicalService.reports,
  download: exportReport,
};

export const timelineService = {
  getPatientTimeline: getTimeline,
  initializePatientEvents: ensurePatientTimeline,
  recordMonitoringStarted: startMonitoringTimeline,
};
