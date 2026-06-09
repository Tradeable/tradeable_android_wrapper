package com.tradeable.sdk.ui

import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.tradeable.sdk.android.wrapper.FlutterBridge
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import com.tradeable.sdk.config.TradeableCallbackEvent
import com.tradeable.sdk.core.TradeableSDK

/**
 * Display modes for TradeableFlutterView matching iOS API
 */
enum class DisplayMode {
    DIRECT,      // Direct display of Flutter view
    CARD_FLIP,   // Card with flip animation
    FULLSCREEN,  // Fullscreen button launcher
    SIDE_DRAWER,
    FULLSCREEN_CONTENT,
    DASHBOARD_CONTENT
}

/**
 * Simplified TradeableFlutterView composable matching iOS API
 */
@Composable
fun TradeableFlutterView(
    mode: DisplayMode = DisplayMode.DIRECT,
    width: Dp = 320.dp,
    height: Dp = 220.dp,
    data: Map<String, Any> = emptyMap(),
    topicId: Int? = null,
    pageId: Int? = null,
    onCloseSideDrawer: (() -> Unit)? = null,
    onCloseFullscreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    when (mode) {
        DisplayMode.DIRECT -> DirectView(
            width = width,
            height = height,
            data = data,
            topicId = topicId,
            pageId = pageId,
            modifier = modifier
        )
        DisplayMode.CARD_FLIP -> CardFlipView(
            width = width,
            height = height,
            data = data,
            topicId = topicId,
            pageId = pageId,
            modifier = modifier
        )
        DisplayMode.FULLSCREEN -> FullscreenButtonView(
            width = width,
            height = height,
            data = data,
            topicId = topicId,
            modifier = modifier
        )
        DisplayMode.SIDE_DRAWER -> SideDrawerContentView(
            width = width,
            height = height,
            data = data,
            pageId = pageId,
            onCloseSideDrawer = onCloseSideDrawer,
            modifier = modifier
        )
        DisplayMode.FULLSCREEN_CONTENT -> FullscreenContentView(
            width = width,
            height = height,
            data = data,
            topicId = topicId,
            onCloseFullscreen = onCloseFullscreen,
            modifier = modifier
        )
        DisplayMode.DASHBOARD_CONTENT -> DashboardContentView(
            width = width,
            height = height,
            data = data,
            onCloseFullscreen = onCloseFullscreen,
            modifier = modifier
        )
    }
}

// MARK: - Direct Display Mode
@Composable
private fun DirectView(
    width: Dp,
    height: Dp,
    data: Map<String, Any>,
    topicId: Int?,
    pageId: Int?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preparedData = prepareData(
        mode = "direct",
        width = width,
        height = height,
        data = data,
        topicId = topicId,
        pageId = pageId
    )
    
    // Send initial state when view is created
    LaunchedEffect(Unit) {
        val bridge = FlutterBridge.getInstance(context)
        bridge.sendViewState(
            mode = preparedData["mode"] as String,
            text = preparedData["text"] as? String ?: "",
            width = preparedData["width"] as Double,
            height = preparedData["height"] as Double,
                topicId = preparedData["topicId"] as? Int ?: 0,
                pageId = preparedData["pageId"] as? Int ?: 0
        )
    }
    
    FlutterContainer(
        initialData = preparedData,
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.background)
    )
}

// MARK: - Card Flip Display Mode
@Composable
private fun CardFlipView(
    width: Dp,
    height: Dp,
    data: Map<String, Any>,
    topicId: Int?,
    pageId: Int?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isFlipped by remember { mutableStateOf(false) }
    val preparedData = prepareData(
        mode = "card",
        width = width,
        height = height,
        data = data,
        topicId = topicId,
        pageId = pageId
    )
    
    // Handle back button when flipped
    BackHandler(enabled = isFlipped) {
        isFlipped = false
    }
    
    Box(
        modifier = modifier
            .width(width)
            .height(height)
    ) {
        if (isFlipped) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                FlutterContainer(
                    initialData = preparedData,
                    onClose = { isFlipped = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            CardFrontView(
                width = width,
                height = height,
                onClick = { isFlipped = true }
            )
        }
    }
}

// MARK: - Card Front View
@Composable
private fun CardFrontView(
    width: Dp,
    height: Dp,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(width)
            .height(height)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Flip",
                    modifier = Modifier.height(50.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Tap to Flip",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// MARK: - Fullscreen Button View
@Composable
private fun FullscreenButtonView(
    width: Dp,
    height: Dp,
    data: Map<String, Any>,
    topicId: Int?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    Button(
        onClick = {
            val intent = Intent(context, TradeableFlutterActivity::class.java).apply {
                putExtra("mode", "fullscreen")
                putExtra("text", data["text"] as? String ?: "Open Fullscreen")
                putExtra("topicId", topicId ?: 0)
                putExtra("width", width.value.toDouble())
                putExtra("height", height.value.toDouble())
                
                data.forEach { (key, value) ->
                    when (value) {
                        is String -> putExtra(key, value)
                        is Int -> putExtra(key, value)
                        is Double -> putExtra(key, value)
                        is Boolean -> putExtra(key, value)
                    }
                }
            }
            context.startActivity(intent)
        },
        modifier = modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = "Open Fullscreen",
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(data["text"] as? String ?: "Open Flutter Fullscreen")
    }
}

@Composable
private fun SideDrawerContentView(
    width: Dp,
    height: Dp,
    data: Map<String, Any>,
    pageId: Int?,
    onCloseSideDrawer: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preparedData = prepareData(
        mode = "nativeSideDrawer",
        width = width,
        height = height,
        data = data,
        pageId = pageId
    )

    FlutterContainer(
        initialData = preparedData,
        onCloseSideDrawer = onCloseSideDrawer,
        modifier = modifier
            .width(width)
            .height(height)
            .background(MaterialTheme.colorScheme.background)
    )

    LaunchedEffect(Unit) {
        val bridge = FlutterBridge.getInstance(context)
        bridge.sendViewState(
            mode = preparedData["mode"] as String,
            text = preparedData["text"] as? String ?: "",
            width = preparedData["width"] as Double,
            height = preparedData["height"] as Double,
            topicId = preparedData["topicId"] as? Int ?: 0,
            pageId = preparedData["pageId"] as? Int ?: 0
        )
    }
}

@Composable
private fun FullscreenContentView(
    width: Dp,
    height: Dp,
    data: Map<String, Any>,
    topicId: Int?,
    onCloseFullscreen: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preparedData = prepareData(
        mode = "fullscreen",
        width = width,
        height = height,
        data = data,
        topicId = topicId
    )

    FlutterContainer(
        initialData = preparedData,
        onClose = onCloseFullscreen,
        modifier = modifier
            .width(width)
            .height(height)
            .background(MaterialTheme.colorScheme.background)
    )

    LaunchedEffect(Unit) {
        val bridge = FlutterBridge.getInstance(context)
        bridge.sendViewState(
            mode = preparedData["mode"] as String,
            text = preparedData["text"] as? String ?: "",
            width = preparedData["width"] as Double,
            height = preparedData["height"] as Double,
            topicId = preparedData["topicId"] as? Int ?: 0,
            pageId = preparedData["pageId"] as? Int ?: 0
        )
    }
}

@Composable
private fun DashboardContentView(
    width: Dp,
    height: Dp,
    data: Map<String, Any>,
    onCloseFullscreen: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preparedData = prepareData(
        mode = "dashboard",
        width = width,
        height = height,
        data = data
    )

    FlutterContainer(
        initialData = preparedData,
        onClose = onCloseFullscreen,
        modifier = modifier
            .width(width)
            .height(height)
            .background(MaterialTheme.colorScheme.background)
    )

    LaunchedEffect(Unit) {
        val bridge = FlutterBridge.getInstance(context)
        bridge.sendViewState(
            mode = preparedData["mode"] as String,
            text = preparedData["text"] as? String ?: "",
            width = preparedData["width"] as Double,
            height = preparedData["height"] as Double,
            topicId = preparedData["topicId"] as? Int ?: 0,
            pageId = preparedData["pageId"] as? Int ?: 0
        )
    }
}

// MARK: - Helper Functions
private fun prepareData(
    mode: String,
    width: Dp,
    height: Dp,
    data: Map<String, Any>,
    topicId: Int? = null,
    pageId: Int? = null
): Map<String, Any> {
    val finalData = data.toMutableMap()
    finalData["width"] = width.value.toDouble()
    finalData["height"] = height.value.toDouble()
    finalData["mode"] = mode
    topicId?.let { finalData["topicId"] = it }
    pageId?.let { finalData["pageId"] = it }
    return finalData
}

// MARK: - Flutter Container
@Composable
private fun FlutterContainer(
    initialData: Map<String, Any>,
    onClose: (() -> Unit)? = null,
    onCloseSideDrawer: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var flutterView by remember { mutableStateOf<io.flutter.embedding.android.FlutterView?>(null) }
    var isReady by remember { mutableStateOf(false) }
    
    // Send data to Flutter when view is created
    LaunchedEffect(flutterView) {
        if (flutterView != null && !isReady) {
            val bridge = FlutterBridge.getInstance(context)
            bridge.sendViewState(
                mode = initialData["mode"] as? String ?: "direct",
                text = initialData["text"] as? String ?: "",
                width = initialData["width"] as? Double ?: 320.0,
                height = initialData["height"] as? Double ?: 220.0,
                topicId = initialData["topicId"] as? Int ?: 0,
                pageId = initialData["pageId"] as? Int ?: 0
            )
            isReady = true
        }
    }
    
    // Setup close handler and cleanup
    DisposableEffect(Unit) {
        val bridge = FlutterBridge.getInstance(context)
        bridge.setupCloseHandler(onClose)
        bridge.setupSideDrawerCloseHandler(onCloseSideDrawer)
        
        onDispose {
            bridge.setupCloseHandler(null)
            bridge.setupSideDrawerCloseHandler(null)
            flutterView?.let { view ->
                val mode = initialData["mode"] as? String
                if (mode == "card") {
                    bridge.detachView(view)
                }
            }
            flutterView = null
            isReady = false
        }
    }
    
    AndroidView(
        factory = { ctx ->
            val bridge = FlutterBridge.getInstance(ctx)
            val view = bridge.createFlutterView()
            view.setBackgroundColor(AndroidColor.TRANSPARENT)
            view.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            flutterView = view
            view
        },
        modifier = modifier.fillMaxSize()
    )
}