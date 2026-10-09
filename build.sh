#!/bin/bash

# =============================================================================
# Tradeable Android Wrapper Build Script
# =============================================================================
# This script:
# 1. Pulls the Flutter SDK from GitHub (specified branch)
# 2. Builds the Flutter module as an AAR
# 3. Integrates it with the Android wrapper
# 4. Produces the final tradeable-android-wrapper.aar
# =============================================================================

set -e

# Configuration (can be overridden by environment variables)
FLUTTER_SDK_REPO="${FLUTTER_SDK_REPO:-https://github.com/deepakgrandhi/tradeable_flutter_sdk_module.git}"
FLUTTER_SDK_BRANCH="${FLUTTER_SDK_BRANCH:-main}"
BUILD_TYPE="${BUILD_TYPE:-release}"
OUTPUT_DIR="${OUTPUT_DIR:-./output}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Directories
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FLUTTER_MODULE_DIR="$SCRIPT_DIR/.flutter_sdk"
FLUTTER_AAR_DIR="$SCRIPT_DIR/tradeable-sdk/libs"

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Tradeable Android Wrapper Build${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Configuration:"
echo "  Flutter SDK Repo: $FLUTTER_SDK_REPO"
echo "  Flutter SDK Branch: $FLUTTER_SDK_BRANCH"
echo "  Build Type: $BUILD_TYPE"
echo "  Output Directory: $OUTPUT_DIR"
echo ""

# -----------------------------------------------------------------------------
# Step 1: Check prerequisites
# -----------------------------------------------------------------------------
echo -e "${YELLOW}[Step 1/6] Checking prerequisites...${NC}"

# Check for Flutter
if ! command -v flutter &> /dev/null; then
    echo -e "${RED}Error: Flutter is not installed or not in PATH${NC}"
    echo "Please install Flutter: https://docs.flutter.dev/get-started/install"
    exit 1
fi

# Check for Git
if ! command -v git &> /dev/null; then
    echo -e "${RED}Error: Git is not installed${NC}"
    exit 1
fi

# Check Flutter version
echo "Flutter version:"
flutter --version

echo -e "${GREEN}✓ Prerequisites check passed${NC}"
echo ""

# -----------------------------------------------------------------------------
# Step 2: Clone/Pull Flutter SDK
# -----------------------------------------------------------------------------
echo -e "${YELLOW}[Step 2/6] Fetching Flutter SDK from GitHub...${NC}"

if [ -d "$FLUTTER_MODULE_DIR" ]; then
    echo "Flutter SDK directory exists, updating..."
    cd "$FLUTTER_MODULE_DIR"
    
    # Reset any local changes
    git reset --hard HEAD
    git clean -fd
    
    # Fetch and checkout the specified branch
    git fetch origin
    git checkout "$FLUTTER_SDK_BRANCH"
    git pull origin "$FLUTTER_SDK_BRANCH"
else
    echo "Cloning Flutter SDK..."
    git clone --branch "$FLUTTER_SDK_BRANCH" "$FLUTTER_SDK_REPO" "$FLUTTER_MODULE_DIR"
fi

cd "$FLUTTER_MODULE_DIR"
COMMIT_HASH=$(git rev-parse --short HEAD)
echo "Flutter SDK at commit: $COMMIT_HASH"

echo -e "${GREEN}✓ Flutter SDK fetched successfully${NC}"
echo ""

# -----------------------------------------------------------------------------
# Step 3: Get Flutter dependencies
# -----------------------------------------------------------------------------
# -----------------------------------------------------------------------------
# Step 3: Get Flutter dependencies
# -----------------------------------------------------------------------------
echo -e "${YELLOW}[Step 3/6] Getting Flutter dependencies...${NC}"

cd "$FLUTTER_MODULE_DIR"

# Clear cached Git dependency to ensure latest commit is fetched
echo "Clearing cached tradeable_learn_widget..."
rm -rf "$HOME/.pub-cache/git/tradeable_learn_widget"* 2>/dev/null || true

echo "Running flutter pub get..."
flutter pub get

echo "Upgrading tradeable_learn_widget..."
flutter pub upgrade tradeable_learn_widget

# echo "Resolved versions:"
# flutter pub deps | grep -A5 youtube_player_flutter || true
# flutter pub deps | grep -A5 flutter_inappwebview || true

echo -e "${GREEN}✓ Dependencies installed${NC}"
echo ""

# -----------------------------------------------------------------------------
# Step 4: Build Flutter module as AAR
# -----------------------------------------------------------------------------
echo -e "${YELLOW}[Step 4/6] Building Flutter module as AAR...${NC}"

cd "$FLUTTER_MODULE_DIR"

# Ensure .android directory exists (required for AAR build)
if [ ! -d ".android" ]; then
    echo "Generating Flutter module Android structure..."
    flutter pub get
    # The .android folder will be generated on first flutter pub get or flutter build
    # Force generation by running flutter build aar with --help first time
    flutter build aar --help > /dev/null 2>&1 || true
fi

# If .android still doesn't exist, create it manually
if [ ! -d ".android" ]; then
    echo "Creating .android structure manually..."
    flutter create --template=module --org=com.tradeable .
fi

# Build the Flutter AAR
echo "Building Flutter AAR (${BUILD_TYPE})..."
flutter build aar --no-debug --no-profile --build-number=1

# The AAR will be in build/host/outputs/repo/
FLUTTER_AAR_PATH="$FLUTTER_MODULE_DIR/build/host/outputs/repo"

echo -e "${GREEN}✓ Flutter AAR built successfully${NC}"
echo ""

# -----------------------------------------------------------------------------
# Step 5: Copy Flutter AAR to Android wrapper
# -----------------------------------------------------------------------------
echo -e "${YELLOW}[Step 5/6] Integrating Flutter AAR...${NC}"

# Create libs directory
mkdir -p "$FLUTTER_AAR_DIR"

# Copy the Flutter release AAR
if [ -d "$FLUTTER_AAR_PATH" ]; then
    echo "Copying Flutter AAR files..."
    cp -r "$FLUTTER_AAR_PATH"/* "$FLUTTER_AAR_DIR/"
    echo "Flutter AAR copied to: $FLUTTER_AAR_DIR"
else
    echo -e "${YELLOW}Warning: Flutter AAR not found at expected path${NC}"
    echo "The AAR may be in a different location. Checking..."
    find "$FLUTTER_MODULE_DIR/build" -name "*.aar" -type f 2>/dev/null || true
fi

echo -e "${GREEN}✓ Flutter AAR integrated${NC}"
echo ""

# -----------------------------------------------------------------------------
# Step 6: Build final Android wrapper AAR
# -----------------------------------------------------------------------------
echo -e "${YELLOW}[Step 6/6] Building Tradeable Android Wrapper AAR...${NC}"

cd "$SCRIPT_DIR"

# Build the Android wrapper: thin AAR + self-contained fat/slim AARs
# (a single assembleFatAar call builds both flavors)
./gradlew clean assembleRelease assembleFatAar

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Copy the final AARs (the fat/slim AARs are written to $OUTPUT_DIR directly by
# the assembleFatAar task)
cp "tradeable-sdk/build/outputs/aar/tradeable-sdk-release.aar" "$OUTPUT_DIR/tradeable-android-wrapper.aar"
ls -la "$OUTPUT_DIR/"

# Create version info file
cat > "$OUTPUT_DIR/version.txt" << EOF
Tradeable Android Wrapper
Build Date: $(date -u +"%Y-%m-%d %H:%M:%S UTC")
Flutter SDK Branch: $FLUTTER_SDK_BRANCH
Flutter SDK Commit: $COMMIT_HASH
Build Type: $BUILD_TYPE
EOF

echo -e "${GREEN}✓ Build completed successfully!${NC}"
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Build Output:${NC}"
echo -e "${GREEN}========================================${NC}"
echo "AAR Location: $OUTPUT_DIR/tradeable-android-wrapper.aar"
echo "Fat AAR Location: $OUTPUT_DIR/tradeable-android-wrapper-fat.aar (self-contained, for libs/ drop-in)"
echo "Slim AAR Location: $OUTPUT_DIR/tradeable-android-wrapper-slim.aar (self-contained minus browser/webkit/relinker)"
echo "Version Info: $OUTPUT_DIR/version.txt"
echo ""

# List output files
ls -la "$OUTPUT_DIR/"
