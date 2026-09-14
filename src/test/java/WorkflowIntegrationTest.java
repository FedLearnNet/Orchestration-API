import bio.cosy.featurecloud.orchestration.api.orchestration.workflow.StartWorkflowNodeDTO;
import bio.cosy.featurecloud.orchestration.api.orchestration.workflow.actions.WorkflowNodeInputActionDTO;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Frame;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@QuarkusTestResource(LearningApiTestResource.class)
public class WorkflowIntegrationTest {

    private static final String TEST_IMAGE = "nginx:alpine";
    private static final long WORKFLOW_ID = 1L;

    @Inject
    DockerClient dockerClient;


    @Test
    public void fullWorkflowScenario(@TempDir Path tempDir) throws IOException {
        // 1. Start workflow step 1
        Long workflowNodeId = 1L;
        StartWorkflowNodeDTO step1 = getStartDTO(workflowNodeId);
        step1.setIsFistNode(true);

        CreateContainerResponse resp1 = given()
                .contentType(ContentType.JSON)
                .body(step1)
                .when().post("/container/workflow")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("Id", notNullValue())
                .extract().as(CreateContainerResponse.class);
        Assertions.assertNotNull(resp1.getId(), "Step 1 container should be created");

        // 2. Upload two files into the workflow input volume
        // File 1
        Path file1 = tempDir.resolve("test_1.txt");
        Files.writeString(file1, "Hello Workflow 1!");
        given()
                .multiPart("file", file1.toFile())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .when().post("/volume/workflow/{workflowId}/node/{workflowNodeId}/upload",
                        WORKFLOW_ID,
                        workflowNodeId)
                .then()
                .statusCode(anyOf(is(200), is(204)));

        // File 2
        Path file2 = tempDir.resolve("test_2.txt");
        Files.writeString(file2, "Hello Workflow 2!");
        given()
                .multiPart("file", file2.toFile())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .when().post("/volume/workflow/{workflowId}/node/{workflowNodeId}/upload",
                        WORKFLOW_ID,
                        workflowNodeId)
                .then()
                .statusCode(anyOf(is(200), is(204)));

        // Move both files to the output directory
        given()
                .contentType(MediaType.APPLICATION_JSON)
                .when().put("/volume/workflow/{workflowId}/node/{workflowNodeId}/move-to-output",
                        WORKFLOW_ID,
                        workflowNodeId)
                .then()
                .statusCode(anyOf(is(200), is(204)));

        // 3. Start workflow step 2 with file 1, rename to processed_test_1.txt
        workflowNodeId += 1;
        StartWorkflowNodeDTO step2 = getStartDTO(workflowNodeId);
        step2.setInputs(getActionDTOs(1L, false, "test_1.txt"));
        startStep(step2);


        // 4. Start workflow step 3 with file 2, rename to processed_test_2.txt
        workflowNodeId += 1;
        StartWorkflowNodeDTO step3 = getStartDTO(workflowNodeId);
        step3.setInputs(getActionDTOs(1L, true, "test_2.txt"));
        startStep(step3);

        // 4. Start workflow step 4, requesting both processed files as inputs
        workflowNodeId += 1;
        StartWorkflowNodeDTO step4 = getStartDTO(workflowNodeId);
        List<WorkflowNodeInputActionDTO> inputs = getActionDTOs(2L, true, "processed_test_1.txt");
        inputs.addAll(getActionDTOs(3L, true, "processed_test_2.txt"));
        step4.setInputs(inputs);
        step4.setIsLastNode(true);
        startStep(step4);

        // 5. Download both files and verify their contents match the upload
        byte[] zipBytes = given()
            .when().get("/volume/workflow/{workflowId}/node/{workflowNodeId}/download",
                WORKFLOW_ID,
                workflowNodeId)
            .then()
            .statusCode(200)
            .contentType("application/zip")
            .extract().asByteArray();

        Map<String, byte[]> downloadedFiles = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    // shallow iteration is enough
                    downloadedFiles.put(entry.getName(), zis.readAllBytes());
                }
            }
        }

        Assertions.assertEquals(2, downloadedFiles.size(), "Both processed files should be present");
        Assertions.assertArrayEquals(
            Files.readAllBytes(file1),
            downloadedFiles.get("processed_processed_test_1.txt"),
            "Processed file 1 must match the original file 1"
        );
        Assertions.assertArrayEquals(
            Files.readAllBytes(file2),
            downloadedFiles.get("processed_processed_test_2.txt"),
            "Processed file 2 must match the original file 2"
        );

        // 6. Stop the workflow (hard cleanup of all step containers & volume)
        given()
                .when().delete("/docker/workflow/{workflowId}/cleanup", WORKFLOW_ID)
                .then()
                .statusCode(anyOf(is(200), is(204)));

        // 7. Verify no containers with our TEST_IMAGE are still running
        given()
                .when().get("/container/running")
                .then()
                .statusCode(200)
                .body("findAll { it.Image == '" + TEST_IMAGE + "' }.size()", equalTo(0));
    }

    // Start a dumnmy workflow step with needsInternetAccess=false
    // Exec into it, run wget to google.com, and verify it fails!
    // Timeout is set to 5 seconds, so the test should not take too long
    @Test
    public void cannotReachInternetWithoutInternetAccess() throws Exception {
        // Start the container
        long workflowId = 999L;
        StartWorkflowNodeDTO step = getStartDTO(1L);
        step.setWorkflowId(workflowId);
        step.setIsFistNode(true);
        step.setNeedsInternetAccess(false);
        step.setNeedsHostAccess(false);
        step.setNeedsFederatedLearningAccess(false);

        String containerId = null;
        try {
            CreateContainerResponse response = given()
                .contentType(ContentType.JSON)
                .body(step)
                .when().post("/container/workflow")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract().as(CreateContainerResponse.class);
            containerId = response.getId();

            // Exec into the container and run wget to google.com, expecting it to fail
            var exec = dockerClient.execCreateCmd(containerId)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd("wget", "-T", "5", "-q", "-O", "-", "https://google.com")
                .exec();
            dockerClient.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<Frame>())
                .awaitCompletion();

            // Verify the exit code is non-zero (wget should fail)
            Long exitCode = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
            Assertions.assertNotNull(exitCode, "Docker should report the wget exit code");
            Assertions.assertNotEquals(0L, exitCode,
                "wget should fail because the container has no internet access");
        } finally {
            given()
                .when().delete("/docker/workflow/{workflowId}/cleanup", workflowId)
                .then()
                .statusCode(anyOf(is(200), is(204)));
        }
    }


    private StartWorkflowNodeDTO getStartDTO(Long workflowNodeId) {
        StartWorkflowNodeDTO step = new StartWorkflowNodeDTO();
        step.setAppImage(TEST_IMAGE);
        step.setWorkflowNodeId(workflowNodeId);
        step.setWorkflowId(WORKFLOW_ID);
        return step;
    }

    private void startStep(StartWorkflowNodeDTO step) {
        given()
                .contentType(ContentType.JSON)
                .body(step)
                .when().post("/container/workflow")
                .then()
                .statusCode(200)
                .body("Id", notNullValue());

        given()
                .contentType(MediaType.APPLICATION_JSON)
                .when().put("/volume/workflow/{workflowId}/node/{workflowNodeId}/move-to-output",
                        WORKFLOW_ID,
                        step.getWorkflowNodeId())
                .then()
                .statusCode(anyOf(is(200), is(204)));
    }

    private List<WorkflowNodeInputActionDTO> getActionDTOs(Long workflowNodeId, boolean isLastUsage, String originalFileName) {
        List<WorkflowNodeInputActionDTO> steps = new ArrayList<>();
        WorkflowNodeInputActionDTO action = new WorkflowNodeInputActionDTO();
        action.setOriginalFileName(originalFileName);
        action.setNewFileName("processed_" + originalFileName);
        action.setInputNodeId(workflowNodeId);
        action.setLastUsage(isLastUsage);
        steps.add(action);
        return steps;
    }
}
