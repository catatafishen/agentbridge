#!/bin/bash -eu
# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
################################################################################
#
# Shared build script for AgentBridge (IntelliJ Copilot Plugin) fuzz targets.
# Used by both OSS-Fuzz (oss-fuzz/Dockerfile) and ClusterFuzzLite
# (.clusterfuzzlite/Dockerfile). Called inside the Docker container by the
# fuzzing infrastructure. $SRC, $OUT, and $JAVA_HOME are set by the base image.

cd "$SRC/agentbridge"

# Build all test classes (fuzz targets live in the test source sets).
# -x buildChatUi: skip the npm/TypeScript chat-UI build — not needed for fuzzing.
gradle :plugin-core:testClasses :mcp-server:testClasses --no-daemon --quiet -x buildChatUi

# Resolve full test runtime classpaths (includes compiled classes + all dep JARs).
CP_CORE=$(gradle :plugin-core:printFuzzClasspath --no-daemon -q -x buildChatUi | tail -1)
CP_MCP=$(gradle :mcp-server:printFuzzClasspath --no-daemon -q | tail -1)

# Bundle a minimal JRE 21 into $OUT/jvm. The clusterfuzzlite-run-fuzzers
# and OSS-Fuzz run containers ship Java 17 only (max class file version 61),
# but our codebase targets Java 21 (version 65). We ship Temurin 21 with the
# fuzzers so jazzer_driver can load libjvm.so from a Java-21 JRE at runtime.
# Use jlink to keep the bundle small (~70 MB instead of ~280 MB full JDK).
"$JAVA_HOME/bin/jlink" \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.base,java.logging,java.xml,java.naming,java.management,java.security.jgss,java.sql,java.desktop,java.instrument,jdk.attach,jdk.unsupported,jdk.management,jdk.management.agent,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.zipfs,jdk.localedata \
  --include-locales=en \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=zip-6 \
  --output "$OUT/jvm"

# Copy every JAR from the classpath into $OUT/ and every class directory into
# $OUT/classes/.  The jazzer_driver wrapper uses "$this_dir/*" (Java wildcard
# classpath) and "$this_dir/classes" at fuzzing runtime.
mkdir -p "$OUT/classes"
for cp in "$CP_CORE" "$CP_MCP"; do
  IFS=':' read -ra entries <<< "$cp"
  for entry in "${entries[@]}"; do
    if [[ -f "$entry" && "$entry" == *.jar ]]; then
      cp -n "$entry" "$OUT/" 2>/dev/null || true
    elif [[ -d "$entry" ]]; then
      cp -rn "$entry/." "$OUT/classes/" 2>/dev/null || true
    fi
  done
done

# Fuzz target classes — each exposes fuzzerTestOneInput(FuzzedDataProvider).
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

  # Create the execution wrapper (the file OSS-Fuzz treats as the fuzzer binary).
  cat > "$OUT/${short_name}" << EOF
#!/bin/bash
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname "\$0")
if [[ "\$@" =~ (^| )-runs=[0-9]+(\$| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi
# Use the bundled Temurin 21 JRE (project compiles to class file version 65,
# which the run container's Java 17 cannot load). The bundled jvm/lib/server
# is prepended to LD_LIBRARY_PATH so jazzer_driver picks up libjvm.so from
# Java 21 instead of the system Java 17.
export JAVA_HOME="\$this_dir/jvm"
LD_LIBRARY_PATH="\$this_dir/jvm/lib/server:\$this_dir/jvm/lib:\$this_dir" \\
ASAN_OPTIONS=\$ASAN_OPTIONS:symbolize=1:external_symbolizer_path=\$this_dir/llvm-symbolizer:detect_leaks=0 \\
\$this_dir/jazzer_driver --agent_path=\$this_dir/jazzer_agent_deploy.jar \\
--cp=\$this_dir/classes:\$this_dir/* \\
--target_class=${target} \\
--jvm_args="\$mem_settings" \\
"\$@"
EOF
  chmod +x "$OUT/${short_name}"
done
