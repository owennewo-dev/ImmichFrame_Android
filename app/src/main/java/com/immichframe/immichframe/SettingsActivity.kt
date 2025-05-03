package com.immichframe.immichframe

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private lateinit var editTextUrl: EditText
    private lateinit var buttonSaveUrl: Button
    private lateinit var chkUseWebView: androidx.appcompat.widget.SwitchCompat
    private lateinit var chkKeepScreenOn: androidx.appcompat.widget.SwitchCompat
    private lateinit var chkBlurredBackground: androidx.appcompat.widget.SwitchCompat
    private lateinit var chkShowCurrentDate: androidx.appcompat.widget.SwitchCompat
    private lateinit var chkScreenDim: androidx.appcompat.widget.SwitchCompat
    private lateinit var buttonAndroidSettings: Button
    private lateinit var editTextAuthSecret: EditText
    private lateinit var editTextDimTimeRange: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_view)

        editTextUrl = findViewById(R.id.editTextUrl)
        buttonSaveUrl = findViewById(R.id.buttonSaveUrl)
        chkUseWebView = findViewById(R.id.chkUseWebView)
        chkKeepScreenOn = findViewById(R.id.chkKeepScreenOn)
        chkBlurredBackground = findViewById(R.id.chkBlurredBackground)
        chkShowCurrentDate = findViewById(R.id.chkShowCurrentDate)
        chkScreenDim = findViewById(R.id.chkDimScreen)
        buttonAndroidSettings = findViewById(R.id.buttonAndroidSettings)
        editTextAuthSecret = findViewById(R.id.editTextAuthSecret)
        editTextDimTimeRange = findViewById(R.id.editTextDimTimeRange)

        loadSettings()

        buttonSaveUrl.setOnClickListener {
            val url = editTextUrl.text.toString()
            val authSecret = editTextAuthSecret.text.toString()
            if (url.isNotEmpty()) {
                saveSettings(url, authSecret)
                Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
        additionalSettingsVisibility(chkUseWebView.isChecked)
        chkUseWebView.setOnCheckedChangeListener { _, isChecked ->
            additionalSettingsVisibility(isChecked)
        }
        screenDimRangeVisibility(chkScreenDim.isChecked)
        chkScreenDim.setOnCheckedChangeListener { _, isChecked ->
            screenDimRangeVisibility(isChecked)
        }
        buttonAndroidSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun additionalSettingsVisibility(isChecked: Boolean) {
        if (isChecked) {
            chkBlurredBackground.visibility = View.GONE
            chkShowCurrentDate.visibility = View.GONE
            buttonAndroidSettings.visibility = View.GONE
        } else {
            chkBlurredBackground.visibility = View.VISIBLE
            chkShowCurrentDate.visibility = View.VISIBLE
            buttonAndroidSettings.visibility = View.VISIBLE
        }
    }

    private fun screenDimRangeVisibility(isChecked: Boolean) {
        if (isChecked) {
            editTextDimTimeRange.visibility = View.VISIBLE
        } else {
            editTextDimTimeRange.visibility = View.GONE
        }
    }

    private fun loadSettings() {
        val sharedPreferences = getSharedPreferences("ImmichFramePrefs", MODE_PRIVATE)
        editTextUrl.setText(sharedPreferences.getString("webview_url", "" ))
        editTextAuthSecret.setText(sharedPreferences.getString("authSecret", "") ?: "")
        chkUseWebView.isChecked = sharedPreferences.getBoolean("useWebView", true)
        chkKeepScreenOn.isChecked = sharedPreferences.getBoolean("keepScreenOn", true)
        chkBlurredBackground.isChecked = sharedPreferences.getBoolean("blurredBackground", true)
        chkShowCurrentDate.isChecked = sharedPreferences.getBoolean("showCurrentDate", true)
        chkScreenDim.isChecked = sharedPreferences.getBoolean("screenDim", false)
        editTextDimTimeRange.setText(
            String.format(
                Locale.US,
                "%02d:%02d-%02d:%02d",
                sharedPreferences.getInt("dimStartHour", 22),
                sharedPreferences.getInt("dimStartMinute", 0),
                sharedPreferences.getInt("dimEndHour", 7),
                sharedPreferences.getInt("dimEndMinute", 0)
            )
        )
        editTextUrl.requestFocus()
    }

    private fun saveSettings(url: String, authSecret: String) {
        val sharedPreferences = getSharedPreferences("ImmichFramePrefs", MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putString("webview_url", url)
            putBoolean("useWebView", chkUseWebView.isChecked)
            putBoolean("keepScreenOn", chkKeepScreenOn.isChecked)
            putBoolean("blurredBackground", chkBlurredBackground.isChecked)
            putBoolean("showCurrentDate", chkShowCurrentDate.isChecked)
            putBoolean("screenDim", chkScreenDim.isChecked)
            putString("authSecret", authSecret)
            apply()
        }
        if (chkScreenDim.isChecked) {
            saveDimTimeRange()
        }
        // Notify widget to update
        val intent = Intent(this, WidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        }
        sendBroadcast(intent)
    }

    private fun saveDimTimeRange() {
        val timeRange = editTextDimTimeRange.text.toString().trim()

        val regex = "^([01]?[0-9]|2[0-3]):([0-5][0-9])-([01]?[0-9]|2[0-3]):([0-5][0-9])$".toRegex()
        if (timeRange.matches(regex)) {
            val times = timeRange.split("-")
            val startTime = times[0]
            val endTime = times[1]

            val startTimeParts = startTime.split(":")
            val endTimeParts = endTime.split(":")

            val startHour = startTimeParts[0].toInt()
            val startMinute = startTimeParts[1].toInt()
            val endHour = endTimeParts[0].toInt()
            val endMinute = endTimeParts[1].toInt()

            getSharedPreferences("ImmichFramePrefs", MODE_PRIVATE).edit()
                .putInt("dimStartHour", startHour)
                .putInt("dimStartMinute", startMinute)
                .putInt("dimEndHour", endHour)
                .putInt("dimEndMinute", endMinute)
                .apply()
        } else {
            getSharedPreferences("ImmichFramePrefs", MODE_PRIVATE).edit()
                .putBoolean("screenDim", false)
                .apply()
            Toast.makeText(this, "Invalid time format. Please use HH:mm-HH:mm.", Toast.LENGTH_LONG)
                .show()
        }
    }
}