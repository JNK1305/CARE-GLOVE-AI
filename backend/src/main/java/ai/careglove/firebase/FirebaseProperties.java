package ai.careglove.firebase;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "careglove.firebase")
public class FirebaseProperties {
    private boolean enabled = true;
    private String databaseUrl = "https://worksafe-ai-63fc8-default-rtdb.firebaseio.com/";
    private String sensorDataPath = "/sensorData";
    private String sensorHistoryPath = "/sensorHistory";
    private int pollIntervalSeconds = 15;
    private String authToken = "";
    private String defaultDeviceId = "GLV-ICU-001";
    private String defaultPatientId = "CG-B2CC11AC158A";
    private String devicePatientMapping = "";
    private int connectTimeout = 10000;
    private int readTimeout = 15000;
    private int freshnessWindowSeconds = 45;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDatabaseUrl() {
        return databaseUrl;
    }

    public void setDatabaseUrl(String databaseUrl) {
        this.databaseUrl = databaseUrl;
    }

    public String getSensorDataPath() {
        return sensorDataPath;
    }

    public void setSensorDataPath(String sensorDataPath) {
        this.sensorDataPath = sensorDataPath;
    }

    public String getSensorHistoryPath() {
        return sensorHistoryPath;
    }

    public void setSensorHistoryPath(String sensorHistoryPath) {
        this.sensorHistoryPath = sensorHistoryPath;
    }

    public int getPollIntervalSeconds() {
        return Math.max(10, Math.min(60, pollIntervalSeconds));
    }

    public void setPollIntervalSeconds(int pollIntervalSeconds) {
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getDefaultDeviceId() {
        return defaultDeviceId;
    }

    public void setDefaultDeviceId(String defaultDeviceId) {
        this.defaultDeviceId = defaultDeviceId;
    }

    public String getDefaultPatientId() {
        return defaultPatientId;
    }

    public void setDefaultPatientId(String defaultPatientId) {
        this.defaultPatientId = defaultPatientId;
    }

    public String getDevicePatientMapping() {
        return devicePatientMapping;
    }

    public void setDevicePatientMapping(String devicePatientMapping) {
        this.devicePatientMapping = devicePatientMapping;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getFreshnessWindowSeconds() {
        return Math.max(15, freshnessWindowSeconds);
    }

    public void setFreshnessWindowSeconds(int freshnessWindowSeconds) {
        this.freshnessWindowSeconds = freshnessWindowSeconds;
    }

    public Map<String, String> getParsedMappings() {
        Map<String, String> map = new HashMap<>();
        if (devicePatientMapping != null && !devicePatientMapping.isBlank()) {
            for (String pair : devicePatientMapping.split(",")) {
                String[] parts = pair.split(":");
                if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                    map.put(parts[0].trim(), parts[1].trim());
                }
            }
        }
        if (defaultDeviceId != null && !defaultDeviceId.isBlank() && defaultPatientId != null && !defaultPatientId.isBlank()) {
            map.putIfAbsent(defaultDeviceId.trim(), defaultPatientId.trim());
        }
        return map;
    }
}
