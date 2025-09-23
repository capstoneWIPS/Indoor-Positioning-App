package com.example.indoor_positioning_app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

class LandingActivity : AppCompatActivity() {

    private lateinit var logoImage: ImageView
    private lateinit var mainTitle: TextView
    private lateinit var subtitle: TextView
    private lateinit var featuresContainer: LinearLayout
    private lateinit var swipeIndicator: LinearLayout
    private lateinit var swipeArrow: ImageView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var landingContainer: RelativeLayout
    private lateinit var particlesContainer: FrameLayout
    private lateinit var gestureDetector: GestureDetector

    private var isTransitioning = false
    private val handler = Handler(Looper.getMainLooper())

    // Touch tracking for manual gesture detection
    private var startY = 0f
    private var startX = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make it fullscreen for dramatic effect lel
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        setContentView(R.layout.activity_landing)

        initializeViews()
        setupGestureDetection()
        startIntroAnimations()
        startBackgroundAnimations()
    }

    private fun initializeViews() {
        logoImage = findViewById(R.id.logoImage)
        mainTitle = findViewById(R.id.mainTitle)
        subtitle = findViewById(R.id.subtitle)
        featuresContainer = findViewById(R.id.featuresContainer)
        swipeIndicator = findViewById(R.id.swipeIndicator)
        swipeArrow = findViewById(R.id.swipeArrow)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        landingContainer = findViewById(R.id.landingContainer)
        particlesContainer = findViewById(R.id.particlesContainer)
    }

    private fun setupGestureDetection() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                Log.d("LandingActivity", "GestureDetector onFling called")
                if (!isTransitioning && e1 != null) {
                    val deltaY = e1.y - e2.y
                    val deltaX = abs(e1.x - e2.x)

                    Log.d("LandingActivity", "Fling - deltaY: $deltaY, deltaX: $deltaX, velocityY: $velocityY")

                    // More lenient swipe detection
                    if (deltaY > 100 && deltaX < 200 && abs(velocityY) > 300) {
                        Log.d("LandingActivity", "Swipe up detected via GestureDetector")
                        triggerTransition()
                        return true
                    }
                }
                return false
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                Log.d("LandingActivity", "Single tap detected")
                if (!isTransitioning) {
                    triggerTransition()
                    return true
                }
                return false
            }
        })

        landingContainer.setOnTouchListener { view, event ->
            Log.d("LandingActivity", "Touch event: ${event.action}")

            val gestureHandled = gestureDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    startX = event.x
                    Log.d("LandingActivity", "Touch down at: ($startX, $startY)")
                }
                MotionEvent.ACTION_UP -> {
                    if (!isTransitioning) {
                        val endY = event.y
                        val endX = event.x
                        val deltaY = startY - endY
                        val deltaX = abs(startX - endX)

                        Log.d("LandingActivity", "Touch up - deltaY: $deltaY, deltaX: $deltaX")

                        if (deltaY > 100 && deltaX < 150) {
                            Log.d("LandingActivity", "Swipe up detected via manual detection")
                            triggerTransition()
                            return@setOnTouchListener true
                        }
                    }
                }
            }

            gestureHandled || true // Always consume the event
        }

        // Add click listener as additional fallback
        landingContainer.setOnClickListener {
            Log.d("LandingActivity", "Container clicked")
            if (!isTransitioning) {
                triggerTransition()
            }
        }
    }

    private fun startIntroAnimations() {
        // Delay to let the activity settle
        handler.postDelayed({
            animateLogo()
        }, 300)

        handler.postDelayed({
            animateTitle()
        }, 800)

        handler.postDelayed({
            animateSubtitle()
        }, 1200)

        handler.postDelayed({
            animateFeatures()
        }, 1600)

        handler.postDelayed({
            animateSwipeIndicator()
        }, 2200)
    }

    private fun animateLogo() {
        val fadeIn = ObjectAnimator.ofFloat(logoImage, "alpha", 0f, 1f)
        val scaleX = ObjectAnimator.ofFloat(logoImage, "scaleX", 0.8f, 1.2f, 1f)
        val scaleY = ObjectAnimator.ofFloat(logoImage, "scaleY", 0.8f, 1.2f, 1f)

        val logoAnimSet = AnimatorSet().apply {
            playTogether(fadeIn, scaleX, scaleY)
            duration = 1000
            interpolator = DecelerateInterpolator()
        }
        logoAnimSet.start()

        // Start pulsing animation
        startLogoPulse()
    }

    private fun startLogoPulse() {
        val pulseAnimator = ObjectAnimator.ofFloat(logoImage, "alpha", 1f, 0.7f, 1f)
        pulseAnimator.apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun animateTitle() {
        val fadeIn = ObjectAnimator.ofFloat(mainTitle, "alpha", 0f, 1f)
        val translateY = ObjectAnimator.ofFloat(mainTitle, "translationY", 50f, 0f)

        val titleAnimSet = AnimatorSet().apply {
            playTogether(fadeIn, translateY)
            duration = 800
            interpolator = DecelerateInterpolator()
        }
        titleAnimSet.start()
    }

    private fun animateSubtitle() {
        val fadeIn = ObjectAnimator.ofFloat(subtitle, "alpha", 0f, 1f)
        val translateY = ObjectAnimator.ofFloat(subtitle, "translationY", 50f, 0f)

        val subtitleAnimSet = AnimatorSet().apply {
            playTogether(fadeIn, translateY)
            duration = 800
            interpolator = DecelerateInterpolator()
        }
        subtitleAnimSet.start()
    }

    private fun animateFeatures() {
        val fadeIn = ObjectAnimator.ofFloat(featuresContainer, "alpha", 0f, 1f)
        val translateY = ObjectAnimator.ofFloat(featuresContainer, "translationY", 50f, 0f)

        val featuresAnimSet = AnimatorSet().apply {
            playTogether(fadeIn, translateY)
            duration = 800
            interpolator = DecelerateInterpolator()
        }
        featuresAnimSet.start()
    }

    private fun animateSwipeIndicator() {
        val fadeIn = ObjectAnimator.ofFloat(swipeIndicator, "alpha", 0f, 1f)
        fadeIn.apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            start()
        }

        // Start bouncing arrow animation
        startArrowBounce()
    }

    private fun startArrowBounce() {
        val bounceAnimator = ObjectAnimator.ofFloat(swipeArrow, "translationY", 0f, -15f, 0f)
        bounceAnimator.apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun startBackgroundAnimations() {
        // Add subtle background movement/particles effect
        handler.postDelayed({
            val backgroundAnimator = ObjectAnimator.ofFloat(particlesContainer, "alpha", 0.3f, 0.6f, 0.3f)
            backgroundAnimator.apply {
                duration = 4000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }, 2000)
    }

    private fun triggerTransition() {
        isTransitioning = true
        Log.d("LandingActivity", "Starting transition animation")

        // Show loading overlay
        loadingOverlay.visibility = View.VISIBLE
        loadingOverlay.alpha = 0f
        val loadingFadeIn = ObjectAnimator.ofFloat(loadingOverlay, "alpha", 0f, 1f)
        loadingFadeIn.duration = 300
        loadingFadeIn.start()

        val slideOut = ObjectAnimator.ofFloat(landingContainer, "translationY", 0f, -200f)
        val fadeOut = ObjectAnimator.ofFloat(landingContainer, "alpha", 1f, 0f)

        val exitAnimSet = AnimatorSet().apply {
            playTogether(slideOut, fadeOut)
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
        }
        exitAnimSet.start()

        handler.postDelayed({
            navigateToMapActivity()
        }, 1500) // Give time for loading animation
    }

    private fun navigateToMapActivity() {
        Log.d("LandingActivity", "Navigating to MapActivity")
        val intent = Intent(this, MapActivity::class.java)
        startActivity(intent)

        // Custom transition animation
        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
        finish()
    }

    override fun onBackPressed() {
        // Prevent back button during transition
        if (!isTransitioning) {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}