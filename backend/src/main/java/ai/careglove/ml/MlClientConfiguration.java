package ai.careglove.ml;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class MlClientConfiguration {
    @Bean
    RestClient mlRestClient(
            RestClient.Builder builder,
            @Value("${careglove.ml.base-url}") String baseUrl,
            @Value("${careglove.ml.connect-timeout:2000}") int connectTimeout,
            @Value("${careglove.ml.read-timeout:5000}") int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeout));
        factory.setReadTimeout(Duration.ofMillis(readTimeout));
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }
}
