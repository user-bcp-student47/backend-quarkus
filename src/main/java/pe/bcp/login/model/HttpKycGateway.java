// HttpKycGateway.java
package service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import model.KycOutcome;

@ApplicationScoped
public class HttpKycGateway implements KycGateway {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String baseUrl;
    private final String scenario;

    @Inject
    public HttpKycGateway(
            ObjectMapper mapper,
            @ConfigProperty(name = "mock.base-url", defaultValue = "http://localhost:8081")
            String baseUrl,
            @ConfigProperty(name = "onboarding.kyc-scenario", defaultValue = "APPROVED")
            String scenario) {
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        String normalizedUrl = baseUrl == null ? "" : baseUrl.trim();
        while (normalizedUrl.endsWith("/")) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
        }
        if (!(normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("mock.base-url debe ser una URL HTTP o HTTPS.");
        }
        this.baseUrl = normalizedUrl;

        String normalizedScenario = scenario == null ? "APPROVED"
                : scenario.trim().toUpperCase(Locale.ROOT);
        if (!normalizedScenario.equals("APPROVED")
                && !normalizedScenario.equals("REJECTED")
                && !normalizedScenario.equals("MANUAL_REVIEW")
                && !normalizedScenario.equals("UNAVAILABLE")) {
            throw new IllegalArgumentException("Escenario KYC de demo no válido.");
        }
        this.scenario = normalizedScenario;
    }

    @Override
    public String createVerification(String documentType, String document) {
        String body = writeJson(Map.of(
                "documentType", documentType,
                "document", document,
                "scenario", scenario));

        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/kyc/verifications"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                KycException.Phase.CREATE);

        try {
            JsonNode root = mapper.readTree(response.body());
            String id = text(root, "id", "verificationId");
            if (id == null || id.isBlank()) {
                throw new KycException(
                        KycException.Phase.CREATE, 502, "Respuesta KYC sin identificador.");
            }
            return id;
        } catch (IOException e) {
            throw new KycException(
                    KycException.Phase.CREATE, 502, "Respuesta KYC no válida.");
        }
    }

    @Override
    public KycOutcome getVerification(String verificationId) {
        String encodedId = URLEncoder.encode(verificationId, StandardCharsets.UTF_8)
                .replace("+", "%20");
        HttpResponse<String> response = send(
                HttpRequest.newBuilder(URI.create(
                                baseUrl + "/kyc/verifications/" + encodedId))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                KycException.Phase.GET);

        try {
            JsonNode root = mapper.readTree(response.body());
            String status = text(root, "status", "kycStatus", "result");
            String walletStatus = text(root, "walletStatus", "wallet_state");
            return new KycOutcome(normalize(status), normalize(walletStatus));
        } catch (IOException e) {
            throw new KycException(
                    KycException.Phase.GET, 502, "Respuesta KYC no válida.");
        }
    }

    @Override
    public void decide(String verificationId, String decision) {
        String encodedId = URLEncoder.encode(verificationId, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String body = writeJson(Map.of("decision", decision));

        send(
                HttpRequest.newBuilder(URI.create(
                                baseUrl + "/kyc/verifications/" + encodedId + "/decision"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                KycException.Phase.DECISION);
    }

    private HttpResponse<String> send(HttpRequest request, KycException.Phase phase) {
        try {
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new KycException(
                        phase, response.statusCode(), "El proveedor KYC devolvió un error.");
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KycException(phase, 0, "La solicitud KYC fue interrumpida.");
        } catch (IOException e) {
            throw new KycException(phase, 0, "No se pudo confirmar la respuesta KYC.");
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new KycException(
                    KycException.Phase.CREATE, 502, "No se pudo construir la solicitud KYC.");
        }
    }

    private static String text(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode value = root.get(field);
            if (value != null && value.isValueNode() && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}