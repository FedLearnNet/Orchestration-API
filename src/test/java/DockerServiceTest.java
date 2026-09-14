import bio.cosy.featurecloud.orchestration.api.orchestration.DockerService;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestHTTPEndpoint(DockerService.class)
public class DockerServiceTest {

    private static final String TEST_IMAGE = "nginx:alpine";


    @Test
    @Order(1)
    public void testGetInfo() {
        given()
                .when().get("info")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("Containers", greaterThanOrEqualTo(0))
                .body("Images", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(2)
    public void testCleanupWorkflow() {
        long workflowId = 123L;
        given()
                .when().delete("workflow/{workflowId}/cleanup", workflowId)
                .then()
                .statusCode(anyOf(is(200), is(204)));
    }
}