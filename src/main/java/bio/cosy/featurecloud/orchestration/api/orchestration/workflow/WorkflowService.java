package bio.cosy.featurecloud.orchestration.api.orchestration.workflow;

import com.github.dockerjava.api.command.CreateContainerResponse;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;


@Path("/container/workflow")
@Produces("application/json")
@Consumes("application/json")
@Authenticated
@Tag(name = "Container", description = "Service for managing simple container")
public interface WorkflowService {


    @POST
    @Operation(summary = "Start a new container")
    @APIResponse(responseCode = "200", description = "Container")
    CreateContainerResponse startWorkflow(StartWorkflowNodeDTO createDTO,
                                          @QueryParam("changeUrl") boolean changeUrl,
                                          @QueryParam("path") String path,
                                          @QueryParam("port") @DefaultValue("8080") Integer port);


    @DELETE
    @Path("{containerId}")
    @Operation(summary = "Delete and stop a container")
    Response cleanupWorkflow(@PathParam("containerId") String containerId,
                             @QueryParam("cleanup") boolean cleanup,
                             @QueryParam("background") boolean runInBackground);

    @DELETE
    @Path("step/{appId}")
    @Operation(summary = "Delete and stop a container")
    Response cleanupWorkflowStep(@PathParam("appId") String appId,
                             @QueryParam("cleanup") boolean cleanup,
                             @QueryParam("background") boolean runInBackground);

}