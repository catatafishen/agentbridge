#!/bin/bash -eu
#
# OSS-Fuzz build script for AgentBridge (IntelliJ Copilot Plugin).
# Called inside the Docker container by OSS-Fuzz infrastructure.
# $SRC, $OUT, and $JAVA_HOME are set by the base image.

cd /src/agentbridge

# Build all test classes (includes fuzz targets)
./gradlew :plugin-core:testClasses :mcp-server:testClasses --no-daemon --quiet

# Resolve full test runtime classpath for each module
CP_CORE=$(./gradlew :plugin-core:printFuzzClasspath --no-daemon -q | tail -1)
CP_MCP=$(./gradlew :mcp-server:printFuzzClasspath --no-daemon -q | tail -1)
FULL_CP="${CP_CORE}:${CP_MCP}"

# Fuzz target classes — each has a fuzzerTestOneInput(FuzzedDataProvider) entry point
TARGETS=(
  com.github.catatafishen.agentbridge.fuzz.AgentIdMapperFuzz
  com.github.catatafishen.agentbridge.fuzz.MarkdownRendererFuzz
  com.github.catatafishen.agentbridge.fuzz.TimeArgParserFuzz
  com.github.catatafishen.agentbridge.fuzz.AbuseDetectorFuzz
  com.github.catatafishen.agentbridge.fuzz.NewSessionResponseFuzz
  com.github.catatafishen.agentbridge.fuzz.JunitXmlParserFuzz
  com.github.copilot.mcp.McpStdioProxyFuzz
)

for target in "${TARGETS[@]}"; do
  short_name="${target##*.}"
  # $JAZZER_FUZZ_TARGET_CLASS is read by the Jazzer driver
  cat > "$OUT/${short_name}.sh" <<EOF
#!/bin/bash
export JAZZER_FUZZ_TARGET_CLASS=${target}
this_dir=\$(dirname "\$0")
"\$this_dir/${short_name}_driver" "\$@"
EOF
  chmod +x "$OUT/${short_name}.sh"

  # Package Jazzer driver + classpath into a single archive
  "$JAVA_HOME/bin/jar" cf "$OUT/${short_name}_seed_corpus.zip" -C /dev/null . 2>/dev/null || true
  compile_java_fuzzer "$SRC/agentbridge" "$target" "$OUT/${short_name}" "$FULL_CP"
done
