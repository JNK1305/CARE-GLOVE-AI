export type Role = "admin" | "senior" | "duty";

export type User = {
  id: string;
  name: string;
  role: Role;
  hospital?: string;
  specialty?: string;
  contact?: string;
  employeeId?: string;
};

export type Doctor = {
  id: string;
  name: string;
  email: string;
  role: Role;
  hospital: string;
  specialty: string;
  contact?: string;
  employeeId?: string;
  active: boolean;
  activePatientsCount: number;
};

export type DemoSession = User;

export type WardName = "ICU" | "NICU" | "Special Ward" | "General Ward";

export type Patient = {
  id: string;
  name: string;
  age: string;
  ward: WardName;
  bed: string;
  bedId: string;
  glove: string;
  doctor: string;
  doctorId: string;
  status: "Stable" | "Watch" | "Critical";
  bpm: number;
  temp: number;
  gsr: number;
  authorized: boolean;
  gender?: string;
  contact?: string;
  emergencyContact?: string;
  bloodGroup?: string;
  condition?: string;
  admissionDate?: string;
  admissionTime?: string;
  lifecycle?: "ADMITTED" | "ACTIVE" | "DISCHARGED";
  monitoringStatus?: "READY" | "ACTIVE" | "STALE" | "DISCONNECTED" | "INSUFFICIENT_DATA" | "MODEL_ERROR" | "DISCHARGED";
  hospital?: string;
};

export type Ward = {
  id: WardName;
  name: WardName;
};

export type Bed = {
  id: string;
  wardId: WardName;
  label: string;
  gloveId: string;
};

export type Glove = {
  id: string;
  bedId: string;
  wardId: WardName;
  status: "AVAILABLE" | "ASSIGNED" | "DEMO ACTIVE" | "DISCONNECTED";
  maintenanceNote?: string;
};

export type SeniorDoctorAssignment = {
  patientId: string;
  doctorId: string;
  doctorName: string;
};

export type MonitoringParameter =
  | "BPM"
  | "Temperature"
  | "GSR"
  | "MULTIVARIATE"
  | "heart_rate"
  | "temperature"
  | "gsr";

export type MonitoringReading = {
  id: string;
  patientId: string;
  gloveId: string;
  parameter: MonitoringParameter;
  value: number;
  timestamp: string;
  source: string;
};

export type MonitoringSample = {
  id: string;
  patientId: string;
  gloveId: string;
  timestamp: string;
  bpm: number;
  temperature: number;
  gsr: number;
  source?: string;
  state?: string;
  signalQuality?: number;
  firmwareVersion?: string;
};

export type AlertSeverity = "WARNING" | "CRITICAL";
export type AlertStatus = "OPEN" | "ACKNOWLEDGED" | "RESOLVED";

export type Alert = {
  id: string;
  patientId: string;
  gloveId: string;
  parameter: MonitoringParameter | string;
  value: number;
  threshold?: number | null;
  severity: AlertSeverity;
  timestamp: string;
  source: "DEMO_SENSOR" | "HARDWARE" | string;
  status: AlertStatus;
  explanation?: string;
  trigger?: string;
  monitoringState?: string;
  acknowledgedAt?: string | null;
  acknowledgedBy?: string | null;
  resolvedAt?: string | null;
  createdAt?: string;
  heartRate?: number | null;
  gsr?: number | null;
  temperature?: number | null;
  modelVersion?: string | null;
  modelScore?: number | null;
  deduplicationKey?: string | null;
};

export type Notification = {
  id: string;
  alertId: string;
  patientId: string;
  severity: AlertSeverity;
  parameter: MonitoringParameter | string;
  value: number;
  timestamp: string;
  read: boolean;
};

export type Report = {
  id: string;
  patientId: string;
  authorId: string;
  authorName: string;
  timestamp: string;
  title: string;
  content: string;
};

export type TestResult = {
  id: string;
  patientId: string;
  authorId: string;
  authorName: string;
  timestamp: string;
  testName: string;
  result: string;
};
