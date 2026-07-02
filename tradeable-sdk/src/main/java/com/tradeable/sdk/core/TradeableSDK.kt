package com.tradeable.sdk.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tradeable.sdk.bridge.FlutterBridge
import com.tradeable.sdk.config.TradeableAnalyticsEvent
import com.tradeable.sdk.config.TradeableCallbackEvent
import com.tradeable.sdk.config.TradeableConfig
import com.tradeable.sdk.config.TradeableCredentials
import com.tradeable.sdk.config.TradeablePageParams
import com.tradeable.sdk.ui.TradeableFlutterActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Main entry point for the Tradeable SDK.
 * 
 * Initialize the SDK in your Application class:
 * ```kotlin
 * TradeableSDK.initialize(
 *     context = this,
 *     config = TradeableConfig(
 *         baseUrl = "https://...",
 *         onRefreshCredentials = { 
 *             TradeableCredentials(...) 
 *         },
 *         onAnalyticsEvent = { event -> /* handle */ }
 *     )
 * )
 * ```
 */
object TradeableSDK {
    
    private const val TAG = "TradeableSDK"
    
    private var applicationContext: WeakReference<Context>? = null
    private var config: TradeableConfig? = null
    private var flutterBridge: FlutterBridge? = null
    
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    private val _currentCredentials = MutableStateFlow<TradeableCredentials?>(null)
    val currentCredentials: StateFlow<TradeableCredentials?> = _currentCredentials.asStateFlow()
    
    /**
     * Initialize the Tradeable SDK.
     * Should be called once in Application.onCreate()
     * 
     * @param context Application context
     * @param config SDK configuration
     */
    fun initialize(context: Context, config: TradeableConfig) {
        if (_isInitialized.value) {
            log("SDK already initialized")
            return
        }
        
        this.applicationContext = WeakReference(context.applicationContext)
        this.config = config
        
        log("Initializing Tradeable SDK...")
        log("Base URL: ${config.baseUrl}")
        
        // Initialize Flutter bridge
        flutterBridge = FlutterBridge(context.applicationContext, config)
        
        // Pre-warm Flutter engine
        sdkScope.launch {
            try {
                flutterBridge?.preWarmEngine()
                _isInitialized.value = true
                log("SDK initialized successfully")
                
                // Fetch initial credentials
                refreshCredentials()
            } catch (e: Exception) {
                log("Failed to initialize SDK: ${e.message}", error = true)
            }
        }
    }
    
    /**
     * Refresh authentication credentials
     */
    suspend fun refreshCredentials(): TradeableCredentials? {
        val cfg = config ?: run {
            log("SDK not initialized", error = true)
            return null
        }
        
        return try {
            val credentials = cfg.onRefreshCredentials()
            _currentCredentials.value = credentials
            flutterBridge?.updateCredentials(credentials)
            log("Credentials refreshed")
            credentials
        } catch (e: Exception) {
            log("Failed to refresh credentials: ${e.message}", error = true)
            null
        }
    }
    
    /**
     * Open a full-page Flutter view
     * 
     * @param activity Current activity
     * @param params Page parameters including route and optional parameters
     */
    fun openFullPage(activity: Activity, params: TradeablePageParams) {
        if (!_isInitialized.value) {
            log("SDK not initialized. Call initialize() first.", error = true)
            return
        }

        val route = params.route.lowercase()
        val mode = when {
            route.contains("dashboard") -> "dashboard"
            route.contains("userprogress") || route.contains("user_progress") -> "userProgressScreen"
            route.contains("course") && route.contains("details") -> "courseDetailsScreen"
            else -> "fullscreen"
        }

        val topicId = params.parameters["topicId"]?.toIntOrNull() ?: 0
        val courseId = params.parameters["courseId"]?.toIntOrNull() ?: 0
        val pageId = params.parameters["pageId"]?.toIntOrNull() ?: 0
        
        val intent = Intent(activity, TradeableFlutterActivity::class.java).apply {
            putExtra("mode", mode)
            putExtra("text", params.title ?: "Fullscreen")
            putExtra("topicId", topicId)
            putExtra("courseId", courseId)
            putExtra("pageId", pageId)
        }
        activity.startActivity(intent)
    }
    
    /**
     * Open the Tradeable dashboard
     */
    fun openDashboard(activity: Activity) {
        openFullPage(activity, TradeablePageParams(route = "/dashboard"))
    }

    fun openUserProgress(activity: Activity, title: String = "My Activity") {
        openFullPage(activity, TradeablePageParams(route = "/userProgress", title = title))
    }

    fun openCourseDetails(activity: Activity, courseId: Int, title: String = "Course Details") {
        openFullPage(
            activity,
            TradeablePageParams(
                route = "/course/details",
                parameters = mapOf("courseId" to courseId.toString()),
                title = title
            )
        )
    }
    
    /**
     * Get the Flutter bridge instance (internal use)
     */
    internal fun getFlutterBridge(): FlutterBridge? = flutterBridge
    
    /**
     * Get the current configuration (internal use)
     */
    internal fun getConfig(): TradeableConfig? = config
    
    /**
     * Get application context (internal use)
     */
    internal fun getApplicationContext(): Context? = applicationContext?.get()
    
    /**
     * Handle analytics event from Flutter
     */
    internal fun handleAnalyticsEvent(event: TradeableAnalyticsEvent) {
        config?.onAnalyticsEvent?.invoke(event)
    }
    
    /**
     * Handle callback event from Flutter
     */
    internal fun handleCallback(event: TradeableCallbackEvent) {
        config?.onCallback?.invoke(event)
    }
    
    /**
     * Check if debug mode is enabled
     */
    internal fun isDebugMode(): Boolean = config?.debugMode == true
    
    /**
     * Cleanup SDK resources
     */
    fun destroy() {
        flutterBridge?.destroy()
        flutterBridge = null
        config = null
        applicationContext = null
        _isInitialized.value = false
        _currentCredentials.value = null
        log("SDK destroyed")
    }
    
    private fun log(message: String, error: Boolean = false) {
        if (config?.debugMode == true || error) {
            if (error) {
                Log.e(TAG, message)
            } else {
                Log.d(TAG, message)
            }
        }
    }
}
