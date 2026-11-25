package bio.cosy.featurecloud.orchestration.execution.k8s;

import bio.cosy.featurecloud.orchestration.docker.DockerLabels;
import bio.cosy.featurecloud.orchestration.docker.LabelsHelper;
import bio.cosy.featurecloud.orchestration.helper.NamingService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Kubernetes label values are far more restricted than Docker's (must match
 * {@code (([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?}, max 63 chars) — this
 * sanitizes {@code LabelsHelper}'s output (built for Docker, where almost
 * any string was fine) before it's attached to a Job/Pod. The label *keys*
 * from {@code LabelsHelper} (e.g. {@code feature-cloud.workflow-id}) are
 * already K8s-safe as-is.
 */
final class K8sLabels {

    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^A-Za-z0-9._-]");

    private K8sLabels() {
    }

    /**
     * This deployment's ownership labels ({@code LabelsHelper.createLabels()}),
     * Kubernetes-safe. Put on everything orch-api creates, and used as the
     * selector for listing and bulk deletes.
     */
    static Map<String, String> owned() {
        return sanitize(LabelsHelper.createLabels());
    }

    /**
     * Whether an object carries this deployment's system name, the same test
     * as {@code LabelsHelper.isOwned} on Docker. Every operation that takes a
     * name from a caller checks this, so orch-api never reads, writes or
     * deletes a Job, Pod or volume it didn't create, such as a database's PVC
     * in the same namespace.
     */
    static boolean isOwned(Map<String, String> labels) {
        // Label values are sanitized for Kubernetes, so compare the same way.
        return labels != null && sanitizeValue(NamingService.getSystemName())
                .equals(labels.get(DockerLabels.SYSTEM_NAME.toString()));
    }

    static Map<String, String> sanitize(Map<String, String> dockerLabels) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        dockerLabels.forEach((key, value) -> sanitized.put(key, sanitizeValue(value)));
        return sanitized;
    }

    static String sanitizeValue(String value) {
        if (value == null || value.isBlank()) {
            return "unset";
        }
        String cleaned = UNSAFE_CHARS.matcher(value).replaceAll("-");
        if (cleaned.length() > 63) {
            cleaned = cleaned.substring(0, 63);
        }
        // trim leading/trailing non-alphanumeric left over from truncation/replacement
        cleaned = K8sNames.trimNonAlphanumeric(cleaned);
        return cleaned.isEmpty() ? "unset" : cleaned;
    }
}
