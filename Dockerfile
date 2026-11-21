# Android build tools include x86-64 Linux binaries.
ARG ANDROID_BUILD_PLATFORM=linux/amd64
FROM --platform=${ANDROID_BUILD_PLATFORM} gradle:8.11.1-jdk17 AS jdk
FROM --platform=${ANDROID_BUILD_PLATFORM} ghcr.io/cirruslabs/android-sdk:35

COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /workspace
COPY . /workspace
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --build-cache :protocol:test :app:lintDebug :app:assembleDebug || { \
        status=$?; \
        report=app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt; \
        if [ -f "$report" ]; then cat "$report"; fi; \
        exit "$status"; \
    }
