import bio.cosy.featurecloud.orchestration.api.orchestration.container.StartAppDTO;
import com.github.dockerjava.api.command.CreateContainerResponse;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.io.InputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTest
@QuarkusTestResource(LearningApiTestResource.class)
@Transactional
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ContainerServiceTest {

    private static final String TEST_IMAGE = "nginx:alpine";
    private static String containerId;

    @Test
    @Order(1)
    public void testListRunningContainers() {
        given()
                .when().get("/container/running")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(2)
    public void testStartContainer() {
        StartAppDTO dto = new StartAppDTO();
        dto.setAppImage(TEST_IMAGE);
        dto.setIdentifier(1L);
        dto.setGroupId(1L);


        CreateContainerResponse resp = given()
                .contentType(ContentType.JSON)
                .body(dto)
                .when().post("/container")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("Id", notNullValue())
                .extract().as(CreateContainerResponse.class);

        containerId = resp.getId();
        Assertions.assertNotNull(containerId);
    }

    @Test
    @Order(3)
    public void testGetRunningContainer() {
        given()
                .when().get("/container/running/{id}", containerId)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("Id", equalTo(containerId));
    }


    @Test
    @Order(4)
    public void testReplaceExistingContainer() {
        String previousContainerId = containerId;
        StartAppDTO dto = new StartAppDTO();
        dto.setAppImage(TEST_IMAGE);
        dto.setIdentifier(1L);
        dto.setGroupId(1L);

        CreateContainerResponse response = given()
                .contentType(ContentType.JSON)
                .body(dto)
                .when().post("/container")
                .then()
                .statusCode(200)
                .body("Id", notNullValue())
                .extract().as(CreateContainerResponse.class);
        containerId = response.getId();
        Assertions.assertNotEquals(previousContainerId, containerId);

        given()
                .when().get("/container/running/{id}", previousContainerId)
                .then().statusCode(404);
        given()
                .when().get("/container/running/{id}", containerId)
                .then().statusCode(200);
    }

    @Test
    @Order(5)
    public void testListSavedContainers() {
        given()
                .when().get("/container/run")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body(notNullValue())
                .body("size()", greaterThanOrEqualTo(3));

    }

    @Test
    @Order(6)
    public void testGetSavedContainer() {
        Long runId = 1L;
        given()
                .when().get("/container/run/{id}", runId)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    @Test
    @Order(7)
    public void testGetSavedContainerLogs() {
        Long runId = 1L;

        given()
                .when().get("/container/run/{id}/logs", runId)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(8)
    public void testDelete() {
        given()
                .queryParam("cleanup", true)
                .when().delete("/container/{id}", containerId)
                .then()
                .statusCode(200);

    }
}
