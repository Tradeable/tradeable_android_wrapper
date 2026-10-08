# Tradeable Android Wrapper

Android AAR library that wraps the [Tradeable Flutter SDK Module](https://github.com/deepakgrandhi/tradeable_flutter_sdk_module) for easy integration into native Android apps using Jetpack Compose.

### Quick Example

```kotlin
// Direct display
TradeableFlutterView(
    mode = DisplayMode.DIRECT,
    width = 320.dp,
    height = 220.dp,
    data = mapOf("text" to "Trading Widget")
)

// Card flip mode
TradeableFlutterView(
    mode = DisplayMode.CARD_FLIP,
    width = 320.dp,
    height = 220.dp,
    data = mapOf("text" to "Tap to Flip")
)

// Fullscreen mode
TradeableFlutterView(
    mode = DisplayMode.FULLSCREEN,
    data = mapOf("text" to "Open Fullscreen")
)
```
## 🔄 Automated Build Process

This wrapper **automatically pulls and integrates** the Flutter SDK module from GitHub. The build script handles:
- ✅ Cloning `tradeable_flutter_sdk_module` from GitHub
- ✅ Installing Flutter dependencies
- ✅ Building Flutter module as AAR
- ✅ Integrating with Android wrapper
- ✅ Producing final `tradeable-android-wrapper.aar`

**Repository**: [deepakgrandhi/tradeable_flutter_sdk_module](https://github.com/deepakgrandhi/tradeable_flutter_sdk_module)

### Building the AAR

```bash
# Build with default settings (main branch)
./build.sh

# Build with specific branch
FLUTTER_SDK_BRANCH=develop ./build.sh

# Output will be in: ./output/tradeable-android-wrapper.aar
```

## Features

- 🎯 **Simplified API matching iOS** - Easy cross-platform development
- 📱 Six display modes: Direct, Card Flip, Fullscreen launcher, Side Drawer, Fullscreen Content, Dashboard Content
- 🔄 Bidirectional communication between Android and Flutter
- 🐛 Bug fixes for card flip and fullscreen modes
- 🏗️ Minimum SDK 26 (Android 8.0)

## New Views Added

The wrapper now supports the following display modes through `TradeableFlutterView`:

- `DisplayMode.DIRECT`
- `DisplayMode.CARD_FLIP`
- `DisplayMode.FULLSCREEN` (button launcher)
- `DisplayMode.SIDE_DRAWER` (Flutter content hosted in a native side drawer)
- `DisplayMode.FULLSCREEN_CONTENT` (Flutter content hosted as fullscreen content)
- `DisplayMode.DASHBOARD_CONTENT` (dashboard content hosted as fullscreen content)

Example:

```kotlin
TradeableFlutterView(
        mode = DisplayMode.SIDE_DRAWER,
        pageId = 6,
        data = mapOf("text" to "Open Side Drawer"),
        onCloseSideDrawer = { /* close native drawer */ }
)
```

## Method Channels for Integration Apps

Channel names:

- `embedded_flutter`
- `embedded_flutter/auth`
- `embedded_flutter/navigation`

Host -> Flutter methods:

- On `embedded_flutter`
    - `setData` with payload keys: `mode`, `text`, `width`, `height`, `topicId`, `pageId`
- On `embedded_flutter/auth`
    - `initializeTFS` with auth payload: `baseUrl`, `authToken`, `portalToken`, `appId`, `clientId`, `publicKey`
- On `embedded_flutter/navigation`
    - `openTradeableSideDrawer` (payload can include `pageId`)
    - `navigateTo`, `replaceRoute`, `popToRoot`, `receiveData`

Flutter -> Host methods:

- On `embedded_flutter`
    - `closeCard`
    - `closeFullscreen`
    - `closeSideDrawer`
- On `embedded_flutter/navigation`
    - `sendData` with actions like:
        - `{ "action": "openTopic", "topicId": 123, "title": "..." }`
        - `{ "action": "openDashboard", "title": "Learn Dashboard" }`

## Installation

### Option A — Maven (recommended, versioned)

Each `vX.Y.Z` tag publishes the AAR to GitHub Packages and attaches it to the
GitHub Release.

**1. Create a token (one-time per developer).** GitHub → Settings → Developer
settings → Personal access tokens → Tokens (classic) → Generate new token →
check `read:packages` → copy the `ghp_…` value. GitHub Packages requires
authentication even for public repos; any valid token with `read:packages` works.

**2. Store it outside the repo** in `~/.gradle/gradle.properties` (never commit it):

```properties
gpr.user=your-github-username
gpr.key=ghp_xxxx…
```

**3. Add the repository** in your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/Tradeable/tradeable_android_wrapper")
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.key").get()
            }
        }
    }
}
```

**4. Add the dependency** in your app `build.gradle.kts` and sync:

```kotlin
dependencies {
    implementation("com.tradeable:android-wrapper:1.0.0")

    // Required transitive dependencies
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
```

> CI builds need the same two values as secrets (`ORG_GRADLE_PROJECT_gpr_user`
> / `ORG_GRADLE_PROJECT_gpr_key` map to the Gradle properties above). If sync
> fails with `401 Unauthorized`, credentials are missing — the artifact itself
> is fine. Prefer no tokens at all? Download the AAR from the Release page
> (no login needed) and use Option B.

### Compatibility requirements

Starting with wrapper `v1.0.0`, the AAR is compiled with **Kotlin 2.4**. Kotlin
metadata is forward-only: an app compiling with an older Kotlin plugin cannot
read it. Consumers need, at build time:

| Tool | Minimum |
| ---- | ------- |
| Kotlin Gradle Plugin | 2.4.0 |
| Android Gradle Plugin | 8.6+ (8.10 recommended) |
| Gradle | 8.11+ |
| JDK | 17 |

Older published AARs are unaffected — they keep working with the toolchain
they were built with. Only upgrades to a new wrapper version pull in the new
requirement, and it will be called out in the release notes.

### Option B — Local AAR

1. Copy `tradeable-android-wrapper.aar` to your app's `libs` folder
2. Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("libs/tradeable-android-wrapper.aar"))
    
    // Required transitive dependencies
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
```

## Quick Start

### 1. Initialize the SDK

In your `Application` class:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        TradeableSDK.initialize(
            context = this,
            config = TradeableConfig(
                baseUrl = "https://api.your-server.com",
                
                onRefreshCredentials = {
                    // Return fresh credentials (called when needed)
                    TradeableCredentials(
                        authorization = "Bearer your-token",
                        portalToken = "portal-token",
                        appId = "app-id",
                        clientId = "client-id",
                        publicKey = "public-key"
                    )
                },
                
                onAnalyticsEvent = { event ->
                    // Track analytics events
                    analytics.track(event.eventName, event.data)
                },
                
                onCallback = { event ->
                    // Handle callbacks from Flutter views
                    when (event.action) {
                        "navigate" -> { /* handle navigation */ }
                        "purchase" -> { /* handle purchase */ }
                    }
                },
                
                debugMode = BuildConfig.DEBUG
            )
        )
    }
}
```

### 2. Embed Flutter Views in Compose

```kotlin
@Composable
fun CourseCard() {
    TradeableFlutterView(
        height = 200.dp,
        width = 300.dp,
        type = "course_card",
        parameters = mapOf("courseId" to "123"),
        onCallback = { event ->
            // Handle card interactions
        }
    )
}
```

### 3. Open Full-Page Flutter Screens

```kotlin
// Open dashboard
Button(onClick = {
    TradeableSDK.openDashboard(activity)
}) {
    Text("Open Dashboard")
}

// Open custom route
Button(onClick = {
    TradeableSDK.openFullPage(
        activity = activity,
        params = TradeablePageParams(
            route = "/course/details",
            parameters = mapOf("courseId" to "123"),
            title = "Course Details"
        )
    )
}) {
    Text("Open Course")
}
```

## API Reference

### TradeableSDK

| Method | Description |
|--------|-------------|
| `initialize(context, config)` | Initialize the SDK |
| `openFullPage(activity, params)` | Open a full-page Flutter view |
| `openDashboard(activity)` | Open the main dashboard |
| `refreshCredentials()` | Manually refresh credentials |
| `destroy()` | Cleanup SDK resources |
| `isInitialized` | StateFlow for initialization status |

### TradeableConfig

| Property | Type | Description |
|----------|------|-------------|
| `baseUrl` | String | Backend API URL |
| `onRefreshCredentials` | suspend () -> TradeableCredentials | Credential refresh callback |
| `onAnalyticsEvent` | (TradeableAnalyticsEvent) -> Unit | Analytics callback |
| `onCallback` | (TradeableCallbackEvent) -> Unit | Flutter callback handler |
| `debugMode` | Boolean | Enable debug logging |

### TradeableFlutterView

| Parameter | Type | Description |
|-----------|------|-------------|
| `mode` | DisplayMode | Display mode to render |
| `height` | Dp | View height |
| `width` | Dp | View width |
| `data` | Map<String, Any> | View payload (for example `text`) |
| `topicId` | Int? | Topic id for fullscreen/topic content |
| `pageId` | Int? | Page id for side drawer content |
| `onCloseSideDrawer` | (() -> Unit)? | Native close callback for side drawer |
| `onCloseFullscreen` | (() -> Unit)? | Native close callback for fullscreen content |
| `modifier` | Modifier | Compose modifier |

### Pre-built Widgets

```kotlin
// Dashboard widget
TradeableDashboard(
    height = 300.dp,
    dateThreshold = 30,
    onCallback = { /* handle */ }
)

// Course card widget
TradeableCourseCard(
    courseId = "123",
    onCallback = { /* handle */ }
)

// Learn sheet widget
TradeableLearnSheet(
    pageId = "home",
    onCallback = { /* handle */ }
)
```

## Building the AAR

### Prerequisites

- JDK 17
- Flutter SDK (3.24+)
- Android SDK (API 34)

### Build Steps

```bash
# Clone this repository
git clone https://github.com/deepakgrandhi/tradeable-android-wrapper.git
cd tradeable-android-wrapper

# Build with specific Flutter SDK branch
FLUTTER_SDK_BRANCH=main ./build.sh

# Or build with custom repository
FLUTTER_SDK_REPO=https://github.com/your-fork/tradeable_flutter_sdk_module.git \
FLUTTER_SDK_BRANCH=develop \
./build.sh
```

The output AAR will be in `./output/tradeable-android-wrapper.aar`

## Project Structure

```
tradeable-android-wrapper/
├── build.sh                    # Main build script
├── tradeable-sdk/              # Android library module
│   ├── src/main/
│   │   ├── java/com/tradeable/sdk/
│   │   │   ├── core/           # Core SDK classes
│   │   │   ├── config/         # Configuration classes
│   │   │   ├── ui/             # Compose UI components
│   │   │   └── bridge/         # Flutter communication
│   │   └── res/
│   └── build.gradle.kts
├── .github/workflows/          # CI/CD
└── output/                     # Built AAR files
```

## Troubleshooting

### Video plays audio but shows black/blank (YouTube/WebView)

The video widget is a native Android `WebView` (YouTube iframe via
`youtube_player_flutter` + `webview_flutter` hybrid composition). Audio
playing while video stays black means the WebView loaded but its frames can't
composite. The wrapper already handles the common causes — make sure the
consumer app uses an AAR built from this repo **after** these fixes:

- `FlutterView` is `FlutterSurfaceView`-backed (opaque, no forced
  transparency) — `TextureView` and transparent backgrounds composite to
  black with audio only.
- `Theme.TradeableSDK` is opaque (translucent windows break `SurfaceView` /
  `WebView` video compositing).
- `GeneratedPluginRegistrant.registerWith(engine)` is called so platform-view
  factories exist.
- The host `Activity` is attached to the `FlutterEngine` via
  `ActivityControlSurface` (ActivityAware plugins such as WebView
  fullscreen/file chooser, `url_launcher` and permission flows need it), and
  `onActivityResult` / `onRequestPermissionsResult` / `onNewIntent` /
  `onUserLeaveHint` / `onLowMemory` are forwarded.
- `TradeableFlutterActivity` forces `FLAG_HARDWARE_ACCELERATED` on its window.

Consumer-side requirements for **embedded** `TradeableFlutterView`s:

1. The host `Activity` must be hardware accelerated (the default). If the app
   manifest sets `android:hardwareAccelerated="false"` (on `<application>` or
   the host `<activity>`), video will be black. The wrapper logs an explicit
   error when it detects a non-accelerated window — see below.
2. Do not apply a translucent theme (`windowIsTranslucent=true`) to the host
   `Activity`.
3. All embedded views on one screen share a single engine per scope: only the
   most recently attached view renders. Don't stack multiple video views
   visibly at once; the wrapper logs a warning when this happens.

Verify with logcat (look for these lines, all present = wiring healthy):

```bash
adb logcat | grep -E "FlutterBridge|TradeableFlutterActivity"
# [EMBEDDED] Attached activity MainActivity
# [FULLSCREEN] Attached activity TradeableFlutterActivity
# [FULLSCREEN] FlutterView attached, hardwareAccelerated=true
# Hardware acceleration flag active=true
```

If you instead see
`Host window is NOT hardware accelerated ... video will be BLACK with audio
only`, fix the consumer manifest. If you see
`PlatformViewsController can only be attached to a single output target`,
you are on an AAR older than the activity-attach fix — rebuild from
`./output/tradeable-android-wrapper.aar` and re-copy it into the consumer
app's `libs/` folder (stale AAR copies are the most common reason the fix
"doesn't work").

### SDK not initializing

Make sure you're calling `TradeableSDK.initialize()` in your Application's `onCreate()`.

### Flutter views showing placeholder

1. Ensure the AAR was built with the Flutter module
2. Check that credentials are being provided
3. Enable `debugMode` to see detailed logs

### Build failures

1. Check Flutter SDK version (3.24+ required)
2. Ensure JAVA_HOME points to JDK 17
3. Run `flutter doctor` in the Flutter SDK directory

## License

MIT License - see [LICENSE](LICENSE) for details.

## Support

For issues and feature requests, please use the [GitHub Issues](https://github.com/deepakgrandhi/tradeable-android-wrapper/issues) page.
