package com.github.catatafishen.agentbridge.services.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HookUpdateNotifierTest {

    @Test
    void keepAllRecordsDeclinedBundledVersionWithoutClaimingCustomContent(@TempDir Path hooksDir)
        throws Exception {
        String relativePath = "run_command.json";
        Path customFile = hooksDir.resolve(relativePath);
        Files.writeString(customFile, "custom project hooks");
        String newBundledHash = HookHashRegistry.computeStringHash("new generic defaults");
        Map<String, String> hashes = new HashMap<>();
        HookUpdateNotifier.Conflict conflict = new HookUpdateNotifier.Conflict(
            relativePath,
            "new generic defaults",
            customFile,
            newBundledHash
        );

        HookUpdateNotifier.keepAll(List.of(conflict), hashes, hooksDir);

        assertEquals("custom project hooks", Files.readString(customFile));
        assertEquals(newBundledHash, hashes.get(relativePath));
        assertEquals(hashes, HookHashRegistry.load(hooksDir));
    }
}
