package com.github.catatafishen.agentbridge.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BwrapSandboxTest {

    @TempDir
    Path tempDir;

    // ─── detectInterpreterResolution ─────────────────────────────────────────

    @Test
    void returnsNullForElfBinary() throws IOException {
        Path binary = tempDir.resolve("agent");
        Files.write(binary, new byte[]{0x7F, 'E', 'L', 'F', 0, 0, 0, 0});

        assertNull(BwrapSandbox.detectInterpreterResolution(binary.toString()));
    }

    @Test
    void returnsNullForEmptyFile() throws IOException {
        Path binary = tempDir.resolve("agent");
        Files.write(binary, new byte[]{});

        assertNull(BwrapSandbox.detectInterpreterResolution(binary.toString()));
    }

    @Test
    void directShebangResolvesWithoutExplicitCall() throws IOException {
        Path fakeNode = tempDir.resolve("node");
        Files.write(fakeNode, new byte[]{0x7F, 'E', 'L', 'F'});

        Path script = tempDir.resolve("agent");
        Files.writeString(script, "#!" + fakeNode + "\nconsole.log('hi');\n");

        BwrapSandbox.InterpreterResolution result =
            BwrapSandbox.detectInterpreterResolution(script.toString());

        assertNotNull(result);
        assertEquals(fakeNode.toString(), result.interpreterPath());
        assertFalse(result.requiresExplicitCall(),
            "Direct shebang: kernel can follow it directly, no explicit call needed");
    }

    @Test
    void directShebangWithNonexistentInterpreterReturnsNull() throws IOException {
        Path script = tempDir.resolve("agent");
        Files.writeString(script, "#!/nonexistent/path/node\nconsole.log('hi');\n");

        assertNull(BwrapSandbox.detectInterpreterResolution(script.toString()));
    }

    @Test
    void envShebangDoesNotThrowEvenIfInterpreterNotOnPath() throws IOException {
        Path script = tempDir.resolve("copilot");
        Files.writeString(script, "#!/usr/bin/env node\nconsole.log('hi');\n");

        // If node is on PATH → returns resolution with requiresExplicitCall=true
        // If node is not on PATH → returns null
        // Either way, must not throw.
        BwrapSandbox.detectInterpreterResolution(script.toString());
    }

    @Test
    void envShebangRequiresExplicitCallWhenInterpreterFound() throws IOException {
        // Write a minimal ELF-like binary as "node" so resolveOnShellPath won't find it
        // through the real PATH (we can't inject PATH here), but we can test the result
        // of the record itself.
        BwrapSandbox.InterpreterResolution resolution =
            new BwrapSandbox.InterpreterResolution("/some/path/node", true);

        assertTrue(resolution.requiresExplicitCall());
        assertEquals("/some/path/node", resolution.interpreterPath());
    }

    // ─── buildWrappedCommandWithResolution ───────────────────────────────────

    @Test
    void envShebangCommandHasInterpreterPrepended() throws IOException {
        Path fakeNode = tempDir.resolve("node");
        Files.write(fakeNode, new byte[]{0x7F, 'E', 'L', 'F'});

        Path agentScript = tempDir.resolve("copilot");
        Files.writeString(agentScript, "#!/usr/bin/env node\n// cli\n");

        BwrapSandbox.InterpreterResolution resolution =
            new BwrapSandbox.InterpreterResolution(fakeNode.toString(), true);

        List<String> originalCmd = List.of(agentScript.toString(), "--stdio");
        List<String> wrapped = BwrapSandbox.buildWrappedCommandWithResolution(
            agentScript.toString(), List.of(), originalCmd, resolution);

        int dashDash = wrapped.indexOf("--");
        assertNotEquals(-1, dashDash, "bwrap command must contain '--' separator");

        List<String> afterDashDash = wrapped.subList(dashDash + 1, wrapped.size());
        assertEquals(fakeNode.toString(), afterDashDash.get(0),
            "Interpreter must come first after '--' for env-shebang scripts");
        assertEquals(agentScript.toString(), afterDashDash.get(1),
            "Script path must follow the interpreter");
        assertEquals("--stdio", afterDashDash.get(2),
            "Original arguments must be preserved");
    }

    @Test
    void directShebangCommandUnchanged() throws IOException {
        Path fakeNode = tempDir.resolve("node");
        Files.write(fakeNode, new byte[]{0x7F, 'E', 'L', 'F'});

        Path agentScript = tempDir.resolve("agent");
        Files.writeString(agentScript, "#!" + fakeNode + "\n// cli\n");

        BwrapSandbox.InterpreterResolution resolution =
            new BwrapSandbox.InterpreterResolution(fakeNode.toString(), false);

        List<String> originalCmd = List.of(agentScript.toString(), "--stdio");
        List<String> wrapped = BwrapSandbox.buildWrappedCommandWithResolution(
            agentScript.toString(), List.of(), originalCmd, resolution);

        int dashDash = wrapped.indexOf("--");
        assertNotEquals(-1, dashDash);

        List<String> afterDashDash = wrapped.subList(dashDash + 1, wrapped.size());
        assertEquals(agentScript.toString(), afterDashDash.get(0),
            "Direct shebang: original command must not have interpreter prepended");
        assertEquals("--stdio", afterDashDash.get(1));
    }

    @Test
    void noInterpreterResolutionCommandUnchanged() throws IOException {
        Path elfBinary = tempDir.resolve("agent");
        Files.write(elfBinary, new byte[]{0x7F, 'E', 'L', 'F', 0, 0, 0, 0});

        List<String> originalCmd = List.of(elfBinary.toString(), "--stdio");
        List<String> wrapped = BwrapSandbox.buildWrappedCommandWithResolution(
            elfBinary.toString(), List.of(), originalCmd, null);

        int dashDash = wrapped.indexOf("--");
        assertNotEquals(-1, dashDash);

        List<String> afterDashDash = wrapped.subList(dashDash + 1, wrapped.size());
        assertEquals(elfBinary.toString(), afterDashDash.get(0),
            "ELF binary: original command must not be modified");
        assertEquals("--stdio", afterDashDash.get(1));
    }

    @Test
    void envShebangInterpreterIsBoundInArgs() throws IOException {
        Path fakeNode = tempDir.resolve("node");
        Files.write(fakeNode, new byte[]{0x7F, 'E', 'L', 'F'});

        Path agentScript = tempDir.resolve("copilot");
        Files.writeString(agentScript, "#!/usr/bin/env node\n// cli\n");

        BwrapSandbox.InterpreterResolution resolution =
            new BwrapSandbox.InterpreterResolution(fakeNode.toString(), true);

        List<String> wrapped = BwrapSandbox.buildWrappedCommandWithResolution(
            agentScript.toString(), List.of(), List.of(agentScript.toString()), resolution);

        int dashDash = wrapped.indexOf("--");
        List<String> bwrapArgs = wrapped.subList(0, dashDash);
        assertTrue(bwrapArgs.contains(fakeNode.toString()),
            "Interpreter must be bound into the sandbox (appear in bwrap args before '--')");
    }

    // ─── buildBwrapArgs (mount ordering + chdir) ──────────────────────────────

    @Test
    void configBindsMountedAfterHomeTmpfs() throws IOException {
        // A config bind under /home (e.g., ~/.copilot) must appear AFTER the --tmpfs /home
        // mount in the arg list. bwrap processes args sequentially: a later bind overlays on
        // top of an earlier tmpfs, so it is visible; a bind placed before tmpfs is hidden.
        Path fakeNode = tempDir.resolve("node");
        Files.write(fakeNode, new byte[]{0x7F, 'E', 'L', 'F'});

        Path agentScript = tempDir.resolve("copilot");
        Files.writeString(agentScript, "#!" + fakeNode + "\n// cli\n");

        Path configBind = Path.of("/home/user/.copilot");

        List<String> wrapped = BwrapSandbox.buildWrappedCommandWithResolution(
            agentScript.toString(), List.of(configBind), List.of(agentScript.toString()), null);

        int dashDash = wrapped.indexOf("--");
        List<String> bwrapArgs = wrapped.subList(0, dashDash);

        int homeTmpfsIdx = -1;
        int configBindIdx = -1;
        for (int i = 0; i < bwrapArgs.size(); i++) {
            if ("--tmpfs".equals(bwrapArgs.get(i)) && i + 1 < bwrapArgs.size()
                && "/home".equals(bwrapArgs.get(i + 1))) {
                homeTmpfsIdx = i;
            }
            if (("--ro-bind".equals(bwrapArgs.get(i)) || "--ro-bind-try".equals(bwrapArgs.get(i)))
                && i + 1 < bwrapArgs.size()
                && configBind.toString().equals(bwrapArgs.get(i + 1))) {
                configBindIdx = i;
            }
        }

        assertNotEquals(-1, homeTmpfsIdx, "--tmpfs /home must be present in bwrap args");
        assertNotEquals(-1, configBindIdx, "config bind must be present in bwrap args");
        assertTrue(homeTmpfsIdx < configBindIdx,
            "--tmpfs /home (idx=" + homeTmpfsIdx + ") must come before config bind (idx="
                + configBindIdx + ") so the bind overlays the tmpfs and is visible");
    }

    @Test
    void chDirSetToTmp() throws IOException {
        // bwrap must use --chdir /tmp. Without it, bwrap inherits the parent's CWD
        // (typically the project path), which is not mounted in the sandbox,
        // causing ENOENT on startup.
        Path elfBinary = tempDir.resolve("agent");
        Files.write(elfBinary, new byte[]{0x7F, 'E', 'L', 'F', 0, 0, 0, 0});

        List<String> wrapped = BwrapSandbox.buildWrappedCommandWithResolution(
            elfBinary.toString(), List.of(), List.of(elfBinary.toString()), null);

        int dashDash = wrapped.indexOf("--");
        List<String> bwrapArgs = wrapped.subList(0, dashDash);

        int chDirIdx = bwrapArgs.indexOf("--chdir");
        assertNotEquals(-1, chDirIdx, "--chdir must be present in bwrap args");
        assertEquals("/tmp", bwrapArgs.get(chDirIdx + 1),
            "--chdir must be set to /tmp so the process starts in a valid sandbox directory");
    }

    // ─── package.json directory binding ─────────────────────────────────────────

    @Test
    void packageDirBoundWhenPackageJsonPresent() throws IOException {
        // When the agent binary lives next to a package.json (e.g. a Node.js package),
        // bwrap must bind the entire parent directory — not just the binary file.
        // Node.js walks up from the script's directory to find package.json and determine
        // whether the file is ESM or CommonJS. Binding only the binary leaves that search
        // with nothing to find, causing "To load an ES module, set 'type': 'module'" errors.
        Path pkgDir = tempDir.resolve("mypkg");
        Files.createDirectory(pkgDir);
        Files.writeString(pkgDir.resolve("package.json"), "{\"type\":\"module\"}");
        Path script = pkgDir.resolve("index.js");
        Files.writeString(script, "#!/usr/bin/env node\nexport default {};\n");

        List<String> wrapped = BwrapSandbox.buildWrappedCommandWithResolution(
            script.toString(), List.of(), List.of(script.toString()), null);

        int dashDash = wrapped.indexOf("--");
        List<String> bwrapArgs = wrapped.subList(0, dashDash);

        assertTrue(bwrapArgs.contains(pkgDir.toString()),
            "Package directory must be bound (not just the binary) when package.json is present");
        assertFalse(bwrapArgs.contains(script.toString()),
            "Binary file path alone must NOT appear as a bind when the package directory is bound");
    }

    @Test
    void singleBinaryBoundWhenNoPackageJson() throws IOException {
        // When the binary has no adjacent package.json (e.g. a compiled ELF binary),
        // the binary file itself is bound — no directory bind.
        Path elfBinary = tempDir.resolve("agent");
        Files.write(elfBinary, new byte[]{0x7F, 'E', 'L', 'F', 0, 0, 0, 0});

        List<String> wrapped = BwrapSandbox.buildWrappedCommandWithResolution(
            elfBinary.toString(), List.of(), List.of(elfBinary.toString()), null);

        int dashDash = wrapped.indexOf("--");
        List<String> bwrapArgs = wrapped.subList(0, dashDash);

        assertTrue(bwrapArgs.contains(elfBinary.toString()),
            "Binary file path must be bound directly when no package.json is present");
        assertFalse(bwrapArgs.contains(tempDir.toString()),
            "Parent directory must NOT be bound when no package.json is present");
    }
}
