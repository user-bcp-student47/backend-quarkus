// OnboardingResource.java
package resource;

import dto.DecisionRequest;
import dto.FlowResponse;
import dto.OtpRequest;
import dto.ReportResponse;
import dto.SubmitRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import service.OnboardingException;
import service.OnboardingService;

@Path("/api/onboarding")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OnboardingResource {
    private final OnboardingService service;

    public OnboardingResource(OnboardingService service) {
        this.service = service;
    }

    @POST
    @Path("/flows")
    public Response start() {
        FlowResponse response = service.start();
        return Response.status(Response.Status.CREATED).entity(response).build();
    }

    @POST
    @Path("/flows/{flowId}/otp")
    public FlowResponse verifyOtp(
            @PathParam("flowId") String flowId,
            OtpRequest request) {
        return service.verifyOtp(flowId, request == null ? null : request.code());
    }

    @POST
    @Path("/flows/{flowId}/submit")
    public FlowResponse submit(
            @PathParam("flowId") String flowId,
            SubmitRequest request) {
        if (request == null) {
            throw OnboardingException.validation(
                    "REQUEST_REQUIRED", "Se requiere el cuerpo de la solicitud.");
        }
        return service.submit(flowId, request.documentType(), request.document());
    }

    @GET
    @Path("/flows/{flowId}")
    public FlowResponse get(@PathParam("flowId") String flowId) {
        return service.get(flowId);
    }

    @POST
    @Path("/flows/{flowId}/decision")
    public FlowResponse decide(
            @PathParam("flowId") String flowId,
            DecisionRequest request) {
        return service.decide(flowId, request == null ? null : request.decision());
    }

    @POST
    @Path("/flows/{flowId}/close")
    public FlowResponse close(@PathParam("flowId") String flowId) {
        return service.close(flowId);
    }

    @GET
    @Path("/report")
    public ReportResponse report() {
        return service.report();
    }
}