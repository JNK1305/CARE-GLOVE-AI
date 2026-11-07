"use client";

import { useEffect, useState } from "react";
import { AlertTriangle, Activity } from "lucide-react";
import type { TimelineEvent } from "../lib/clinical";
import { timelineService } from "../lib/services";
import { api } from "../lib/api";

type Props = { patientId: string };

export function PatientAlertTimeline({ patientId }: Props) {
  const [events, setEvents] = useState<TimelineEvent[]>([]);

  useEffect(() => {
    let isCancelled = false;

    const sync = async () => {
      try {
        const journeyEvents = await api.getJourney(patientId);
        if (!isCancelled && journeyEvents && journeyEvents.length > 0) {
          setEvents(journeyEvents);
          return;
        }
      } catch {
        // Backend journey endpoint unavailable or unauthorized, fall back to local store
      }
      if (!isCancelled) {
        setEvents(timelineService.getPatientTimeline(patientId));
      }
    };

    void sync();
    window.addEventListener("storage", sync);
    window.addEventListener("care-glove-data-updated", sync);
    return () => {
      isCancelled = true;
      window.removeEventListener("storage", sync);
      window.removeEventListener("care-glove-data-updated", sync);
    };
  }, [patientId]);

  return (
    <section
      className="panel patient-alert-timeline"
      aria-label="Patient journey"
    >
      <div className="panel-heading">
        <div>
          <span className="eyebrow">PATIENT JOURNEY</span>
          <h2>Patient timeline</h2>
        </div>
        <span className="updated">
          <Activity size={14} /> {events.length} events
        </span>
      </div>
      {events.length === 0 ? (
        <p className="muted-copy">No patient journey events have been recorded.</p>
      ) : (
        <div className="alert-timeline-list">
          {events.slice().reverse().slice(0, 8).map((event) => (
            <article className="alert-timeline-event" key={event.id}>
              <div
                className={`alert-timeline-icon ${event.severity === "CRITICAL" ? "critical" : event.severity === "WARNING" ? "warning" : ""}`}
              >
                {event.type === "ALERT" ? <AlertTriangle size={15} /> : <Activity size={15} />}
              </div>
              <div className="alert-timeline-copy">
                <div className="alert-timeline-title">
                  <strong>{event.description}</strong>
                  <span>
                    {new Date(event.timestamp).toLocaleTimeString([], {
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </span>
                </div>
                <div className="alert-timeline-details">
                  <span>{event.actor}</span>
                  <span>{event.type.replaceAll("_", " ")}</span>
                  {event.severity && <span>Severity: {event.severity}</span>}
                  <span>{new Date(event.timestamp).toLocaleString()}</span>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}
