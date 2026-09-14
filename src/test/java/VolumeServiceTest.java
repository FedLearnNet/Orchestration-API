import bio.cosy.featurecloud.orchestration.api.orchestration.volume.VolumeService;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import com.github.dockerjava.api.DockerClient;
import jakarta.inject.Inject;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.withArgs;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestHTTPEndpoint(VolumeService.class)
public class VolumeServiceTest {

    @Inject
    DockerClient dockerClient;

    @Test
    public void missingDownloadDoesNotCreateAnEmptyVolume() {
        String missingVolume = "missing-output-" + UUID.randomUUID();
        given().when().get("{name}/download", missingVolume)
                .then().statusCode(404)
                .body(containsString("Output volume does not exist"));
        org.junit.jupiter.api.Assertions.assertThrows(
                com.github.dockerjava.api.exception.NotFoundException.class,
                () -> dockerClient.inspectVolumeCmd(missingVolume).exec());
    }

    private static final String VOLUME_NAME = "test-volume";
    private static final String TEST_IMAGE = "nginx:alpine";

    @Test
    @Order(1)
    public void cleanUpAll() {
        given()
                .when().delete()
                .then().statusCode(anyOf(is(200), is(204)));
    }

    @Test
    @Order(2)
    public void listVolumesInitially() {
        given()
                .when().get()
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(3)
    public void createVolume() {
        given()
                .contentType(ContentType.JSON)
                .when().post("{name}", VOLUME_NAME)
                .then()
                .statusCode(201);
    }

    @Test
    @Order(4)
    public void listVolumesAfterCreate() {
        given()
                .contentType(ContentType.JSON)
                .when().get()
                .then()
                .statusCode(200)
                .body("find { it.Name == '%s' }", withArgs(VOLUME_NAME), notNullValue());
    }

    @Test
    @Order(5)
    public void getVolumeByName() {
        given()
                .when().get("{name}", VOLUME_NAME)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("Name", equalTo(VOLUME_NAME));
    }

    //TODO
    /*
    @Test
    @Order(6)
    public void volumeSizeInitiallyZero() {
        given()
                .when().get("{name}/size", VOLUME_NAME)
                .then()
                .statusCode(200)
                .body(notNullValue())
                .body(startsWith("0"));
    }*/

    @Test
    @Order(7)
    public void uploadFilesToVolume(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "Hello Volume!");

        given()
                .multiPart("file", file.toFile())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .when().post("{name}/upload", VOLUME_NAME)
                .then()
                .statusCode(anyOf(is(200), is(204)));
    }

    @Test
    @Order(8)
    public void downloadFilesFromVolume() {
        given()
                .when().get("{name}/download", VOLUME_NAME)
                .then()
                .statusCode(200)
                .contentType("application/zip")
                .body(notNullValue());
    }

    @Test
    @Order(9)
    public void removeVolumeByName() {
        given()
                .when().delete("{name}", VOLUME_NAME)
                .then()
                .statusCode(anyOf(is(200), is(204)));
    }

}
