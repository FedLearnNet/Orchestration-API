package bio.cosy.featurecloud.orchestration.api.orchestration.container;

import bio.cosy.featurecloud.orchestration.api.logging.ContainerLogDTO;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.List;


@Path("/container")
@Produces("application/json")
@Consumes("application/json")
@Authenticated
@Tag(name = "Container", description = "Service for managing simple container")
public interface ContainerService {

    @GET
    @Path("running")
    @Operation(summary = "List all running containers")
    @APIResponse(responseCode = "200", description = "List of running containers")
    List<Container> list();

    @GET
    @Path("running/{id}")
    @Operation(summary = "get specific running container")
    @APIResponse(responseCode = "200", description = "Container")
    Container get(@PathParam("id") String id);

    @GET
    @Path("running/{id}/logs/stream")
    @Operation(summary = "get logs of a specific running container")
    @APIResponse(responseCode = "200", description = "Logs as stream")
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    Multi<String> getLogsStream(@PathParam("id") String id);


    @GET
    @Path("run")
    @Operation(summary = "List all saved containers")
    @APIResponse(responseCode = "200", description = "List of running containers")
    List<ContainerRunDTO> listRuns();

    @GET
    @Path("run/{id}")
    @Operation(summary = "get specific saved container")
    @APIResponse(responseCode = "200", description = "Container")
    ContainerRunDTO getRun(@PathParam("id") Long id);

    @GET
    @Path("run/{id}/logs")
    @Operation(summary = "get logs of a specific saved container")
    @APIResponse(responseCode = "200", description = "Logs as stream")
    List<ContainerLogDTO> getRunLogs(@PathParam("id") Long id);


    @POST
    @Operation(summary = "Start a new container")
    @APIResponse(responseCode = "200", description = "Container")
    CreateContainerResponse startContainer(StartAppDTO createDTO,
                                           @QueryParam("changeUrl") boolean changeUrl,
                                           @QueryParam("sendConsoleLog") @DefaultValue("false") boolean sendConsoleLog,
                                           @QueryParam("path") String path,
                                           @QueryParam("port") @DefaultValue("8080") Integer port);

    @POST
    @Path("pipeline")
    @Operation(summary = "Start a new pipeline container")
    @APIResponse(responseCode = "200", description = "Container")
    CreateContainerResponse startPipeline(StartPipelineDTO createDTO,
                                           @QueryParam("changeUrl") boolean changeUrl,
                                           @QueryParam("path") String path,
                                           @QueryParam("port") @DefaultValue("8080") Integer port);

    @DELETE
    @Path("{containerId}")
    @Operation(summary = "Delete and stop a container")
    Response cleanupWorkflow(@PathParam("containerId") String containerId, @QueryParam("cleanup") boolean cleanup);

    @GET
    @Path("fc")
    @Operation(summary = "List all FeatureCloud containers")
    @APIResponse(responseCode = "200", description = "List of FeatureCloud containers")
    List<Container> listFeatureCloud();

    @DELETE
    @Path("fc")
    @Operation(summary = "Stop and remove multiple containers")
    @APIResponse(responseCode = "200", description = "Container cleanup initiated in background")
    Response cleanupContainers(List<String> containerIds, @QueryParam("cleanup") boolean cleanup);

}