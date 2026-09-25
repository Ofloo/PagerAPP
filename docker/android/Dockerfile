# Android build image for PagerAPP.
# The host only needs Docker; no JDK/SDK is installed on Ubuntu.
# The base image already provides the Android SDK (ANDROID_HOME=/opt/android-sdk-linux),
# platform android-34, build-tools 34.0.0, and a JDK. We only add what is missing.
FROM ghcr.io/cirruslabs/android-sdk:34

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
