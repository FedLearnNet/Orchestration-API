package bio.cosy.featurecloud.orchestration.api.orchestration.volume;

import com.github.dockerjava.api.command.InspectVolumeResponse;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.List;


@Path("/volume")
@Produces("application/json")
@Consumes("application/json")
@Tag(name = "Volumes", description = "Service for managing volumes")
public interface VolumeService {

    @GET
    @Operation(summary = "List all volumes")
    List<InspectVolumeResponse> listVolumes();

    @GET
    @Path("{name}")
    @Operation(summary = "Get a specific volume by name")
    InspectVolumeResponse getVolume(@PathParam("name") String name);

    @POST
    @Path("{name}")
    @Operation(summary = "Create a volume with the given name")
    @APIResponse(responseCode = "204", description = "Volume created")
    Response createVolume(@PathParam("name") String name);


    @POST
    @Path("{name}/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Upload a file into a volume")
    Response uploadFiles(@PathParam("name") String name, @RestForm("file") FileUpload file,
                         @RestForm("fileName") String fileName);

    @POST
    @Path("{name}/config/upload")
    @Operation(summary = "Upload a config.yml into a volume")
    Response uploadConfig(@PathParam("name") String name, @Valid ConfigYMLDTO config);

    @POST
    @Path("workflow/{workflowId}/node/{workflowNodeId}/upload")
    @Operation(summary = "Upload a file into a volume")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @APIResponse(responseCode = "204", description = "Volume created")
    Response uploadFilesIds(@PathParam("workflowId") Long workflowId,
                            @PathParam("workflowNodeId") Long workflowNodeId,
                            @RestForm("file") FileUpload file,
                            @RestForm("fileName") String fileName);

    @POST
    @Path("workflow/{workflowId}/node/{workflowNodeId}/upload/config")
    @Operation(summary = "Upload a file into a volume")
    @APIResponse(responseCode = "204", description = "Volume created")
    Response uploadFilesIdsConfig(@PathParam("workflowId") Long workflowId,
                                  @PathParam("workflowNodeId") Long workflowNodeId,
                                  @Valid ConfigYMLDTO config);

    @GET
    @Path("{name}/download")
    @Produces("application/zip")
    @Operation(summary = "Download all files from a volume")
    Response downloadFiles(@PathParam("name") String name);

    @GET
    @Path("workflow/{workflowId}/node/{workflowNodeId}/download")
    @Operation(summary = "Download all files from a volume")
    @Produces("application/zip")
    Response downloadFilesIds(@PathParam("workflowId") Long workflowId,
                              @PathParam("workflowNodeId") Long workflowNodeId);

    @PUT
    @Path("workflow/{workflowId}/node/{workflowNodeId}/move-to-output")
    @Operation(summary = "Moves files from input to output volume")
    Response moveFilesToOutput(@PathParam("workflowId") Long workflowId,
                               @PathParam("workflowNodeId") Long workflowNodeId);

    @GET
    @Path("{name}/size")
    @Operation(summary = "Get the size of a volume")
    Response volumeSize(@PathParam("name") String name);

    @DELETE
    @Path("{name}")
    @Operation(summary = "Remove a volume by name")
    Response removeVolume(@PathParam("name") String name);

    @DELETE
    @Operation(summary = "Remove all volumes")
    Response removeAllVolumes();


}