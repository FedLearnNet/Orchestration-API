package bio.cosy.featurecloud.orchestration.api.orchestration;

import bio.cosy.featurecloud.orchestration.service.DockerAppService;
import bio.cosy.featurecloud.orchestration.service.DockerOrchestration;
import bio.cosy.featurecloud.orchestration.service.DockerVolumeService;
import bio.cosy.featurecloud.orchestration.service.DockerCleanupService;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Info;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;

@ApplicationScoped
public class DockerServiceImpl implements DockerService {

    @Inject
    DockerOrchestration dockerOrchestration;

    @Inject
    DockerAppService dockerAppService;

    @Inject
    DockerVolumeService dockerVolumeService;

    @Inject
    DockerCleanupService dockerCleanupService;

    @Override
    public Info getInfo() {
        return dockerOrchestration.getInfo();
    }

    @Override
    public Response cleanupWorkflow(Long workflowId) {
        try {
            dockerCleanupService.cleanupWorkflow(workflowId);
            return Response.ok().build();
        } catch (NotModifiedException e) {
            return Response.ok().build();
        } catch (Exception e) {
            Log.errorf(e, "Error cleaning up workflow %d: %s", workflowId, e.getMessage());
            return Response.status(INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    @Override
    public Response cleanupWorkflowNode(Long workflowNodeId) {
        try {
            dockerCleanupService.cleanupWorkflowNode(workflowNodeId);
            return Response.ok().build();
        } catch (NotModifiedException e) {
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }
}
