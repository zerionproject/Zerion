# Reproducible Monero wallet2_api build for Android (XMR-P0).
# Pinned base + NDK + Monero source; builds official Monero from source only,
# no prebuilt Monero binary. Produces libzmonero.so per ABI.
# The base image is pinned by content digest (the tag alone can be moved),
# and the host toolchain comes from a dated Debian snapshot at exact package
# versions, so the image cannot change under the recipe.
FROM debian:bookworm-20250630-slim@sha256:6ac2c08566499cc2415926653cf2ed7c3aedac445675a013cc09469c9e118fdd

ENV DEBIAN_FRONTEND=noninteractive
ENV DEBIAN_SNAPSHOT=20260921T000000Z

RUN printf 'Types: deb\nURIs: http://snapshot.debian.org/archive/debian/%s\nSuites: bookworm bookworm-updates\nComponents: main\nSigned-By: /usr/share/keyrings/debian-archive-keyring.gpg\n\nTypes: deb\nURIs: http://snapshot.debian.org/archive/debian-security/%s\nSuites: bookworm-security\nComponents: main\nSigned-By: /usr/share/keyrings/debian-archive-keyring.gpg\n' \
        "${DEBIAN_SNAPSHOT}" "${DEBIAN_SNAPSHOT}" > /etc/apt/sources.list.d/debian.sources \
    && echo 'Acquire::Check-Valid-Until "false";' > /etc/apt/apt.conf.d/99snapshot \
    && echo 'Acquire::Retries "5";' >> /etc/apt/apt.conf.d/99snapshot

# Toolchain + Monero build dependencies (host side), at the versions the
# accepted hashes were produced with.
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates=20250419~deb12u1 \
        curl=7.88.1-10+deb12u15 \
        unzip=6.0-28+deb12u1 \
        git=1:2.39.5-0+deb12u3 \
        build-essential=12.9 \
        cmake=3.25.1-1 \
        pkg-config=1.8.1-1 \
        libtool=2.4.7-7~deb12u1 \
        automake=1:1.16.5-1.3 \
        autoconf=2.71-3 \
        ccache=4.8+really4.7.5-1 \
        gperf=3.1-1 \
        python3=3.11.2-1+b1 \
        file=1:5.44-3 \
        xz-utils=5.4.1-1+deb12u1 \
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
ENV OPENSSL_VERSION=3.5.8
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
