package bio.cosy.featurecloud.orchestration;


import bio.cosy.featurecloud.orchestration.base.ErrorResponseDTO;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class UnhandledExceptionMapper implements ExceptionMapper<Exception> {

    @Override
    public Response toResponse(Exception ex) {
        var body = new ErrorResponseDTO(500, "Internal Server Error", ex.getMessage());
        return Response.status(500)
                .type(MediaType.APPLICATION_JSON)
                .entity(body)
                .build();
    }
}