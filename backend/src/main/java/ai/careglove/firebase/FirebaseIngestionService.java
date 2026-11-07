package ai.careglove.firebase;

import ai.careglove.common.Role;
import ai.careglove.device.DeviceEntity;
import ai.careglove.device.DeviceRepository;
import ai.careglove.patient.PatientEntity;
import ai.careglove.patient.PatientRepository;
import ai.careglove.user.UserEntity;
import ai.careglove.user.UserRepository;
import ai.careglove.vitals.IngestReadingRequest;
import ai.careglove.vitals.ReadingIngestionService;
import ai.careglove.vitals.SensorReadingRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class FirebaseIngestionService {
    private static final Logger log = LoggerFactory.getLogger(FirebaseIngestionService.class);

    private final FirebaseProperties properties;
    private final ReadingIngestionService ingestionService;
    private final SensorReadingRepository readingRepository;
    private final PatientRepository patientRepository;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    private final RestClient restClient;
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean isPolling = new AtomicBoolean(false);

    // Monitoring state
    private volatile boolean connected = false;
    private volatile String deviceState = "DISCONNECTED"; // CONNECTED, STALE, DISCONNECTED, INVALID_DATA
    private volatile Instant lastPollTime = null;
    private volatile Instant lastSuccessfulPollTime = null;
    private volatile Instant lastValidMeasurementTime = null;
    private volatile Long lastPayloadTimestamp = null;
    private volatile String lastError = null;
    private final AtomicLong totalReadingsIngested = new AtomicLong(0);

    // Deduplication trackers
    private volatile String lastSensorDataSignature = null;
    private volatile String lastProcessedHistoryKey = null;
    private volatile Instant lastProcessedHistoryTimestamp = Instant.EPOCH;

    public FirebaseIngestionService(
            FirebaseProperties properties,
            ReadingIngestionService ingestionService,
            SensorReadingRepository readingRepository,
            PatientRepository patientRepository,
            DeviceRepository deviceRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.ingestionService = ingestionService;
        this.readingRepository = readingRepository;
        this.patientRepository = patientRepository;
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeout()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeout()));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @PostConstruct
    public synchronized void start() {
        if (!properties.isEnabled()) {
            log.info("Firebase ingestion is disabled by configuration (careglove.firebase.enabled=false).");
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "careglove-firebase-ingest");
            t.setDaemon(true);
            return t;
        });

        int interval = properties.getPollIntervalSeconds();
        scheduler.scheduleWithFixedDelay(this::pollSafe, 5, interval, TimeUnit.SECONDS);
        log.info("Firebase ingestion service started with polling interval: {}s against {}", interval, properties.getDatabaseUrl());
    }

    @PreDestroy
    public synchronized void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            log.info("Shutting down Firebase ingestion scheduler...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Firebase ingestion scheduler shutdown complete.");
        }
    }

    public void pollSafe() {
        if (!isPolling.compareAndSet(false, true)) {
            return;
        }
        try {
            pollOnce();
        } catch (Exception e) {
            log.error("Unexpected error in Firebase ingestion cycle: {}", e.getMessage(), e);
        } finally {
            isPolling.set(false);
        }
    }

    public synchronized IngestionResult pollOnce() {
        lastPollTime = Instant.now();
        int newReadings = 0;
        try {
            // 1. Ingest latest live reading from sensorData path
            int liveCount = ingestSensorData();
            newReadings += liveCount;

            // 2. Ingest historical/push records from sensorHistory path
            int historyCount = ingestSensorHistory();
            newReadings += historyCount;

            connected = true;
            lastSuccessfulPollTime = Instant.now();
            if (newReadings > 0) {
                deviceState = "CONNECTED";
                lastValidMeasurementTime = Instant.now();
                lastError = null;
            } else if (lastValidMeasurementTime != null) {
                long age = Duration.between(lastValidMeasurementTime, Instant.now()).getSeconds();
                if (age > properties.getFreshnessWindowSeconds()) {
                    deviceState = "STALE";
                }
            } else {
                deviceState = "STALE";
            }
            return new IngestionResult(true, newReadings, null);
        } catch (RestClientResponseException ex) {
            connected = false;
            deviceState = "DISCONNECTED";
            String errorMsg = "Firebase RTDB HTTP " + ex.getStatusCode() + ": " + ex.getStatusText();
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) {
                errorMsg = "Firebase access denied (HTTP " + ex.getStatusCode().value() + "). Please configure CAREGLOVE_FIREBASE_AUTH_TOKEN.";
            }
            lastError = errorMsg;
            log.warn("Firebase ingestion error: {}", errorMsg);
            return new IngestionResult(false, newReadings, errorMsg);
        } catch (Exception ex) {
            connected = false;
            deviceState = "DISCONNECTED";
            String errorMsg = "Firebase connection failure: " + ex.getMessage();
            lastError = errorMsg;
            log.warn("Firebase ingestion error: {}", errorMsg);
            return new IngestionResult(false, newReadings, errorMsg);
        }
    }

    private int ingestSensorData() {
        String path = properties.getSensorDataPath();
        if (path == null || path.isBlank()) return 0;

        String url = buildFirebaseUrl(path);
        String rawJson = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
        if (rawJson == null || rawJson.isBlank() || "null".equals(rawJson.trim())) {
            return 0;
        }

        String trimmed = rawJson.trim();
        try {
            FirebaseSensorReading reading = objectMapper.readValue(trimmed, FirebaseSensorReading.class);
            reading.setSourcePath(path);
            reading.setRecordKey("latest");

            FirebaseSensorReading.ValidationResult validation = reading.validate();
            if (!validation.isValid()) {
                deviceState = "INVALID_DATA";
                lastError = "SensorData validation failed: " + validation.reason();
                log.debug("SensorData reading skipped: {}", validation.reason());
                lastSensorDataSignature = trimmed;
                return 0;
            }

            Long currentTimestamp = reading.getTimestamp();
            boolean isNewMeasurement = false;
            if (currentTimestamp != null) {
                if (lastPayloadTimestamp == null || !currentTimestamp.equals(lastPayloadTimestamp)) {
                    isNewMeasurement = true;
                }
            } else if (!trimmed.equals(lastSensorDataSignature)) {
                isNewMeasurement = true;
            }

            if (!isNewMeasurement) {
                Instant now = Instant.now();
                if (lastValidMeasurementTime != null &&
                        Duration.between(lastValidMeasurementTime, now).getSeconds() > properties.getFreshnessWindowSeconds()) {
                    deviceState = "STALE";
                } else if (lastValidMeasurementTime == null) {
                    deviceState = "STALE";
                }
                return 0;
            }

            boolean ingested = processReading(reading, "FIREBASE_RTDB_LIVE");
            lastSensorDataSignature = trimmed;
            lastPayloadTimestamp = currentTimestamp;
            if (ingested) {
                lastValidMeasurementTime = Instant.now();
                deviceState = "CONNECTED";
                lastError = null;
                return 1;
            }
        } catch (Exception e) {
            deviceState = "INVALID_DATA";
            lastError = "Failed to parse /sensorData payload: " + e.getMessage();
            log.warn("Failed to parse /sensorData payload: {}", e.getMessage());
        }
        return 0;
    }

    private int ingestSensorHistory() {
        String path = properties.getSensorHistoryPath();
        if (path == null || path.isBlank()) return 0;

        String url = buildFirebaseUrl(path);
        String rawJson = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
        if (rawJson == null || rawJson.isBlank() || "null".equals(rawJson.trim())) {
            return 0;
        }

        int ingestedCount = 0;
        try {
            Map<String, FirebaseSensorReading> historyMap = objectMapper.readValue(
                    rawJson, new TypeReference<Map<String, FirebaseSensorReading>>() {});

            if (historyMap == null || historyMap.isEmpty()) return 0;

            // Sort entries chronologically by push key / timestamp
            List<Map.Entry<String, FirebaseSensorReading>> entries = new ArrayList<>(historyMap.entrySet());
            entries.sort((e1, e2) -> {
                Long t1 = e1.getValue().getTimestamp();
                Long t2 = e2.getValue().getTimestamp();
                if (t1 != null && t2 != null) return t1.compareTo(t2);
                return e1.getKey().compareTo(e2.getKey());
            });

            // If we have never processed history, start by checking recent entries only to avoid backlogging 300+ items
            int startIndex = 0;
            if (lastProcessedHistoryKey == null && entries.size() > 5) {
                startIndex = entries.size() - 5;
            }

            for (int i = startIndex; i < entries.size(); i++) {
                Map.Entry<String, FirebaseSensorReading> entry = entries.get(i);
                String key = entry.getKey();
                FirebaseSensorReading reading = entry.getValue();
                reading.setRecordKey(key);
                reading.setSourcePath(path);

                Instant ts = reading.resolveTimestamp(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
                if (!ts.isAfter(lastProcessedHistoryTimestamp) && key.equals(lastProcessedHistoryKey)) {
                    continue;
                }

                FirebaseSensorReading.ValidationResult validation = reading.validate();
                if (!validation.isValid()) {
                    continue;
                }

                boolean ingested = processReading(reading, "FIREBASE_RTDB_HISTORY");
                if (ingested) {
                    ingestedCount++;
                    lastValidMeasurementTime = ts;
                    deviceState = "CONNECTED";
                    lastError = null;
                }
                lastProcessedHistoryKey = key;
                if (ts.isAfter(lastProcessedHistoryTimestamp)) {
                    lastProcessedHistoryTimestamp = ts;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse /sensorHistory payload: {}", e.getMessage());
        }
        return ingestedCount;
    }

    private boolean processReading(FirebaseSensorReading reading, String sourceTag) {
        String deviceId = reading.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            deviceId = properties.getDefaultDeviceId();
        }

        // Resolve patient association
        String patientId = resolvePatientForDevice(deviceId);
        if (patientId == null) {
            log.warn("No patient assigned to device '{}'. Skipping ingestion for reading key '{}'.",
                    deviceId, reading.getRecordKey());
            return false;
        }

        Instant timestamp = reading.resolveTimestamp(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));

        // Check if database already has a reading for this patient and timestamp
        if (readingRepository.findByPatientIdAndTimestamp(patientId, timestamp).isPresent()) {
            return false; // already ingested
        }

        // Ensure device is assigned to patient in devices table so ingestion check passes
        ensureDeviceAssignment(deviceId, patientId);

        UserEntity actor = getSystemActor();
        Double bpm = reading.resolveBpm();
        Double gsr = reading.resolveGsr();
        Double temp = reading.resolveTemperature();

        Double signalQuality = reading.calculateSignalQuality();
        if (!reading.isSensorContactAdequate()) {
            log.warn("Sensor reading for patient {} device {} has inadequate contact (signalQuality={}, ir={}, gsrRaw={})",
                    patientId, deviceId, signalQuality, reading.getIr(), reading.getGsrRaw());
        }

        IngestReadingRequest request = new IngestReadingRequest(
                patientId,
                deviceId,
                timestamp,
                bpm,
                gsr,
                temp,
                sourceTag,
                signalQuality,
                "v1.0-firebase");

        try {
            ingestionService.ingest(request, actor);
            totalReadingsIngested.incrementAndGet();
            log.info("Successfully ingested {} reading for patient {} device {}: BPM={}, Temp={}, GSR={}",
                    sourceTag, patientId, deviceId, bpm, temp, gsr);
            return true;
        } catch (Exception e) {
            log.warn("Ingestion error for patient {} timestamp {}: {}", patientId, timestamp, e.getMessage());
            return false;
        }
    }

    private String resolvePatientForDevice(String deviceId) {
        // 1. Check configured mapping
        Map<String, String> mappings = properties.getParsedMappings();
        if (mappings.containsKey(deviceId)) {
            String candidateId = mappings.get(deviceId);
            if (patientRepository.findById(candidateId).isPresent()) {
                return candidateId;
            }
        }

        // 2. Check DeviceEntity in repository
        Optional<DeviceEntity> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isPresent() && deviceOpt.get().getPatientId() != null) {
            String candidateId = deviceOpt.get().getPatientId();
            if (patientRepository.findById(candidateId).isPresent()) {
                return candidateId;
            }
        }

        // 3. Fallback to default patient if valid
        if (properties.getDefaultPatientId() != null && patientRepository.findById(properties.getDefaultPatientId()).isPresent()) {
            return properties.getDefaultPatientId();
        }

        // 4. Fallback to first admitted patient in DB if available
        List<PatientEntity> all = patientRepository.findAll();
        for (PatientEntity p : all) {
            if (!"DISCHARGED".equalsIgnoreCase(p.getLifecycle())) {
                return p.getId();
            }
        }
        return null;
    }

    private void ensureDeviceAssignment(String deviceId, String patientId) {
        deviceRepository.findById(deviceId).ifPresentOrElse(device -> {
            if (!patientId.equals(device.getPatientId())) {
                device.setPatientId(patientId);
                device.setStatus("ASSIGNED");
                deviceRepository.save(device);
            }
        }, () -> {
            DeviceEntity newDev = new DeviceEntity();
            newDev.setId(deviceId);
            newDev.setPatientId(patientId);
            newDev.setWard("ICU");
            newDev.setBed("B-01");
            newDev.setStatus("ASSIGNED");
            deviceRepository.save(newDev);
        });
    }

    private UserEntity getSystemActor() {
        return userRepository.findByEmailIgnoreCase("admin@careglove.demo").orElseGet(() -> {
            UserEntity fallback = new UserEntity();
            fallback.setId("system-firebase");
            fallback.setEmail("admin@careglove.demo");
            fallback.setName("System Firebase Ingestion");
            fallback.setRole(Role.admin);
            return fallback;
        });
    }

    private String buildFirebaseUrl(String path) {
        String base = properties.getDatabaseUrl();
        if (!base.endsWith("/")) base = base + "/";
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        String url = base + cleanPath + ".json";
        if (properties.getAuthToken() != null && !properties.getAuthToken().isBlank()) {
            url += "?auth=" + properties.getAuthToken().trim();
        }
        return url;
    }

    public FirebaseStatus getStatus() {
        return new FirebaseStatus(
                properties.isEnabled(),
                connected,
                deviceState,
                properties.getDatabaseUrl(),
                properties.getSensorDataPath(),
                properties.getSensorHistoryPath(),
                properties.getPollIntervalSeconds(),
                properties.getFreshnessWindowSeconds(),
                properties.getDefaultDeviceId(),
                properties.getDefaultPatientId(),
                lastPollTime,
                lastSuccessfulPollTime,
                lastValidMeasurementTime,
                lastPayloadTimestamp,
                totalReadingsIngested.get(),
                lastError);
    }

    public record IngestionResult(boolean success, int readingsIngested, String error) {}

    public record FirebaseStatus(
            boolean enabled,
            boolean connected,
            String deviceState,
            String databaseUrl,
            String sensorDataPath,
            String sensorHistoryPath,
            int pollIntervalSeconds,
            int freshnessWindowSeconds,
            String defaultDeviceId,
            String defaultPatientId,
            Instant lastPollTime,
            Instant lastSuccessfulPollTime,
            Instant lastValidMeasurementTime,
            Long lastPayloadTimestamp,
            long totalReadingsIngested,
            String lastError) {}
}
