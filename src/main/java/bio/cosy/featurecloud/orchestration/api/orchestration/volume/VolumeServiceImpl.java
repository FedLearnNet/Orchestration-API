package bio.cosy.featurecloud.orchestration.api.orchestration.volume;

import bio.cosy.featurecloud.orchestration.api.orchestration.workflow.StartWorkflowNodeDTO;
import bio.cosy.featurecloud.orchestration.service.DockerVolumeService;
import com.github.dockerjava.api.command.CreateVolumeResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@ApplicationScoped
public class VolumeServiceImpl implements VolumeService {

    @Inject
    DockerVolumeService dockerVolumeService;

    @Override
    public List<InspectVolumeResponse> listVolumes() {
        return dockerVolumeService.listVolumes();
    }

    @Override
    public InspectVolumeResponse getVolume(String name) {
        return dockerVolumeService.getVolume(name);
    }

    @Override
    public Response createVolume(String name) {
        CreateVolumeResponse created = dockerVolumeService.createVolume(name);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @Override
    public Response uploadFilesIds(Long workflowId, Long workflowNodeId, FileUpload file, String fileName) {
        StartWorkflowNodeDTO createDTO = new StartWorkflowNodeDTO();
        createDTO.setWorkflowNodeId(workflowNodeId);
        createDTO.setWorkflowId(workflowId);
        String volumeName = createDTO.getInputVolumeName();
        return uploadFiles(volumeName, file, fileName);
    }

    @Override
    public Response uploadFilesIdsConfig(Long workflowId, Long workflowNodeId, ConfigYMLDTO config) {
        StartWorkflowNodeDTO createDTO = new StartWorkflowNodeDTO();
        createDTO.setWorkflowNodeId(workflowNodeId);
        createDTO.setWorkflowId(workflowId);

        String volumeName = createDTO.getInputVolumeName();
        return uploadConfig(volumeName, config);
    }

    @Override
    public Response uploadFiles(String name, FileUpload file, String fileName) {
        Log.infof("Uploading file %s to volume %s", fileName, name);
        try {
            Path tempFile = file.uploadedFile();
            dockerVolumeService.moveDataToWorkspace(
                    name,
                    tempFile,
                    Optional.ofNullable(fileName).orElse(file.fileName())
            );
            return Response
                    .ok()
                    .entity("File successfully uploaded into volume '" + name + "'.")
                    .build();

        } catch (Exception e) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("Upload failed: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public Response uploadConfig(String name, ConfigYMLDTO config) {
        try {
            String fileName = Optional.ofNullable(config.fileName).orElse("config");
            String fileType = Optional.ofNullable(config.fileType).orElse(".yml");
            Path tempFile = Files.createTempFile(fileName, fileType);
            Files.writeString(tempFile, config.content);
            dockerVolumeService.moveDataToWorkspace(
                    name,
                    tempFile,
                    fileName + fileType
            );
            return Response
                    .ok()
                    .entity("File successfully uploaded into volume '" + name + "'.")
                    .build();

        } catch (Exception e) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("Upload failed: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public Response downloadFiles(String name) {
        try {
            Path tmpDir = Files.createTempDirectory("vol-download-" + name + "-");
            dockerVolumeService.moveDataToHost(
                    name,
                    tmpDir.toAbsolutePath().toString()
            );
            List<Path> outputFiles;
            try (var paths = Files.walk(tmpDir)) {
                outputFiles = paths.filter(Files::isRegularFile).toList();
            }
            Log.infof("Volume export ready: volume=%s files=%d", name, outputFiles.size());
            if (outputFiles.isEmpty()) {
                Log.warnf("Output volume %s contains no files", name);
            }
            StreamingOutput stream = os -> {
                try (ZipOutputStream zos = new ZipOutputStream(os)) {
                    outputFiles.forEach(path -> {
                        ZipEntry entry = new ZipEntry(tmpDir.relativize(path).toString());
                        try {
                            zos.putNextEntry(entry);
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException ioe) {
                            throw new RuntimeException("Error zipping file " + path, ioe);
                        }
                    });
                }
            };
            return Response
                    .ok(stream)
                    .header("Content-Disposition", "attachment; filename=\"" + name + ".zip\"")
                    .build();

        } catch (NotFoundException e) {
            Log.warnf("Volume download refused: volume=%s does not exist", name);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Output volume does not exist: " + name).build();
        } catch (Exception e) {
            Log.errorf(e, "Volume download failed: volume=%s", name);
            return Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Download failed: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public Response downloadFilesIds(Long workflowId, Long workflowNodeId) {
        StartWorkflowNodeDTO createDTO = new StartWorkflowNodeDTO();
        createDTO.setWorkflowNodeId(workflowNodeId);
        createDTO.setWorkflowId(workflowId);
        String volumeName = createDTO.getOutputVolumeName();
        Log.infof("Resolving output volume: workflowId=%d executionOrder=%d volume=%s", workflowId, workflowNodeId, volumeName);
        return downloadFiles(volumeName);
    }

    @Override
    public Response moveFilesToOutput(Long workflowId, Long workflowNodeId) {
        StartWorkflowNodeDTO createDTO = new StartWorkflowNodeDTO();
        createDTO.setWorkflowNodeId(workflowNodeId);
        createDTO.setWorkflowId(workflowId);
        String outputVolume = createDTO.getOutputVolumeName();
        String inputVolume = createDTO.getInputVolumeName();

        try {
            dockerVolumeService.moveDataFromInputToOutput(inputVolume, outputVolume);
        } catch (Exception e) {
            Log.errorf("Error moving files from input to output volume: %s", e.getMessage());
            throw new RuntimeException("Error moving files from input to output volume", e);
        }

        return Response.ok().build();
    }


    @Override
    public Response volumeSize(String name) {
        return null;
    }

    @Override
    public Response removeVolume(String name) {
        dockerVolumeService.removeVolume(name);
        return Response.ok().build();
    }

    @Override
    public Response removeAllVolumes() {
        dockerVolumeService.removeAllVolumes();
        return Response.ok().build();
    }
}
