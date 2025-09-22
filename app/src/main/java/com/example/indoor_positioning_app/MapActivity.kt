package com.example.indoor_positioning_app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import org.json.JSONArray
import java.nio.FloatBuffer
import kotlin.math.*

class MapActivity : AppCompatActivity() {

    private lateinit var mapImageView: SubsamplingScaleImageView
    private lateinit var floorChipGroup: ChipGroup
    private lateinit var fabSensors: FloatingActionButton
    private lateinit var saveButtonVar: Button
    private lateinit var wifiManager: WifiManager
    private lateinit var gestureDetector: GestureDetector

    // Use normalized coordinates (0.0 to 1.0) instead of absolute pixel coordinates
    private var normalizedX = 0.0
    private var normalizedY = 0.0

    private var scanIndex = 0
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val scanInterval = 500L
    private var latestScanResults: List<android.net.wifi.ScanResult> = emptyList()

    // Data structure to store all scan data
    private val outermostmap = mutableMapOf<Int, MutableMap<String, Any?>>()
    private val outermostmap2 = mutableMapOf<String, Any?>()

    private var readMap = mutableMapOf<String,MutableMap<Int, MutableMap<String, Any?>>>()
    var scanIndexMap = mutableMapOf<String, Int>()

    // Store original image dimensions for consistent coordinate calculation
    private var originalImageWidth = 0
    private var originalImageHeight = 0

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (isScanning) {
                startWifiScan()
                handler.postDelayed(this, scanInterval)
                getpositionn()
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

    private val decodeFloor2: MutableMap<Int,String> = mutableMapOf(
        0 to "Floor Five" ,
        1 to "Floor Four" ,
        2 to "Floor One" ,
        3 to "Floor Six" ,
        4 to "Floor Three" ,
        5 to "Floor Two" ,
        6 to "Ground Floor"
    )

    private val decodeFloorplan: MutableMap<Int, Int> = mutableMapOf(
        0 to R.drawable.fifth_floor_new,
        1 to R.drawable.fourth_floor,
        2 to R.drawable.first_floor,
        3 to R.drawable.sixth_floor_new,
        4 to R.drawable.third_floor,
        5 to R.drawable.second_floor,
        6 to R.drawable.ground_floor
    )



    var text11 = ""

    private lateinit  var colsjson: JSONObject //reads JSON containing BSSID:colno pairs
    //private var inputl = mutableListOf<Float>() // input list to pass to ONNX model

    var colLen1 = 0
    private lateinit var inputl: FloatArray

    //private var inputl = FloatArray(colLen1)
    private var temp = 0




    private var currentFloor = "Ground Floor"
    private lateinit var originalBitmap: Bitmap
    private lateinit var workingBitmap: Bitmap



    val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // initalise list with defaault values,finaly this list is going to ONNX
    private fun initparamss(){
        var xx1 = 0
        for (i in 0..5) {
            inputl[xx1++]=(0.0).toFloat()
        }
        for (i in (6..(colLen1-1))){
            inputl[xx1++]=(-120.0).toFloat()
        }
        print(inputl.contentToString())
        //finalInputL.add(inputl)

    }

    // Create an OrtSession with the given OrtEnvironment
    private fun createORTSession( ortEnvironment: OrtEnvironment) : OrtSession {
        val modelBytes = resources.openRawResource( R.raw.rssiknn ).readBytes()
        return ortEnvironment.createSession( modelBytes )
    }

    private fun runPrediction( ortSession: OrtSession , ortEnvironment: OrtEnvironment ) : Array<FloatArray> {
        // Get the name of the input node
        val inputName = ortSession.inputNames?.iterator()?.next()
        // Make a FloatBuffer of the inputs
        val floatBufferInputs = FloatBuffer.wrap( inputl )
        // Create input tensor with floatBufferInputs of shape ( 1 , 1 )
        val inputTensor = OnnxTensor.createTensor( ortEnvironment , floatBufferInputs , longArrayOf( 1, colLen1.toLong() ) )
        // Run the model
        val results = ortSession.run( mapOf( inputName to inputTensor ) )
        // Fetch and return the results
        val output = results[0].value as Array<FloatArray>
        println(output[0].contentToString())
        return output
    }





    // Scale marker radius relative to image size for consistency
    private fun getMarkerRadius(): Float {
        return if (originalImageWidth > 0) {
            (originalImageWidth * 0.005f).coerceAtLeast(10f)
        } else {
            20f // Default fallback
        }
    }

    private fun initializeBitmaps() {
        try {
            Log.d("MapActivity", "Initializing bitmaps for floor: $currentFloor")

            // Load original bitmap and get its dimensions
            val resBitmap = BitmapFactory.decodeResource(
                resources,
                floorPlans[currentFloor] ?: R.drawable.ground_floor
            ) ?: throw IllegalStateException("Failed to load bitmap resource")

            originalImageWidth = resBitmap.width
            originalImageHeight = resBitmap.height

            Log.d("MapActivity", "Image dimensions: ${originalImageWidth}x${originalImageHeight}")

            // Create new copies without recycling existing ones yet
            val newOriginal = resBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val newWorking = newOriginal.copy(Bitmap.Config.ARGB_8888, true)

            // Only recycle old bitmaps after we've created new ones
            if (::originalBitmap.isInitialized && !originalBitmap.isRecycled) {
                originalBitmap.recycle()
            }
            if (::workingBitmap.isInitialized && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }

            originalBitmap = newOriginal
            workingBitmap = newWorking

            // Load existing markers from JSON
            //loadExistingMarkers()

        } catch (e: Exception) {
            Log.e("MapActivity", "Error initializing bitmaps: ${e.message}", e)
            Toast.makeText(this, "Error initializing bitmaps", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadExistingMarkers() {
        try {
            if (originalImageWidth <= 0 || originalImageHeight <= 0) {
                Log.w("MapActivity", "Cannot load markers: invalid image dimensions")
                return
            }

            // Start with a fresh copy of the original image
            workingBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(workingBitmap)

            val jsonFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${currentFloor.replace(" ", "_")}.json")

            if (jsonFile.exists()) {
                val existingJson = JSONObject(jsonFile.readText())

                if (existingJson.has("all_scans")) {
                    val allScansArray = existingJson.getJSONArray("all_scans")

                    for (i in 0 until allScansArray.length()) {
                        val scanObject = allScansArray.getJSONObject(i)

                        if (scanObject.has("position")) {
                            val position = scanObject.getJSONObject("position")

                            // Check if we have normalized coordinates (new format)
                            if (position.has("normalized_x") && position.has("normalized_y")) {
                                val normalizedX = position.getDouble("normalized_x")
                                val normalizedY = position.getDouble("normalized_y")

                                val pixelX = (normalizedX * originalImageWidth).toFloat()
                                val pixelY = (normalizedY * originalImageHeight).toFloat()

                                canvas.drawCircle(pixelX, pixelY, getMarkerRadius(), paint)
                                Log.d("MapActivity", "Loaded marker at normalized: ($normalizedX, $normalizedY) pixel: ($pixelX, $pixelY)")
                            }
                            // Fallback to old format (absolute coordinates)
                            else if (position.has("x") && position.has("y")) {
                                val pixelX = position.getInt("x").toFloat()
                                val pixelY = position.getInt("y").toFloat()

                                canvas.drawCircle(pixelX, pixelY, getMarkerRadius(), paint)
                                Log.d("MapActivity", "Loaded marker at pixel: ($pixelX, $pixelY) (old format)")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Error loading existing markers: ${e.message}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        if (checkIfPermissionsGranted()) {
            startContinuousScanning()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
        }

        stopContinuousScanning()
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
        initializeWifi()
        setupFloorSelector()
        setupFab()
        checkPermissions()

        // Initialize bitmaps BEFORE setting up map interaction
        initializeBitmaps()
        setupMapInteraction()
        loadFloorPlan(currentFloor)

        text11 = resources.openRawResource(R.raw.colsp2)
            .bufferedReader().use { it.readText() }
        Log.d("CompatActivity", "JSONnn text: $text11")

        colsjson=JSONObject(text11)
        colLen1 = colsjson.length()

        inputl = FloatArray(colLen1)

        initparamss()


        saveButtonVar = findViewById(R.id.button3)

        if (checkAndRequestPermissions()) {
            startContinuousScanning()
        }

        saveButtonVar.setOnClickListener {
            getpositionn()
        }
    }

    fun getpositionn(){
        //deleteLastPoint()
        val ortEnvironment = OrtEnvironment.getEnvironment()
        val ortSession = createORTSession( ortEnvironment )
        for (result2 in latestScanResults) {
            if (result2.SSID.isNotEmpty()) {
                val zz1=result2.SSID
                if (zz1=="PESU-CIE" ||  zz1=="PESU-Commandcenter" || zz1=="PESU-EC-Campus" || zz1=="PESU-PIXELB" || zz1=="PESU-Research1" || zz1=="PESU-Research2" ) {
                    val zz2=colsjson.get(zz1)
                    inputl[zz2 as Int]=(1).toFloat()
                    if (result2.BSSID.isNotEmpty()){ //get index in inputl by takin bssid as key from colsjson,put rssi in this index
                        if (colsjson.has(result2.BSSID)){
                            val zz3=colsjson.get(result2.BSSID)
                            inputl[zz3 as Int]=(result2.level).toFloat()
                        }
                    }
                    //alternatively later on make sep list for ssids, so u can chec if its ther in ssids
                }
            }
        }
        val output = runPrediction( ortSession , ortEnvironment )[0]
        val a = output[0]
        val b = output[1]
        val c = output[2]
        print(output.contentToString())
        normalizedX= a.toDouble()
        normalizedY=b.toDouble()
        temp=round(c).toInt()
        currentFloor=decodeFloor2[temp]!!
        val currentScale2 = mapImageView.scale
        val currentCenter2 = mapImageView.center
        //initializeBitmaps()
        for (i in 0 until floorChipGroup.childCount) {
            val chip = floorChipGroup.getChildAt(i) as Chip
            chip.isChecked = (chip.text.toString() == currentFloor) // Check the chip that matches the new floor, uncheck others
        }
        loadFloorPlan(currentFloor)
        addMarkerAndSave(currentScale2,currentCenter2)

        Toast.makeText(this, "Outputt: $currentFloor $c $a, $b ", Toast.LENGTH_SHORT).show()
        Log.d("MapActivity", "Single tap detected at screen coordinates: (${a}, ${b}) at floor ${currentFloor} flor no ${c}")
        initparamss()
    }

    private fun initializeViews() {
        mapImageView = findViewById(R.id.mapImageView)
        floorChipGroup = findViewById(R.id.floorChipGroup)
        fabSensors = findViewById(R.id.fabSensors)
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

                        // Store current zoom and pan state
                        val currentScale = mapImageView.scale
                        val currentCenter = mapImageView.center

                        // Convert to normalized coordinates (0.0 to 1.0)
                        normalizedX = (point.x.toDouble() / originalImageWidth).coerceIn(0.0, 1.0)
                        normalizedY = (point.y.toDouble() / originalImageHeight).coerceIn(0.0, 1.0)

                        Log.d("MapActivity", "Normalized coordinates: ($normalizedX, $normalizedY)")

                        Toast.makeText(
                            this@MapActivity,
                            "Tapped at normalized: (${String.format("%.3f", normalizedX)}, ${String.format("%.3f", normalizedY)})",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Add marker and save data
                        //addMarkerAndSave(currentScale, currentCenter)
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

    private fun markpoint(x:Float,y:Float) {
        try {
            Log.d("MapActivity", "Adding marker at normalized coordinates: ($normalizedX, $normalizedY)")

            // Check if bitmaps are valid
            if (!::workingBitmap.isInitialized || workingBitmap.isRecycled) {
                Log.e("MapActivity", "Working bitmap not valid - reinitializing")
                initializeBitmaps()
                if (!::workingBitmap.isInitialized || workingBitmap.isRecycled) {
                    throw IllegalStateException("Could not initialize valid working bitmap")
                }
            }

            // Create a new copy of the working bitmap to draw on
            val newWorkingBitmap = workingBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(newWorkingBitmap)

            val pixelX = (x * originalImageWidth).toFloat()
            val pixelY = (y * originalImageHeight).toFloat()

            Log.d("MapActivity", "Drawing marker at pixel coordinates: ($pixelX, $pixelY)")
            canvas.drawCircle(pixelX, pixelY, getMarkerRadius(), paint)

            // Replace the working bitmap
            if (!workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
            workingBitmap = newWorkingBitmap

            // Update the image view
            mapImageView.setImage(ImageSource.bitmap(workingBitmap))


        } catch (e: Exception) {
            Log.e("MapActivity", "Error adding marker: ${e.message}", e)
            Toast.makeText(this, "Error adding marker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getcurposition1(){
        processScanResults()

    }

    private fun addMarkerAndSave(preserveScale: Float, preserveCenter: PointF?) {
        try {
            Log.d("MapActivity", "Adding marker at normalized coordinates: ($normalizedX, $normalizedY)")

            // Check if bitmaps are valid
            if (!::workingBitmap.isInitialized || workingBitmap.isRecycled) {
                Log.e("MapActivity", "Working bitmap not valid - reinitializing")
                initializeBitmaps()
                if (!::workingBitmap.isInitialized || workingBitmap.isRecycled) {
                    throw IllegalStateException("Could not initialize valid working bitmap")
                }
            }

            // Create a new copy of the working bitmap to draw on
            val newWorkingBitmap = workingBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(newWorkingBitmap)

            val pixelX = (normalizedX * originalImageWidth).toFloat()
            val pixelY = (normalizedY * originalImageHeight).toFloat()

            Log.d("MapActivity", "Drawing marker at pixel coordinates: ($pixelX, $pixelY)")
            canvas.drawCircle(pixelX, pixelY, getMarkerRadius(), paint)

            // Replace the working bitmap
            if (!workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
            workingBitmap = newWorkingBitmap

            // Process and save the scan data first
            //processScanResults()
            //saveRoomDataJson()

            // Update the image view
            mapImageView.setImage(ImageSource.bitmap(workingBitmap))

            // Restore zoom/pan after a short delay
            handler.postDelayed({
                try {
                    if (preserveCenter != null && mapImageView.isReady) {
                        mapImageView.setScaleAndCenter(preserveScale, preserveCenter)
                    }
                } catch (e: Exception) {
                    Log.e("MapActivity", "Error restoring zoom state: ${e.message}")
                }
            }, 100)

        } catch (e: Exception) {
            Log.e("MapActivity", "Error adding marker: ${e.message}", e)
            Toast.makeText(this, "Error adding marker: ${e.message}", Toast.LENGTH_SHORT).show()
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
        }
    }

    private fun stopContinuousScanning() {
        isScanning = false
        handler.removeCallbacks(scanRunnable)
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
        } catch (e: SecurityException) {
            Toast.makeText(this, "Security exception during scan: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun scanFailure() {
        Toast.makeText(this, "WiFi scan failed. Will try again.", Toast.LENGTH_SHORT).show()
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

    private fun setupFab() {
        fabSensors.setOnClickListener {
            finish()
        }
    }

    private fun loadFloorPlan(floorName: String) {
        try {
            Log.d("MapActivity", "Loading floor plan: $floorName")
            currentFloor = floorName
            initializeBitmaps()

            if (::workingBitmap.isInitialized && !workingBitmap.isRecycled) {
                mapImageView.setImage(ImageSource.bitmap(workingBitmap))

                // Configure the scale settings
                mapImageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
                mapImageView.setMaxScale(10.0f)
                mapImageView.setMinScale(0.1f)
                mapImageView.setDoubleTapZoomScale(2.0f)
                mapImageView.setDoubleTapZoomDpi(160)

                // Enable gestures
                mapImageView.setPanEnabled(true)
                mapImageView.setZoomEnabled(true)
                mapImageView.setQuickScaleEnabled(true)
            } else {
                Log.e("MapActivity", "Working bitmap not available for floor plan")
                Toast.makeText(this, "Error loading floor plan bitmap", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Error loading floor plan: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("MapActivity", "Error loading floor plan", e)
        }
    }

    private fun processScanResults() {
        try {
            val positionk = mutableMapOf<String, Any>()
            val readingsk = mutableMapOf<String, MutableMap<String, Int>>()

            // Store both normalized and pixel coordinates for backward compatibility stuffs :\
            positionk["normalized_x"] = normalizedX
            positionk["normalized_y"] = normalizedY
            positionk["pixel_x"] = (normalizedX * originalImageWidth).toInt()
            positionk["pixel_y"] = (normalizedY * originalImageHeight).toInt()
            positionk["image_width"] = originalImageWidth
            positionk["image_height"] = originalImageHeight


            for (result in latestScanResults) {
                if (result.SSID.isNotEmpty()) {
                    if (result.SSID !in readingsk) {
                        readingsk[result.SSID] = mutableMapOf()
                    }
                    readingsk[result.SSID]!![result.BSSID] = result.level

                }
            }


            outermostmap2["position"] = positionk
            outermostmap2["readings"] = readingsk
            outermostmap2["floor"] = currentFloor
            outermostmap2["timestamp"] = System.currentTimeMillis()

            printOutermostMap()
            scanIndex++
        } catch (e: Exception) {
            Log.e("MapActivity", "Error processing scan results: ${e.message}", e)
        }
    }

    private fun saveRoomDataJson() {
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


            val position = outermostmap2["position"] as? Map<String, Any>
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


            val readings = outermostmap2["readings"] as? Map<String, Map<String, Int>>
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
            scanJson.put("floor", outermostmap2["floor"])
            scanJson.put("scanned_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))

            allScansArray.put(scanJson)

            existingJson.put("all_scans", allScansArray)
            existingJson.put("total_scan_sessions", allScansArray.length())
            existingJson.put("last_updated", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))

            FileWriter(file, false).use { writer ->
                writer.write(existingJson.toString(2))
            }

            Toast.makeText(this, "Data saved to Downloads/$fileName (${allScansArray.length()} scan sessions)", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error saving data: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("MapActivity", "Error saving data: ${e.message}", e)
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopContinuousScanning()


        try {
            if (::originalBitmap.isInitialized && !originalBitmap.isRecycled) {
                originalBitmap.recycle()
            }
            if (::workingBitmap.isInitialized && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e("MapActivity", "Error cleaning up bitmaps", e)
        }

        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (e: IllegalArgumentException) {

        }
    }

    private fun printOutermostMap() {
        Log.d("MapActivity", "=== OUTERMOST MAP CONTENTS ===")
        Log.d("MapActivity", "Total entries: ${outermostmap.size}")

        for ((index, scanData) in outermostmap) {
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
        Log.d("MapActivity", "=== END MAP CONTENTS ===")
    }
}