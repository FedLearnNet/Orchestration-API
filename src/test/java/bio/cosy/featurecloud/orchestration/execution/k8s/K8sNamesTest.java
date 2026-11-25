package bio.cosy.featurecloud.orchestration.execution.k8s;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PVC names must be lowercase RFC 1123 subdomains. orch-api's volume names
 * come from {@code BaseName} and contain {@code _}, which Kubernetes rejects
 * with a 422.
 */
class K8sNamesTest {

    private static final String RFC1123_SUBDOMAIN =
            "[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*";

    @Test
    void aBaseNameVolumeNameIsMadeValid() {
        assertEquals("23066b0878f77e41-fc-w5-n0-volume-output",
                K8sNames.pvcName("23066b0878f77e41-fc-w5-n0_volume_output"));
    }

    @Test
    void baseNameVolumesAreValidAndDistinct() {
        // BaseName.createInputVolumeName / createOutputVolumeName, for both
        // container name shapes it builds (workflow node and group/id).
        for (String container : List.of("23066b0878f77e41-fc-w5-n0", "23066b0878f77e41_fc_g7_id42")) {
            String in = K8sNames.pvcName(container + "_volume_input");
            String out = K8sNames.pvcName(container + "_volume_output");
            assertTrue(in.matches(RFC1123_SUBDOMAIN), in);
            assertTrue(out.matches(RFC1123_SUBDOMAIN), out);
            assertNotEquals(in, out);
        }
    }

    @Test
    void validNamesAreLeftAlone() {
        assertEquals("already-valid.name-1", K8sNames.pvcName("already-valid.name-1"));
    }

    @Test
    void mappingIsIdempotent() {
        for (String name : List.of("a_b_C", "_x_", "ok-name", "x".repeat(300) + "_y")) {
            String once = K8sNames.pvcName(name);
            assertEquals(once, K8sNames.pvcName(once), name);
        }
    }

    @Test
    void longNamesStayWithinTheLimitAndApart() {
        String a = K8sNames.pvcName("x".repeat(300) + "_a");
        String b = K8sNames.pvcName("x".repeat(300) + "_b");
        assertTrue(a.length() <= 253 && a.matches(RFC1123_SUBDOMAIN), a);
        assertNotEquals(a, b);
    }

    @Test
    void trimMatchesTheOldRegexAndStaysFastOnLongRuns() {
        for (String in : new String[]{"", "-", "--a--", "a", "/a/b/", "..x..", "A-b_C", "9", "-9-", "a--b"}) {
            String regex = in.replaceAll("^[^A-Za-z0-9]+", "").replaceAll("[^A-Za-z0-9]+$", "");
            org.junit.jupiter.api.Assertions.assertEquals(regex, K8sNames.trimNonAlphanumeric(in), in);
        }
        String slashes = "/".repeat(200_000) + "a" + "/".repeat(200_000);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),
                () -> org.junit.jupiter.api.Assertions.assertEquals("a", K8sNames.trimNonAlphanumeric(slashes)));
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> K8sNames.pvcName("x" + "/".repeat(100_000) + "x"));
    }
}
