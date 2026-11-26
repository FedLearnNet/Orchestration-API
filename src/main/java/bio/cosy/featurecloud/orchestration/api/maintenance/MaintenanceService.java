package bio.cosy.featurecloud.orchestration.api.maintenance;

import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestStreamElementType;

@Path("/maintenance")
@Produces("application/json")
@Authenticated
@Tag(name = "Maintenance", description = "Upgrades and backups of this deployment by flnet in a container")
public interface MaintenanceService {

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Operation(summary = "State of the last upgrade, what runs and the backups",
            description = "Streams the state now and, while flnet runs, on every change until it ends")
    Multi<MaintenanceStatusDTO> status();

    @POST
    @Path("{mode}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Operation(summary = "Start flnet of a version in a mode", description = "Streams the state on every change until flnet "
            + "ends; a refused start, e.g. as one runs already, is a last state with error. all: a BACKUP also copies "
            + "configuration and secrets; backup: the one a RESTORE puts back")
    Multi<MaintenanceStatusDTO> start(@PathParam("mode") MaintenanceMode mode, @QueryParam("version") String version,
                                      @QueryParam("all") boolean all, @QueryParam("backup") String backup);
}
