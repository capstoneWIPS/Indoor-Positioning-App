package com.example.indoor_positioning_app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.google.android.material.navigation.NavigationView
import org.json.JSONArray
import java.nio.FloatBuffer
import kotlin.math.*
import com.google.android.material.appbar.MaterialToolbar
import androidx.appcompat.app.ActionBarDrawerToggle

// Custom overlay view for position marker
class PositionOverlayView(context: Context) : View(context) {
    private var normalizedX = 0.0
    private var normalizedY = 0.0
    private var mapImageView: SubsamplingScaleImageView? = null
    private var currentPulseScale = 1.0f
    private var originalImageWidth = 0
    private var originalImageHeight = 0

    private val positionDotPaint = Paint().apply {
        color = Color.parseColor("#007AFF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val positionRingPaint = Paint().apply {
        color = Color.parseColor("#80007AFF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val positionBorderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    fun setMapImageView(imageView: SubsamplingScaleImageView) {
        mapImageView = imageView
        Log.d("PositionOverlay", "Map image view set")
    }

    fun updatePosition(x: Double, y: Double, imgWidth: Int, imgHeight: Int) {
        Log.d("PositionOverlay", "updatePosition called: x=$x, y=$y, imgWidth=$imgWidth, imgHeight=$imgHeight")
        normalizedX = x
        normalizedY = y
        originalImageWidth = imgWidth
        originalImageHeight = imgHeight
        invalidate()
    }

    fun updatePulseScale(scale: Float) {
        currentPulseScale = scale
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        Log.d("PositionOverlay", "onDraw called - normalizedX: $normalizedX, normalizedY: $normalizedY")

        val imageView = mapImageView
        Log.d("PositionOverlay", "ImageView isReady: ${imageView?.isReady}, normalizedX > 0: ${normalizedX > 0.0}, normalizedY > 0: ${normalizedY > 0.0}")

        if (imageView?.isReady == true && normalizedX > 0.0 && normalizedY > 0.0) {
            // Convert normalized coordinates to source coordinates
            val sourceX = (normalizedX * originalImageWidth).toFloat()
            val sourceY = (normalizedY * originalImageHeight).toFloat()

            Log.d("PositionOverlay", "Source coordinates: sourceX=$sourceX, sourceY=$sourceY")

            // Convert source coordinates to view coordinates
            val viewCoord = imageView.sourceToViewCoord(sourceX, sourceY)
            Log.d("PositionOverlay", "View coordinates: ${viewCoord?.x}, ${viewCoord?.y}")

            if (viewCoord != null && viewCoord.x >= 0 && viewCoord.y >= 0 &&
                viewCoord.x <= width && viewCoord.y <= height) {

                val baseRadius = 20f
                val pulseRadius = baseRadius * currentPulseScale

                Log.d("PositionOverlay", "Drawing marker at: ${viewCoord.x}, ${viewCoord.y}, radius: $baseRadius")

                // Draw pulsating outer ring
                val ringPaint = Paint(positionRingPaint).apply {
                    alpha = ((1.0f - (currentPulseScale - 1.0f)) * 80).toInt().coerceIn(20, 80)
                }
                canvas.drawCircle(viewCoord.x, viewCoord.y, pulseRadius, ringPaint)

                // Draw main position dot with white border
                canvas.drawCircle(viewCoord.x, viewCoord.y, baseRadius + 4f, positionBorderPaint)
                canvas.drawCircle(viewCoord.x, viewCoord.y, baseRadius, positionDotPaint)
            } else {
                Log.d("PositionOverlay", "ViewCoord is null or outside bounds")
            }
        } else {
            Log.d("PositionOverlay", "Conditions not met for drawing marker")
        }
    }
}
class MapActivity : AppCompatActivity() {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var mapImageView: SubsamplingScaleImageView
    private lateinit var floorChipGroup: ChipGroup
    private lateinit var fabSensors: FloatingActionButton
    private lateinit var saveButton: Button
    private lateinit var wifiManager: WifiManager
    private lateinit var gestureDetector: GestureDetector

    // Add the overlay view
    private lateinit var positionOverlay: PositionOverlayView

    // Use normalized coordinates (0.0 to 1.0) instead of absolute pixel coordinates
    private var normalizedX = 0.0
    private var normalizedY = 0.0

    private var scanIndex = 0
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val scanInterval = 500L
    private var latestScanResults: List<android.net.wifi.ScanResult> = emptyList()

    // Data structure to store all scan data
    private val outermostMap = mutableMapOf<Int, MutableMap<String, Any?>>()
    private val currentScanData = mutableMapOf<String, Any?>()

    private var readMap = mutableMapOf<String,MutableMap<Int, MutableMap<String, Any?>>>()
    var scanIndexMap = mutableMapOf<String, Int>()

    // Store original image dimensions for consistent coordinate calculation
    private var originalImageWidth = 0
    private var originalImageHeight = 0

    // Animation properties for pulsating dot
    private var pulseAnimator: ValueAnimator? = null
    private var currentPulseScale = 1.0f

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (isScanning) {
                startWifiScan()
                handler.postDelayed(this, scanInterval)
                getCurrentPosition()
            }
        }
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                scanSuccess()
            } else {
                scanFailure()
            }
        }
    }

    private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
    }

    private val PERMISSION_REQUEST_CODE = 100

    private val floorPlans: MutableMap<String, Int> = mutableMapOf(
        "Ground Floor" to R.drawable.ground_floor,
        "Floor One" to R.drawable.first_floor,
        "Floor Two" to R.drawable.second_floor,
        "Floor Three" to R.drawable.third_floor,
        "Floor Four" to R.drawable.fourth_floor,
        "Floor Five" to R.drawable.fifth_floor_new,
        "Floor Six" to R.drawable.sixth_floor_new
    )

    private val encodeFloor: MutableMap<String, Int> = mutableMapOf(
        "Floor Five" to 0,
        "Floor Four" to 1,
        "Floor One" to 2,
        "Floor Six" to 3,
        "Floor Three" to 4,
        "Floor Two" to 5,
        "Ground Floor" to 6
    )

    private val decodeFloor: MutableMap<Int,String> = mutableMapOf(
        0 to "Floor Five",
        1 to "Floor Four",
        2 to "Floor One",
        3 to "Floor Six",
        4 to "Floor Three",
        5 to "Floor Two",
        6 to "Ground Floor"
    )

    private val decodeFloorPlan: MutableMap<Int, Int> = mutableMapOf(
        0 to R.drawable.fifth_floor_new,
        1 to R.drawable.fourth_floor,
        2 to R.drawable.first_floor,
        3 to R.drawable.sixth_floor_new,
        4 to R.drawable.third_floor,
        5 to R.drawable.second_floor,
        6 to R.drawable.ground_floor
    )

    var jsonColumnText = ""

    private lateinit var columnsJson: JSONObject // reads JSON containing BSSID:column number pairs
    var columnLength = 0
    private lateinit var modelInput: FloatArray

    private var floorPrediction = 0

    private var currentFloor = "Ground Floor"
    private lateinit var originalBitmap: Bitmap
    private var isFloorChanged = false

    // Initialize list with default values, finally this list goes to ONNX model
    private fun initializeModelParameters() {
        var index = 0
        for (i in 0..5) {
            modelInput[index++] = 0.0f
        }
        for (i in 6 until columnLength) {
            modelInput[index++] = -120.0f
        }
        Log.d("MapActivity", "Model input initialized: ${modelInput.contentToString()}")
    }

    // Create an OrtSession with the given OrtEnvironment
    private fun createONNXSession(ortEnvironment: OrtEnvironment): OrtSession {
        val modelBytes = resources.openRawResource(R.raw.rssiknn).readBytes()
        return ortEnvironment.createSession(modelBytes)
    }

    private fun runPositionPrediction(ortSession: OrtSession, ortEnvironment: OrtEnvironment): Array<FloatArray> {
        // Get the name of the input node
        val inputName = ortSession.inputNames?.iterator()?.next()
        // Make a FloatBuffer of the inputs
        val floatBufferInputs = FloatBuffer.wrap(modelInput)
        // Create input tensor with floatBufferInputs of shape (1, columnLength)
        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment,
            floatBufferInputs,
            longArrayOf(1, columnLength.toLong())
        )
        // Run the model
        val results = ortSession.run(mapOf(inputName to inputTensor))
        // Fetch and return the results
        val output = results[0].value as Array<FloatArray>
        Log.d("MapActivity", "Model prediction output: ${output[0].contentToString()}")
        return output
    }

    private fun initializeBitmaps() {
        try {
            Log.d("MapActivity", "Initializing bitmap for floor: $currentFloor")

            val resBitmap = BitmapFactory.decodeResource(
                resources,
                floorPlans[currentFloor] ?: R.drawable.ground_floor
            ) ?: throw IllegalStateException("Failed to load bitmap resource")

            originalImageWidth = resBitmap.width
            originalImageHeight = resBitmap.height

            // Only recycle old bitmap if it exists
            if (::originalBitmap.isInitialized && !originalBitmap.isRecycled) {
                originalBitmap.recycle()
            }

            originalBitmap = resBitmap

        } catch (e: Exception) {
            Log.e("MapActivity", "Error initializing bitmap: ${e.message}", e)
            Toast.makeText(this, "Error initializing bitmap", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(1.0f, 2.0f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                currentPulseScale = animation.animatedValue as Float
                positionOverlay.updatePulseScale(currentPulseScale)
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        currentPulseScale = 1.0f
    }

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        if (checkIfPermissionsGranted()) {
            startContinuousScanning()
        }
        startPulseAnimation()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
        }

        stopContinuousScanning()
        stopPulseAnimation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mapMain)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initializeViews()
        setupDrawer()
        initializeWifi()
        setupFloorSelector()
        setupFloatingActionButton()
        checkPermissions()

        // Initialize bitmaps BEFORE setting up map interaction
        initializeBitmaps()
        setupMapInteraction()
        loadFloorPlan(currentFloor)

        jsonColumnText = resources.openRawResource(R.raw.colsp2)
            .bufferedReader().use { it.readText() }
        Log.d("MapActivity", "JSON column text loaded successfully")

        columnsJson = JSONObject(jsonColumnText)
        columnLength = columnsJson.length()

        modelInput = FloatArray(columnLength)

        initializeModelParameters()

        saveButton = findViewById(R.id.button3)

        if (checkAndRequestPermissions()) {
            startContinuousScanning()
        }

        saveButton.setOnClickListener {
            getCurrentPosition()
        }
    }

    fun getCurrentPosition() {


        // Then continue with your existing ONNX prediction code...
        val ortEnvironment = OrtEnvironment.getEnvironment()
        val ortSession = createONNXSession(ortEnvironment)

        for (result in latestScanResults) {
            if (result.SSID.isNotEmpty()) {
                val ssid = result.SSID
                if (ssid in listOf("PESU-CIE", "PESU-Commandcenter", "PESU-EC-Campus",
                        "PESU-PIXELB", "PESU-Research1", "PESU-Research2")) {
                    val columnIndex = columnsJson.get(ssid)
                    modelInput[columnIndex as Int] = 1f

                    if (result.BSSID.isNotEmpty()) {
                        if (columnsJson.has(result.BSSID)) {
                            val bssidColumnIndex = columnsJson.get(result.BSSID)
                            modelInput[bssidColumnIndex as Int] = result.level.toFloat()
                        }
                    }
                }
            }
        }

        val output = runPositionPrediction(ortSession, ortEnvironment)[0]
        val x = output[0]
        val y = output[1]
        val floorNumber = output[2]

        Log.d("MapActivity", "Position prediction: x=$x, y=$y, floor=$floorNumber")

        // Use predicted coordinates
        normalizedX = x.toDouble()
        normalizedY = y.toDouble()
        floorPrediction = round(floorNumber).toInt()
        val newFloor = decodeFloor[floorPrediction]!!

        // Check if floor changed
        if (newFloor != currentFloor) {
            currentFloor = newFloor
            isFloorChanged = true

            // Update floor selection chips
            for (i in 0 until floorChipGroup.childCount) {
                val chip = floorChipGroup.getChildAt(i) as Chip
                chip.isChecked = (chip.text.toString() == currentFloor)
            }

            loadFloorPlan(currentFloor)
        }

        // Update position marker
        addPositionMarkerAndSave()

        Log.d("MapActivity", "Position updated: floor=$currentFloor, coordinates=($normalizedX, $normalizedY)")
        initializeModelParameters()
    }
    private fun initializeViews() {
        // Initialize drawer components
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)

        // Your existing views
        mapImageView = findViewById(R.id.mapImageView)
        floorChipGroup = findViewById(R.id.floorChipGroup)
        fabSensors = findViewById(R.id.fabSensors)

        // Create or reuse overlay
        if (!::positionOverlay.isInitialized) {
            positionOverlay = PositionOverlayView(this)
        }

        // Remove from parent if it has one
        (positionOverlay.parent as? ViewGroup)?.removeView(positionOverlay)

        positionOverlay.setMapImageView(mapImageView)
        positionOverlay.setBackgroundColor(Color.TRANSPARENT)

        // Add overlay to the MAIN CoordinatorLayout instead of the FrameLayout
        val mainLayout = findViewById<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.mapMain)
        val layoutParams = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams.MATCH_PARENT,
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams.MATCH_PARENT
        )
        layoutParams.behavior = com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()

        mainLayout.addView(positionOverlay, layoutParams)

        Log.d("MapActivity", "Overlay added to main layout")
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
    private fun setupDrawer() {
        val toolbar = findViewById<MaterialToolbar>(R.id.mapToolbar)
        setSupportActionBar(toolbar)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // Set up navigation view
        navigationView.setNavigationItemSelectedListener { menuItem ->
            // Handle navigation item clicks if you add menu items
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun initializeWifi() {
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private fun setupMapInteraction() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                Log.d("MapActivity", "Single tap detected at screen coordinates: (${e.x}, ${e.y})")

                try {
                    // Ensure image dimensions are valid
                    if (originalImageWidth <= 0 || originalImageHeight <= 0) {
                        Log.e("MapActivity", "Invalid image dimensions: ${originalImageWidth}x${originalImageHeight}")
                        Toast.makeText(this@MapActivity, "Error: Invalid image dimensions", Toast.LENGTH_SHORT).show()
                        return false
                    }

                    // Convert screen coordinates to image coordinates
                    val point = mapImageView.viewToSourceCoord(e.x, e.y)
                    if (point != null) {
                        Log.d("MapActivity", "Converted to image coordinates: (${point.x}, ${point.y})")

                        // Convert to normalized coordinates (0.0 to 1.0)
                        normalizedX = (point.x.toDouble() / originalImageWidth).coerceIn(0.0, 1.0)
                        normalizedY = (point.y.toDouble() / originalImageHeight).coerceIn(0.0, 1.0)

                        Log.d("MapActivity", "Normalized coordinates: ($normalizedX, $normalizedY)")

                        Toast.makeText(
                            this@MapActivity,
                            "Tapped at: (${String.format("%.3f", normalizedX)}, ${String.format("%.3f", normalizedY)})",
                            Toast.LENGTH_SHORT
                        ).show()

                    } else {
                        Log.w("MapActivity", "Failed to convert screen coordinates to image coordinates")
                        Toast.makeText(this@MapActivity, "Unable to detect tap position", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("MapActivity", "Error processing tap: ${e.message}", e)
                    Toast.makeText(this@MapActivity, "Error processing tap: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        })

        mapImageView.setOnTouchListener { _, event ->
            // Let the gesture detector handle single taps
            gestureDetector.onTouchEvent(event)
            // Always return false to let SubsamplingScaleImageView handle all gestures
            false
        }
    }

    // Ultra-simple position update - just tells overlay to redraw
    private fun updatePositionOnly() {
        positionOverlay.updatePosition(
            normalizedX,
            normalizedY,
            originalImageWidth,
            originalImageHeight
        )
    }

    private fun addPositionMarkerAndSave() {
        try {
            Log.d("MapActivity", "Adding position marker at normalized coordinates: ($normalizedX, $normalizedY)")

            if (isFloorChanged) {
                // Floor changed - load new floor plan but DON'T change the image until ready
                isFloorChanged = false
            }

            // Simply update the overlay - this never interferes with zoom/pan
            updatePositionOnly()

        } catch (e: Exception) {
            Log.e("MapActivity", "Error adding position marker: ${e.message}", e)
            Toast.makeText(this, "Error adding position marker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun checkIfPermissionsGranted(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissionsToRequest = ArrayList<String>()

        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
            return false
        }

        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startContinuousScanning()
            } else {
                Toast.makeText(
                    this,
                    "Permission denied. Cannot scan WiFi networks without required permissions.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun startContinuousScanning() {
        if (!isScanning) {
            isScanning = true
            handler.post(scanRunnable)
            Log.d("MapActivity", "Started continuous WiFi scanning")
        }
    }

    private fun stopContinuousScanning() {
        isScanning = false
        handler.removeCallbacks(scanRunnable)
        Log.d("MapActivity", "Stopped continuous WiFi scanning")
    }

    private fun startWifiScan() {
        try {
            if (!wifiManager.isWifiEnabled) {
                Toast.makeText(this, "WiFi is disabled. Please enable WiFi to scan.", Toast.LENGTH_LONG).show()
                return
            }

            val success = wifiManager.startScan()
            if (!success) {
                scanFailure()
            }
        } catch (e: SecurityException) {
            Toast.makeText(
                this,
                "Permission denied: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            stopContinuousScanning()
        }
    }

    private fun scanSuccess() {
        try {
            val results = wifiManager.scanResults
            latestScanResults = results
            Log.d("MapActivity", "WiFi scan successful: ${results.size} networks found")
        } catch (e: SecurityException) {
            Toast.makeText(this, "Security exception during scan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun scanFailure() {
        Log.w("MapActivity", "WiFi scan failed - will retry")
        Toast.makeText(this, "WiFi scan failed. Retrying...", Toast.LENGTH_SHORT).show()
    }

    private fun setupFloorSelector() {
        floorPlans.keys.forEachIndexed { index, floorName ->
            val chip = Chip(this).apply {
                text = floorName
                isCheckable = true
                isChecked = floorName == currentFloor

                setOnClickListener {
                    if (isChecked) {
                        currentFloor = floorName
                        loadFloorPlan(floorName)

                        for (i in 0 until floorChipGroup.childCount) {
                            val otherChip = floorChipGroup.getChildAt(i) as Chip
                            if (otherChip != this) {
                                otherChip.isChecked = false
                            }
                        }
                    } else {
                        isChecked = true
                    }
                }
            }

            floorChipGroup.addView(chip)
        }
    }

    private fun setupFloatingActionButton() {
        fabSensors.setOnClickListener {
            finish()
        }
    }

    private fun loadFloorPlan(floorName: String) {
        try {
            Log.d("MapActivity", "Loading floor plan: $floorName")
            currentFloor = floorName
            initializeBitmaps()

            if (::originalBitmap.isInitialized && !originalBitmap.isRecycled) {
                mapImageView.setImage(ImageSource.bitmap(originalBitmap))

                mapImageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                mapImageView.setMaxScale(10.0f)
                mapImageView.setMinScale(0.1f)
                mapImageView.setDoubleTapZoomScale(2.0f)
                mapImageView.setDoubleTapZoomDpi(160)

                mapImageView.setPanEnabled(true)
                mapImageView.setZoomEnabled(true)
                mapImageView.setQuickScaleEnabled(true)
            } else {
                Log.e("MapActivity", "Original bitmap not available for floor plan")
                Toast.makeText(this, "Error loading floor plan bitmap", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Error loading floor plan: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("MapActivity", "Error loading floor plan", e)
        }
    }

    private fun processScanResults() {
        try {
            val position = mutableMapOf<String, Any>()
            val readings = mutableMapOf<String, MutableMap<String, Int>>()

            // Store both normalized and pixel coordinates for backward compatibility
            position["normalized_x"] = normalizedX
            position["normalized_y"] = normalizedY
            position["pixel_x"] = (normalizedX * originalImageWidth).toInt()
            position["pixel_y"] = (normalizedY * originalImageHeight).toInt()
            position["image_width"] = originalImageWidth
            position["image_height"] = originalImageHeight

            for (result in latestScanResults) {
                if (result.SSID.isNotEmpty()) {
                    if (result.SSID !in readings) {
                        readings[result.SSID] = mutableMapOf()
                    }
                    readings[result.SSID]!![result.BSSID] = result.level
                }
            }

            currentScanData["position"] = position
            currentScanData["readings"] = readings
            currentScanData["floor"] = currentFloor
            currentScanData["timestamp"] = System.currentTimeMillis()

            printScanData()
            scanIndex++
        } catch (e: Exception) {
            Log.e("MapActivity", "Error processing scan results: ${e.message}", e)
        }
    }

    private fun savePositionDataJson() {
        try {
            val fileName = "${currentFloor.replace(" ", "_")}.json"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)

            val existingJson = if (file.exists()) {
                try {
                    JSONObject(file.readText())
                } catch (e: Exception) {
                    JSONObject()
                }
            } else {
                JSONObject()
            }

            val allScansArray = existingJson.optJSONArray("all_scans") ?: JSONArray()

            val scanJson = JSONObject()

            val position = currentScanData["position"] as? Map<String, Any>
            if (position != null) {
                val positionJson = JSONObject()
                positionJson.put("normalized_x", position["normalized_x"])
                positionJson.put("normalized_y", position["normalized_y"])
                positionJson.put("pixel_x", position["pixel_x"])
                positionJson.put("pixel_y", position["pixel_y"])
                positionJson.put("image_width", position["image_width"])
                positionJson.put("image_height", position["image_height"])
                scanJson.put("position", positionJson)
            }

            val readings = currentScanData["readings"] as? Map<String, Map<String, Int>>
            if (readings != null) {
                val readingsJson = JSONObject()
                for ((ssid, bssidMap) in readings) {
                    val bssidJson = JSONObject()
                    for ((bssid, rssi) in bssidMap) {
                        bssidJson.put(bssid, rssi)
                    }
                    readingsJson.put(ssid, bssidJson)
                }
                scanJson.put("readings", readingsJson)
            }

            scanJson.put("index", allScansArray.length())
            scanJson.put("floor", currentScanData["floor"])
            scanJson.put("scanned_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))

            allScansArray.put(scanJson)

            existingJson.put("all_scans", allScansArray)
            existingJson.put("total_scan_sessions", allScansArray.length())
            existingJson.put("last_updated", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))

            FileWriter(file, false).use { writer ->
                writer.write(existingJson.toString(2))
            }

            Toast.makeText(this, "Position data saved to Downloads/$fileName (${allScansArray.length()} scan sessions)", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error saving position data: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("MapActivity", "Error saving position data: ${e.message}", e)
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopContinuousScanning()
        stopPulseAnimation()

        try {
            if (::originalBitmap.isInitialized && !originalBitmap.isRecycled) {
                originalBitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Error cleaning up bitmap", e)
        }

        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
        }
    }

    private fun printScanData() {
        Log.d("MapActivity", "=== SCAN DATA CONTENTS ===")
        Log.d("MapActivity", "Total entries: ${outermostMap.size}")

        for ((index, scanData) in outermostMap) {
            Log.d("MapActivity", "--- Scan Index: $index ---")

            val position = scanData["position"] as? Map<String, Any>
            if (position != null) {
                Log.d("MapActivity", "Position: normalized=(${position["normalized_x"]}, ${position["normalized_y"]}), pixel=(${position["pixel_x"]}, ${position["pixel_y"]})")
            }

            Log.d("MapActivity", "Floor: ${scanData["floor"]}")
            Log.d("MapActivity", "Timestamp: ${scanData["timestamp"]}")

            val readings = scanData["readings"] as? Map<String, Map<String, Int>>
            if (readings != null) {
                Log.d("MapActivity", "WiFi Networks found: ${readings.size}")
                for ((ssid, bssidMap) in readings) {
                    Log.d("MapActivity", "  SSID: $ssid")
                    for ((bssid, rssi) in bssidMap) {
                        Log.d("MapActivity", "    BSSID: $bssid RSSI: $rssi dBm")
                    }
                }
            }
            Log.d("MapActivity", "------------------------")
        }
        Log.d("MapActivity", "=== END SCAN DATA ===")
    }
}