package bio.cosy.featurecloud.orchestration.api.orchestration;

import bio.cosy.featurecloud.orchestration.execution.AppExecutionService;
import bio.cosy.featurecloud.orchestration.execution.OrchestrationInfoService;
import bio.cosy.featurecloud.orchestration.service.DockerRegistryService;
import com.github.dockerjava.api.exception.NotModifiedException;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;

@ApplicationScoped
public class DockerServiceImpl implements DockerService {

    @Inject
    OrchestrationInfoService orchestrationInfoService;

    @Inject
    AppExecutionService appExecutionService;

    @Inject
    DockerRegistryService dockerRegistryService;

    @Override
    public OrchestrationInfoDTO getInfo() {
        return orchestrationInfoService.getInfo();
    }

    @Override
    public RegistryImagesDTO getRegistryImages() {
        return dockerRegistryService.getRegistryImages();
    }

    @Override
    public boolean imageExistsInRegistry(String name) {
        return dockerRegistryService.imageExistsInRegistry(name);
    }

    @Override
    public String resolveToolImage(String slug, String commitHash) {
        return dockerRegistryService.resolveToolImage(slug, commitHash);
    }

    @Override
    public Response removeLocalImage(String name) {
        appExecutionService.removeImage(name);
        return Response.noContent().build();
    }

    @Override
    public Response cleanupWorkflow(Long workflowId) {
        try {
            appExecutionService.cleanupWorkflow(workflowId);
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
            appExecutionService.cleanupWorkflowNode(workflowNodeId);
            return Response.ok().build();
        } catch (NotModifiedException e) {
            return Response.ok().build();
        } catch (Exception e) {
            return Response.status(INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }
}
