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
# ClusterFuzzLite Dockerfile for AgentBridge.
#
# Build context is the repository root (ClusterFuzzLite invokes
# `docker build -f .clusterfuzzlite/Dockerfile <repo-root>`).
#
# Pinned by digest to satisfy supply-chain checks. Update via:
#   docker pull gcr.io/oss-fuzz-base/base-builder-jvm
#   docker inspect --format='{{index .RepoDigests 0}}' gcr.io/oss-fuzz-base/base-builder-jvm

FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:cb42140eb0e2cc7a89e9fd290993514637dea7e40fff796647dc68666ea6b337

# AgentBridge requires JDK 21. The base image ships an older JDK that cannot
# compile our sources. Install Eclipse Temurin 21 from the official Adoptium
# apt repo and make it the default JDK for the rest of the build.
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget gnupg ca-certificates \
    && mkdir -p /etc/apt/keyrings \
    && wget -qO /etc/apt/keyrings/adoptium.asc https://packages.adoptium.net/artifactory/api/gpg/key/public \
    && echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" \
       > /etc/apt/sources.list.d/adoptium.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends temurin-21-jdk unzip \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH
# jazzer_driver loads libjvm.so via LD_LIBRARY_PATH="${JVM_LD_LIBRARY_PATH}:..."
# at fuzzer runtime. Override the base image's Java-17 path so it picks up
# our Temurin 21 JVM instead (class file version 65 requires Java 21+).
ENV JVM_LD_LIBRARY_PATH=/usr/lib/jvm/temurin-21-jdk-amd64/lib/server

# Install Gradle 9.4.1 (matches gradle-wrapper.properties distributionUrl).
# The wrapper JAR is not committed (intentionally removed in favour of a
# system Gradle install), so we provision it here instead.
RUN wget -q https://services.gradle.org/distributions/gradle-9.4.1-bin.zip -O /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && rm /tmp/gradle.zip
ENV GRADLE_HOME=/opt/gradle-9.4.1
ENV PATH=$GRADLE_HOME/bin:$PATH

COPY . $SRC/agentbridge
WORKDIR $SRC/agentbridge

# Reuse the shared OSS-Fuzz build script. Both integrations produce the same
# Jazzer wrapper layout in $OUT, so a single script works for both.
COPY oss-fuzz/build.sh $SRC/build.sh
