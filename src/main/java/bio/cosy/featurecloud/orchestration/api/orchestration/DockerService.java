package bio.cosy.featurecloud.orchestration.api.orchestration;

import com.github.dockerjava.api.model.Info;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;


@Path("/docker")
@Produces("application/json")
@Consumes("application/json")
@Authenticated
@Tag(name = "Docker", description = "Service for getting docker info")
public interface DockerService {

    @GET
    @Path("info")
    @Operation(summary = "get info")
    @APIResponse(responseCode = "200", description = "info")
    Info getInfo();


    @DELETE
    @Path("workflow/{workflowId}/cleanup")
    @Operation(summary = "Cleanup workflow")
    Response cleanupWorkflow(@PathParam("workflowId") Long workflowId);

    @DELETE
    @Path("workflow/node/{workflowNodeId}/cleanup")
    @Operation(summary = "Cleanup workflow")
    Response cleanupWorkflowNode(@PathParam("workflowNodeId") Long workflowNodeId);

}