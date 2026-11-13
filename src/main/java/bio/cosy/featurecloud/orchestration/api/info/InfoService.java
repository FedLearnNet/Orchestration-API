package bio.cosy.featurecloud.orchestration.api.info;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;


@Path("/info")
@Produces("application/json")
@Consumes("application/json")
@Authenticated
@Tag(name = "Info", description = "Service for system info")
public interface InfoService {

    @GET
    @Path("computing")
    @Operation(summary = "get computing info")
    @APIResponse(responseCode = "200", description = "info")
    SystemComputingInfoDTO getInfo();


}
