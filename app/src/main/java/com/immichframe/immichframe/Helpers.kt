package com.immichframe.immichframe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64
import retrofit2.Call
import retrofit2.http.GET
import androidx.core.graphics.scale
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory


object Helpers {
    fun textSizeMultiplier(context: Context, currentSizeSp: Float, multiplier: Float): Float {
        val resources = context.resources
        val fontScale = resources.configuration.fontScale
        val density = resources.displayMetrics.density
        val currentSizePx = currentSizeSp * density * fontScale
        val newSizePx = currentSizePx * multiplier

        return newSizePx / (density * fontScale)
    }

    fun cssFontSizeToSp(cssSize: String?, context: Context, baseFontSizePx: Float = 16f): Float {
        val resources = context.resources
        val displayMetrics = resources.displayMetrics
        val fontScale = resources.configuration.fontScale
        val density = displayMetrics.density

        // Handle null cssSize
        val effectiveCssSize = cssSize ?: "medium"

        return when {
            effectiveCssSize.equals("xx-small", ignoreCase = true) -> 8f * fontScale
            effectiveCssSize.equals("x-small", ignoreCase = true) -> 10f * fontScale
            effectiveCssSize.equals("small", ignoreCase = true) -> 12f * fontScale
            effectiveCssSize.equals("medium", ignoreCase = true) -> 16f * fontScale
            effectiveCssSize.equals("large", ignoreCase = true) -> 20f * fontScale
            effectiveCssSize.equals("x-large", ignoreCase = true) -> 24f * fontScale
            effectiveCssSize.equals("xx-large", ignoreCase = true) -> 32f * fontScale

            effectiveCssSize.endsWith("px", ignoreCase = true) -> {
                val px = effectiveCssSize.removeSuffix("px").toFloatOrNull() ?: baseFontSizePx
                px / (density * fontScale)
            }

            effectiveCssSize.endsWith("pt", ignoreCase = true) -> {
                val pt = effectiveCssSize.removeSuffix("pt").toFloatOrNull() ?: baseFontSizePx
                val px = pt * (density * 160f / 72f)
                px / (density * fontScale)
            }

            effectiveCssSize.endsWith("em", ignoreCase = true) -> {
                val em = effectiveCssSize.removeSuffix("em").toFloatOrNull() ?: 1f
                val px = em * baseFontSizePx
                px / (density * fontScale)
            }

            else -> 16f * fontScale
        }
    }

    fun mergeImages(leftImage: Bitmap, rightImage: Bitmap, lineColor: Int): Bitmap {
        val lineWidth = 10
        val targetHeight = maxOf(leftImage.height, rightImage.height) // Use max height

        val totalWidth = leftImage.width + rightImage.width + lineWidth

        val result = Bitmap.createBitmap(totalWidth, targetHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawBitmap(leftImage, 0f, 0f, paint)

        // Draw dividing line
        paint.color = lineColor
        canvas.drawRect(
            leftImage.width.toFloat(), // Line starts after left image
            0f,
            (leftImage.width + lineWidth).toFloat(),
            targetHeight.toFloat(),
            paint
        )

        canvas.drawBitmap(rightImage, (leftImage.width + lineWidth).toFloat(), 0f, paint)

        return result
    }

    fun decodeBitmapFromBytes(data: String): Bitmap {
        val decodedImage = Base64.decode(data, Base64.DEFAULT)

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return BitmapFactory.decodeByteArray(decodedImage, 0, decodedImage.size, options)
    }

    fun reduceBitmapQuality(bitmap: Bitmap, maxSize: Int = 1000): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Calculate new dimensions while maintaining aspect ratio
        val scaleFactor = maxSize.toFloat() / width.coerceAtLeast(height)
        val newWidth = (width * scaleFactor).toInt()
        val newHeight = (height * scaleFactor).toInt()

        val resizedBitmap = bitmap.scale(newWidth, newHeight)

        return resizedBitmap
    }

    data class ImageResponse(
        val randomImageBase64: String,
        val thumbHashImageBase64: String,
        val photoDate: String,
        val imageLocation: String
    )

    data class ServerSettings(
        val margin: String,
        val interval: Int,
        val transitionDuration: Double,
        val downloadImages: Boolean,
        val renewImagesDuration: Int,
        val showClock: Boolean,
        val clockFormat: String,
        val showPhotoDate: Boolean,
        val photoDateFormat: String,
        val showImageDesc: Boolean,
        val showPeopleDesc: Boolean,
        val showImageLocation: Boolean,
        val imageLocationFormat: String,
        val primaryColor: String?,
        val secondaryColor: String,
        val style: String,
        val baseFontSize: String?,
        val showWeatherDescription: Boolean,
        val unattendedMode: Boolean,
        val imageZoom: Boolean,
        val imageFill: Boolean,
        val layout: String,
        val language: String
    )

    data class Weather(
        val location: String,
        val temperature: Double,
        val unit: String,
        val temperatureUnit: String,
        val description: String,
        val iconId: String
    )

    interface ApiService {
        @GET("api/Asset/RandomImageAndInfo")
        fun getImageData(): Call<ImageResponse>

        @GET("api/Config")
        fun getServerSettings(): Call<ServerSettings>

        @GET("api/Weather")
        fun getWeather(): Call<Weather>
    }
    fun createRetrofit(baseUrl: String, authSecret: String): Retrofit {
        val normalizedBaseUrl = if (!baseUrl.endsWith("/")) "$baseUrl/" else baseUrl

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val originalRequest = chain.request()

                val request = if (authSecret.isNotEmpty()) {
                    originalRequest.newBuilder()
                        .addHeader("Authorization", "Bearer $authSecret")
                        .build()
                } else {
                    originalRequest
                }

                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

}