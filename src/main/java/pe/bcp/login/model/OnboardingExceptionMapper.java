// OnboardingExceptionMapper.java
package resource;

import dto.ErrorResponse;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import service.OnboardingException;

@Provider
public class OnboardingExceptionMapper implements ExceptionMapper<OnboardingException> {
    @Override
    public Response toResponse(OnboardingException exception) {
        ErrorResponse body = new ErrorResponse(
                exception.getCode(),
                exception.getMessage(),
                exception.isRetryable());
        return Response.status(exception.getHttpStatus()).entity(body).build();
    }
}