package io.github.vay1314.camerasnap;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigProviderTest {
    @Test public void moduleAndSystemCanAccessConfiguration() {
        assertTrue(ConfigProvider.isTrustedCaller(1000, 10123));
        assertTrue(ConfigProvider.isTrustedCaller(10123, 10123));
    }

    @Test public void otherApplicationUidsAreRejected() {
        assertFalse(ConfigProvider.isTrustedCaller(10124, 10123));
        assertFalse(ConfigProvider.isTrustedCaller(110123, 10123));
        assertFalse(ConfigProvider.isTrustedCaller(1001, 10123));
        assertFalse(ConfigProvider.isTrustedCaller(0, 10123));
        assertFalse(ConfigProvider.isTrustedCaller(-1, 10123));
    }
}
