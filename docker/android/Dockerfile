# Android build image for PagerAPP.
# The host only needs Docker; no JDK/SDK is installed on Ubuntu.
FROM ghcr.io/cirruslabs/android-sdk:34

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jdk unzip curl \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

WORKDIR /workspace
