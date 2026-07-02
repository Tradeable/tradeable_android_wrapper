package com.tradeable.sdk.android.wrapper

import android.app.Activity
import android.content.Context
import android.util.Log
import io.flutter.embedding.android.FlutterTextureView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

/**
 * FlutterBridge - Manages Flutter integration for Android Mirrors iOS FlutterBridge implementation
 * Handles method channels for auth and navigation
 */
class FlutterBridge private constructor(private val context: Context) {

    enum class ViewScope {
        EMBEDDED,
        FULLSCREEN
    }

    companion object {
        private var instance: FlutterBridge? = null
        private const val TAG = "FlutterBridge"
        private const val BASE_CHANNEL = "embedded_flutter"
        private const val AUTH_CHANNEL = "embedded_flutter/auth"
        private const val NAV_CHANNEL = "embedded_flutter/navigation"

        fun getInstance(context: Context): FlutterBridge {
            return instance
                    ?: synchronized(this) {
                        instance ?: FlutterBridge(context.applicationContext).also { instance = it }
                    }
        }
    }

    private data class EngineBundle(
            val engine: FlutterEngine,
            val baseChannel: MethodChannel,
            val authChannel: MethodChannel,
            val navChannel: MethodChannel
    )

    private val engineBundles = mutableMapOf<ViewScope, EngineBundle>()

    private val closeHandlersByOwner = LinkedHashMap<String, () -> Unit>()
    private val sideDrawerHandlersByOwner = LinkedHashMap<String, () -> Unit>()
    private val dataHandlersByOwner = LinkedHashMap<String, (Map<String, Any>) -> Unit>()
    private var activeOwner: String? = null
    private val legacyOwner = "legacy"
    private var viewStateVersion: Long = 0L

    private val flutterViewsByScope =
            mutableMapOf(
                    ViewScope.EMBEDDED to mutableListOf<FlutterView>(),
                    ViewScope.FULLSCREEN to mutableListOf<FlutterView>()
            )

    val authHandler = AuthHandler()
    val navigationHandler = NavigationHandler()

    private var isInitialized = false
    private val tfsInitializedByScope =
            mutableMapOf(ViewScope.EMBEDDED to false, ViewScope.FULLSCREEN to false)

    fun initialize(activity: Activity) {
        if (isInitialized) {
            Log.d(TAG, "FlutterBridge already initialized")
            return
        }
        ensureEngine(ViewScope.EMBEDDED)
        ensureEngine(ViewScope.FULLSCREEN)

        isInitialized = true
        Log.d(TAG, "FlutterBridge initialized with engine and channels")
    }

    private fun ensureEngine(scope: ViewScope): EngineBundle {
        engineBundles[scope]?.let {
            return it
        }

        val engine = FlutterEngine(context)
        engine.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault())

        val baseChannel = MethodChannel(engine.dartExecutor.binaryMessenger, BASE_CHANNEL)
        val authChannel = MethodChannel(engine.dartExecutor.binaryMessenger, AUTH_CHANNEL)
        val navChannel = MethodChannel(engine.dartExecutor.binaryMessenger, NAV_CHANNEL)

        baseChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "closeCard", "closeFullscreen" -> {
                    Log.d(TAG, "[$scope] Flutter requested close: ${call.method}")
                    activeCloseHandler()?.invoke()
                    result.success(null)
                }
                "closeSideDrawer" -> {
                    Log.d(TAG, "[$scope] Flutter requested side drawer close")
                    activeSideDrawerHandler()?.invoke()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        navChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "sendData" -> {
                    @Suppress("UNCHECKED_CAST")
                    val payload = (call.arguments as? Map<String, Any>) ?: emptyMap()
                    Log.d(TAG, "[$scope] Received navigation data from Flutter: $payload")
                    val handler = activeDataHandler()
                    if (handler == null) {
                        Log.w(
                                TAG,
                                "Dropped sendData: no active data handler. activeOwner=$activeOwner handlers=${dataHandlersByOwner.keys}"
                        )
                    } else {
                        handler.invoke(payload)
                    }
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        val bundle = EngineBundle(engine, baseChannel, authChannel, navChannel)
        engineBundles[scope] = bundle
        return bundle
    }

    fun createFlutterView(scope: ViewScope = ViewScope.EMBEDDED): FlutterView {
        val bundle = ensureEngine(scope)

        // Ensure engine is in resumed state before creating view
        bundle.engine.lifecycleChannel.appIsResumed()
        Log.d(TAG, "Engine lifecycle set to resumed before view creation")

        // Use TextureView-backed FlutterView for better behavior inside Compose scroll/lazy
        // containers.
        return FlutterView(context, FlutterTextureView(context)).apply {
            attachToFlutterEngine(bundle.engine)
            flutterViewsByScope.getValue(scope).add(this)
            Log.d(
                    TAG,
                    "Created $scope Flutter view. Total views: ${flutterViewsByScope.getValue(scope).size}"
            )
        }
    }

    /** Setup close handler for card flip and fullscreen modes */
    fun setupCloseHandler(handler: (() -> Unit)?) {
        registerCloseHandler(legacyOwner, handler)
        if (handler != null) {
            activateOwner(legacyOwner)
        } else if (activeOwner == legacyOwner) {
            activeOwner = null
        }
    }

    fun setupSideDrawerCloseHandler(handler: (() -> Unit)?) {
        registerSideDrawerCloseHandler(legacyOwner, handler)
        if (handler != null) {
            activateOwner(legacyOwner)
        } else if (activeOwner == legacyOwner) {
            activeOwner = null
        }
    }

    fun setupDataHandler(handler: ((Map<String, Any>) -> Unit)?) {
        registerDataHandler(legacyOwner, handler)
        if (handler != null) {
            activateOwner(legacyOwner)
        } else if (activeOwner == legacyOwner) {
            activeOwner = null
        }
    }

    @Synchronized
    fun registerCloseHandler(owner: String, handler: (() -> Unit)?) {
        if (handler == null) {
            closeHandlersByOwner.remove(owner)
            Log.d(TAG, "Close handler cleared for owner=$owner")
        } else {
            closeHandlersByOwner[owner] = handler
            Log.d(TAG, "Close handler registered for owner=$owner")
        }
    }

    @Synchronized
    fun registerSideDrawerCloseHandler(owner: String, handler: (() -> Unit)?) {
        if (handler == null) {
            sideDrawerHandlersByOwner.remove(owner)
            Log.d(TAG, "Side drawer handler cleared for owner=$owner")
        } else {
            sideDrawerHandlersByOwner[owner] = handler
            Log.d(TAG, "Side drawer handler registered for owner=$owner")
        }
    }

    @Synchronized
    fun registerDataHandler(owner: String, handler: ((Map<String, Any>) -> Unit)?) {
        if (handler == null) {
            dataHandlersByOwner.remove(owner)
            Log.d(TAG, "Data handler cleared for owner=$owner")
        } else {
            dataHandlersByOwner[owner] = handler
            Log.d(TAG, "Data handler registered for owner=$owner")
        }
    }

    @Synchronized
    fun clearOwnerHandlers(owner: String) {
        closeHandlersByOwner.remove(owner)
        sideDrawerHandlersByOwner.remove(owner)
        dataHandlersByOwner.remove(owner)
        if (activeOwner == owner) {
            activeOwner = null
            Log.d(TAG, "Active owner cleared after removing owner=$owner")
        }
    }

    @Synchronized
    fun activateOwner(owner: String?) {
        activeOwner = owner
        Log.d(TAG, "Activated owner=$owner")
    }

    @Synchronized
    private fun activeCloseHandler(): (() -> Unit)? {
        val owner = activeOwner
        return if (owner != null) {
            closeHandlersByOwner[owner] ?: closeHandlersByOwner[legacyOwner]
        } else {
            closeHandlersByOwner[legacyOwner]
        }
    }

    @Synchronized
    private fun activeSideDrawerHandler(): (() -> Unit)? {
        val owner = activeOwner
        return if (owner != null) {
            sideDrawerHandlersByOwner[owner] ?: sideDrawerHandlersByOwner[legacyOwner]
        } else {
            sideDrawerHandlersByOwner[legacyOwner]
        }
    }

    @Synchronized
    private fun activeDataHandler(): ((Map<String, Any>) -> Unit)? {
        val owner = activeOwner
        if (owner != null) {
            dataHandlersByOwner[owner]?.let {
                return it
            }
        }
        dataHandlersByOwner[legacyOwner]?.let {
            return it
        }
        return dataHandlersByOwner.entries.lastOrNull()?.value
    }

    /** Manually detach a specific view when no longer needed */
    fun detachView(view: FlutterView) {
        view.detachFromFlutterEngine()
        flutterViewsByScope.forEach { (scope, views) ->
            if (views.remove(view)) {
                Log.d(TAG, "Detached $scope Flutter view. Remaining views: ${views.size}")
            }
        }
    }

    /** Pause the Flutter engine (call in onPause) */
    fun pauseEngine() {
        engineBundles.values.forEach { bundle ->
            bundle.engine.lifecycleChannel.appIsInactive()
            bundle.engine.lifecycleChannel.appIsPaused()
        }
        Log.d(TAG, "Flutter engine paused")
    }

    /** Resume the Flutter engine (call in onResume) */
    fun resumeEngine() {
        engineBundles.values.forEach { bundle -> bundle.engine.lifecycleChannel.appIsResumed() }
        Log.d(TAG, "Flutter engine resumed")
    }

    /** Stop the Flutter engine (call in onStop) */
    fun stopEngine() {
        engineBundles.values.forEach { bundle ->
            bundle.engine.lifecycleChannel.appIsInactive()
            bundle.engine.lifecycleChannel.appIsPaused()
            bundle.engine.lifecycleChannel.appIsDetached()
        }
        Log.d(TAG, "Flutter engine stopped")
    }

    /** Start the Flutter engine (call in onStart) */
    fun startEngine() {
        engineBundles.values.forEach { bundle -> bundle.engine.lifecycleChannel.appIsResumed() }
        Log.d(TAG, "Flutter engine started")
    }

    fun sendViewState(
            mode: String,
            text: String = "",
            width: Double = 300.0,
            height: Double = 200.0,
            topicId: Int = 0,
            pageId: Int = 0,
            courseId: Int = 0,
            scope: ViewScope = ViewScope.EMBEDDED
    ) {
        val bundle = ensureEngine(scope)
        viewStateVersion += 1
        val data =
                mapOf(
                        "mode" to mode,
                        "text" to text,
                        "width" to width,
                        "height" to height,
                        "topicId" to topicId,
                        "pageId" to pageId,
                        "courseId" to courseId,
                        "hostViewStateVersion" to viewStateVersion
                )

        bundle.baseChannel.invokeMethod("setData", data)
        Log.d(TAG, "[$scope] Sent view state: mode=$mode, text=$text")
    }

    fun initializeTFS(
            baseUrl: String,
            authToken: String,
            portalToken: String,
            appId: String,
            clientId: String,
            publicKey: String
    ) {
        val data =
                mapOf(
                        "baseUrl" to baseUrl,
                        "authToken" to authToken,
                        "portalToken" to portalToken,
                        "appId" to appId,
                        "clientId" to clientId,
                        "publicKey" to publicKey
                )

        val scopes = listOf(ViewScope.EMBEDDED, ViewScope.FULLSCREEN)
        scopes.forEach { scope ->
            val bundle = ensureEngine(scope)
            bundle.authChannel.invokeMethod(
                    "initializeTFS",
                    data,
                    object : MethodChannel.Result {
                        override fun success(result: Any?) {
                            tfsInitializedByScope[scope] = true
                            Log.d(TAG, "[$scope] TFS initialized successfully")
                        }

                        override fun error(
                                errorCode: String,
                                errorMessage: String?,
                                errorDetails: Any?
                        ) {
                            Log.e(TAG, "[$scope] TFS initialization failed: $errorMessage")
                        }

                        override fun notImplemented() {
                            Log.e(TAG, "[$scope] TFS initialization not implemented")
                        }
                    }
            )
        }
    }

    fun isTFSInitialized(scope: ViewScope = ViewScope.EMBEDDED): Boolean =
            tfsInitializedByScope[scope] == true

    fun navigateTo(route: String, arguments: Map<String, Any> = emptyMap()) {
        ensureEngine(ViewScope.EMBEDDED).navChannel.invokeMethod("navigateTo", arguments)
        Log.d(TAG, "Navigate to: $route")
    }

    fun updateNavigationState(screenData: Map<String, Any>) {
        navigationHandler.updateState(screenData)
    }
}
