package bio.cosy.featurecloud.orchestration;

import bio.cosy.featurecloud.orchestration.base.ErrorResponseDTO;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

    @Override
    public Response toResponse(WebApplicationException ex) {
        Response r = ex.getResponse();
        int status = r != null ? r.getStatus() : 500;

        String reason = (r != null && r.getStatusInfo() != null)
                ? r.getStatusInfo().getReasonPhrase()
                : "Error";

        var body = new ErrorResponseDTO(status, reason, ex.getMessage());

        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}