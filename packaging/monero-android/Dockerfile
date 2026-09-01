# Reproducible Monero wallet2_api build for Android (XMR-P0).
# Pinned base + NDK + Monero source; builds official Monero from source only,
# no prebuilt Monero binary. Produces libzmonero.so per ABI.
FROM debian:bookworm-20250630-slim

ENV DEBIAN_FRONTEND=noninteractive

# Toolchain + Monero build dependencies (host side).
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl unzip git build-essential cmake pkg-config \
        libtool automake autoconf ccache gperf python3 file xz-utils \
    && rm -rf /var/lib/apt/lists/*

# --- Pinned Android NDK r27b (== Android Studio ndkVersion 27.1.12297006) ---
# The downloaded zip's SHA-256 is recorded into the build manifest at run time
# (PROVENANCE.md carries the pinned expected value once first observed).
ENV NDK_VERSION=r27b
ENV NDK_SHA256=33e16af1a6bbabe12cad54b2117085c07eab7e4fa67cdd831805f0e94fd826c1
RUN curl -fsSL -o /tmp/ndk.zip \
        https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip \
    && echo "${NDK_SHA256}  /tmp/ndk.zip" | sha256sum -c - \
    && echo "${NDK_SHA256}  /tmp/ndk.zip" > /opt/ndk.sha256 \
    && unzip -q /tmp/ndk.zip -d /opt \
    && rm /tmp/ndk.zip
ENV ANDROID_NDK_HOME=/opt/android-ndk-${NDK_VERSION}

# --- Pinned dependency + Monero versions (recorded in PROVENANCE.md) ---
ENV OPENSSL_VERSION=1.1.1w
ENV BOOST_VERSION=1.84.0
ENV BOOST_UNDERSCORE=1_84_0
ENV SODIUM_VERSION=1.0.19
ENV MONERO_TAG=v0.18.5.1
ENV MONERO_COMMIT=4f92268d7c16741cfb41e5bbe2aa46cc260a9ea5

WORKDIR /build
COPY build-monero-android.sh /build/build-monero-android.sh
COPY jni/zmonero.cpp /build/jni/zmonero.cpp
RUN chmod +x /build/build-monero-android.sh

# ABI is passed at run time: docker run -e ABI=arm64-v8a ...
ENTRYPOINT ["/build/build-monero-android.sh"]
