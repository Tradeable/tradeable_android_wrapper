package com.tradeable.sdk.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.graphics.Color
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import com.tradeable.sdk.android.wrapper.FlutterBridge
import com.tradeable.sdk.android.wrapper.FlutterBridge.ViewScope
import java.util.UUID

/**
 * Activity that displays a full-page Flutter view.
 * Matches iOS TradeableFlutterView pattern with mode, data, and topicId
 */
class TradeableFlutterActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "TradeableFlutterActivity"
        const val MAX_INIT_RETRIES = 50
        const val INIT_RETRY_DELAY_MS = 100L
    }
    
    private var flutterView: io.flutter.embedding.android.FlutterView? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // WebView/YouTube video frames cannot composite without hardware
        // acceleration (result: black video with audio). The manifest already
        // declares it, but enforce it programmatically in case the consumer
        // app overrides the attribute during manifest merging.
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        val hwFlags = window.attributes.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        Log.d(TAG, "Hardware acceleration flag active=${
            hwFlags != 0
        }")

        // Give ActivityAware plugins (WebView video/fullscreen, url_launcher,
        // permissions) a host Activity. Manual add-to-app embeddings must do
        // this themselves; FlutterActivity normally does it via its delegate.
        FlutterBridge.getInstance(this).attachActivity(this)
        
        // Extract intent extras (matching iOS pattern)
        val mode = intent.getStringExtra("mode") ?: "fullscreen"
        val text = intent.getStringExtra("text") ?: "Open Fullscreen"
        val topicId = intent.getIntExtra("topicId", 0)
        val width = intent.getDoubleExtra("width", 0.0)
        val height = intent.getDoubleExtra("height", 0.0)
        
        Log.d(TAG, "Opening Flutter: mode=$mode, topicId=$topicId, width=$width, height=$height")
        
        setContent {
            MaterialTheme {
                TradeableFullPageContent(
                    mode = mode,
                    text = text,
                    topicId = topicId,
                    width = width,
                    height = height,
                    onBack = { finish() },
                    onViewCreated = { view -> flutterView = view }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        FlutterBridge.getInstance(this).onNewIntent(intent)
        Log.d(TAG, "onNewIntent - updated fullscreen payload")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val bridge = FlutterBridge.getInstance(this)
        if (!bridge.onActivityResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        val bridge = FlutterBridge.getInstance(this)
        if (!bridge.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onUserLeaveHint() {
        FlutterBridge.getInstance(this).onUserLeaveHint()
        super.onUserLeaveHint()
    }

    override fun onLowMemory() {
        FlutterBridge.getInstance(this).onLowMemory()
        super.onLowMemory()
    }
    
    override fun onDestroy() {
        Log.d(TAG, "onDestroy - cleaning up Flutter view")
        flutterView?.let { view ->
            try {
                val bridge = FlutterBridge.getInstance(this)
                bridge.detachView(view)
                flutterView = null
            } catch (e: Exception) {
                Log.e(TAG, "Error detaching Flutter view", e)
            }
        }
        try {
            FlutterBridge.getInstance(this).detachActivity(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching activity", e)
        }
        super.onDestroy()
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause - pausing Flutter engine")
        FlutterBridge.getInstance(this).pauseEngine()
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - resuming Flutter engine")
        // Always resume engine even if view isn't created yet
        FlutterBridge.getInstance(this).resumeEngine()
    }
    
    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
    }
    
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TradeableFullPageContent(
    mode: String,
    text: String,
    topicId: Int,
    width: Double,
    height: Double,
    onBack: () -> Unit,
    onViewCreated: (io.flutter.embedding.android.FlutterView) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    var initState by remember { mutableStateOf<InitState>(InitState.Initializing) }
    var viewReady by remember { mutableStateOf(false) }
    
    // Always finish the Activity on system back
    BackHandler {
        onBack()
    }
    
    // Wait for TFS initialization
    LaunchedEffect(Unit) {
        val bridge = FlutterBridge.getInstance(context)
        var retries = 0
        
        Log.d("TradeableFlutterActivity", "Waiting for TFS initialization...")
        
        while (!bridge.isTFSInitialized() && retries < TradeableFlutterActivity.MAX_INIT_RETRIES) {
            delay(TradeableFlutterActivity.INIT_RETRY_DELAY_MS)
            retries++
        }
        
        if (!bridge.isTFSInitialized(ViewScope.FULLSCREEN)) {
            Log.w("TradeableFlutterActivity", "TFS not initialized after ${retries * TradeableFlutterActivity.INIT_RETRY_DELAY_MS}ms")
            initState = InitState.Failed("SDK initialization timeout")
            return@LaunchedEffect
        }
        
        Log.d("TradeableFlutterActivity", "TFS initialized, ready to create view")
        initState = InitState.Ready
    }
    
    // Send data to Flutter after view is created
    LaunchedEffect(viewReady) {
        if (viewReady) {
            delay(100) // Small delay to ensure view is fully attached
            Log.d("TradeableFlutterActivity", "View ready, sending view state")
            try {
                dispatchViewState(activity, context, mode, text, width, height, topicId)
                Log.d("TradeableFlutterActivity", "View state sent successfully")
            } catch (e: Exception) {
                Log.e("TradeableFlutterActivity", "Error sending view state", e)
            }
        }
    }

    DisposableEffect(lifecycleOwner, viewReady, mode, text, width, height, topicId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && viewReady) {
                dispatchViewState(activity, context, mode, text, width, height, topicId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Fullscreen Flutter should own the whole surface; no top app bar overlay
    Scaffold(
        topBar = {},
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when (initState) {
                is InitState.Initializing -> {
                    CircularProgressIndicator()
                }
                is InitState.Ready -> {
                    FlutterViewContainer(
                        onViewCreated = { view ->
                            onViewCreated(view)
                            viewReady = true
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is InitState.Failed -> {
                    Text(
                        text = "Failed to load: ${(initState as InitState.Failed).error}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun FlutterViewContainer(
    onViewCreated: (io.flutter.embedding.android.FlutterView) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ownerKey = remember { "fullscreen:${UUID.randomUUID()}" }
    var flutterView by remember { mutableStateOf<io.flutter.embedding.android.FlutterView?>(null) }
    
    // Observe lifecycle events and hook close handler so Flutter back buttons finish the Activity
    DisposableEffect(lifecycleOwner, ownerKey) {
        val bridge = FlutterBridge.getInstance(context)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    Log.d("FlutterViewContainer", "Lifecycle ON_RESUME")
                    bridge.activateOwner(ownerKey)
                }
                Lifecycle.Event.ON_PAUSE -> Log.d("FlutterViewContainer", "Lifecycle ON_PAUSE")
                Lifecycle.Event.ON_DESTROY -> Log.d("FlutterViewContainer", "Lifecycle ON_DESTROY")
                else -> {}
            }
        }
        bridge.registerCloseHandler(ownerKey) { (context as? Activity)?.finish() }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            Log.d("FlutterViewContainer", "DisposableEffect cleanup")
            lifecycleOwner.lifecycle.removeObserver(observer)
            bridge.clearOwnerHandlers(ownerKey)
            flutterView?.let { view ->
                try {
                    bridge.detachView(view)
                } catch (e: Exception) {
                    Log.e("FlutterViewContainer", "Error detaching view", e)
                }
            }
        }
    }
    
    AndroidView(
        factory = { ctx ->
            Log.d("FlutterViewContainer", "Creating Flutter view")
            val bridge = FlutterBridge.getInstance(ctx)
            // Pass the host Activity context (not application): SurfaceView
            // window attachment and plugin flows resolve against it.
            // Activity attach itself is handled in onCreate/onDestroy.
            val view = bridge.createFlutterView(ctx, ViewScope.FULLSCREEN)
            // Do NOT force a transparent background: SurfaceView-backed
            // FlutterView + WebView (YouTube) renders white/blank with audio
            // only when transparency is forced. Opaque default matches pure Flutter.
            flutterView = view
            onViewCreated(view)

            // Force layout to ensure view renders
            view.post {
                Log.d("FlutterViewContainer", "View posted to ensure rendering started")
            }

            view
        },
        modifier = modifier,
        update = { view ->
            Log.d("FlutterViewContainer", "AndroidView update callback - view size: ${view.width}x${view.height}")
        }
    )
}

private sealed class InitState {
    object Initializing : InitState()
    object Ready : InitState()
    data class Failed(val error: String) : InitState()
}

private fun dispatchViewState(
    activity: Activity?,
    context: Context,
    fallbackMode: String,
    fallbackText: String,
    fallbackWidth: Double,
    fallbackHeight: Double,
    fallbackTopicId: Int
) {
    val intent = activity?.intent
    val mode = intent?.getStringExtra("mode") ?: fallbackMode
    val text = intent?.getStringExtra("text") ?: fallbackText
    val width = intent?.getDoubleExtra("width", fallbackWidth) ?: fallbackWidth
    val height = intent?.getDoubleExtra("height", fallbackHeight) ?: fallbackHeight
    val topicId = intent?.getIntExtra("topicId", fallbackTopicId) ?: fallbackTopicId
    val pageId = intent?.getIntExtra("pageId", 0) ?: 0
    val courseId = intent?.getIntExtra("courseId", 0) ?: 0

    FlutterBridge.getInstance(context).sendViewState(
        mode = mode,
        text = text,
        width = if (width > 0) width else 400.0,
        height = if (height > 0) height else 600.0,
        topicId = topicId,
        pageId = pageId,
        courseId = courseId,
        scope = ViewScope.FULLSCREEN
    )
}