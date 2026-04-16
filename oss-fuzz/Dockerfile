FROM gcr.io/oss-fuzz-base/base-builder-jvm

RUN curl -sSfL -o /usr/local/bin/gradle https://services.gradle.org/distributions/gradle-8.14-bin.zip || true

RUN git clone --depth 1 https://github.com/catatafishen/agentbridge.git /src/agentbridge
WORKDIR /src/agentbridge

COPY build.sh $SRC/
