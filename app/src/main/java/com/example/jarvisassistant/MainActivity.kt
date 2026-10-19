package com.example.jarvisassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private var isActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 11, 20)
        window.navigationBarColor = Color.rgb(7, 11, 20)
        buildInterface()
        isActive = getSharedPreferences(JarvisService.PREFS, MODE_PRIVATE)
            .getBoolean(JarvisService.KEY_RUNNING, false)
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        isActive = getSharedPreferences(JarvisService.PREFS, MODE_PRIVATE)
            .getBoolean(JarvisService.KEY_RUNNING, false)
        refreshState()
    }

    private fun buildInterface() {
        val background = Color.rgb(7, 11, 20)
        val cyan = Color.rgb(69, 215, 255)
        val white = Color.rgb(225, 241, 250)
        val muted = Color.rgb(139, 165, 184)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(30), dp(24), dp(24))
            setBackgroundColor(background)
        }
        val scroll = ScrollView(this).apply { addView(page) }

        val eyebrow = TextView(this).apply {
            text = "PERSONAL VOICE INTERFACE"
            textSize = 11f
            letterSpacing = 0.22f
            setTextColor(cyan)
            gravity = Gravity.CENTER
        }
        page.addView(eyebrow, fullWidth())

        val title = TextView(this).apply {
            text = "J.A.R.V.I.S."
            textSize = 34f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = 0.12f
            setTextColor(white)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(3))
        }
        page.addView(title, fullWidth())

        val subtitle = TextView(this).apply {
            text = "ГОЛОСОВОЙ ПОМОЩНИК · ПРОТОТИП 0.1"
            textSize = 10f
            letterSpacing = 0.07f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        page.addView(subtitle, fullWidth())

        val reactor = TextView(this).apply {
            text = "◎"
            textSize = 94f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(cyan)
            gravity = Gravity.CENTER
            setPadding(0, dp(30), 0, dp(24))
        }
        page.addView(reactor, fullWidth())

        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(white)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        }
        page.addView(statusText, fullWidth())

        actionButton = Button(this).apply {
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.rgb(4, 15, 23))
            setBackgroundColor(cyan)
            setOnClickListener { toggleAssistant() }
        }
        page.addView(actionButton, LinearLayout.LayoutParams(-1, dp(54)))

        val description = TextView(this).apply {
            text = "Скажи «Джарвис», дождись ответа и произнеси команду.\n\nПока доступны базовые действия: открыть YouTube, Telegram, Chrome или настройки; узнать время, дату и заряд батареи."
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setLineSpacing(dp(5).toFloat(), 1.0f)
            setPadding(0, dp(24), 0, dp(18))
        }
        page.addView(description, fullWidth())

        val note = TextView(this).apply {
            text = "ПРОТОТИП • микрофон активен только при включённом режиме"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(92, 116, 136))
        }
        page.addView(note, fullWidth())
        setContentView(scroll)
    }

    private fun toggleAssistant() {
        if (isActive) {
            stopService(Intent(this, JarvisService::class.java))
            isActive = false
            refreshState()
            return
        }

        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            startAssistant()
        }
    }

    private fun startAssistant() {
        val intent = Intent(this, JarvisService::class.java).apply {
            action = JarvisService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        isActive = true
        refreshState()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (audioGranted) startAssistant() else {
                statusText.text = "Для работы Джарвису нужен доступ к микрофону."
            }
        }
    }

    private fun refreshState() {
        if (isActive) {
            statusText.text = "● СИСТЕМА АКТИВНА\nСлушаю команду «Джарвис»"
            statusText.setTextColor(Color.rgb(69, 215, 255))
            actionButton.text = "Выключить Джарвиса"
        } else {
            statusText.text = "○ СИСТЕМА ОЖИДАЕТ ЗАПУСКА"
            statusText.setTextColor(Color.rgb(139, 165, 184))
            actionButton.text = "Активировать Джарвиса"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun fullWidth(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)

    companion object {
        private const val REQUEST_PERMISSIONS = 401
    }
}
