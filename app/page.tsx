"use client";

import { type ReactNode, useCallback, useEffect, useState } from "react";
import {
  BackendSensorAdapter,
  DemoSensorAdapter,
  type MonitoringDataSource,
  type MonitoringReading,
  type MonitoringSample,
  type MonitoringSnapshot,
  monitoringSourceLabel,
} from "../lib/monitoring";
import { api, API_BASE } from "../lib/api";
import { PatientAlertTimeline } from "../components/patient-alert-timeline";
import { ClinicalInstructions } from "../components/clinical-instructions";
import type { Patient, Role, Report, MonitoringParameter, WardName } from "../lib/domain";
import {
  describeVitalRangeStatus,
  vitalRangeLabel,
} from "../lib/clinical";
import type { ClinicalRecord } from "../lib/clinical";
import {
  alertService,
  authService,
  availableBeds,
  canAccessPatient,
  createPatient,
  dischargePatient,
  ensurePatientTimeline,
  exportReport,
  getBeds,
  getGloves,
  listAlertsForPatient,
  listNotificationsForPatients,
  loadPatients,
  notificationService,
  patientsVisibleToRole,
  startMonitoringTimeline,
  timelineService,
  updatePatient,
  wards,
  clinicalService,
  gloveIdFor,
} from "../lib/services";
import {
  Activity,
  AlertTriangle,
  Bell,
  BedDouble,
  CheckCircle2,
  ChevronRight,
  ClipboardList,
  Command,
  FileText,
  HeartPulse,
  LayoutDashboard,
  LogOut,
  Menu,
  MessageSquare,
  Pencil,
  Plus,
  Search,
  ShieldCheck,
  Stethoscope,
  TestTube2,
  Thermometer,
  Users,
  Watch,
  X,
  type LucideIcon,
} from "lucide-react";

const patientsSeed: Patient[] = [
  {
    id: "CG-1042",
    name: "Rahul Patil",
    age: "54 yrs",
    ward: "ICU",
    bed: "B-02",
    bedId: "ICU:B-02",
    glove: "GLV-ICU-002",
    doctor: "Dr. Amit Sharma",
    doctorId: "sharma",
    status: "Critical",
    bpm: 96,
    temp: 37.4,
    gsr: 5,
    authorized: true,
  },
  {
    id: "CG-B2CC11AC158A",
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
  {
    id: "CG-1044",
    name: "Imran Shaikh",
    age: "61 yrs",
    ward: "ICU",
    bed: "B-03",
    bedId: "ICU:B-03",
    glove: "GLV-ICU-003",
    doctor: "Dr. Priya Patil",
    doctorId: "patil",
    status: "Stable",
    bpm: 78,
    temp: 36.8,
    gsr: 4.2,
    authorized: true,
  },
  {
    id: "CG-1045",
    name: "Baby of Priya N.",
    age: "2 days",
    ward: "NICU",
    bed: "B-01",
    bedId: "NICU:B-01",
    glove: "GLV-NICU-001",
    doctor: "Dr. Priya Patil",
    doctorId: "patil",
    status: "Stable",
    bpm: 122,
    temp: 36.7,
    gsr: 2.2,
    authorized: true,
  },
  {
    id: "CG-1046",
    name: "Kavita More",
    age: "38 yrs",
    ward: "Special Ward",
    bed: "B-01",
    bedId: "Special Ward:B-01",
    glove: "GLV-SPL-001",
    doctor: "Dr. Amit Sharma",
    doctorId: "sharma",
    status: "Stable",
    bpm: 72,
    temp: 36.6,
    gsr: 3.6,
    authorized: true,
  },
];
const roleInfo = {
  admin: {
    label: "ADMIN",
    name: "Meera Kulkarni",
    subtitle: "System administrator",
  },
  senior: {
    label: "SENIOR DOCTOR",
    name: "Dr. Amit Sharma",
    subtitle: "Senior doctor",
  },
  duty: {
    label: "ON-DUTY STAFF",
    name: "Anita Joshi",
    subtitle: "On-duty staff / doctor",
  },
};
const navByRole = {
  admin: [
    ["Overview", "overview", LayoutDashboard],
    ["Patients", "patients", Users],
    ["Wards & beds", "wards", BedDouble],
    ["Gloves", "gloves", Watch],
    ["Doctors", "doctors", Stethoscope],
    ["Live Monitoring", "monitoring", Activity],
    ["Alerts", "alerts", AlertTriangle],
    ["Notifications", "notifications", Bell],
    ["Reports", "reports", ClipboardList],
  ],
  senior: [
    ["Dashboard", "overview", LayoutDashboard],
    ["My Patients", "patients", Users],
    ["Live Monitoring", "monitoring", Activity],
    ["Alerts", "alerts", AlertTriangle],
    ["Notifications", "notifications", Bell],
    ["Reports", "reports", ClipboardList],
    ["Doctor Notes", "notes", MessageSquare],
  ],
  duty: [
    ["Dashboard", "overview", LayoutDashboard],
    ["Patients", "patients", Users],
    ["Live Monitoring", "monitoring", Activity],
    ["Alerts", "alerts", AlertTriangle],
    ["Notifications", "notifications", Bell],
    ["Reports", "reports", ClipboardList],
    ["Patient Reports / Tests", "tests", TestTube2],
  ],
} as const;

function routeFor(role: Role, page: string, patient?: string) {
  return `/${role === "senior" ? "doctor" : role}${patient ? `/patients/${patient}` : page === "overview" ? "" : `/${page}`}`;
}
function go(role: Role, page: string, patient?: string) {
  history.pushState({}, "", routeFor(role, page, patient));
  window.dispatchEvent(new PopStateEvent("popstate"));
}
function status(status: Patient["status"]) {
  return (
    <span className={`status status-${status.toLowerCase()}`}>
      <i className="status-dot" />
      {status}
    </span>
  );
}
function Metric({
  icon: Icon,
  label,
  value,
  detail,
  onClick,
}: {
  icon: LucideIcon;
  label: string;
  value: string | number;
  detail: string;
  onClick?: () => void;
}) {
  return (
    <button className="metric-card" onClick={onClick}>
      <div className="metric-icon metric-teal">
        <Icon size={18} />
      </div>
      <div className="metric-copy">
        <span>{label}</span>
        <strong>{value}</strong>
        <small>{detail}</small>
      </div>
      <ChevronRight className="metric-arrow" size={17} />
    </button>
  );
}
function Header({
  role,
  title,
  search,
  setSearch,
  onLogout,
  notificationCount,
  onMenu,
  onNotifications,
}: {
  role: Role;
  title: string;
  search: string;
  setSearch: (value: string) => void;
  onLogout: () => void;
  notificationCount: number;
  onMenu: () => void;
  onNotifications: () => void;
}) {
  const info = roleInfo[role];
  return (
    <header className="topbar">
      <button
        className="icon-btn mobile-menu"
        onClick={onMenu}
        aria-label="Open navigation"
        aria-expanded={false}
      >
        <Menu size={18} />
      </button>
      <div className="breadcrumbs">
        <span>CARE HOSPITAL</span>
        <ChevronRight size={14} />
        <b>{title.toUpperCase()}</b>
      </div>
      <div className="top-actions">
        <div className="searchbox">
          <Search size={16} />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search patients, beds, gloves..."
          />
        </div>
        <span className="live-pill">
          <i /> DEMO
        </span>
        <button
          className="icon-btn notification-btn"
          onClick={onNotifications}
          aria-label="Notifications"
        >
          <Bell size={18} />
          {notificationCount > 0 && <i>{notificationCount}</i>}
        </button>
        <div className="profile">
          <div className="avatar">
            {info.name
              .split(" ")
              .map((x) => x[0])
              .join("")
              .slice(0, 2)}
          </div>
          <div>
            <b>{info.name}</b>
            <span>{info.label}</span>
          </div>
          <button className="icon-btn" onClick={onLogout} aria-label="Logout">
            <LogOut size={16} />
          </button>
        </div>
      </div>
    </header>
  );
}
function Sidebar({
  role,
  page,
  setPage,
  open,
  close,
  alertCount,
}: {
  role: Role;
  page: string;
  setPage: (page: string) => void;
  open: boolean;
  close: () => void;
  alertCount: number;
}) {
  return (
    <>
    {open && <button className="sidebar-scrim" aria-label="Close navigation" onClick={close} />}
    <aside className={`sidebar ${open ? "sidebar-open" : ""}`}>
      <div className="brand">
        <div className="brand-mark">
          <Command size={20} />
        </div>
        <div>
          <b>CARE-GLOVE</b>
          <span>AI monitoring system</span>
        </div>
      </div>
      <div className="facility">
        <span className="facility-kicker">CARE HOSPITAL</span>
        <span className="facility-name">Clinical operations</span>
        <span className="online">
          <i /> Demo mode active
        </span>
      </div>
      <div className="nav-label">
        {role === "admin"
          ? "SYSTEM MANAGEMENT"
          : role === "senior"
            ? "CLINICAL WORKSPACE"
            : "OPERATIONS"}
      </div>
      <nav>
        {navByRole[role].map(([label, key, Icon]) => (
          <button
            key={key}
            className={`nav-item ${page === key ? "active" : ""}`}
            onClick={() => {
              setPage(key);
              close();
            }}
          >
            <Icon size={17} />
            <span>{label}</span>
            {key === "alerts" && alertCount > 0 && <b>{alertCount}</b>}
          </button>
        ))}
      </nav>
      <div className="sidebar-bottom">
        <div className="prototype-note">
          <ShieldCheck size={15} />
          <span>
            Research prototype
            <br />
            <small>Demo thresholds · not clinically validated</small>
          </span>
        </div>
      </div>
    </aside>
    </>
  );
}
function PageHeader({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="page-header">
      <div>
        <div className="eyebrow">{eyebrow}</div>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {action}
    </div>
  );
}
function PatientRows({
  patients,
  onPatient,
  monitoring,
}: {
  patients: Patient[];
  onPatient: (p: Patient) => void;
  monitoring?: MonitoringSnapshot;
}) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Patient</th>
            <th>Ward / Bed</th>
            <th>Glove</th>
            <th>BPM</th>
            <th>Temperature</th>
            <th>GSR</th>
            <th>Alert</th>
            <th>Reading timestamp</th>
            <th>Source</th>
          </tr>
        </thead>
        <tbody>
          {patients.map((p) => {
            const reading = monitoring?.latestReadings[p.id];
            const patientReading =
              reading?.patientId === p.id && (!p.glove || !reading.gloveId || reading.gloveId === p.glove)
                ? reading
                : undefined;
            const patientDevStatus =
              (p.glove ? monitoring?.deviceStatus[p.glove] : undefined) ||
              monitoring?.deviceStatus[p.id] ||
              (patientReading ? "STALE" : "DISCONNECTED");
            return (
              <tr key={p.id} onClick={() => onPatient(p)}>
              <td>
                <div className="patient-cell">
                  <div className="patient-avatar">
                    {p.name
                      .split(" ")
                      .map((n) => n[0])
                      .join("")
                      .slice(0, 2)}
                  </div>
                  <div>
                    <b>{p.name}</b>
                    <span>
                      {p.id} · {p.age}
                    </span>
                  </div>
                </div>
              </td>
              <td>
                <b>{p.ward}</b>
                <span>Bed {p.bed}</span>
              </td>
              <td>{p.glove}</td>
              <td>{patientReading ? patientReading.bpm : (p.bpm != null ? p.bpm : "—")}</td>
              <td>{patientReading ? patientReading.temperature : (p.temp != null ? p.temp : "—")}°C</td>
              <td>{patientReading ? patientReading.gsr : (p.gsr != null ? p.gsr : "—")} µS</td>
              <td>{status(p.status)}</td>
              <td>{patientReading ? `${new Date(patientReading.timestamp).toLocaleDateString()} ${new Date(patientReading.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })} (${patientDevStatus})` : "No stored reading"}</td>
              <td>{patientReading ? monitoringSourceLabel(monitoring?.source, patientReading.source) : "—"}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
function Login({ onLogin }: { onLogin: (role: Role) => void }) {
  const [role, setRole] = useState<Role>("admin");
  const [email, setEmail] = useState("admin@careglove.demo");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const credentials: Record<Role, string> = {
    admin: "admin@careglove.demo",
    senior: "doctor@careglove.demo",
    duty: "staff@careglove.demo",
  };
  const selectRole = (next: Role) => {
    setRole(next);
    setEmail(credentials[next]);
    setError("");
  };
  const submit = async () => {
    if (!email.trim() || !password.trim()) {
      setError("Enter your email and password to continue.");
      return;
    }
    try {
      const backendUp = await api.isBackendReachable();
      if (backendUp) {
        const authRes = await api.login(role, email.trim(), password.trim());
        authService.saveSession({
          id: authRes.user.id,
          name: authRes.user.name,
          role: authRes.user.role,
          token: authRes.token,
        });
      } else {
        setError(`Backend service is unreachable at ${API_BASE}. Ensure the Spring Boot backend is running on port 8080.`);
        return;
      }
    } catch (authenticationError) {
      setError(
        authenticationError instanceof Error
          ? authenticationError.message
          : "The sign-in request could not be completed.",
      );
      return;
    }
    onLogin(role);
  };
  return (
    <main className="login-shell">
      <section className="login-visual">
        <div className="login-visual-inner">
          <div className="brand login-brand">
            <div className="brand-mark">
              <Command size={22} />
            </div>
            <div>
              <b>CARE-GLOVE AI</b>
              <span>Smart wearable physiological monitoring system</span>
            </div>
          </div>
          <div className="login-visual-copy">
          <div className="eyebrow">CLINICAL TELEMETRY</div>
          <h1>Real-time wearable physiological monitoring.</h1>
            <p>
            Continuous clinical telemetry from smart sensor gloves via Spring Boot backend and Firebase RTDB.
          </p>
          </div>
          <div className="signal-card">
          <div className="signal-orbit">
            <HeartPulse size={22} />
          </div>
          <div>
            <b>Live telemetry active</b>
            <span>Connected to backend & MySQL · Port 8080</span>
          </div>
          <i />
          </div>
          <div className="login-disclaimer">
          <ShieldCheck size={15} /> Research prototype · demo thresholds are
          not clinically validated
          </div>
        </div>
      </section>
      <section className="login-panel">
        <div className="login-card">
          <div className="eyebrow">DEMO ENTRY</div>
          <h2>Welcome to CARE-GLOVE AI</h2>
          <p className="login-copy">
          Sign in to the demo monitoring workspace.
          </p>
          <div className="role-picker" aria-label="Select your role">
            {(["admin", "senior", "duty"] as Role[]).map((r) => (
              <button
                type="button"
                key={r}
                className={role === r ? "selected" : ""}
                onClick={() => selectRole(r)}
                aria-pressed={role === r}
              >
                <strong>{roleInfo[r].label}</strong>
                <span>
                  {r === "admin"
                    ? "System management and monitoring"
                    : r === "senior"
                      ? "Patient monitoring and clinical instructions"
                      : "Operational monitoring and patient support"}
                </span>
              </button>
            ))}
          </div>
          <label>
            Email / Username
            <input
              value={email}
              onChange={(e) => {
                setEmail(e.target.value);
                setError("");
              }}
              autoComplete="username"
            />
          </label>
          <label>
            Password
            <input
              type="password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                setError("");
              }}
              autoComplete="current-password"
              onKeyDown={(e) => {
                if (
                  e.key === "Enter" &&
                  !e.nativeEvent.isComposing &&
                  e.keyCode !== 229
                )
                  submit();
              }}
            />
          </label>
          {error && (
            <div className="login-error" role="alert">
              {error}
            </div>
          )}
          <button
            type="button"
            className="button primary login-button"
            onClick={submit}
          >
            Sign in to workspace <ChevronRight size={16} />
          </button>
          <div className="demo-credentials">
            <b>Demo access</b>
            <span>Selected role: {roleInfo[role].label}</span>
            <small>Demo credentials are for local simulation only. Production authentication requires a backend and session layer.</small>
          </div>
        </div>
      </section>
    </main>
  );
}
function withLatestReadings(patients: Patient[], monitoring: MonitoringSnapshot) {
  const alertsByPatient = new Map<string, ReturnType<typeof alertService.list>>();
  alertService.list().forEach((alert) => {
    if (alert.status !== "OPEN") return;
    const current = alertsByPatient.get(alert.patientId) || [];
    alertsByPatient.set(alert.patientId, [...current, alert]);
  });
  return patients.map((patient) => {
    const reading = monitoring.latestReadings[patient.id];
    const devStatus = (patient.glove ? monitoring.deviceStatus[patient.glove] : undefined) || monitoring.deviceStatus[patient.id];
    if (!reading || reading.patientId !== patient.id) {
      if (devStatus) {
        return {
          ...patient,
          monitoringStatus: (devStatus === "LIVE / CONNECTED" ? "ACTIVE" : devStatus === "STALE" ? "STALE" : devStatus === "DISCONNECTED" ? "DISCONNECTED" : patient.monitoringStatus) as Patient["monitoringStatus"],
        };
      }
      return patient;
    }
    const alerts = alertsByPatient.get(patient.id) || [];
    const severity = alerts.some((alert) => alert.severity === "CRITICAL")
      ? "Critical"
      : alerts.length
        ? "Watch"
        : patient.status || "Stable";
    return {
      ...patient,
      bpm: reading.bpm,
      temp: reading.temperature,
      gsr: reading.gsr,
      status: severity as Patient["status"],
      monitoringStatus: (devStatus === "LIVE / CONNECTED" ? "ACTIVE" : devStatus === "STALE" ? "STALE" : devStatus === "DISCONNECTED" ? "DISCONNECTED" : patient.monitoringStatus || "ACTIVE") as Patient["monitoringStatus"],
    };
  });
}

function Dashboard({
  role,
  patients,
  setPage,
  onPatient,
  onAdd,
  monitoring,
}: {
  role: Role;
  patients: Patient[];
  setPage: (page: string) => void;
  onPatient: (patient: Patient) => void;
  onAdd: () => void;
  monitoring: MonitoringSnapshot;
}) {
  const visible = patientsVisibleToRole(role, patients, authService.restore()?.id)
    .filter((patient) => patient.lifecycle !== "DISCHARGED");
  const title =
    role === "admin"
      ? "System command center"
      : role === "senior"
        ? "My patient workspace"
        : "Operational monitoring";
  const desc =
    role === "admin"
      ? "Manage the entire CARE-GLOVE system across every ward."
      : role === "senior"
        ? "Monitor and manage my assigned patients."
        : "Monitor patients and respond to operational alerts.";
  const beds = getBeds(patients);
  const activePatients = patients.filter(
    (patient: Patient) => patient.lifecycle !== "DISCHARGED",
  );
  const activeVisiblePatients = visible.filter(
    (patient) => patient.lifecycle !== "DISCHARGED",
  );
  const occupiedBeds = activePatients.length;
  const openAlerts = alertService
    .list()
    .filter(
      (alert) =>
        alert.status === "OPEN" &&
        (role === "admin" ||
          visible.some((patient: Patient) => patient.id === alert.patientId)),
    );
  const criticalAlerts = openAlerts.filter((alert) => alert.severity === "CRITICAL");
  const warningAlerts = openAlerts.filter((alert) => alert.severity === "WARNING");
  const recentActivity = visible
    .flatMap((patient) =>
      timelineService.getPatientTimeline(patient.id).map((event) => ({
        event,
        patient,
      })),
    )
    .sort((a, b) => b.event.timestamp.localeCompare(a.event.timestamp))
    .slice(0, 5);
  const gloves = getGloves(patients);
  const dashboardDate = new Date().toLocaleDateString("en-GB", {
    weekday: "long",
    day: "2-digit",
    month: "long",
    year: "numeric",
  });
  return (
    <>
      <PageHeader
        eyebrow={dashboardDate.toUpperCase()}
        title={title}
        description={desc}
        action={
          role === "admin" && (
            <button className="button primary" onClick={onAdd}>
              <Plus size={16} /> Add patient
            </button>
          )
        }
      />
      <div className="demo-banner">
        <ShieldCheck size={17} />
        <div>
          <b>Research monitoring prototype</b>
          <span>
            Demo thresholds are configurable and not clinically validated.
          </span>
        </div>
      </div>
      <section className="metric-grid">
        {role === "admin" ? (
          <>
            <Metric
              icon={BedDouble}
              label="Total beds"
              value={beds.length}
              detail={`${occupiedBeds} occupied · ${beds.length - occupiedBeds} available`}
              onClick={() => setPage("wards")}
            />
            <Metric
              icon={Users}
              label="Active patients"
              value={activePatients.length}
              detail="All wards"
              onClick={() => setPage("patients")}
            />
            <Metric
              icon={Watch}
              label="Connected gloves"
              value={gloves.filter((glove) => glove.status !== "AVAILABLE").length}
              detail={`${gloves.filter((glove) => glove.status === "DEMO ACTIVE").length} demo active`}
              onClick={() => setPage("gloves")}
            />
            <Metric
              icon={AlertTriangle}
              label="Active alerts"
              value={openAlerts.length}
              detail={`${openAlerts.filter((alert) => alert.severity === "CRITICAL").length} critical · ${openAlerts.filter((alert) => alert.severity === "WARNING").length} warning`}
              onClick={() => setPage("alerts")}
            />
          </>
        ) : (
          <>
            <Metric
              icon={Users}
              label={role === "senior" ? "Active patients" : "Patients under monitoring"}
              value={role === "senior" ? activeVisiblePatients.length : visible.length}
              detail={role === "senior" ? "Assigned to your care" : "Authorized records"}
              onClick={() => setPage("patients")}
            />
            <Metric
              icon={HeartPulse}
              label="Patients with readings"
              value={activeVisiblePatients.filter((patient) => {
                const reading = monitoring.latestReadings[patient.id];
                return reading?.patientId === patient.id && reading.gloveId === patient.glove;
              }).length}
              detail={`${activeVisiblePatients.length} active patients`}
              onClick={() => setPage("monitoring")}
            />
            <Metric
              icon={AlertTriangle}
              label={role === "senior" ? "Open alerts" : "Critical alerts"}
              value={role === "senior" ? openAlerts.length : visible.filter((p: Patient) => p.status === "Critical").length}
              detail={role === "senior" ? `${criticalAlerts.length} critical · ${warningAlerts.length} warning` : "Requires attention"}
              onClick={() => setPage("alerts")}
            />
            <Metric
              icon={Watch}
              label="Assigned wards"
              value={new Set(activeVisiblePatients.map((patient) => patient.ward)).size}
              detail={`${activeVisiblePatients.length} active patients`}
            />
          </>
        )}
      </section>
      <section className="panel table-panel">
        <div className="panel-heading">
          <div>
            <span className="eyebrow">
              {role === "admin"
                ? "ALL PATIENTS"
                : role === "senior"
                  ? "ASSIGNED PATIENTS"
                  : "OPERATIONAL QUEUE"}
            </span>
            <h2>
              {role === "admin"
                ? "Patients under monitoring"
                : role === "senior"
                  ? "My Patients"
                  : "Patients Under Monitoring"}
            </h2>
          </div>
          <button className="text-button" onClick={() => setPage("patients")}>
            Open full list <ChevronRight size={14} />
          </button>
        </div>
        <PatientRows patients={visible} onPatient={onPatient} monitoring={monitoring} />
      </section>
      {role === "senior" && (
        <section className="panel notification-list" aria-label="Recent patient activity">
          <div className="panel-heading">
            <div>
              <span className="eyebrow">CARE TEAM TIMELINE</span>
              <h2>Recent patient activity</h2>
            </div>
          </div>
          {recentActivity.length ? (
            recentActivity.map(({ event, patient }) => (
              <button key={event.id} onClick={() => onPatient(patient)}>
                {event.type === "ALERT" ? <AlertTriangle size={17} /> : <Activity size={17} />}
                <div>
                  <b>{patient.name} · {event.description}</b>
                  <span>{patient.id} · {patient.ward} · {event.actor}</span>
                  <small>{new Date(event.timestamp).toLocaleString()}</small>
                </div>
                <ChevronRight size={15} />
              </button>
            ))
          ) : (
            <p className="empty-copy">No patient activity has been recorded.</p>
          )}
        </section>
      )}
    </>
  );
}
function Detail({
  patient: p,
  role,
  back,
  reading,
  history,
  source,
  alerts,
  deviceStatus,
  onEdit,
  onAssign,
  onDischarge,
  onReport,
  onSaved,
  openMonitoring,
}: {
  patient: Patient;
  role: Role;
  back: () => void;
  reading?: MonitoringSample;
  history: MonitoringReading[];
  source: MonitoringSnapshot["source"];
  alerts: ReturnType<typeof alertService.list>;
  deviceStatus: string;
  onEdit: () => void;
  onAssign: () => void;
  onDischarge: () => void;
  onReport: () => void;
  onSaved: () => void;
  openMonitoring: () => void;
}) {
  const current = reading;
  const demographics = [
    ["Gender", p.gender],
    ["Contact", p.contact],
    ["Emergency contact", p.emergencyContact],
    ["Blood group", p.bloodGroup],
    ["Condition", p.condition],
    ["Admission", [p.admissionDate, p.admissionTime].filter(Boolean).join(" · ")],
  ].filter((entry): entry is [string, string] => Boolean(entry[1]));
  return (
    <>
      <button className="back-link" onClick={back}>
        <ChevronRight size={16} className="back-icon" /> Back to patients
      </button>
      <PageHeader
        eyebrow="PATIENT PROFILE"
        title={p.name}
        description={`${p.id} · ${p.age} · ${p.ward} · Bed ${p.bed}`}
        action={
          <div className="header-actions">
            <button
              className="button secondary"
              onClick={onReport}
            >
              <FileText size={16} /> Create / download report
            </button>
            <button
              className="button primary"
              onClick={openMonitoring}
            >
              <Activity size={16} /> Open monitoring
            </button>
          </div>
        }
      />
      <div className="patient-hero">
        <div className="hero-identity">
          <div className="hero-avatar">
            {p.name
              .split(" ")
              .map((n) => n[0])
              .join("")
              .slice(0, 2)}
          </div>
          <div>
            <span className="eyebrow">CURRENT LOCATION</span>
            <h2>
              {p.ward} <span>·</span> Bed {p.bed}
            </h2>
            <p>
              <Watch size={14} /> {p.glove} <span className="divider" />
              <Stethoscope size={14} /> {p.doctor}
            </p>
          </div>
        </div>
        <div className="header-actions">
          {status(p.status)}
          <span className="updated">{p.lifecycle || "ADMITTED"} · {p.monitoringStatus || "READY"}</span>
        </div>
      </div>
      {demographics.length > 0 && (
        <section className="panel table-panel">
          <div className="panel-heading">
            <div>
              <span className="eyebrow">PATIENT PROFILE</span>
              <h2>Demographics</h2>
            </div>
          </div>
          <div className="table-scroll">
            <table>
              <tbody>
                {demographics.map(([label, value]) => (
                  <tr key={label}>
                    <th>{label}</th>
                    <td>{value}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
      <section className="panel">
        <div className="panel-heading">
          <div>
            <span className="eyebrow">CURRENT VITALS</span>
            <h2>{deviceStatus === "LIVE / CONNECTED" ? "Live readings" : deviceStatus === "STALE" ? "Last known readings (Stale)" : "Current vitals"}</h2>
          </div>
          {current ? (() => {
            const readingDate = new Date(current.timestamp);
            const ageSeconds = Math.max(0, Math.floor((Date.now() - readingDate.getTime()) / 1000));
            const ageDisplay = ageSeconds < 60
              ? `${ageSeconds}s ago`
              : ageSeconds < 3600
                ? `${Math.floor(ageSeconds / 60)}m ago`
                : ageSeconds < 86400
                  ? `${Math.floor(ageSeconds / 3600)}h ago`
                  : `${Math.floor(ageSeconds / 86400)}d ago`;
            return (
              <span className="updated">
                <i /> {monitoringSourceLabel(source, current.source)} · {deviceStatus} ·{" "}
                {readingDate.toLocaleDateString()} {readingDate.toLocaleTimeString([], {
                  hour: "2-digit",
                  minute: "2-digit",
                  second: "2-digit",
                })} ({ageDisplay})
              </span>
            );
          })() : <span className="updated">Awaiting a stored reading</span>}
        </div>
        {current ? (
          <div className="vitals-grid">
            <Vital
              icon={HeartPulse}
              label="Heart rate"
              value={current.bpm}
              unit="BPM"
              status={vitalRangeLabel(describeVitalRangeStatus(current.bpm, { min: 50, max: 120, criticalMin: 40, criticalMax: 140 }))}
            />
            <Vital
              icon={Thermometer}
              label="Temperature"
              value={current.temperature}
              unit="°C"
              status={vitalRangeLabel(describeVitalRangeStatus(current.temperature, { min: 35.5, max: 38, criticalMin: 34, criticalMax: 39 }))}
            />
            <Vital
              icon={Watch}
              label="Skin conductance"
              value={current.gsr}
              unit="µS"
              status={vitalRangeLabel(describeVitalRangeStatus(current.gsr, { max: 10, criticalMax: 12 }))}
            />
          </div>
        ) : <p className="empty-copy">No sensor readings have been stored for this patient.</p>}
      </section>
      <PatientAlertTimeline patientId={p.id} />
      <section className="panel monitoring-history-panel">
        <div className="panel-heading">
          <div>
            <span className="eyebrow">MONITORING HISTORY</span>
            <h2>Stored readings by parameter</h2>
          </div>
          {(() => {
            const recordCount = new Set(history.map((r) => r.timestamp)).size;
            return <span className="updated">{recordCount} stored reading{recordCount === 1 ? "" : "s"} · {deviceStatus}</span>;
          })()}
        </div>
        <div className="history-chart-grid">
          <HistoryChart parameter="BPM" label="Heart rate" unit="BPM" history={history} />
          <HistoryChart parameter="Temperature" label="Temperature" unit="°C" history={history} />
          <HistoryChart parameter="GSR" label="Skin conductance" unit="µS" history={history} />
        </div>
        <ReadingLog readings={history} />
      </section>
      <section className="panel" aria-label="Current patient alerts">
        <div className="panel-heading">
          <div>
            <span className="eyebrow">CURRENT STATUS</span>
            <h2>Open alerts</h2>
          </div>
          <span className="updated">
            {alerts.filter((alert) => alert.patientId === p.id && alert.status === "OPEN").length} active
          </span>
        </div>
        {alerts
          .filter((alert) => alert.patientId === p.id && alert.status === "OPEN")
          .map((alert) => (
            <div className={`monitoring-alert monitoring-alert-${alert.severity.toLowerCase()}`} key={alert.id} role="status">
              <AlertTriangle size={16} />
              <div>
                <b>{alert.severity} · {alert.parameter} {alert.value}</b>
                <span>{new Date(alert.timestamp).toLocaleString()} · threshold {alert.threshold} · {alert.source}</span>
                {alert.explanation && <p style={{ margin: "4px 0 0", fontSize: "0.85rem", opacity: 0.9 }}>{alert.explanation}</p>}
              </div>
            </div>
          ))}
        {!alerts.some((alert) => alert.patientId === p.id && alert.status === "OPEN") && (
          <p className="empty-copy">No open alerts for this patient.</p>
        )}
        {alerts.some((alert) => alert.patientId === p.id && alert.status === "RESOLVED") && (
          <div style={{ marginTop: "1rem", borderTop: "1px solid var(--border)", paddingTop: "0.75rem" }}>
            <span className="eyebrow" style={{ display: "block", marginBottom: "0.5rem" }}>RECENTLY RESOLVED</span>
            {alerts
              .filter((alert) => alert.patientId === p.id && alert.status === "RESOLVED")
              .slice(0, 5)
              .map((alert) => (
                <div className="monitoring-alert monitoring-alert-resolved" key={alert.id} style={{ opacity: 0.8, marginBottom: "0.4rem" }}>
                  <CheckCircle2 size={16} />
                  <div>
                    <b>RESOLVED · {alert.parameter} ({alert.value})</b>
                    <span>{new Date(alert.timestamp).toLocaleString()} · threshold {alert.threshold}</span>
                  </div>
                </div>
              ))}
          </div>
        )}
      </section>
      <div className="detail-actions">
        {role === "admin" &&
          [
            ["Edit patient", onEdit],
            ["Assign / reassign", onAssign],
            ["Discharge patient", onDischarge],
          ].map(([x, action]) => (
              <button
                className="button secondary"
                key={x as string}
                onClick={action as () => void}
              >
                <Pencil size={15} />
                {x as string}
              </button>
          ))}
      </div>
      <ClinicalInstructions
        key={`${p.id}:${role}`}
        patientId={p.id}
        patientName={p.name}
        age={p.age}
        gender={p.gender}
        ward={p.ward}
        bed={p.bed}
        glove={p.glove}
        monitoringStatus={p.monitoringStatus === "ACTIVE" ? "ACTIVE" : "READY"}
        role={role}
        doctorId={p.doctorId}
        doctorName={p.doctor}
        onSaved={onSaved}
      />
    </>
  );
}
function Vital({
  icon: Icon,
  label,
  value,
  unit,
  status,
}: {
  icon: typeof HeartPulse;
  label: string;
  value: number;
  unit: string;
  status?: string;
}) {
  return (
    <div className="vital">
      <div className="vital-icon vital-teal">
        <Icon size={16} />
      </div>
      <div>
        <span>{label}</span>
        <strong>
          {value}
          <em>{unit}</em>
        </strong>
        <small>{status ?? "Range not configured"}</small>
      </div>
    </div>
  );
}
function HistoryChart({
  parameter,
  label,
  unit,
  history,
}: {
  parameter: MonitoringParameter;
  label: string;
  unit: string;
  history: MonitoringReading[];
}) {
  const readings = history
    .filter((reading) => reading.parameter === parameter)
    .sort((a, b) => a.timestamp.localeCompare(b.timestamp))
    .slice(-30);
  const values = readings.map((reading) => reading.value);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const points = readings
    .map((reading, index) => {
      const x = readings.length < 2 ? 50 : (index / (readings.length - 1)) * 100;
      const y =
        36 -
        ((reading.value - min) / (max - min || 1)) * 32;
      return `${x},${y}`;
    })
    .join(" ");
  const first = readings[0];
  const latest = readings.at(-1);
  return (
    <article className="history-chart">
      <div className="history-chart-heading">
        <div>
          <b>{label}</b>
          <span>{latest ? `${latest.value} ${unit}` : "No readings yet"}</span>
        </div>
        <small>{readings.length} stored readings</small>
      </div>
      {readings.length ? (
        <>
          <svg
            viewBox="0 0 100 40"
            preserveAspectRatio="none"
            role="img"
            aria-label={`${label} history based on ${readings.length} persisted readings`}
            data-parameter={parameter}
          >
            <line x1="0" y1="4" x2="100" y2="4" />
            <line x1="0" y1="20" x2="100" y2="20" />
            <line x1="0" y1="36" x2="100" y2="36" />
            <polyline points={points} />
          </svg>
          <div className="history-chart-axis">
            <time>{new Date(first!.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</time>
            <time>{new Date(latest!.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</time>
          </div>
          <small className="history-chart-source">
            Latest: {new Date(latest!.timestamp).toLocaleString()} · {latest!.source} · {latest!.gloveId}
          </small>
        </>
      ) : (
        <p className="empty-copy">Monitoring history will appear when a reading is stored.</p>
      )}
    </article>
  );
}
function ReadingLog({ readings }: { readings: MonitoringReading[] }) {
  const [expanded, setExpanded] = useState(false);
  const ordered = [...readings].sort((a, b) => b.timestamp.localeCompare(a.timestamp));
  const shown = expanded ? ordered : ordered.slice(0, 16);
  return (
    <div className="reading-log">
      <div className="panel-heading">
        <div>
          <span className="eyebrow">STORED SENSOR DATA</span>
          <h2>Recent readings</h2>
        </div>
        {ordered.length > 16 && (
          <button className="text-button" onClick={() => setExpanded((value) => !value)}>
            {expanded ? "Show recent" : `View all ${ordered.length}`}
          </button>
        )}
      </div>
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Patient</th>
              <th>Glove</th>
              <th>Parameter</th>
              <th>Value</th>
              <th>Timestamp</th>
              <th>Source</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((reading) => (
              <tr key={reading.id}>
                <td>{reading.patientId}</td>
                <td>{reading.gloveId}</td>
                <td>{reading.parameter}</td>
                <td>{reading.value}{reading.parameter === "Temperature" ? "°C" : reading.parameter === "GSR" ? " µS" : " BPM"}</td>
                <td>{new Date(reading.timestamp).toLocaleString()}</td>
                <td>{reading.source}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!ordered.length && <p className="empty-copy">No stored sensor readings yet.</p>}
    </div>
  );
}
function RecordList({
  records,
  patients,
  onPatient,
}: {
  records: ClinicalRecord[];
  patients: Patient[];
  onPatient: (patient: Patient) => void;
}) {
  const patientById = new Map(patients.map((patient) => [patient.id, patient]));
  return (
    <section className="panel table-panel">
      <div className="clinical-record-grid">
        {records.length ? (
          records.map((record) => (
            <button
              type="button"
              className="clinical-record clinical-record-button"
              key={record.id}
              onClick={() => {
                const patient = patientById.get(record.patientId);
                if (patient) onPatient(patient);
              }}
            >
              <span className="eyebrow">{record.type.replaceAll("_", " ")}</span>
              <b>{patientById.get(record.patientId)?.name || record.patientId}</b>
              <p>
                {record.content ||
                  (record.type === "TEST"
                    ? `${record.testName}: ${record.result}`
                    : `${record.medicineName} · ${record.dosage}`)}
              </p>
              <small>{record.actorName} · {new Date(record.timestamp).toLocaleString()}</small>
            </button>
          ))
        ) : (
          <p className="empty-copy">No clinical records have been created.</p>
        )}
      </div>
    </section>
  );
}
type WorkspacePageProps = {
  page: string;
  role: Role;
  patients: Patient[];
  reportPatient?: Patient | null;
  monitoringPatient?: Patient | null;
  onPatient: (patient: Patient) => void;
  onSelectReportPatient: (patientId: string) => void;
  search: string;
  onReadNotification: (id: string) => void;
  onMarkAllRead: () => void;
  onCreateReport: (patient: Patient) => void;
  monitoring: MonitoringSnapshot;
  wardRoute?: string | null;
  onWardSelect: (ward: WardName) => void;
};

function WorkspacePage({
  page,
  role,
  patients,
  reportPatient = null,
  monitoringPatient = null,
  onPatient,
  onSelectReportPatient,
  search,
  onReadNotification,
  onMarkAllRead,
  onCreateReport,
  monitoring,
  wardRoute,
  onWardSelect,
}: WorkspacePageProps) {
  const [selectedWard, setSelectedWard] = useState<string | null>(wardRoute ?? null);
  const visible = patientsVisibleToRole(role, patients, authService.restore()?.id);
  const filtered = visible.filter((p: Patient) =>
    `${p.name} ${p.ward} ${p.glove}`
      .toLowerCase()
      .includes(search.toLowerCase()),
  );
  const titles: Record<string, [string, string]> = {
    patients: ["Patient directory", "Review connected patient records."],
    monitoring: [
      "Live monitoring",
      "Review readings linked to assigned patient records.",
    ],
    alerts: ["Alerts", "Review readings that need attention."],
    notifications: [
      "Notifications",
      "Role-specific updates and monitoring events.",
    ],
    reports: ["Reports", "Patient reports and monitoring history."],
    notes: ["Doctor notes", "Clinical notes for assigned patients."],
    tests: [
      "Patient reports / tests",
      "Record operational reports and test history.",
    ],
    wards: ["Wards & beds", "Capacity, beds and patient assignment by ward."],
    gloves: ["Gloves", "Connected wearable inventory and assignments."],
    doctors: ["Doctors", "Care team assignments across the hospital."],
  };
  const [title, desc] = titles[page] || titles.patients;
  const visibleIds = new Set<string>(
    visible.map((patient: Patient) => patient.id),
  );
  const alerts = listAlertsForPatient(alertService.list(), visibleIds);
  const notifications = listNotificationsForPatients(
    notificationService.list(),
    visibleIds,
  ).sort((a, b) => b.timestamp.localeCompare(a.timestamp));
  const reports = clinicalService
    .reports()
    .filter((report) => visibleIds.has(report.patientId))
    .sort((a, b) => b.timestamp.localeCompare(a.timestamp));
  const clinicalRecords = clinicalService
    .list()
    .filter((record) => visibleIds.has(record.patientId))
    .sort((a, b) => b.timestamp.localeCompare(a.timestamp));
  const tests = clinicalService
    .tests()
    .filter((test) => visibleIds.has(test.patientId))
    .sort((a, b) => b.timestamp.localeCompare(a.timestamp));
  const beds = getBeds(patients);
  const gloves = getGloves(patients);
  const patientFor = (patientId: string) =>
    patients.find((patient: Patient) => patient.id === patientId);
  return (
    <>
      <PageHeader
        eyebrow="CARE OPERATIONS"
        title={title}
        description={desc}
        action={page === "notifications" && notifications.some((notification) => !notification.read) ? (
          <button className="button secondary" onClick={onMarkAllRead}>Mark all read</button>
        ) : page === "reports" ? (() => {
          return (
            <button
              className="button primary"
              onClick={() => {
                if (!reportPatient) return;
                onCreateReport(reportPatient);
              }}
              disabled={!reportPatient}
              title={reportPatient ? `Create a report for ${reportPatient.name}` : "Select a patient before creating a report"}
            >
              <FileText size={16} /> {reportPatient ? `Create report · ${reportPatient.name}` : "Select patient first"}
            </button>
          );
        })() : null}
      />
      {page === "wards" ? (
        selectedWard ? (
          <section className="panel table-panel">
            <button className="text-button" onClick={() => setSelectedWard(null)}>← All wards</button>
            <h2>{selectedWard} beds</h2>
            <div className="table-scroll"><table><thead><tr><th>Bed</th><th>Glove</th><th>Patient</th><th>Senior Doctor</th><th>Status</th></tr></thead><tbody>
              {beds.filter((bed) => bed.wardId === selectedWard).map((bed) => {
                const assigned = patients.find((patient: Patient) => patient.bedId === bed.id && patient.lifecycle !== "DISCHARGED");
                const device = gloves.find((glove) => glove.id === bed.gloveId);
                return <tr key={bed.id} onClick={() => assigned && onPatient(assigned)}><td>{bed.label}</td><td>{bed.gloveId}</td><td>{assigned?.name || "Available"}</td><td>{assigned?.doctor || "—"}</td><td>{device?.status || "AVAILABLE"}</td></tr>;
              })}
            </tbody></table></div>
          </section>
        ) : (
          <div className="ward-grid">
            {wards.map((ward) => {
              const wardBeds = beds.filter((bed) => bed.wardId === ward.id);
              const occupied = patients.filter((patient: Patient) => patient.ward === ward.id && patient.lifecycle !== "DISCHARGED").length;
              return <button className="ward-card ward-teal" key={ward.id} onClick={() => { setSelectedWard(ward.id); onWardSelect(ward.id); }}>
                <div className="ward-icon"><BedDouble size={18} /></div>
                <h3>{ward.name}</h3>
                <div className="ward-stats"><span><b>{occupied}</b> occupied</span><span><b>{wardBeds.length}</b> beds</span></div>
                <small>View beds and patients <ChevronRight size={14} /></small>
              </button>;
            })}
          </div>
        )
      ) : page === "alerts" ? (
        <section className="panel notification-list">
          {alerts.length ? alerts.map((alert) => {
            const patient = patientFor(alert.patientId);
            return <button key={alert.id} onClick={() => patient && onPatient(patient)}><AlertTriangle size={17}/><div><b>{alert.severity} · {alert.parameter} {alert.value}</b><span>{patient?.name || alert.patientId} · {alert.gloveId} · {alert.status}</span><small>{new Date(alert.timestamp).toLocaleString()} · threshold {alert.threshold} · {alert.source}</small>{alert.explanation && <small style={{ display: "block", color: "var(--muted)" }}>{alert.explanation}</small>}</div><ChevronRight size={15}/></button>;
          }) : <p className="empty-copy">No alerts for the patients in this workspace.</p>}
        </section>
      ) : page === "notifications" ? (
        <section className="panel notification-list">
          {notifications.length ? notifications.map((notification) => {
            const patient = patientFor(notification.patientId);
            return <button className={notification.read ? "notification-read" : "notification-unread"} key={notification.id} onClick={() => { onReadNotification(notification.id); if (patient) onPatient(patient); }}>
              <Bell size={16} />
              <div>
                <b>{notification.severity} · {notification.parameter} alert</b>
                <span>{patient?.name || notification.patientId} · {notification.value} · {patient?.ward} {patient?.bed}</span>
                <small>{new Date(notification.timestamp).toLocaleString()} · {notification.read ? "Read" : "Unread"}</small>
              </div>
              <ChevronRight size={15} />
            </button>;
          }) : <p className="empty-copy">No notifications for the patients in this workspace.</p>}
        </section>
      ) : page === "monitoring" ? (
        <>
          {monitoringPatient && (
            <section className="panel" aria-label="Selected patient monitoring">
              <div className="panel-heading">
                <div>
                  <span className="eyebrow">SELECTED PATIENT · {monitoringPatient.id}</span>
                  <h2>{monitoringPatient.name}</h2>
                  <p>{monitoringPatient.ward} · Bed {monitoringPatient.bed} · {monitoringPatient.glove}</p>
                  {status(monitoringPatient.status)}
                </div>
                <button className="button secondary" onClick={() => onPatient(monitoringPatient)}>View patient profile</button>
              </div>
              {(() => {
                const reading = monitoring.latestReadings[monitoringPatient.id];
                const statusInfo = monitoring.monitoringStatuses?.[monitoringPatient.id];
                const devState = monitoring.deviceStatus[monitoringPatient.glove] || "ASSIGNED";
                if (!reading || reading.patientId !== monitoringPatient.id || reading.gloveId !== monitoringPatient.glove) {
                  return (
                    <div style={{ padding: "1rem" }}>
                      <p className="empty-copy">No current reading is available for this patient and assigned glove.</p>
                      {statusInfo && statusInfo.message && (
                        <p className="muted-copy" style={{ marginTop: "0.5rem" }}>
                          Status: {statusInfo.monitoringStatus} · {statusInfo.message}
                        </p>
                      )}
                    </div>
                  );
                }
                const ageSec = Math.max(0, Math.floor((Date.now() - new Date(reading.timestamp).getTime()) / 1000));
                const ageLabel = ageSec < 60 ? `${ageSec}s ago` : `${Math.floor(ageSec / 60)}m ago`;
                return (
                  <>
                    <div className="panel-heading">
                      <span className="updated">
                        {monitoringSourceLabel(monitoring.source)} · {new Date(reading.timestamp).toLocaleTimeString()} ({ageLabel})
                      </span>
                      <span className={`updated ${devState === "LIVE / CONNECTED" ? "status-stable" : devState === "STALE" ? "status-watch" : "status-critical"}`}>
                        {devState}
                      </span>
                    </div>
                    {statusInfo && statusInfo.message && (
                      <div className="panel-heading" style={{ paddingTop: 0 }}>
                        <span className="muted-copy">
                          {statusInfo.message}
                          {statusInfo.latestPrediction?.reason ? ` · ${statusInfo.latestPrediction.reason}` : ""}
                        </span>
                      </div>
                    )}
                    <div className="vitals-grid">
                      <Vital icon={HeartPulse} label="Heart rate" value={reading.bpm} unit="BPM" />
                      <Vital icon={Thermometer} label="Temperature" value={reading.temperature} unit="°C" />
                      <Vital icon={Watch} label="Skin conductance" value={reading.gsr} unit="µS" />
                    </div>
                  </>
                );
              })()}
            </section>
          )}
          <section className="panel table-panel">
            <PatientRows
              patients={filtered.filter((patient: Patient) => patient.lifecycle !== "DISCHARGED")}
              onPatient={onPatient}
              monitoring={monitoring}
            />
          </section>
        </>
      ) : page === "reports" ? (
        <>
          <section className="panel">
            <label>
              Patient for new report
              <select
                value={reportPatient?.id || ""}
                onChange={(event) => onSelectReportPatient(event.target.value)}
              >
                <option value="">Select an assigned patient</option>
                {visible
                  .filter((patient) => patient.lifecycle !== "DISCHARGED")
                  .map((patient) => (
                    <option key={patient.id} value={patient.id}>
                      {patient.name} · {patient.id}
                    </option>
                  ))}
              </select>
            </label>
            <p className="muted-copy">The report patient is selected here and will not change when you search or filter the directory.</p>
          </section>
          <section className="panel table-panel">
            <div className="table-scroll"><table><thead><tr><th>Report</th><th>Patient</th><th>Author</th><th>Created</th><th>Action</th></tr></thead><tbody>
              {reports.map((report) => <tr key={report.id}><td>{report.title}<span>{report.id}</span></td><td>{patientFor(report.patientId)?.name || report.patientId}</td><td>{report.authorName}</td><td>{new Date(report.timestamp).toLocaleString()}</td><td><button className="text-button" onClick={() => exportReport(report)}>Download</button></td></tr>)}
            </tbody></table></div>
            {!reports.length && <p className="empty-copy">No patient reports have been created.</p>}
          </section>
        </>
      ) : page === "gloves" ? (
        <section className="panel table-panel">
          <div className="table-scroll"><table><thead><tr><th>Glove</th><th>Ward / Bed</th><th>Patient</th><th>Status</th><th>Last reading</th><th>Maintenance</th></tr></thead><tbody>
            {gloves.map((glove) => {
              const patient = patients.find((entry: Patient) => entry.bedId === glove.bedId && entry.lifecycle !== "DISCHARGED");
              const last = patient ? monitoring.latestReadings[patient.id] : undefined;
              return <tr key={glove.id} onClick={() => patient && onPatient(patient)}><td>{glove.id}</td><td>{glove.wardId} · {glove.bedId.split(":")[1]}</td><td>{patient?.name || "Available"}</td><td>{glove.status}</td><td>{last ? new Date(last.timestamp).toLocaleString() : "No reading yet"}</td><td>{glove.maintenanceNote || "No maintenance note recorded"}</td></tr>;
            })}
          </tbody></table></div>
        </section>
      ) : page === "notes" ? (
        <RecordList records={clinicalRecords} patients={patients} onPatient={onPatient} />
      ) : page === "tests" ? (
        <section className="panel table-panel">
          <div className="table-scroll"><table><thead><tr><th>Test</th><th>Patient</th><th>Result</th><th>Author</th><th>Timestamp</th></tr></thead><tbody>
            {tests.map((test) => {
              const patient = patientFor(test.patientId);
              return <tr key={test.id} onClick={() => { if (patient) onPatient(patient); }}><td>{test.testName}</td><td>{patient?.name || test.patientId}</td><td>{test.result}</td><td>{test.authorName}</td><td>{new Date(test.timestamp).toLocaleString()}</td></tr>;
            })}
          </tbody></table></div>
          {!tests.length && <p className="empty-copy">No test results have been recorded.</p>}
        </section>
      ) : ["patients", "doctors"].includes(page) ? (
        <section className="panel table-panel">
          <PatientRows
            patients={filtered.filter((patient: Patient) => patient.lifecycle !== "DISCHARGED")}
            onPatient={onPatient}
            monitoring={monitoring}
          />
        </section>
      ) : (
        <RecordList records={clinicalRecords} patients={patients} onPatient={onPatient} />
      )}
    </>
  );
}
const doctors = [
  { id: "sharma", name: "Dr. Amit Sharma" },
  { id: "patil", name: "Dr. Priya Patil" },
];
function nextPatientId(patients: Patient[]) {
  const next = patients.reduce((max, patient) => {
    const number = Number(patient.id.match(/^CG-(\d+)$/i)?.[1] || 0);
    return Math.max(max, number);
  }, 1041) + 1;
  return `CG-${next}`;
}
function AdmissionModal({
  patients,
  onClose,
  onSave,
}: {
  patients: Patient[];
  onClose: () => void;
  onSave: (patient: Patient) => void;
}) {
  const [form, setForm] = useState({
    name: "",
    id: nextPatientId(patients),
    age: "",
    gender: "",
    contact: "",
    emergencyContact: "",
    bloodGroup: "",
    date: new Date().toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" }),
    time: "10:30",
    condition: "",
    ward: "ICU" as WardName,
    bed: "",
    doctorId: "",
    errors: "",
  });
  const set = (key: string, value: string) =>
    setForm((prev) => ({ ...prev, [key]: value, errors: "" }));
  const bedChoices = availableBeds(patients, form.ward);
  const bed = bedChoices.some((candidate) => candidate.label === form.bed)
    ? form.bed
    : "";
  const glove = bed ? gloveIdFor(form.ward, bed) : "";
  const doctor = doctors.find((d) => d.id === form.doctorId);
  const submit = () => {
    const duplicate = patients.some(
      (p) => p.id.toLowerCase() === form.id.trim().toLowerCase(),
    );
    if (
      !form.name.trim() ||
      !form.id.trim() ||
      duplicate ||
      !form.age ||
      !Number.isInteger(Number(form.age)) ||
      Number(form.age) < 0 ||
      !form.ward ||
      !bed ||
      !doctor ||
      !form.condition.trim()
    ) {
      setForm((prev) => ({
        ...prev,
        errors: duplicate
          ? "Patient ID already exists. Use a unique ID."
          : "Complete the required patient and assignment fields.",
      }));
      return;
    }
    const patient: Patient = {
      id: form.id.trim(),
      name: form.name.trim(),
      age: `${form.age} yrs`,
      ward: form.ward,
      bed,
      bedId: `${form.ward}:${bed}`,
      glove,
      doctor: doctor.name,
      doctorId: doctor.id,
      status: "Stable",
      bpm: 78,
      temp: 36.8,
      gsr: 3.4,
      authorized: true,
      gender: form.gender,
      contact: form.contact,
      emergencyContact: form.emergencyContact,
      bloodGroup: form.bloodGroup,
      condition: form.condition.trim(),
      admissionDate: form.date,
      admissionTime: form.time,
      lifecycle: "ACTIVE",
      monitoringStatus: "READY",
    };
    try {
      onSave(patient);
    } catch (saveError) {
      setForm((prev) => ({
        ...prev,
        errors: saveError instanceof Error ? saveError.message : "Patient could not be registered.",
      }));
    }
  };
  return (
    <div className="modal-backdrop">
      <section
        className="modal admission-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="admission-title"
      >
        <div className="modal-heading">
          <div>
            <span className="eyebrow">PATIENT ADMISSION</span>
            <h2 id="admission-title">Register patient</h2>
            <p>Create one connected patient, bed, glove and doctor record.</p>
          </div>
          <button
            className="icon-btn"
            onClick={onClose}
            aria-label="Close registration"
          >
            <X size={18} />
          </button>
        </div>
        <div className="form-grid">
          <label>
            Full name *
            <input
              value={form.name}
              onChange={(e) => set("name", e.target.value)}
              placeholder="Rahul Patil"
            />
          </label>
          <label>
            Patient ID *
            <input
              value={form.id}
              onChange={(e) => set("id", e.target.value)}
            />
          </label>
          <label>
            Age *
            <input
              type="number"
              min="0"
              value={form.age}
              onChange={(e) => set("age", e.target.value)}
              placeholder="45"
            />
          </label>
          <label>
            Gender
            <select
              value={form.gender}
              onChange={(e) => set("gender", e.target.value)}
            >
              <option value="">Select gender</option>
              <option>Male</option>
              <option>Female</option>
              <option>Other</option>
            </select>
          </label>
          <label>
            Contact number
            <input
              value={form.contact}
              onChange={(e) => set("contact", e.target.value)}
              placeholder="+91 98765 43210"
            />
          </label>
          <label>
            Emergency contact
            <input
              value={form.emergencyContact}
              onChange={(e) => set("emergencyContact", e.target.value)}
            />
          </label>
          <label>
            Blood group
            <select
              value={form.bloodGroup}
              onChange={(e) => set("bloodGroup", e.target.value)}
            >
              <option value="">Select group</option>
              {["A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"].map((x) => (
                <option key={x}>{x}</option>
              ))}
            </select>
          </label>
          <label>
            Admission date
            <input
              value={form.date}
              onChange={(e) => set("date", e.target.value)}
            />
          </label>
          <label>
            Admission time
            <input
              type="time"
              value={form.time}
              onChange={(e) => set("time", e.target.value)}
            />
          </label>
          <label className="form-wide">
            Reason / clinical condition *
            <input
              value={form.condition}
              onChange={(e) => set("condition", e.target.value)}
              placeholder="Respiratory monitoring"
            />
          </label>
          <label>
            Ward *
            <select
              value={form.ward}
              onChange={(e) => {
                set("ward", e.target.value);
                set("bed", "");
              }}
            >
              {wards.map((ward) => (
                <option key={ward.id} value={ward.id}>{ward.name}</option>
              ))}
            </select>
          </label>
          <label>
            Available bed *
            <select value={bed} onChange={(e) => set("bed", e.target.value)}>
              <option value="">Select available bed</option>
              {bedChoices.map((candidate) => (
                <option key={candidate.id} value={candidate.label}>
                  {candidate.wardId}-{candidate.label} · {candidate.gloveId} · AVAILABLE
                </option>
              ))}
            </select>
          </label>
          <div className="assignment-summary form-wide">
            <span>Assigned glove</span>
            <strong>{glove || "Select an available bed"}</strong>
            <small>
              One bed = one glove · {glove ? "AVAILABLE" : "Waiting for bed"}
            </small>
          </div>
          <label className="form-wide">
            Senior doctor *
            <select
              value={form.doctorId}
              onChange={(e) => set("doctorId", e.target.value)}
            >
              <option value="">Select senior doctor</option>
              {doctors.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name}
                </option>
              ))}
            </select>
          </label>
        </div>
        {form.errors && (
          <div className="form-error">
            <AlertTriangle size={15} />
            {form.errors}
          </div>
        )}
        <div className="admission-review">
          <span className="eyebrow">CONFIRMATION SUMMARY</span>
          <div>
            <b>{form.name || "Patient name"}</b>
            <span>
              {form.ward} · {bed || "Bed pending"} · {glove || "Glove pending"}{" "}
              · {doctor?.name || "Doctor pending"}
            </span>
          </div>
        </div>
        <div className="modal-actions">
          <button className="button secondary" onClick={onClose}>
            Cancel
          </button>
          <button className="button primary" onClick={submit}>
            <CheckCircle2 size={16} /> Confirm admission
          </button>
        </div>
      </section>
    </div>
  );
}
function PatientManagementModal({
  mode,
  patient,
  patients,
  onClose,
  onSave,
}: {
  mode: "edit" | "assign";
  patient: Patient;
  patients: Patient[];
  onClose: () => void;
  onSave: (update: Partial<Patient>) => void;
}) {
  const [name, setName] = useState(patient.name);
  const [condition, setCondition] = useState(patient.condition || "");
  const [ward, setWard] = useState<WardName>(patient.ward);
  const [bed, setBed] = useState(patient.bed);
  const [doctorId, setDoctorId] = useState(patient.doctorId);
  const [authorized, setAuthorized] = useState(patient.authorized);
  const [error, setError] = useState("");
  const beds = availableBeds(patients.filter((entry) => entry.id !== patient.id), ward);
  const choices = beds.some((entry) => entry.label === patient.bed && ward === patient.ward)
    ? beds
    : [...beds, ...getBeds(patients).filter((entry) => entry.id === patient.bedId)];
  const doctor = doctors.find((entry) => entry.id === doctorId);
  const submit = () => {
    if (!name.trim() || !doctor || !choices.some((entry) => entry.label === bed)) {
      setError("Enter a patient name, available bed, and Senior Doctor.");
      return;
    }
    try {
      onSave({
        name: name.trim(),
        condition: condition.trim(),
        ward,
        bed,
        bedId: `${ward}:${bed}`,
        glove: gloveIdFor(ward, bed),
        doctorId: doctor.id,
        doctor: doctor.name,
        authorized,
      });
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : "Patient changes could not be saved.");
    }
  };
  return (
    <div className="modal-backdrop">
      <section className="modal admission-modal" role="dialog" aria-modal="true">
        <div className="modal-heading">
          <div>
            <span className="eyebrow">PATIENT MANAGEMENT</span>
            <h2>{mode === "edit" ? "Edit patient" : "Reassign patient"}</h2>
            <p>{patient.id} · {patient.name}</p>
          </div>
          <button className="icon-btn" onClick={onClose} aria-label="Close dialog"><X size={18} /></button>
        </div>
        <div className="form-grid">
          <label>Patient name *<input value={name} onChange={(event) => setName(event.target.value)} /></label>
          <label>Reason / condition<input value={condition} onChange={(event) => setCondition(event.target.value)} /></label>
          <label>Ward *<select value={ward} onChange={(event) => { setWard(event.target.value as WardName); setBed(""); }}>
            {wards.map((entry) => <option key={entry.id} value={entry.id}>{entry.name}</option>)}
          </select></label>
          <label>Available bed *<select value={bed} onChange={(event) => setBed(event.target.value)}>
            <option value="">Select available bed</option>
            {choices.map((entry) => <option key={entry.id} value={entry.label}>{entry.label} · {entry.gloveId}</option>)}
          </select></label>
          <div className="assignment-summary form-wide"><span>Assigned glove</span><strong>{bed ? gloveIdFor(ward, bed) : "Select an available bed"}</strong><small>One bed = one glove</small></div>
          <label className="form-wide">Senior Doctor *<select value={doctorId} onChange={(event) => setDoctorId(event.target.value)}>
            {doctors.map((entry) => <option key={entry.id} value={entry.id}>{entry.name}</option>)}
          </select></label>
          <label className="permission-toggle form-wide">
            <input type="checkbox" checked={authorized} onChange={(event) => setAuthorized(event.target.checked)} />
            Authorized for on-duty staff access
          </label>
        </div>
        {error && <p className="form-error" role="alert">{error}</p>}
        <div className="modal-actions">
          <button className="button secondary" onClick={onClose}>Cancel</button>
          <button className="button primary" onClick={submit}>{mode === "edit" ? "Save changes" : "Save assignment"}</button>
        </div>
      </section>
    </div>
  );
}
function LoadingShell() {
  return (
    <div
      style={{
        minHeight: "100vh",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        background: "var(--cream)",
        color: "var(--ink)",
        fontFamily: "var(--font-inter), ui-sans-serif, system-ui, sans-serif",
      }}
    >
      <div className="brand-mark" style={{ width: 48, height: 48, borderRadius: 12, marginBottom: 14 }}>
        <Command size={24} />
      </div>
      <div style={{ letterSpacing: "0.1em", fontSize: 13, fontWeight: 700, color: "var(--graphite)" }}>
        CARE-GLOVE AI
      </div>
      <div style={{ fontSize: 11, color: "var(--muted)", marginTop: 6 }}>
        Initializing monitoring workspace...
      </div>
    </div>
  );
}

function App() {
  const [initialized, setInitialized] = useState(false);
  const [loggedRole, setLoggedRole] = useState<Role | null>(null);
  const [monitoring, setMonitoring] = useState<MonitoringSnapshot>(() => ({
    latestReadings: {},
    history: {},
    deviceStatus: {},
    source: "FIREBASE_RTDB_LIVE",
  }));
  const [role, setRole] = useState<Role>("admin");
  const [page, setPageState] = useState("overview");
  const [selected, setSelected] = useState<Patient | null>(null);
  const [reportPatientId, setReportPatientId] = useState<string | null>(null);
  const [monitoringPatientId, setMonitoringPatientId] = useState<string | null>(null);
  const [selectedWard, setSelectedWard] = useState<WardName | null>(null);
  const [patients, setPatients] = useState<Patient[]>(patientsSeed);
  const [search, setSearch] = useState("");
  const [toast, setToast] = useState("");
  const [, setRevision] = useState(0);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  useEffect(() => {
    let activeSource: MonitoringDataSource | null = null;
    let isCancelled = false;

    async function setupMonitoring() {
      if (!loggedRole) return;
      const session = authService.restore();
      if (!session?.token) return;

      const isExplicitDemo = session?.token === "demo-admin-session" && (session as any)?.mode === "demo";

      if (isExplicitDemo) {
        const demoSource = new DemoSensorAdapter(patients);
        activeSource = demoSource;
        const sync = () => {
          const latestReadings: Record<string, MonitoringSample> = {};
          const history: Record<string, MonitoringReading[]> = {};
          const deviceStatus: MonitoringSnapshot["deviceStatus"] = {};
          patients.forEach((patient) => {
            const reading = demoSource.getLatestReading(patient.id);
            if (reading) {
              latestReadings[patient.id] = reading;
              alertService.evaluate(reading, "DEMO_SENSOR");
            }
            history[patient.id] = demoSource.getHistory(patient.id);
            deviceStatus[patient.glove] = "DEMO ACTIVE";
          });
          setMonitoring({ latestReadings, history, deviceStatus, source: "DEMO_SENSOR" });
        };
        sync();
        const unsubscribe = demoSource.subscribe((readings) => {
          if (isCancelled) return;
          const patientId = readings[0]?.patientId;
          if (!patientId) return;
          const sample = demoSource.getLatestReading(patientId);
          if (!sample) return;
          alertService.evaluate(sample, readings[0]!.source);
          const patient = patients.find((entry) => entry.id === patientId);
          if (patient) startMonitoringTimeline(patient);
          setMonitoring((previous) => ({
            ...previous,
            latestReadings: { ...previous.latestReadings, [patientId]: sample },
            history: { ...previous.history, [patientId]: demoSource.getHistory(patientId) },
            source: "DEMO_SENSOR",
          }));
        });
        demoSource.start();

        return () => {
          unsubscribe();
          demoSource.stop();
        };
      }

      // Real Monitoring Workflow
      const backendUp = await api.isBackendReachable();
      if (isCancelled) return;

      if (!backendUp) {
        setMonitoring((previous) => ({
          ...previous,
          source: "FIREBASE_RTDB_LIVE",
          deviceStatus: Object.fromEntries(patients.map((p) => [p.glove || p.id, "DISCONNECTED"])),
        }));
        return;
      }

      let activePatients = patients;
      try {
        const remotePatients = await api.getPatients();
        if (remotePatients && remotePatients.length > 0) {
          activePatients = remotePatients;
          if (!isCancelled) {
            setPatients(remotePatients);
          }
        }
      } catch (err) {
        if (isCancelled) return;
        const msg = err instanceof Error ? err.message : String(err);
        if (msg.includes("Authentication required") || msg.includes("401")) {
          return;
        }
        console.warn("Backend patients unavailable, continuing with assigned roster:", msg);
      }

      const backendSource = new BackendSensorAdapter(activePatients);
      activeSource = backendSource;

      try {
        const remoteAlerts = await api.getAllAlerts();
        if (!isCancelled && remoteAlerts) {
          alertService.syncBackendAlerts(remoteAlerts);
          setRevision((r) => r + 1);
        }
        const remoteNotifs = await api.getNotifications();
        if (!isCancelled && remoteNotifs) {
          notificationService.syncBackendNotifications(remoteNotifs);
          setRevision((r) => r + 1);
        }
      } catch (err) {
        console.warn("Background sync warning for alerts/notifications:", err);
      }

      const syncAll = () => {
        if (isCancelled) return;
        const latestReadings: Record<string, MonitoringSample> = {};
        const history: Record<string, MonitoringReading[]> = {};
        const deviceStatus = backendSource.getAllDeviceStatuses();
        const monitoringStatuses = backendSource.getAllMonitoringStatuses();
        activePatients.forEach((patient) => {
          const reading = backendSource.getLatestReading(patient.id);
          if (reading) latestReadings[patient.id] = reading;
          history[patient.id] = backendSource.getHistory(patient.id);
        });
        setMonitoring({
          latestReadings,
          history,
          deviceStatus,
          monitoringStatuses,
          source: "FIREBASE_RTDB_LIVE",
        });
      };

      const unsubscribe = backendSource.subscribe((readings) => {
        if (isCancelled) return;
        const patientId = readings[0]?.patientId;
        if (patientId) {
          const patient = activePatients.find((entry) => entry.id === patientId);
          if (patient) startMonitoringTimeline(patient, "FIREBASE_RTDB_LIVE");
        }

        void api.getAllAlerts().then((alerts) => {
          if (!isCancelled && alerts) {
            alertService.syncBackendAlerts(alerts);
            setRevision((r) => r + 1);
          }
        }).catch((err) => {
          console.warn("Alert sync warning on reading:", err);
        });
        void api.getNotifications().then((notifs) => {
          if (!isCancelled && notifs) {
            notificationService.syncBackendNotifications(notifs);
            setRevision((r) => r + 1);
          }
        }).catch((err) => {
          console.warn("Notification sync warning on reading:", err);
        });

        syncAll();
      });

      // Eager initial poll to populate live patient dashboard immediately
      await backendSource.poll();
      syncAll();
      backendSource.start();

      return () => {
        unsubscribe();
        backendSource.stop();
      };
    }

    let cleanupFn: (() => void) | undefined;
    void setupMonitoring().then((cleanup) => {
      cleanupFn = cleanup;
    });

    return () => {
      isCancelled = true;
      if (cleanupFn) cleanupFn();
      else if (activeSource) activeSource.stop();
    };
  }, [loggedRole]);
  useEffect(() => {
    const handleAuthExpired = () => {
      setLoggedRole(null);
      setSelected(null);
      setPageState("overview");
      setToast("Session expired. Please sign in to continue.");
      if (typeof window !== "undefined" && window.location.pathname !== "/login") {
        history.replaceState({}, "", "/login");
      }
    };
    window.addEventListener("care-glove-auth-expired", handleAuthExpired);
    return () => window.removeEventListener("care-glove-auth-expired", handleAuthExpired);
  }, []);
  useEffect(() => {
    ensurePatientTimeline(patients);
  }, [patients]);
  const [admissionOpen, setAdmissionOpen] = useState(false);
  const [management, setManagement] = useState<{
    patient: Patient;
    mode: "edit" | "assign";
  } | null>(null);
  const sessionUser = initialized ? authService.restore() : null;
  const visibleIds = new Set(
    patientsVisibleToRole(loggedRole || "admin", patients, sessionUser?.id)
      .map((patient) => patient.id),
  );
  const notificationCount = notificationService
    .list()
    .filter((notification) => visibleIds.has(notification.patientId) && !notification.read)
    .length;
  const setPage = (next: string) => {
    const allowed = navByRole[role].some(([, route]) => route === next);
    if (!allowed && next !== "overview") {
      notify("This workspace does not have access to that page.");
      return;
    }
    setPageState(next);
    setSelected(null);
    if (next !== "wards") setSelectedWard(null);
    if (loggedRole) go(loggedRole, next);
  };
  const notify = (message: string) => {
    setToast(message);
    window.setTimeout(() => setToast(""), 2500);
  };
  const refreshPatients = () => {
    setPatients(loadPatients(patientsSeed));
    setRevision((current) => current + 1);
  };
  useEffect(() => {
    const onDataChanged = (event: Event) => {
      if (event instanceof CustomEvent) {
        const key = event.detail?.key;
        if (key === "care-glove-patients") {
          try {
            refreshPatients();
          } catch (error) {
            notify(error instanceof Error ? error.message : "Could not refresh saved data.");
          }
          return;
        }
        if (
          key === "care-glove-alerts" ||
          key === "care-glove-notifications" ||
          key === "care-glove-clinical-timeline" ||
          key === "care-glove-clinical"
        ) {
          setRevision((current) => current + 1);
          return;
        }
      } else {
        setRevision((current) => current + 1);
      }
    };
    window.addEventListener("care-glove-data-updated", onDataChanged);
    window.addEventListener("storage", onDataChanged);
    return () => {
      window.removeEventListener("care-glove-data-updated", onDataChanged);
      window.removeEventListener("storage", onDataChanged);
    };
  }, []);

  const enterPatient = (patient: Patient) => {
    if (!loggedRole || !canAccessPatient(loggedRole, patient, authService.restore()?.id)) {
      notify("Access to this patient is not authorized.");
      setSelected(null);
      if (loggedRole) {
        setPageState("patients");
        go(loggedRole, "patients");
      }
      return;
    }
    setSelected(patient);
    setReportPatientId(patient.id);
    setMonitoringPatientId(patient.id);
    go(loggedRole, "patients", patient.id);
  };
  const generateReport = (patient: Patient) => {
    const currentPatient = patients.find((entry) => entry.id === patient.id);
    const session = authService.restore();
    if (!currentPatient || !loggedRole || !canAccessPatient(loggedRole, currentPatient, session?.id)) {
      notify("A report cannot be created for a patient outside the current workspace.");
      return;
    }
    const sampleCandidate = monitoring.latestReadings[currentPatient.id];
    const sample = sampleCandidate?.patientId === currentPatient.id && sampleCandidate.gloveId === currentPatient.glove
      ? sampleCandidate
      : undefined;
    const patientAlerts = alertService.list().filter((alert) => alert.patientId === currentPatient.id);
    const patientRecords = clinicalService.list().filter((record) => record.patientId === currentPatient.id);
    const report: Report = {
      id: `RPT-${crypto.randomUUID()}`,
      patientId: currentPatient.id,
      authorId: session?.id || "demo-user",
      authorName: roleInfo[loggedRole || "admin"].name,
      timestamp: new Date().toISOString(),
      title: `${currentPatient.name} monitoring report`,
      content: [
        `Patient: ${currentPatient.id} · ${currentPatient.name}`,
        `Ward / bed: ${currentPatient.ward} · ${currentPatient.bed}`,
        `Glove: ${currentPatient.glove}`,
        `Senior Doctor: ${currentPatient.doctor}`,
        sample
          ? `Latest readings: BPM ${sample.bpm}, Temperature ${sample.temperature}°C, GSR ${sample.gsr} µS`
          : "No monitoring samples have been recorded.",
        sample ? `Reading time/source: ${sample.timestamp} · ${monitoringSourceLabel(monitoring.source)}` : "",
        `Alerts: ${patientAlerts.length}`,
        ...patientAlerts.map((alert) => `${alert.timestamp} ${alert.severity} ${alert.parameter}: ${alert.value} (threshold ${alert.threshold})`),
        `Clinical records: ${patientRecords.length}`,
        ...patientRecords.map((record) => `${record.timestamp} ${record.type}: ${record.content || record.testName || record.medicineName || ""}`),
      ].filter(Boolean).join("\n"),
    };
    try {
      clinicalService.createReport(report);
      exportReport(report);
      notify("Patient report created and downloaded.");
      setRevision((current) => current + 1);
    } catch (error) {
      notify(error instanceof Error ? error.message : "The report could not be created.");
    }
  };
  const savePatient = (patient: Patient) => {
    const next = createPatient(patients, patient);
    setPatients(next);
    setAdmissionOpen(false);
    notify(`${patient.name} admitted · ${patient.bed} occupied · ${patient.glove} in use`);
    setPage("patients");
  };
  const syncRoute = useCallback(() => {
    const session = authService.restore();
    if (!session || !session.token || session.token.split(".").length !== 3) {
      setLoggedRole(null);
      setSelected(null);
      setReportPatientId(null);
      setMonitoringPatientId(null);
      if (window.location.pathname !== "/login") history.replaceState({}, "", "/login");
      return;
    }
    const parts = window.location.pathname.split("/").filter(Boolean);
    const pathRole: Role | null =
      parts[0] === "admin" ? "admin" : parts[0] === "doctor" ? "senior" : parts[0] === "duty" ? "duty" : null;
    if (pathRole && pathRole !== session.role) {
      setRole(session.role);
      setLoggedRole(session.role);
      setPageState("overview");
      setSelected(null);
      history.replaceState({}, "", routeFor(session.role, "overview"));
      return;
    }
    if (!pathRole || parts[0] === "login") {
      setRole(session.role);
      setLoggedRole(session.role);
      setPageState("overview");
      setSelected(null);
      history.replaceState({}, "", routeFor(session.role, "overview"));
      return;
    }
    const nextPage = parts[1] || "overview";
    const allowedPages = navByRole[session.role].map(([, route]) => route);
    if (nextPage !== "patients" && nextPage !== "overview" && !allowedPages.includes(nextPage as never)) {
      setRole(session.role);
      setLoggedRole(session.role);
      setPageState("overview");
      setSelected(null);
      setSelectedWard(null);
      history.replaceState({}, "", routeFor(session.role, "overview"));
      return;
    }
    setRole(session.role);
    setLoggedRole(session.role);
    setPageState(nextPage);
    const wardName = nextPage === "wards" ? decodeURIComponent(parts[2] || "") : "";
    if (wardName && !wards.some((ward) => ward.id === wardName)) {
      setSelectedWard(null);
      setPageState("overview");
      history.replaceState({}, "", routeFor(session.role, "overview"));
      return;
    }
    setSelectedWard((wards.some((ward) => ward.id === wardName) ? wardName : null) as WardName | null);
    const patientId = nextPage === "patients" ? parts[2] : undefined;
    if (patientId) {
      const patient = patients.find((entry) => entry.id === patientId);
      if (!patient || !canAccessPatient(session.role, patient, session.id)) {
        setSelected(null);
        setPageState("patients");
        history.replaceState({}, "", routeFor(session.role, "patients"));
        setToast("Access to this patient is not authorized.");
      } else {
        setSelected(patient);
        setReportPatientId(patient.id);
        setMonitoringPatientId(patient.id);
      }
    } else {
      setSelected(null);
    }
  }, [patients]);
  useEffect(() => {
    const loadedPatients = loadPatients(patientsSeed);
    setPatients(loadedPatients);
    const session = authService.restore();
    if (session?.token && session.token.split(".").length === 3) {
      setRole(session.role);
      setLoggedRole(session.role);
    } else {
      setLoggedRole(null);
    }
    setInitialized(true);
  }, []);

  useEffect(() => {
    if (!initialized) return;
    syncRoute();
    window.addEventListener("popstate", syncRoute);
    return () => window.removeEventListener("popstate", syncRoute);
  }, [initialized, syncRoute]);

  if (!initialized) {
    return <LoadingShell />;
  }

  if (!loggedRole)
    return (
      <Login
        onLogin={(r) => {
          setRole(r);
          setLoggedRole(r);
          setPageState("overview");
          setSelected(null);
          setReportPatientId(null);
          setMonitoringPatientId(null);
          setSearch("");
          go(r, "overview");
        }}
      />
    );
  const current = selected ? null : page;
  const livePatients = withLatestReadings(patients, monitoring);
  const liveSelected = selected
    ? livePatients.find((patient) => patient.id === selected.id) || selected
    : null;
  const visiblePatients = patientsVisibleToRole(role, livePatients, authService.restore()?.id);
  const reportPatient = visiblePatients.find((patient) => patient.id === reportPatientId && patient.lifecycle !== "DISCHARGED") || null;
  const monitoringPatient = visiblePatients.find((patient) => patient.id === monitoringPatientId && patient.lifecycle !== "DISCHARGED") || null;
  return (
    <div className="app-shell">
      <Sidebar
        role={role}
        page={page}
        setPage={setPage}
        open={mobileNavOpen}
        close={() => setMobileNavOpen(false)}
        alertCount={alertService.list().filter((alert) => alert.status === "OPEN" && visibleIds.has(alert.patientId)).length}
      />
      <main className="main-content">
        <Header
          role={role}
          title={
            selected
              ? "Patient profile"
              : page === "overview"
                ? roleInfo[role].label
                : page
          }
          search={search}
          setSearch={setSearch}
          notificationCount={notificationCount}
          onMenu={() => setMobileNavOpen((open) => !open)}
          onNotifications={() => setPage("notifications")}
          onLogout={() => {
            void api.logout().catch(() => {});
            authService.logout();
            setLoggedRole(null);
            setSelected(null);
            setPageState("overview");
            setRole("admin");
            setSearch("");
            history.pushState({}, "", "/login");
          }}
        />
        <div className="content-wrap">
          {selected ? (
            <Detail
              patient={liveSelected!}
              role={role}
              back={() => setPage("patients")}
              reading={monitoring.latestReadings[liveSelected!.id]}
              history={(monitoring.history[liveSelected!.id] ?? []).filter(
                (reading) =>
                  reading.patientId === liveSelected!.id &&
                  (!liveSelected!.glove || !reading.gloveId || reading.gloveId === liveSelected!.glove),
              )}
              source={monitoring.source}
              alerts={alertService.list()}
              deviceStatus={
                (liveSelected!.glove ? monitoring.deviceStatus[liveSelected!.glove] : undefined) ||
                monitoring.deviceStatus[liveSelected!.id] ||
                (monitoring.latestReadings[liveSelected!.id] ? "STALE" : "NO DATA")
              }
              onEdit={() => setManagement({ patient: liveSelected!, mode: "edit" })}
              onAssign={() => setManagement({ patient: liveSelected!, mode: "assign" })}
              onDischarge={() => {
                if (!window.confirm(`Discharge ${liveSelected!.name} and release ${liveSelected!.bed} / ${liveSelected!.glove}?`)) return;
                try {
                  setPatients(dischargePatient(patients, liveSelected!.id));
                  setSelected(null);
                  setPage("patients");
                  notify(`${liveSelected!.name} discharged. The bed and glove are available again.`);
                } catch (error) {
                  notify(error instanceof Error ? error.message : "Patient could not be discharged.");
                }
              }}
              onReport={() => generateReport(liveSelected!)}
              onSaved={() => {
                setRevision((current) => current + 1);
                notify("Clinical record saved.");
              }}
              openMonitoring={() => setPage("monitoring")}
            />
          ) : current === "overview" ? (
            <Dashboard
              role={role}
              patients={livePatients}
              setPage={setPage}
              onPatient={enterPatient}
              onAdd={() => setAdmissionOpen(true)}
              monitoring={monitoring}
            />
          ) : (
            <WorkspacePage
              page={page}
              role={role}
              patients={livePatients}
              onPatient={enterPatient}
              search={search}
              onReadNotification={(id: string) => {
                notificationService.markRead(id);
                setRevision((current) => current + 1);
              }}
              onMarkAllRead={() => {
                notificationService.markAllRead(visibleIds);
                setRevision((current) => current + 1);
              }}
              onCreateReport={generateReport}
              reportPatient={reportPatient}
              monitoringPatient={monitoringPatient}
              onSelectReportPatient={(patientId) => setReportPatientId(patientId || null)}
              monitoring={monitoring}
              wardRoute={selectedWard}
              onWardSelect={(ward: WardName) => {
                setSelectedWard(ward);
                setSelected(null);
                setPageState("wards");
                const route = `/${role === "senior" ? "doctor" : role}/wards/${encodeURIComponent(ward)}`;
                history.pushState({}, "", route);
                window.dispatchEvent(new PopStateEvent("popstate"));
              }}
            />
          )}
        </div>
      </main>
      {toast && (
        <div className="toast">
          <CheckCircle2 size={17} />
          {toast}
        </div>
      )}
      {admissionOpen && (
        <AdmissionModal
          patients={patients}
          onClose={() => setAdmissionOpen(false)}
          onSave={savePatient}
        />
      )}
      {management && (
        <PatientManagementModal
          mode={management.mode}
          patient={management.patient}
          patients={patients}
          onClose={() => setManagement(null)}
          onSave={(changes) => {
            const next = updatePatient(patients, management.patient.id, changes);
            setPatients(next);
            setSelected(next.find((patient) => patient.id === management.patient.id) || null);
            setManagement(null);
            notify("Patient details and assignments saved.");
          }}
        />
      )}
    </div>
  );
}
export default App;
