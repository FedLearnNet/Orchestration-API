import bio.cosy.featurecloud.orchestration.api.orchestration.container.StartAppDTO;
import bio.cosy.featurecloud.orchestration.api.orchestration.container.StartPipelineDTO;
import bio.cosy.featurecloud.orchestration.api.orchestration.workflow.StartWorkflowNodeDTO;
import bio.cosy.featurecloud.orchestration.api.orchestration.workflow.actions.WorkflowNodeInputActionDTO;
import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.QuarkusTest;
import bio.cosy.featurecloud.orchestration.docker.DockerLabels;
import bio.cosy.featurecloud.orchestration.docker.LabelsHelper;
import bio.cosy.featurecloud.orchestration.helper.NamingService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class BaseNameTest {

    @Test
    void keepsNamesStableAcrossCallsAndRequests() {
        StartWorkflowNodeDTO first = workflowNode(1L);
        StartWorkflowNodeDTO laterRequest = workflowNode(1L);

        assertEquals(first.getContainerName(), first.getContainerName());
        assertEquals(first.getInputVolumeName(), laterRequest.getInputVolumeName());
        assertEquals(first.getOutputVolumeName(), laterRequest.getOutputVolumeName());
        assertNotEquals(first.getInputVolumeName(), first.getOutputVolumeName());
        assertNotEquals(first.getContainerName(), workflowNode(2L).getContainerName());
    }

    @Test
    void resolvesThePreviousNodesActualOutputVolume() {
        StartWorkflowNodeDTO previousNode = workflowNode(1L);
        WorkflowNodeInputActionDTO input = new WorkflowNodeInputActionDTO();
        input.setInputNodeId(1L);

        assertEquals(previousNode.getOutputVolumeName(), input.getOutputVolumeName(12L));
    }

    @Test
    void sharesTheNamespaceAcrossDtoTypes() {
        String prefix = workflowNode(1L).getNetworkHash();

        assertEquals(NamingService.getSystemName(), prefix);
        assertEquals(prefix, LabelsHelper.createLabels().get(DockerLabels.SYSTEM_NAME.toString()));
        assertEquals(prefix, new StartAppDTO().getNetworkHash());
        assertEquals(prefix, new StartPipelineDTO().getNetworkHash());
        assertEquals(prefix, new WorkflowNodeInputActionDTO().getNetworkHash());
    }

    private StartWorkflowNodeDTO workflowNode(long nodeId) {
        StartWorkflowNodeDTO dto = new StartWorkflowNodeDTO();
        dto.setWorkflowId(12L);
        dto.setWorkflowNodeId(nodeId);
        return dto;
    }
}
