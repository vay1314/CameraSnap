package io.github.vay1314.camerasnap;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaSavePathTest {
    @Test public void supportsPublicCameraFoldersAndUnicode() {
        assertEquals("DCIM", MediaSavePath.normalize("DCIM"));
        assertEquals("DCIM/街拍/照片 视频", MediaSavePath.normalize("DCIM/街拍/照片 视频"));
    }

    @Test public void normalizesOuterWhitespaceAndTrailingSeparators() {
        assertEquals(MediaSavePath.DEFAULT, MediaSavePath.normalize("  DCIM/Camera/Snap///  "));
    }

    @Test public void rejectsAbsoluteAndUnsupportedRoots() {
        for (String path : new String[]{"", "/storage/emulated/0/DCIM/Snap", "/DCIM/Snap", "Download/Snap", "DCIM2/Snap"})
            assertThrows(IllegalArgumentException.class, () -> MediaSavePath.normalize(path));
    }

    @Test public void rejectsTraversalAndHiddenDirectories() {
        for (String path : new String[]{"DCIM/../Download", "DCIM/./Snap", "DCIM/.hidden", "DCIM//Snap"})
            assertThrows(IllegalArgumentException.class, () -> MediaSavePath.normalize(path));
    }

    @Test public void rejectsInvalidOrOversizedDirectoryNames() {
        for (String path : new String[]{"DCIM/Snap\nTest", "DCIM/Snap\\Test", "DCIM/Snap:Test", "DCIM/ Snap", "DCIM/" + "照".repeat(86)})
            assertThrows(IllegalArgumentException.class, () -> MediaSavePath.normalize(path));
    }

    @Test public void missingOrInvalidSavedConfigurationUsesDefault() {
        assertEquals(MediaSavePath.DEFAULT, MediaSavePath.orDefault(null));
        assertEquals(MediaSavePath.DEFAULT, MediaSavePath.orDefault("DCIM/../Download"));
        assertEquals("DCIM/Snap", MediaSavePath.orDefault("DCIM/Snap"));
    }
}
