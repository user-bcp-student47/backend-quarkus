// OnboardingService.java
package service;

import dto.FlowResponse;
import dto.ReportResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import model.FlowEventType;
import model.FlowMetrics;
import model.FlowState;
import model.KycOutcome;
import model.OnboardingEvent;
import model.OnboardingFlow;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import repository.JdbcOnboardingRepository;

@ApplicationScoped
public class OnboardingService {
    private static final List<String> REQUIRED_STEPS =
            List.of("PHONE_OTP", "KYC_DATA", "RESULT");
    private static final long PRODUCT_REFERENCE_MS = 180_000L;

    private final JdbcOnboardingRepository repository;
    private final KycGateway kycGateway;
    private final boolean demoEnabled;

    @Inject
    public OnboardingService(
            JdbcOnboardingRepository repository,
            KycGateway kycGateway,
            @ConfigProperty(name = "onboarding.demo-enabled", defaultValue = "false")
            boolean demoEnabled) {
        this.repository = repository;
        this.kycGateway = kycGateway;
        this.demoEnabled = demoEnabled;
    }

    public FlowResponse start() {
        requireDemoEnabled();
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        OnboardingFlow flow = new OnboardingFlow(
                id, FlowState.STARTED, now, null, null, null, null, null, null, null);
        repository.create(flow, event(
                id, FlowEventType.FLOW_STARTED, "PHONE_OTP", "STARTED", now));
        return response(flow);
    }

    public FlowResponse verifyOtp(String flowId, String code) {
        requireDemoEnabled();
        OnboardingFlow current = findRequired(flowId);
        ensureOpen(current);

        if (current.state() != FlowState.STARTED) {
            throw OnboardingException.invalidState("El flujo no está esperando la verificación OTP.");
        }
        if (code == null || !code.matches("[0-9]{6}") || !"123456".equals(code)) {
            throw OnboardingException.validation(
                    "OTP_INVALID", "Código no válido. Para la demo, ingresa el código de seis dígitos.");
        }

        Instant now = Instant.now();
        OnboardingFlow updated = new OnboardingFlow(
                current.flowId(), FlowState.OTP_VERIFIED, current.startedAt(),
                current.submittedAt(), current.kycStartedAt(), current.kycCompletedAt(),
                current.activatedAt(), current.kycVerificationId(),
                current.closeResult(), current.closedAt());

        boolean saved = repository.transition(
                updated,
                FlowState.STARTED,
                List.of(
                        event(current.flowId(), FlowEventType.STEP_COMPLETED,
                                "PHONE_OTP", "COMPLETED", now)));
        if (!saved) {
            throw OnboardingException.invalidState("El flujo cambió; consulta su estado e inténtalo de nuevo.");
        }
        return response(updated);
    }

    public FlowResponse submit(String flowId, String documentType, String document) {
        requireDemoEnabled();
        validateTestDocument(documentType, document);

        OnboardingFlow current = findRequired(flowId);
        ensureOpen(current);

        if (current.state() == FlowState.SUBMISSION_UNKNOWN
                && current.kycVerificationId() != null) {
            return refreshUnknown(current);
        }
        if (current.state() != FlowState.OTP_VERIFIED) {
            if (current.state() == FlowState.SUBMISSION_UNKNOWN) {
                throw new OnboardingException(
                        409, "SUBMISSION_UNKNOWN",
                        "No se pudo confirmar el envío. No se repetirá la creación automáticamente.",
                        false);
            }
            throw OnboardingException.invalidState("Completa primero la verificación OTP.");
        }

        Instant now = Instant.now();
        if (!repository.claimSubmission(current.flowId(), now)) {
            throw OnboardingException.invalidState("El flujo ya se está procesando o cambió de estado.");
        }

        try {
            String kycId = kycGateway.createVerification("TEST", document);
            OnboardingFlow submitting = findRequired(flowId);
            OnboardingFlow withKycId = new OnboardingFlow(
                    submitting.flowId(), FlowState.SUBMITTING, submitting.startedAt(),
                    submitting.submittedAt(), submitting.kycStartedAt(),
                    submitting.kycCompletedAt(), submitting.activatedAt(),
                    kycId, submitting.closeResult(), submitting.closedAt());

            if (!repository.transition(
                    withKycId,
                    FlowState.SUBMITTING,
                    List.of(event(current.flowId(), FlowEventType.KYC_VERIFICATION_CREATED,
                            "KYC_DATA", "CREATED", Instant.now())))) {
                throw OnboardingException.invalidState("No se pudo guardar el estado de la verificación.");
            }

            KycOutcome outcome = kycGateway.getVerification(kycId);
            return applyOutcome(findRequired(flowId), outcome);
        } catch (KycException e) {
            return handleSubmissionFailure(flowId, e);
        }
    }

    public FlowResponse get(String flowId) {
        requireDemoEnabled();
        return response(findRequired(flowId));
    }

    public FlowResponse decide(String flowId, String decision) {
        requireDemoEnabled();
        OnboardingFlow current = findRequired(flowId);
        ensureOpen(current);

        String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("ACTIVE") && !normalized.equals("REJECTED")) {
            throw OnboardingException.validation(
                    "INVALID_DECISION", "La decisión debe ser ACTIVE o REJECTED.");
        }
        if (current.state() != FlowState.PENDING || current.kycVerificationId() == null) {
            throw OnboardingException.invalidState("Solo se puede resolver una revisión pendiente.");
        }

        try {
            kycGateway.decide(current.kycVerificationId(), normalized);
            KycOutcome outcome = kycGateway.getVerification(current.kycVerificationId());
            FlowState finalState = outcomeState(outcome);
            if (finalState == FlowState.PENDING) {
                throw OnboardingException.invalidState(
                        "El mock todavía informa una revisión pendiente.");
            }
            return applyOutcome(findRequired(flowId), outcome);
        } catch (KycException e) {
            throw mappedKycError(e);
        }
    }

    public FlowResponse close(String flowId) {
        requireDemoEnabled();
        OnboardingFlow current = findRequired(flowId);
        if (current.closedAt() != null) {
            return response(current);
        }

        Instant now = Instant.now();
        String result = current.submittedAt() == null ? "INCOMPLETE" : "SUBMITTED";
        OnboardingFlow updated = new OnboardingFlow(
                current.flowId(), current.state(), current.startedAt(),
                current.submittedAt(), current.kycStartedAt(), current.kycCompletedAt(),
                current.activatedAt(), current.kycVerificationId(), result, now);

        boolean saved = repository.transition(
                updated, current.state(),
                List.of(event(current.flowId(), FlowEventType.FLOW_CLOSED,
                        null, result, now)));
        if (!saved) {
            throw OnboardingException.invalidState("El flujo cambió; consulta su estado.");
        }
        return response(updated);
    }

    public ReportResponse report() {
        requireDemoEnabled();
        List<FlowMetrics> rows = repository.findMetrics();

        List<Long> onboarding = rows.stream()
                .filter(row -> row.startedAt() != null && row.submittedAt() != null)
                .map(row -> Math.max(0L,
                        row.submittedAt().toEpochMilli() - row.startedAt().toEpochMilli()))
                .toList();

        List<Long> kyc = rows.stream()
                .filter(row -> row.kycStartedAt() != null && row.kycCompletedAt() != null)
                .map(row -> Math.max(0L,
                        row.kycCompletedAt().toEpochMilli() - row.kycStartedAt().toEpochMilli()))
                .toList();

        List<Long> activation = rows.stream()
                .filter(row -> row.submittedAt() != null && row.activatedAt() != null)
                .map(row -> Math.max(0L,
                        row.activatedAt().toEpochMilli() - row.submittedAt().toEpochMilli()))
                .toList();

        return new ReportResponse(
                onboarding.size(),
                median(onboarding),
                median(kyc),
                median(activation),
                PRODUCT_REFERENCE_MS,
                "Duración de onboarding = envío de pasos menos inicio; KYC y activación se reportan aparte. "
                        + "H2 es efímero; la referencia de 3 minutos no es un resultado validado.");
    }

    private FlowResponse refreshUnknown(OnboardingFlow current) {
        try {
            KycOutcome outcome = kycGateway.getVerification(current.kycVerificationId());
            return applyOutcome(findRequired(current.flowId().toString()), outcome);
        } catch (KycException e) {
            throw mappedKycError(e);
        }
    }

    private FlowResponse handleSubmissionFailure(String flowId, KycException failure) {
        OnboardingFlow current = findRequired(flowId);
        Instant now = Instant.now();

        if (failure.getPhase() == KycException.Phase.CREATE
                && failure.getHttpStatus() == 503) {
            OnboardingFlow retryable = new OnboardingFlow(
                    current.flowId(), FlowState.OTP_VERIFIED, current.startedAt(),
                    current.submittedAt(), null, null, null, null,
                    current.closeResult(), current.closedAt());
            repository.transition(
                    retryable, FlowState.SUBMITTING,
                    List.of(event(current.flowId(), FlowEventType.KYC_UNAVAILABLE,
                            "KYC_DATA", "RETRYABLE", now)));
            throw new OnboardingException(
                    503, "KYC_UNAVAILABLE",
                    "No se pudo completar la verificación. Puedes reintentar.",
                    true);
        }

        FlowState state = failure.getPhase() == KycException.Phase.CREATE
                && failure.getHttpStatus() == 400
                ? FlowState.OTP_VERIFIED
                : FlowState.SUBMISSION_UNKNOWN;

        OnboardingFlow updated = new OnboardingFlow(
                current.flowId(), state, current.startedAt(), current.submittedAt(),
                current.kycStartedAt(), current.kycCompletedAt(), current.activatedAt(),
                current.kycVerificationId(), current.closeResult(), current.closedAt());

        FlowEventType eventType = state == FlowState.SUBMISSION_UNKNOWN
                ? FlowEventType.SUBMISSION_UNKNOWN : FlowEventType.KYC_RESULT;
        repository.transition(
                updated, FlowState.SUBMITTING,
                List.of(event(current.flowId(), eventType, "KYC_DATA",
                        state.name(), now)));

        if (failure.getPhase() == KycException.Phase.CREATE
                && failure.getHttpStatus() == 400) {
            throw new OnboardingException(
                    502, "KYC_REQUEST_REJECTED",
                    "El proveedor no aceptó la solicitud de prueba.", false);
        }
        throw mappedKycError(failure);
    }

    private FlowResponse applyOutcome(OnboardingFlow current, KycOutcome outcome) {
        FlowState next = outcomeState(outcome);
        Instant now = Instant.now();
        Instant completedAt = next == FlowState.PENDING ? null : now;
        Instant activatedAt = next == FlowState.ACTIVE ? now : null;

        OnboardingFlow updated = new OnboardingFlow(
                current.flowId(), next, current.startedAt(), current.submittedAt(),
                current.kycStartedAt(), completedAt, activatedAt,
                current.kycVerificationId(), current.closeResult(), current.closedAt());

        boolean saved = repository.transition(
                updated,
                current.state(),
                List.of(
                        event(current.flowId(), FlowEventType.STEP_COMPLETED,
                                "KYC_DATA", "COMPLETED", now),
                        event(current.flowId(), FlowEventType.KYC_RESULT,
                                "RESULT", next.name(), now)));
        if (!saved) {
            throw OnboardingException.invalidState("El flujo cambió durante la respuesta KYC.");
        }
        return response(updated);
    }

    private static FlowState outcomeState(KycOutcome outcome) {
        String status = normalize(outcome.status());
        String wallet = normalize(outcome.walletStatus());

        if ("REJECTED".equals(status) || "REJECTED".equals(wallet)) {
            return FlowState.REJECTED;
        }
        if ("MANUAL_REVIEW".equals(status)
                || "PENDING".equals(status)
                || "PENDING".equals(wallet)) {
            return FlowState.PENDING;
        }
        if (("APPROVED".equals(status) || "ACTIVE".equals(status))
                && (wallet == null || "ACTIVE".equals(wallet))) {
            return FlowState.ACTIVE;
        }
        throw new OnboardingException(
                502, "KYC_INVALID_RESPONSE",
                "El proveedor devolvió un estado que no se pudo confirmar.", false);
    }

    private static OnboardingException mappedKycError(KycException failure) {
        if (failure.getHttpStatus() == 503) {
            return new OnboardingException(
                    503, "KYC_UNAVAILABLE",
                    "No se pudo completar la verificación. Puedes reintentar la consulta.",
                    true);
        }
        if (failure.getHttpStatus() == 409) {
            return new OnboardingException(
                    409, "KYC_CONFLICT",
                    "El proveedor no pudo aplicar la operación solicitada.", false);
        }
        if (failure.getHttpStatus() == 404) {
            return new OnboardingException(
                    502, "KYC_VERIFICATION_NOT_FOUND",
                    "No se pudo confirmar la verificación con el proveedor.", false);
        }
        return new OnboardingException(
                502, "KYC_PROVIDER_ERROR",
                "No se pudo confirmar la respuesta del proveedor KYC.", false);
    }

    private static void validateTestDocument(String documentType, String document) {
        if (!"TEST".equals(documentType)) {
            throw OnboardingException.validation(
                    "TEST_DOCUMENT_REQUIRED", "Usa el tipo de documento de prueba TEST.");
        }
        if (document == null || !document.startsWith("TEST-")
                || document.length() <= "TEST-".length()
                || document.length() > 128
                || !document.equals(document.trim())) {
            throw OnboardingException.validation(
                    "TEST_DOCUMENT_INVALID",
                    "Usa un documento ficticio con prefijo TEST- y un sufijo no vacío.");
        }
    }

    private OnboardingFlow findRequired(String flowId) {
        UUID id;
        try {
            id = UUID.fromString(flowId);
        } catch (RuntimeException e) {
            throw OnboardingException.badRequest("INVALID_FLOW_ID", "El identificador del flujo no es válido.");
        }
        OnboardingFlow flow = repository.findById(id);
        if (flow == null) {
            throw OnboardingException.notFound();
        }
        return flow;
    }

    private static void ensureOpen(OnboardingFlow flow) {
        if (flow.closedAt() != null) {
            throw OnboardingException.invalidState("El flujo ya está cerrado.");
        }
    }

    private void requireDemoEnabled() {
        if (!demoEnabled) {
            throw new OnboardingException(
                    403, "DEMO_DISABLED", "El onboarding de demo no está habilitado.", false);
        }
    }

    private static OnboardingEvent event(
            UUID id, FlowEventType type, String step, String result, Instant at) {
        return new OnboardingEvent(id, type, step, result, at);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Long median(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder())
                .collect(Collectors.toCollection(ArrayList::new));
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2.0);
    }

    private static FlowResponse response(OnboardingFlow flow) {
        String currentStep = switch (flow.state()) {
            case STARTED -> "PHONE_OTP";
            case OTP_VERIFIED -> "KYC_DATA";
            case SUBMITTING, PENDING, ACTIVE, REJECTED, SUBMISSION_UNKNOWN -> "RESULT";
        };

        String kycStatus = switch (flow.state()) {
            case PENDING -> "MANUAL_REVIEW";
            case ACTIVE -> "APPROVED";
            case REJECTED -> "REJECTED";
            default -> null;
        };
        String walletStatus = switch (flow.state()) {
            case PENDING -> "PENDING";
            case ACTIVE -> "ACTIVE";
            case REJECTED -> "REJECTED";
            default -> null;
        };
        boolean retryable = flow.state() == FlowState.OTP_VERIFIED;
        String message = switch (flow.state()) {
            case STARTED -> "Ingresa el código de prueba para continuar.";
            case OTP_VERIFIED -> "Verificación local completada. Ingresa los datos ficticios TEST.";
            case SUBMITTING -> "La verificación está en proceso.";
            case PENDING -> "La revisión de demo sigue pendiente.";
            case ACTIVE -> "Verificación aprobada; la wallet está activa.";
            case REJECTED -> "La verificación fue rechazada; la wallet no está activa.";
            case SUBMISSION_UNKNOWN -> "No se pudo confirmar el resultado. No se repetirá la creación automáticamente.";
        };

        return new FlowResponse(
                flow.flowId().toString(),
                flow.state().name(),
                currentStep,
                REQUIRED_STEPS,
                kycStatus,
                walletStatus,
                flow.state() == FlowState.ACTIVE,
                retryable,
                flow.submittedAt(),
                flow.closeResult(),
                message);
    }
}