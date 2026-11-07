package ai.careglove.reports;

import java.time.Instant;

public final class ReportDtos {
    private ReportDtos() {}

    public record ReportResponse(
            String id,
            String patientId,
            String authorId,
            String authorName,
            Instant timestamp,
            String title,
            String content) {
        static ReportResponse from(ReportEntity report) {
            return new ReportResponse(report.getId(), report.getPatientId(), report.getAuthorId(),
                    report.getAuthorName(), report.getTimestamp(), report.getTitle(), report.getContent());
        }
    }
}
