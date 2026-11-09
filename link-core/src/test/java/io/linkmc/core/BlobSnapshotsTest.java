package io.linkmc.core;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BlobSnapshotsTest{
    @Test void emptyManifestIsAnExplicitTombstone()throws Exception{
        assertTrue(BlobSnapshots.get(Map.of("world/manifest",new byte[0],"world/0",new byte[]{1}),"world").isEmpty());
    }
    @Test void roundTripAndCorruptionDetection()throws Exception{
        byte[] bytes=new byte[9*1024*1024];new Random(1).nextBytes(bytes);
        Map<String,byte[]> data=new HashMap<>();BlobSnapshots.put(data,"world",bytes);
        assertArrayEquals(bytes,BlobSnapshots.get(data,"world").orElseThrow());
        data.get("world/1")[0]^=1;
        assertThrows(IllegalStateException.class,()->BlobSnapshots.get(data,"world"));
    }
    @Test void missingPartIsNeverSilentlyLoaded()throws Exception{
        Map<String,byte[]> data=new HashMap<>();BlobSnapshots.put(data,"world",new byte[]{1});data.remove("world/0");
        assertThrows(IllegalStateException.class,()->BlobSnapshots.get(data,"world"));
    }
}
