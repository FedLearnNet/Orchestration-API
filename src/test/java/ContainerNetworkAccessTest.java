import bio.cosy.featurecloud.orchestration.docker.ContainerNetworkAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerNetworkAccessTest {

    @Test
    public void treatsUnsetFlagsAsNotRequested() {
        ContainerNetworkAccess access = ContainerNetworkAccess.of(null, null, null);

        assertFalse(access.internet());
        assertFalse(access.host());
        assertFalse(access.federatedLearning());
        assertTrue(access.internalOnly());
    }

    @Test
    public void keepsTheNetworkRoutableForInternetAccess() {
        assertFalse(ContainerNetworkAccess.of(true, false, false).internalOnly());
    }

    @Test
    public void keepsTheNetworkRoutableForHostAccess() {
        // An internal network has no route to the host-gateway behind host.docker.internal
        ContainerNetworkAccess access = ContainerNetworkAccess.of(false, true, false);

        assertFalse(access.internalOnly());
        assertTrue(access.hostAccessWidensNetwork());
    }

    @Test
    public void federatedLearningAloneStaysInternalOnly() {
        // The controller joins the dedicated network, so no route off the bridge is needed
        ContainerNetworkAccess access = ContainerNetworkAccess.of(false, false, true);

        assertTrue(access.internalOnly());
        assertFalse(access.hostAccessWidensNetwork());
    }

    @Test
    public void doesNotWarnWhenInternetAccessWasRequestedAnyway() {
        assertFalse(ContainerNetworkAccess.of(true, true, false).hostAccessWidensNetwork());
    }
}
