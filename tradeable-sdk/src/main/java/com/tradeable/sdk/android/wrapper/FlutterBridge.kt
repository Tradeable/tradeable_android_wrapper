package com.tradeable.sdk.android.wrapper

import android.app.Activity
import android.content.Context
import android.util.Log
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

/**
 * FlutterBridge - Manages Flutter integration for Android
 * Mirrors iOS FlutterBridge implementation
 * Handles method channels for auth and navigation
 */
class FlutterBridge private constructor(private val context: Context) {
    
    companion object {
        private var instance: FlutterBridge? = null
        private const val TAG = "FlutterBridge"
        private const val BASE_CHANNEL = "embedded_flutter"
        private const val AUTH_CHANNEL = "embedded_flutter/auth"
        private const val NAV_CHANNEL = "embedded_flutter/navigation"
        
        fun getInstance(context: Context): FlutterBridge {
            return instance ?: synchronized(this) {
                instance ?: FlutterBridge(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private var flutterEngine: FlutterEngine? = null
    private var baseChannel: MethodChannel? = null
    private var authChannel: MethodChannel? = null
    private var navChannel: MethodChannel? = null
    
    // Track views but don't auto-detach them
    private val flutterViews = mutableListOf<FlutterView>()
    private var closeHandler: (() -> Unit)? = null
    private var sideDrawerCloseHandler: (() -> Unit)? = null
    private var dataHandler: ((Map<String, Any>) -> Unit)? = null
    
    val authHandler = AuthHandler()
    val navigationHandler = NavigationHandler()
    
    private var isInitialized = false
    private var isTFSInitialized = false
    
    fun initialize(activity: Activity) {
        if (isInitialized) {
            Log.d(TAG, "FlutterBridge already initialized")
            return
        }
        
        // Create Flutter engine
        flutterEngine = FlutterEngine(context)
        flutterEngine?.dartExecutor?.executeDartEntrypoint(
            DartExecutor.DartEntrypoint.createDefault()
        )
        
        // Set up method channels
        flutterEngine?.let { engine ->
            baseChannel = MethodChannel(engine.dartExecutor.binaryMessenger, BASE_CHANNEL)
            authChannel = MethodChannel(engine.dartExecutor.binaryMessenger, AUTH_CHANNEL)
            navChannel = MethodChannel(engine.dartExecutor.binaryMessenger, NAV_CHANNEL)
            
            // Setup method call handler for close callbacks from Flutter
            baseChannel?.setMethodCallHandler { call, result ->
                when (call.method) {
                    "closeCard", "closeFullscreen" -> {
                        Log.d(TAG, "Flutter requested close: ${call.method}")
                        closeHandler?.invoke()
                        result.success(null)
                    }
                    "closeSideDrawer" -> {
                        Log.d(TAG, "Flutter requested side drawer close")
                        sideDrawerCloseHandler?.invoke()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

            navChannel?.setMethodCallHandler { call, result ->
                when (call.method) {
                    "sendData" -> {
                        @Suppress("UNCHECKED_CAST")
                        val payload = (call.arguments as? Map<String, Any>) ?: emptyMap()
                        Log.d(TAG, "Received navigation data from Flutter: $payload")
                        dataHandler?.invoke(payload)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
        }
        
        isInitialized = true
        Log.d(TAG, "FlutterBridge initialized with engine and channels")
    }
    
    fun createFlutterView(): FlutterView {
        if (flutterEngine == null) {
            Log.e(TAG, "FlutterEngine not initialized! Call initialize() first")
            throw IllegalStateException("FlutterEngine not initialized")
        }
        
        // Ensure engine is in resumed state before creating view
        flutterEngine?.lifecycleChannel?.appIsResumed()
        Log.d(TAG, "Engine lifecycle set to resumed before view creation")
        
        // Create new view - DON'T detach existing views, allow multiple views to coexist
        return FlutterView(context).apply {
            attachToFlutterEngine(flutterEngine!!)
            flutterViews.add(this)
            Log.d(TAG, "Created Flutter view. Total views: ${flutterViews.size}")
        }
    }
    
    /**
     * Setup close handler for card flip and fullscreen modes
     */
    fun setupCloseHandler(handler: (() -> Unit)?) {
        closeHandler = handler
        Log.d(TAG, "Close handler ${if (handler != null) "registered" else "cleared"}")
    }

    fun setupSideDrawerCloseHandler(handler: (() -> Unit)?) {
        sideDrawerCloseHandler = handler
        Log.d(TAG, "Side drawer close handler ${if (handler != null) "registered" else "cleared"}")
    }

    fun setupDataHandler(handler: ((Map<String, Any>) -> Unit)?) {
        dataHandler = handler
        Log.d(TAG, "Data handler ${if (handler != null) "registered" else "cleared"}")
    }
    
    /**
     * Manually detach a specific view when no longer needed
     */
    fun detachView(view: FlutterView) {
        view.detachFromFlutterEngine()
        flutterViews.remove(view)
        Log.d(TAG, "Detached Flutter view. Remaining views: ${flutterViews.size}")
    }
    
    /**
     * Pause the Flutter engine (call in onPause)
     */
    fun pauseEngine() {
        flutterEngine?.lifecycleChannel?.appIsInactive()
        flutterEngine?.lifecycleChannel?.appIsPaused()
        Log.d(TAG, "Flutter engine paused")
    }
    
    /**
     * Resume the Flutter engine (call in onResume)
     */
    fun resumeEngine() {
        flutterEngine?.lifecycleChannel?.appIsResumed()
        Log.d(TAG, "Flutter engine resumed")
    }
    
    /**
     * Stop the Flutter engine (call in onStop)
     */
    fun stopEngine() {
        flutterEngine?.lifecycleChannel?.appIsInactive()
        flutterEngine?.lifecycleChannel?.appIsPaused()
        flutterEngine?.lifecycleChannel?.appIsDetached()
        Log.d(TAG, "Flutter engine stopped")
    }
    
    /**
     * Start the Flutter engine (call in onStart)
     */
    fun startEngine() {
        flutterEngine?.lifecycleChannel?.appIsResumed()
        Log.d(TAG, "Flutter engine started")
    }
    
    fun sendViewState(
        mode: String,
        text: String = "",
        width: Double = 300.0,
        height: Double = 200.0,
        topicId: Int = 0,
        pageId: Int = 0
    ) {
        val data = mapOf(
            "mode" to mode,
            "text" to text,
            "width" to width,
            "height" to height,
            "topicId" to topicId,
            "pageId" to pageId
        )
        
        baseChannel?.invokeMethod("setData", data)
        Log.d(TAG, "Sent view state: mode=$mode, text=$text")
    }
    
    fun initializeTFS(
        baseUrl: String,
        authToken: String,
        portalToken: String,
        appId: String,
        clientId: String,
        publicKey: String
    ) {
        val data = mapOf(
            "baseUrl" to baseUrl,
            "authToken" to authToken,
            "portalToken" to portalToken,
            "appId" to appId,
            "clientId" to clientId,
            "publicKey" to publicKey
        )
        
        authChannel?.invokeMethod("initializeTFS", data, object : MethodChannel.Result {
            override fun success(result: Any?) {
                isTFSInitialized = true
                Log.d(TAG, "TFS initialized successfully")
            }
            
            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                Log.e(TAG, "TFS initialization failed: $errorMessage")
            }
            
            override fun notImplemented() {
                Log.e(TAG, "TFS initialization not implemented")
            }
        })
    }
    
    fun isTFSInitialized(): Boolean = isTFSInitialized
    
    fun navigateTo(route: String, arguments: Map<String, Any> = emptyMap()) {
        navChannel?.invokeMethod("navigateTo", arguments)
        Log.d(TAG, "Navigate to: $route")
    }
    
    fun updateNavigationState(screenData: Map<String, Any>) {
        navigationHandler.updateState(screenData)
    }
}
