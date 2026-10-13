package bio.cosy.featurecloud.orchestration.service;

import bio.cosy.featurecloud.orchestration.config.PipelineConfig;
import bio.cosy.featurecloud.orchestration.config.PipelineConfig.ImageTrustConfig;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@ApplicationScoped
public class ImageTrustService {

    private static final Pattern DIGEST_REFERENCE = Pattern.compile("[^\\s@]+@sha256:[0-9a-f]{64}");
    private static final String VERIFY_LOG_NAME = "verify.log";

    @Inject
    PipelineConfig pipelineConfig;

    public void verifyApplicationImage(String imageName) throws Exception {
        verifyImage(imageName, pipelineConfig.imageTrust().tools(), "private");
    }

    public void verifyPipelineImage(String imageName) throws Exception {
        verifyImage(imageName, pipelineConfig.imageTrust().pipeline(), "ghcr");
    }

    private void verifyImage(String imageName, ImageTrustConfig.Policy policy, String clientName) throws Exception {
        if (!policy.enabled()) {
            return;
        }
        if (imageName == null || !DIGEST_REFERENCE.matcher(imageName).matches()) {
            throw new ForbiddenException("Image trust requires a registry manifest digest: " + imageName);
        }
        String publicKey = policy.publicKey().orElse(null);
        if (StringUtils.isBlank(publicKey)) {
            throw new IllegalStateException("Image trust public key is not configured for " + clientName);
        }
        runImageTrust(imageName, publicKey);
    }

    private void runImageTrust(String imageName, String publicKey) throws IOException, InterruptedException {
        Log.infof("Verifying Docker image signature: %s", imageName);
        try {
            List<String> command = List.of(
                    pipelineConfig.imageTrust().executable(),
                    "verify",
                    "--key", publicKey,
                    imageName);
            int exitCode = runCosign(command);
            if (exitCode != 0) {
                throw new ForbiddenException("Docker image signature is missing or invalid: " + imageName);
            }
            Log.infof("Docker image signature verified: %s", imageName);
        } catch (Exception e) {
            Log.errorf("Failed to verify Docker image %s: %s", imageName, e.getMessage());
            throw e;
        }
    }

    private int runCosign(List<String> command) throws IOException, InterruptedException {
        Duration timeout = pipelineConfig.imageTrust().timeout();
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalStateException("Image trust timeout must be positive");
        }
        Path workDir = Files.createTempDirectory("orch-image-trust-");
        Process process = null;
        try {

            process = buildProcess(workDir, command)
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Docker image signature verification timed out");
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            cleanupFiles(workDir);
        }
    }

    private ProcessBuilder buildProcess(Path workDir, List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(workDir.resolve(VERIFY_LOG_NAME).toFile());
        builder.environment().put("DOCKER_CONFIG", workDir.toString());
        return builder;
    }

    private void cleanupFiles(Path workDir) throws IOException {
        Files.deleteIfExists(workDir.resolve(VERIFY_LOG_NAME));
        Files.deleteIfExists(workDir);
    }

}
