package com.immichframe.immichframe

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.dreams.DreamService
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ScreenSaverService : DreamService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var webView: WebView
    private lateinit var imageView1: ImageView
    private lateinit var imageView2: ImageView
    private lateinit var txtPhotoInfo: TextView
    private lateinit var txtDateTime: TextView
    private lateinit var serverSettings: Helpers.ServerSettings
    private var retrofit: Retrofit? = null
    private lateinit var apiService: Helpers.ApiService
    private var isWeatherTimerRunning = false
    private var useWebView = true
    private var blurredBackground = true
    private var showCurrentDate = true
    private var currentWeather = ""
    private var isImageTimerRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private var previousImage: Helpers.ImageResponse? = null
    private var currentImage: Helpers.ImageResponse? = null
    private var portraitCache: Helpers.ImageResponse? = null
    private val imageRunnable = object : Runnable {
        override fun run() {
            if (isImageTimerRunning) {
                handler.postDelayed(this, (serverSettings.interval * 1000).toLong())
                getNextImage()
            }
        }
    }
    private var isShowingFirst = true
    private var zoomAnimator: ObjectAnimator? = null

    override fun onDreamingStarted() {
        super.onAttachedToWindow()
        isFullscreen = true
        isInteractive = false
        setContentView(R.layout.screen_saver_view)
        webView = findViewById(R.id.webView)
        webView.setBackgroundColor(Color.BLACK)
        imageView1 = findViewById(R.id.imageView1)
        imageView2 = findViewById(R.id.imageView2)
        txtPhotoInfo = findViewById(R.id.txtPhotoInfo)
        txtDateTime = findViewById(R.id.txtDateTime)

        acquireWakeLock()
        loadSettings()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        stopImageTimer()
        releaseWakeLock()
        handler.removeCallbacksAndMessages(null)
    }

    private fun showImage(imageResponse: Helpers.ImageResponse) {
        CoroutineScope(Dispatchers.IO).launch {
            //get the window size
            val decorView = window.decorView
            val width = decorView.width
            val height = decorView.height
            val maxSize = maxOf(width, height)

            var randomBitmap = Helpers.decodeBitmapFromBytes(imageResponse.randomImageBase64)
            val thumbHashBitmap = Helpers.decodeBitmapFromBytes(imageResponse.thumbHashImageBase64)
            var isMerged = false

            val isPortrait = randomBitmap.height > randomBitmap.width
            if (isPortrait && serverSettings.layout == "splitview") {
                if (portraitCache != null) {
                    var decodedPortraitImageBitmap =
                        Helpers.decodeBitmapFromBytes(portraitCache!!.randomImageBase64)
                    decodedPortraitImageBitmap =
                        Helpers.reduceBitmapQuality(decodedPortraitImageBitmap, maxSize)
                    randomBitmap = Helpers.reduceBitmapQuality(randomBitmap, maxSize)

                    val colorString =
                        serverSettings.primaryColor?.takeIf { it.isNotBlank() } ?: "#FFFFFF"
                    val parsedColor = Color.parseColor(colorString)

                    randomBitmap =
                        Helpers.mergeImages(decodedPortraitImageBitmap, randomBitmap, parsedColor)
                    isMerged = true

                    decodedPortraitImageBitmap.recycle()
                } else {
                    portraitCache = imageResponse
                    getNextImage()
                    return@launch
                }
            } else {
                randomBitmap = Helpers.reduceBitmapQuality(randomBitmap, maxSize * 2)
            }

            withContext(Dispatchers.Main) {
                updateUI(randomBitmap, thumbHashBitmap, isMerged, imageResponse)
            }
        }
    }

    private fun updateUI(
        finalImage: Bitmap,
        thumbHashBitmap: Bitmap,
        isMerged: Boolean,
        imageResponse: Helpers.ImageResponse
    ) {
        val imageViewOld = if (isShowingFirst) imageView1 else imageView2
        val imageViewNew = if (isShowingFirst) imageView2 else imageView1

        zoomAnimator?.cancel()
        imageViewNew.alpha = 0f
        imageViewNew.scaleX = 1f
        imageViewNew.scaleY = 1f
        imageViewNew.setImageBitmap(finalImage)
        imageViewNew.visibility = View.VISIBLE

        if (blurredBackground) {
            imageViewNew.background = BitmapDrawable(resources, thumbHashBitmap)
        } else {
            imageViewNew.background = null
        }

        imageViewNew.animate()
            .alpha(1f)
            .setDuration((serverSettings.transitionDuration * 1000).toLong())
            .withEndAction {
                if (serverSettings.imageZoom) {
                    startZoomAnimation(imageViewNew)
                }
            }
            .start()

        imageViewOld.animate()
            .alpha(0f)
            .setDuration((serverSettings.transitionDuration * 1000).toLong())
            .withEndAction {
                imageViewOld.visibility = View.GONE
            }
            .start()

        // Toggle active ImageView
        isShowingFirst = !isShowingFirst

        if (isMerged) {
            val mergedPhotoDate =
                if (portraitCache!!.photoDate.isNotEmpty() || imageResponse.photoDate.isNotEmpty()) {
                    "${portraitCache!!.photoDate} | ${imageResponse.photoDate}"
                } else {
                    ""
                }

            val mergedImageLocation =
                if (portraitCache!!.imageLocation.isNotEmpty() || imageResponse.imageLocation.isNotEmpty()) {
                    "${portraitCache!!.imageLocation} | ${imageResponse.imageLocation}"
                } else {
                    ""
                }

            updatePhotoInfo(mergedPhotoDate, mergedImageLocation)
            portraitCache = null
        } else {
            updatePhotoInfo(imageResponse.photoDate, imageResponse.imageLocation)
        }

        updateDateTimeWeather()
    }

    private fun updatePhotoInfo(photoDate: String, photoLocation: String) {
        if (serverSettings.showPhotoDate || serverSettings.showImageLocation) {
            val photoInfo = buildString {
                if (serverSettings.showPhotoDate && photoDate.isNotEmpty()) {
                    append(photoDate)
                }
                if (serverSettings.showImageLocation && photoLocation.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(photoLocation)
                }
            }
            txtPhotoInfo.text = photoInfo
        }
    }

    private fun updateDateTimeWeather() {
        if (serverSettings.showClock) {
            val currentDateTime = Calendar.getInstance().time

            val formattedDate = try {
                SimpleDateFormat(serverSettings.photoDateFormat, Locale.getDefault()).format(
                    currentDateTime
                )
            } catch (e: Exception) {
                ""
            }

            val formattedTime = try {
                SimpleDateFormat(serverSettings.clockFormat, Locale.getDefault()).format(
                    currentDateTime
                )
            } catch (e: Exception) {
                ""
            }

            val dt = if (showCurrentDate && formattedDate.isNotEmpty()) {
                "$formattedDate\n$formattedTime"
            } else {
                formattedTime
            }

            txtDateTime.text = SpannableString(dt).apply {
                val start =
                    if (showCurrentDate && formattedDate.isNotEmpty()) formattedDate.length + 1 else 0
                setSpan(RelativeSizeSpan(2f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        if (serverSettings.showWeatherDescription) {
            txtDateTime.append(currentWeather)
        }
    }

    private fun getNextImage() {
        apiService.getImageData().enqueue(object : Callback<Helpers.ImageResponse> {
            override fun onResponse(
                call: Call<Helpers.ImageResponse>,
                response: Response<Helpers.ImageResponse>
            ) {
                if (response.isSuccessful) {
                    val imageResponse = response.body()
                    if (imageResponse != null) {
                        previousImage = currentImage
                        currentImage = imageResponse
                        showImage(imageResponse)
                    }
                } else {
                    Toast.makeText(
                        applicationContext,
                        "Failed to load image (HTTP ${response.code()})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(call: Call<Helpers.ImageResponse>, t: Throwable) {
                t.printStackTrace()
                Toast.makeText(
                    applicationContext,
                    "Failed to load image: ${t.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    private fun startImageTimer() {
        if (!isImageTimerRunning) {
            isImageTimerRunning = true
            handler.postDelayed(imageRunnable, (serverSettings.interval * 1000).toLong())
        }
    }

    private fun stopImageTimer() {
        isImageTimerRunning = false
        handler.removeCallbacks(imageRunnable)
    }

    private fun startZoomAnimation(imageView: ImageView) {
        zoomAnimator?.cancel()
        zoomAnimator = ObjectAnimator.ofPropertyValuesHolder(
            imageView,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.2f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.2f)
        )
        zoomAnimator?.duration = (serverSettings.interval * 1000).toLong()
        zoomAnimator?.start()
    }

    private fun getWeather() {
        apiService.getWeather().enqueue(object : Callback<Helpers.Weather> {
            override fun onResponse(
                call: Call<Helpers.Weather>,
                response: Response<Helpers.Weather>
            ) {
                if (response.isSuccessful) {
                    val weatherResponse = response.body()
                    if (weatherResponse != null) {
                        currentWeather =
                            "\n ${weatherResponse.location}, ${weatherResponse.temperatureUnit} \n ${weatherResponse.description}"
                    }
                }
            }

            override fun onFailure(call: Call<Helpers.Weather>, t: Throwable) {
                Log.e("Weather", "Failed to fetch weather: ${t.message}")
            }
        })
    }

    private fun getServerSettings(
        onSuccess: (Helpers.ServerSettings) -> Unit,
        onFailure: (Throwable) -> Unit,
        maxRetries: Int = 18,
        retryDelayMillis: Long = 5000
    ) {
        var retryCount = 0

        fun attemptFetch() {
            apiService.getServerSettings().enqueue(object : Callback<Helpers.ServerSettings> {
                override fun onResponse(
                    call: Call<Helpers.ServerSettings>,
                    response: Response<Helpers.ServerSettings>
                ) {
                    if (response.isSuccessful) {
                        val serverSettingsResponse = response.body()
                        if (serverSettingsResponse != null) {
                            onSuccess(serverSettingsResponse)
                        } else {
                            handleFailure(Exception("Empty response body"))
                        }
                    } else {
                        handleFailure(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }

                override fun onFailure(call: Call<Helpers.ServerSettings>, t: Throwable) {
                    handleFailure(t)
                }

                private fun handleFailure(t: Throwable) {
                    if (retryCount < maxRetries) {
                        retryCount++
                        Toast.makeText(
                            this@ScreenSaverService,
                            "Retrying to fetch server settings... Attempt $retryCount of $maxRetries",
                            Toast.LENGTH_SHORT
                        ).show()
                        Handler(Looper.getMainLooper()).postDelayed({
                            attemptFetch()
                        }, retryDelayMillis)
                    } else {
                        onFailure(t)
                    }
                }
            })
        }

        attemptFetch()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadSettings() {
        val sharedPreferences = getSharedPreferences("ImmichFramePrefs", MODE_PRIVATE)
        blurredBackground = sharedPreferences.getBoolean("blurredBackground", true)
        showCurrentDate = sharedPreferences.getBoolean("showCurrentDate", true)
        var savedUrl = sharedPreferences.getString("webview_url", "") ?: ""
        useWebView = sharedPreferences.getBoolean("useWebView", true)
        val authSecret = sharedPreferences.getString("authSecret", "") ?: ""

        webView.visibility = if (useWebView) View.VISIBLE else View.GONE
        imageView1.visibility = if (useWebView) View.GONE else View.VISIBLE
        imageView2.visibility = if (useWebView) View.GONE else View.VISIBLE
        txtPhotoInfo.visibility = View.GONE //enabled in onSettingsLoaded based on server settings
        txtDateTime.visibility = View.GONE //enabled in onSettingsLoaded based on server settings

        if (useWebView) {
            savedUrl = if (authSecret.isNotEmpty()) {
                Uri.parse(savedUrl)
                    .buildUpon()
                    .appendQueryParameter("authsecret", authSecret)
                    .build()
                    .toString()
            } else {
                savedUrl
            }

            handler.removeCallbacksAndMessages(null)
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url
                    if (url != null) {
                        // Open the URL in the default browser
                        val intent = Intent(Intent.ACTION_VIEW, url)
                        startActivity(intent)
                        return true
                    }
                    return false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    view?.reload()
                }
            }
            webView.settings.javaScriptEnabled = true
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.settings.domStorageEnabled = true
            webView.loadUrl(savedUrl)
        } else {
            retrofit = Helpers.createRetrofit(savedUrl, authSecret)
            apiService = retrofit!!.create(Helpers.ApiService::class.java)
            getServerSettings(
                onSuccess = { settings ->
                    serverSettings = settings
                    onSettingsLoaded()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this,
                        "Failed to load server settings: ${error.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    private fun onSettingsLoaded() {
        if (serverSettings.showPhotoDate || serverSettings.showImageLocation) {
            txtPhotoInfo.visibility = View.VISIBLE
            txtPhotoInfo.textSize =
                Helpers.cssFontSizeToSp(serverSettings.baseFontSize, this)
            if (serverSettings.primaryColor != null) {
                txtPhotoInfo.setTextColor(
                    runCatching { Color.parseColor(serverSettings.primaryColor) }.getOrDefault(Color.WHITE)
                )
            } else {
                txtPhotoInfo.setTextColor(Color.WHITE)
            }
        } else {
            txtPhotoInfo.visibility = View.GONE
        }
        if (serverSettings.showClock) {
            txtDateTime.visibility = View.VISIBLE
            txtDateTime.textSize = Helpers.cssFontSizeToSp(serverSettings.baseFontSize, this)
            if (serverSettings.primaryColor != null) {
                txtDateTime.setTextColor(
                    runCatching { Color.parseColor(serverSettings.primaryColor) }.getOrDefault(Color.WHITE)
                )
            } else {
                txtDateTime.setTextColor(Color.WHITE)
            }
        } else {
            txtDateTime.visibility = View.GONE
        }

        getNextImage()
        startImageTimer()

        if (serverSettings.showWeatherDescription) {
            getWeather()
            if (!isWeatherTimerRunning) {
                isWeatherTimerRunning = true
                handler.postDelayed(object : Runnable {
                    override fun run() {
                        getWeather()
                        handler.postDelayed(this, 600000)
                    }
                }, (300000))
            }

        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ImmichFrame::ScreenSaverWakeLock"
            )
            wakeLock?.acquire(120 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}