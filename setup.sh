#!/bin/bash
#
# Setup script for building the SIP-GSM Gateway APK on Debian/Ubuntu.
# Installs JDK 17, Android SDK, build tools, and Gradle wrapper.
#
# Usage:
#   sudo ./setup.sh        # installs system packages + Android SDK
#
# After setup, build with:
#   ./build.sh
#
set -e

ANDROID_SDK_DIR="/opt/android-sdk"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
GRADLE_WRAPPER_URL="https://services.gradle.org/distributions/gradle-8.5-bin.zip"
GRADLE_WRAPPER_JAR_VERSION="8.5"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${GREEN}[+]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
err()  { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# ── 1. System packages ──────────────────────────────

install_system_packages() {
    log "Installing system packages..."

    apt-get update -qq

    apt-get install -y -qq \
        openjdk-17-jdk \
        curl \
        unzip \
        zip \
        git \
        wget \
        > /dev/null

    # Verify Java
    java -version 2>&1 | head -1
    JAVA_VER=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VER" -lt 17 ] 2>/dev/null; then
        err "JDK 17+ required but got version $JAVA_VER"
    fi
    log "JDK 17 installed"
}

# ── 2. Android SDK command-line tools ────────────────

install_android_sdk() {
    if [ -d "$ANDROID_SDK_DIR/cmdline-tools/latest/bin" ]; then
        log "Android SDK command-line tools already installed"
        return
    fi

    log "Installing Android SDK to $ANDROID_SDK_DIR..."
    mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"

    log "Downloading command-line tools..."
    curl -fsSL "$CMDLINE_TOOLS_URL" -o /tmp/android-cmdline-tools.zip

    # Extract — the zip contains a 'cmdline-tools' folder
    rm -rf /tmp/android-cmdline-extract
    mkdir -p /tmp/android-cmdline-extract
    unzip -qo /tmp/android-cmdline-tools.zip -d /tmp/android-cmdline-extract

    # Move to the expected 'latest' directory
    rm -rf "$ANDROID_SDK_DIR/cmdline-tools/latest"
    mv /tmp/android-cmdline-extract/cmdline-tools "$ANDROID_SDK_DIR/cmdline-tools/latest"

    # Cleanup
    rm -f /tmp/android-cmdline-tools.zip
    rm -rf /tmp/android-cmdline-extract

    log "Android command-line tools installed"
}

# ── 3. Android SDK packages ─────────────────────────

install_sdk_packages() {
    export ANDROID_HOME="$ANDROID_SDK_DIR"
    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

    log "Accepting Android SDK licenses..."
    yes 2>/dev/null | sdkmanager --licenses > /dev/null 2>&1 || true

    log "Installing SDK packages (platform 34, build-tools 34.0.0)..."
    sdkmanager --install \
        "platform-tools" \
        "platforms;android-34" \
        "build-tools;34.0.0" \
        > /dev/null 2>&1

    log "Android SDK packages installed:"
    echo "  - platform-tools (adb)"
    echo "  - platforms;android-34"
    echo "  - build-tools;34.0.0"
}

# ── 4. Gradle wrapper ───────────────────────────────

setup_gradle_wrapper() {
    log "Setting up Gradle wrapper..."
    cd "$SCRIPT_DIR"
    mkdir -p gradle/wrapper

    # Validate existing jar — must be a real zip/jar, not a corrupt file
    if [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
        if unzip -t "gradle/wrapper/gradle-wrapper.jar" >/dev/null 2>&1; then
            log "Gradle wrapper jar already exists and is valid"
            chmod +x "$SCRIPT_DIR/gradlew" 2>/dev/null || true
            return
        else
            warn "Existing gradle-wrapper.jar is corrupt, re-downloading..."
            rm -f "gradle/wrapper/gradle-wrapper.jar"
        fi
    fi

    # Download Gradle distribution and use it to generate the wrapper
    log "Downloading Gradle $GRADLE_WRAPPER_JAR_VERSION distribution..."
    curl -fsSL "$GRADLE_WRAPPER_URL" -o /tmp/gradle-dist.zip

    rm -rf /tmp/gradle-extract
    mkdir -p /tmp/gradle-extract
    unzip -qo /tmp/gradle-dist.zip -d /tmp/gradle-extract

    GRADLE_BIN="/tmp/gradle-extract/gradle-${GRADLE_WRAPPER_JAR_VERSION}/bin/gradle"
    if [ -x "$GRADLE_BIN" ]; then
        log "Generating wrapper with gradle $GRADLE_WRAPPER_JAR_VERSION..."
        cd "$SCRIPT_DIR"
        "$GRADLE_BIN" wrapper --gradle-version "$GRADLE_WRAPPER_JAR_VERSION"
    else
        err "Gradle binary not found in distribution"
    fi

    rm -f /tmp/gradle-dist.zip
    rm -rf /tmp/gradle-extract

    if [ ! -f "$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
        err "Failed to generate gradle-wrapper.jar"
    fi

    log "Gradle wrapper jar ready"
    chmod +x "$SCRIPT_DIR/gradlew" 2>/dev/null || true
}

# ── 5. Environment and local.properties ─────────────

setup_environment() {
    cd "$SCRIPT_DIR"

    # Create local.properties for the Android project
    echo "sdk.dir=$ANDROID_SDK_DIR" > local.properties
    log "Created local.properties (sdk.dir=$ANDROID_SDK_DIR)"

    # Make build scripts executable
    chmod +x build.sh gradlew 2>/dev/null || true

    # Set ownership so non-root user can build
    if [ -n "$SUDO_USER" ]; then
        chown -R "$SUDO_USER:$SUDO_USER" "$SCRIPT_DIR/local.properties"
        chown -R "$SUDO_USER:$SUDO_USER" "$SCRIPT_DIR/gradle"
        # SDK needs to be readable by the build user
        chmod -R o+rX "$ANDROID_SDK_DIR"
    fi

    # Write env file that build.sh can source
    cat > "$SCRIPT_DIR/.env.build" << EOF
export ANDROID_HOME=$ANDROID_SDK_DIR
export ANDROID_SDK_ROOT=$ANDROID_SDK_DIR
export PATH=\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
EOF

    log "Build environment file written to .env.build"
    log ""
    log "Add to your shell profile (~/.bashrc or ~/.profile):"
    echo "  source $SCRIPT_DIR/.env.build"
}

# ── Main ─────────────────────────────────────────────

echo ""
echo "========================================="
echo "  SIP-GSM Gateway — Build Environment"
echo "========================================="
echo ""

# Check if running as root
if [ "$(id -u)" -ne 0 ]; then
    err "This script must be run as root (sudo ./setup.sh)"
fi

install_system_packages
install_android_sdk
install_sdk_packages
setup_gradle_wrapper
setup_environment

echo ""
echo "========================================="
echo "  Setup complete!"
echo "========================================="
echo ""
echo "  Android SDK:  $ANDROID_SDK_DIR"
echo "  Java:         $(java -version 2>&1 | head -1)"
echo ""
echo "  To build:"
echo "    source .env.build   # load env (or add to ~/.bashrc)"
echo "    ./build.sh          # build debug APK"
echo ""
