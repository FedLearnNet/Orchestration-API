package bio.cosy.featurecloud.orchestration.execution.k8s;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Maps orch-api's volume names, built for Docker by {@code BaseName}
 * (e.g. {@code 23066b0878f77e41-fc-w5-n0_volume_output}), to valid
 * PersistentVolumeClaim names. Kubernetes only accepts a lowercase RFC 1123
 * subdomain there, so {@code _} gets the run rejected with a 422.
 *
 * <p>The Docker name stays the volume's identity everywhere else in
 * orch-api; this is applied at each call into the Kubernetes API. It is
 * idempotent, so a name read back from Kubernetes (a listing, a Pod's
 * claim) maps to itself when it is passed in again.
 */
final class K8sNames {

    private static final int MAX_LENGTH = 253;
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^a-z0-9.-]");

    private K8sNames() {
    }

    static String pvcName(String volumeName) {
        if (volumeName == null || volumeName.isBlank()) {
            return volumeName;
        }
        String cleaned = trimNonAlphanumeric(UNSAFE_CHARS.matcher(volumeName.toLowerCase()).replaceAll("-"));
        if (cleaned.length() > MAX_LENGTH) {
            String hash = sha256(volumeName).substring(0, 10);
            cleaned = trimNonAlphanumeric(cleaned.substring(0, MAX_LENGTH - hash.length() - 1)) + "-" + hash;
        }
        return cleaned.isEmpty() ? "vol-" + sha256(volumeName).substring(0, 10) : cleaned;
    }

    /**
     * Strips leading and trailing characters that aren't ASCII letters or
     * digits. A loop rather than {@code replaceAll("[^a-z0-9]+$", "")}: that
     * pattern takes quadratic time on long runs of such characters.
     */
    static String trimNonAlphanumeric(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && !isAsciiAlphanumeric(value.charAt(start))) {
            start++;
        }
        while (end > start && !isAsciiAlphanumeric(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
