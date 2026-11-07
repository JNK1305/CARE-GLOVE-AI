"use client";

import { useEffect, useMemo, useState } from "react";
import { FileText, Pill, Plus, Send, UserRound, X } from "lucide-react";
import type {
  ClinicalRecord,
  ClinicalRecordType,
  TimelineEvent,
} from "@/lib/clinical";
import {
  loadClinicalRecords,
  loadTimeline,
  recordLabel,
  recordText,
} from "@/lib/clinical";
import { clinicalService } from "@/lib/services";

type Props = {
  patientId: string;
  patientName: string;
  age?: string;
  gender?: string;
  ward: string;
  bed: string;
  glove: string;
  monitoringStatus?: "READY" | "ACTIVE";
  role: "admin" | "senior" | "duty";
  doctorId: string;
  doctorName: string;
  onSaved?: () => void;
};
type FormState = {
  content: string;
  medicineName: string;
  dosage: string;
  frequency: string;
  instruction: string;
  testName: string;
  result: string;
  notes: string;
};
const blank: FormState = {
  content: "",
  medicineName: "",
  dosage: "",
  frequency: "",
  instruction: "",
  testName: "",
  result: "",
  notes: "",
};
const staffTypes: ClinicalRecordType[] = [
  "OBSERVATION",
  "REPORT",
  "TEST",
  "ACTION",
  "PATIENT_RESPONSE",
];

export function ClinicalInstructions({
  patientId,
  patientName,
  age,
  gender,
  ward,
  bed,
  glove,
  monitoringStatus,
  role,
  doctorId,
  doctorName,
  onSaved,
}: Props) {
  const [type, setType] = useState<ClinicalRecordType | null>(null);
  const [records, setRecords] = useState<ClinicalRecord[]>(() =>
    loadClinicalRecords(),
  );
  const [timeline, setTimeline] = useState<TimelineEvent[]>(() =>
    loadTimeline(),
  );
  const [form, setForm] = useState<FormState>(blank);
  const [error, setError] = useState("");
  const patientRecords = useMemo(
    () =>
      records
        .filter((r) => r.patientId === patientId)
        .sort((a, b) => b.timestamp.localeCompare(a.timestamp)),
    [records, patientId],
  );
  const patientTimeline = useMemo(
    () =>
      timeline
        .filter((event) => event.patientId === patientId)
        .sort(
          (a, b) =>
            b.timestamp.localeCompare(a.timestamp) ||
            b.id.localeCompare(a.id),
        ),
    [timeline, patientId],
  );
  const canCreateDoctor = role === "senior" && doctorId === "sharma";
  const canCreateStaff = role === "duty";
  const actorName = role === "duty" ? "Anita Joshi" : doctorName;
  const actorId = role === "duty" ? "anita-joshi" : doctorId;
  const open = (next: ClinicalRecordType) => {
    setType(next);
    setError("");
    setForm(blank);
  };
  const update = (key: keyof FormState, value: string) =>
    setForm((current) => ({ ...current, [key]: value }));
  const save = () => {
    if (!type) return;
    const clean = Object.fromEntries(
      Object.entries(form).map(([key, value]) => [key, value.trim()]),
    ) as FormState;
    const missing =
      type === "TEST"
        ? !clean.testName || !clean.result
        : type === "MEDICINE_INSTRUCTION"
          ? !clean.medicineName || !clean.dosage
          : !clean.content;
    if (missing) {
      setError(
        type === "TEST"
          ? "Test name and result are required."
          : "Please complete the required field before saving.",
      );
      return;
    }
    const record: ClinicalRecord = {
      id: `CLN-${Date.now()}`,
      patientId,
      actorId,
      actorName,
      actorRole: role,
      type,
      timestamp: new Date().toISOString(),
      content: clean.content || undefined,
      medicineName: clean.medicineName || undefined,
      dosage: clean.dosage || undefined,
      frequency: clean.frequency || undefined,
      instruction: clean.instruction || undefined,
      testName: clean.testName || undefined,
      result: clean.result || undefined,
      notes: clean.notes || undefined,
    };
    try {
      clinicalService.create(record);
      setRecords(loadClinicalRecords());
      setTimeline(loadTimeline());
      setType(null);
      onSaved?.();
    } catch (saveError) {
      setError(
        saveError instanceof Error
          ? saveError.message
          : "Could not save this clinical record.",
      );
    }
  };
  useEffect(() => {
    const sync = () => {
      setRecords(loadClinicalRecords());
      setTimeline(loadTimeline());
    };
    window.addEventListener("care-glove-data-updated", sync);
    window.addEventListener("storage", sync);
    return () => {
      window.removeEventListener("care-glove-data-updated", sync);
      window.removeEventListener("storage", sync);
    };
  }, []);
  const renderForm = () =>
    type === "TEST" ? (
      <div className="form-grid">
        <label>
          Test name
          <input
            autoFocus
            value={form.testName}
            onChange={(e) => update("testName", e.target.value)}
          />
        </label>
        <label>
          Result
          <input
            value={form.result}
            onChange={(e) => update("result", e.target.value)}
          />
        </label>
        <label>
          Notes (optional)
          <textarea
            value={form.notes}
            onChange={(e) => update("notes", e.target.value)}
          />
        </label>
      </div>
    ) : (
      <label>
        {type === "OBSERVATION"
          ? "Observation"
          : type === "REPORT"
            ? "Report"
            : type === "ACTION"
              ? "Action taken"
              : type === "PATIENT_RESPONSE"
                ? "Patient response"
                : type === "NOTE"
                  ? "Note content"
                  : "Suggestion / instruction"}
        <textarea
          autoFocus
          value={form.content}
          onChange={(e) => update("content", e.target.value)}
        />
      </label>
    );
  return (
    <section className="panel clinical-panel">
      <div className="panel-heading">
        <div>
          <span className="eyebrow">CARE TEAM RECORDS</span>
          <h2>
            {role === "duty" ? "Staff clinical records" : "Clinical records"}
          </h2>
        </div>
        <span className="updated">
          <UserRound size={14} /> Shared with authorized care team
        </span>
      </div>
      <div className="clinical-actions">
        {canCreateDoctor && (
          <>
            <button className="button secondary" onClick={() => open("NOTE")}>
              <FileText size={15} /> Add note
            </button>
            <button
              className="button secondary"
              onClick={() => open("MEDICINE_INSTRUCTION")}
            >
              <Pill size={15} /> Add medicine instruction
            </button>
            <button
              className="button secondary"
              onClick={() => open("SUGGESTION")}
            >
              <Send size={15} /> Add suggestion
            </button>
          </>
        )}
        {canCreateStaff &&
          staffTypes.map((item) => (
            <button
              className="button secondary"
              key={item}
              onClick={() => open(item)}
            >
              <Plus size={15} /> Add {recordLabel(item).toLowerCase()}
            </button>
          ))}
      </div>
      <div className="clinical-record-grid">
        {patientRecords.length ? (
          patientRecords.map((record) => (
            <article className="clinical-record" key={record.id}>
              <div className="clinical-record-top">
                <span className="eyebrow">{recordLabel(record.type)}</span>
                <time>
                  {new Date(record.timestamp).toLocaleString([], {
                    dateStyle: "medium",
                    timeStyle: "short",
                  })}
                </time>
              </div>
              <p>{recordText(record)}</p>
              <small>Entered by {record.actorName}</small>
            </article>
          ))
        ) : (
          <p className="empty-copy">No clinical records recorded yet.</p>
        )}
      </div>
      <div className="clinical-timeline">
        <div className="timeline-heading">
          <div>
            <span className="eyebrow">PATIENT JOURNEY</span>
            <h3>{patientName}</h3>
            <small>
              {patientId}
              {age ? ` · ${age}` : ""}
              {gender ? ` · ${gender}` : ""}
            </small>
          </div>
          <span className="timeline-status">
            {monitoringStatus === "ACTIVE"
              ? "Monitoring active"
              : "Device ready"}
          </span>
        </div>
        <div className="timeline-context">
          <span>
            {ward} · Bed {bed}
          </span>
          <span>{glove}</span>
          <span>{doctorName}</span>
        </div>
        {patientTimeline.map((event) => (
          <div className="timeline-event" key={event.id}>
            <i />
            <div>
              <div className="timeline-event-top">
                <span className="eyebrow">
                  {event.type.replaceAll("_", " ")}
                </span>
                <time>
                  {new Date(event.timestamp).toLocaleString([], {
                    dateStyle: "medium",
                    timeStyle: "short",
                  })}
                </time>
              </div>
              <b>{event.description}</b>
              <small>
                Source / actor: {event.actor}
                {event.severity ? ` · ${event.severity}` : ""}
              </small>
            </div>
          </div>
        ))}
      </div>
      {type && (
        <div className="modal-backdrop">
          <section className="modal-card clinical-modal">
            <button
              className="icon-btn modal-close"
              onClick={() => setType(null)}
              aria-label="Cancel"
            >
              <X size={17} />
            </button>
            <span className="eyebrow">{recordLabel(type)}</span>
            <h2>Add {recordLabel(type).toLowerCase()}</h2>
            {type === "MEDICINE_INSTRUCTION" ? (
              <div className="form-grid">
                <label>
                  Medicine name
                  <input
                    value={form.medicineName}
                    onChange={(e) => update("medicineName", e.target.value)}
                  />
                </label>
                <label>
                  Dosage
                  <input
                    value={form.dosage}
                    onChange={(e) => update("dosage", e.target.value)}
                  />
                </label>
                <label>
                  Frequency
                  <input
                    value={form.frequency}
                    onChange={(e) => update("frequency", e.target.value)}
                  />
                </label>
                <label>
                  Additional instruction
                  <textarea
                    value={form.instruction}
                    onChange={(e) => update("instruction", e.target.value)}
                  />
                </label>
              </div>
            ) : (
              renderForm()
            )}
            {error && <p className="form-error">{error}</p>}
            <div className="modal-actions">
              <button
                className="button secondary"
                onClick={() => setType(null)}
              >
                Cancel
              </button>
              <button className="button primary" onClick={save}>
                <Plus size={15} /> Save record
              </button>
            </div>
          </section>
        </div>
      )}
    </section>
  );
}
