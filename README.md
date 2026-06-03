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
- 📱 Three display modes: Direct, Card Flip, Fullscreen
- 🔄 Bidirectional communication between Android and Flutter
- 🐛 Bug fixes for card flip and fullscreen modes
- 🏗️ Minimum SDK 26 (Android 8.0)

## Installation

### Local AAR

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
| `height` | Dp | View height |
| `width` | Dp | View width |
| `type` | String | Widget type (e.g., "course_card") |
| `parameters` | Map<String, String> | Parameters for the widget |
| `onCallback` | (TradeableCallbackEvent) -> Unit | Callback handler |
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
