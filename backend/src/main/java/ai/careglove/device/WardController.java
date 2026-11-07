package ai.careglove.device;

import ai.careglove.patient.PatientAccessService;
import ai.careglove.patient.PatientEntity;
import ai.careglove.user.UserEntity;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WardController {
    private final WardRepository wards;
    private final BedRepository beds;
    private final DeviceRepository devices;
    private final PatientAccessService patientAccess;

    public WardController(WardRepository wards, BedRepository beds, DeviceRepository devices, PatientAccessService patientAccess) {
        this.wards = wards;
        this.beds = beds;
        this.devices = devices;
        this.patientAccess = patientAccess;
    }

    public record WardResponse(String id, String name) {}
    public record BedResponse(String id, String wardId, String label, String gloveId, String patientId, String patientName) {}
    public record GloveResponse(String id, String bedId, String wardId, String status, String maintenanceNote, String patientId) {}

    @GetMapping("/wards")
    public List<WardResponse> wards() {
        return wards.findAll().stream().map(item -> new WardResponse(item.getId(), item.getName())).toList();
    }

    @GetMapping("/wards/{wardId}/beds")
    public List<BedResponse> beds(@PathVariable String wardId, @AuthenticationPrincipal UserEntity user) {
        return beds.findByWardIdOrderByLabelAsc(wardId).stream().map(bed -> {
            DeviceEntity device = devices.findById(bed.getGloveId()).orElse(null);
            PatientEntity patient = device == null || device.getPatientId() == null ? null : visiblePatient(device.getPatientId(), user);
            return new BedResponse(bed.getId(), bed.getWardId(), bed.getLabel(), bed.getGloveId(),
                    patient == null ? null : patient.getId(), patient == null ? null : patient.getName());
        }).toList();
    }

    @GetMapping("/gloves")
    public List<GloveResponse> gloves(@AuthenticationPrincipal UserEntity user) {
        var visiblePatientIds = patientAccess.visibleTo(user).stream().map(PatientEntity::getId).collect(java.util.stream.Collectors.toSet());
        return devices.findAllByOrderByIdAsc().stream()
                .filter(device -> user.getRole().name().equals("admin") || device.getPatientId() == null || visiblePatientIds.contains(device.getPatientId()))
                .map(device -> new GloveResponse(device.getId(), device.getWard() + ":" + device.getBed(), device.getWard(),
                        device.getStatus(), device.getMaintenanceNote(), device.getPatientId()))
                .toList();
    }

    private PatientEntity visiblePatient(String id, UserEntity user) {
        try {
            return patientAccess.getVisible(id, user);
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            if (exception.getStatusCode().value() == 403 || exception.getStatusCode().value() == 404) return null;
            throw exception;
        }
    }
}
