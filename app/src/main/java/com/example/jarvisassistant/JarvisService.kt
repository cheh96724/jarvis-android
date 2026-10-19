package com.example.jarvisassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarvisService : Service(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var active = false
    private var speaking = false
    private var awaitingCommand = false
    private var ttsReady = false
    private var stopAfterSpeech = false

    private val restartListening = Runnable {
        if (active && !speaking) startListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
        initRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        active = true
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply()
        val notification = makeNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        scheduleListening(800)
        return START_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ru", "RU"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(0.94f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        speaking = false
                        if (stopAfterSpeech) stopSelf() else scheduleListening(600)
                    }
                }
                @Deprecated("Deprecated by Android")
                override fun onError(utteranceId: String?) {
                    handler.post {
                        speaking = false
                        if (stopAfterSpeech) stopSelf() else scheduleListening(600)
                    }
                }
                override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
            })
        }
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer = try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        } catch (_: Exception) {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                scheduleListening(900)
            }
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val heard = matches?.firstOrNull().orEmpty()
                handleRecognizedText(heard)
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
    }

    private fun startListening() {
        if (!active || speaking) return
        if (recognizer == null) {
            say("Не найден сервис распознавания речи. Проверь, установлен ли голосовой ввод на телефоне.")
            return
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            scheduleListening(1200)
        }
    }

    private fun handleRecognizedText(raw: String) {
        if (!active) return
        val text = normalize(raw)
        if (text.isBlank()) {
            scheduleListening(350)
            return
        }

        if (awaitingCommand) {
            awaitingCommand = false
            executeCommand(text)
            return
        }

        val wakeWords = listOf("джарвис", "джервис", "джарвиз", "джарвес")
        val wake = wakeWords.firstOrNull { text.contains(it) }
        if (wake != null) {
            val command = text.replace(wake, "").trim(' ', ',', '.', '!', '?', ':', ';', '-')
            if (command.isBlank()) {
                awaitingCommand = true
                say("Слушаю.")
            } else {
                executeCommand(command)
            }
        } else {
            scheduleListening(250)
        }
    }

    private fun executeCommand(command: String) {
        val c = normalize(command)
        when {
            c.contains("открой") && (c.contains("ютуб") || c.contains("youtube")) -> openApp("com.google.android.youtube", "https://www.youtube.com", "Открываю YouTube.")
            c.contains("открой") && (c.contains("телеграм") || c.contains("telegram") || c.contains("телегу")) -> openApp("org.telegram.messenger", "https://telegram.org", "Открываю Telegram.")
            c.contains("открой") && (c.contains("хром") || c.contains("chrome")) -> openApp("com.android.chrome", "https://www.google.com", "Открываю Chrome.")
            c.contains("открой") && (c.contains("настройки") || c.contains("настройку")) -> {
                say("Открываю настройки.")
                handler.postDelayed({ launchIntent(Intent(Settings.ACTION_SETTINGS)) }, 650)
            }
            c.contains("открой") && (c.contains("будильник") || c.contains("часы")) -> {
                say("Открываю часы.")
                handler.postDelayed({ launchIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }, 650)
            }
            c.contains("поставь будильник") -> setAlarmFromCommand(c)
            c.contains("заряд") || c.contains("батаре") || c.contains("процент") -> say("Заряд аккумулятора: ${batteryPercent()} процентов.")
            c.contains("который час") || c == "время" || c.contains("сколько времени") -> {
                say("Сейчас ${SimpleDateFormat("H:mm", Locale("ru", "RU")).format(Date())}.")
            }
            c.contains("какое сегодня число") || c.contains("какая дата") || c == "дата" -> {
                say("Сегодня ${SimpleDateFormat("d MMMM yyyy года", Locale("ru", "RU")).format(Date())}.")
            }
            c.contains("что ты умеешь") || c.contains("какие команды") -> say("Я умею открывать YouTube, Telegram, Chrome, настройки и часы, сообщать время, дату и заряд батареи. Скажи, например: Джарвис, открой YouTube.")
            c == "спасибо" || c.contains("спасибо джарвис") -> say("Всегда к вашим услугам.")
            c.contains("остановись") || c.contains("выключись") || c.contains("отключись") -> {
                stopAfterSpeech = true
                say("Отключаю голосовой режим.")
            }
            else -> say("Пока это базовая версия. Я не понял команду. Скажи: что ты умеешь.")
        }
    }

    private fun openApp(packageName: String, fallbackUrl: String, spoken: String) {
        say(spoken)
        handler.postDelayed({
            val appIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (appIntent != null) launchIntent(appIntent)
            else launchIntent(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(fallbackUrl)))
        }, 650)
    }

    private fun launchIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            say("Не получилось открыть приложение.")
        }
    }

    private fun setAlarmFromCommand(command: String) {
        val match = Regex("(\\d{1,2})(?::|\\s)(\\d{2})").find(command)
        if (match == null) {
            say("Назови время, например: поставь будильник на 8:30.")
            return
        }
        val hour = match.groupValues[1].toIntOrNull() ?: 8
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) {
            say("Такое время не подходит.")
            return
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Джарвис")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        say("Открываю создание будильника на $hour часов $minute минут.")
        handler.postDelayed({ launchIntent(intent) }, 650)
    }

    private fun batteryPercent(): Int {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 0
    }

    private fun say(message: String) {
        if (!active) return
        speaking = true
        try { recognizer?.cancel() } catch (_: Exception) { }
        if (!ttsReady || tts == null) {
            speaking = false
            scheduleListening(900)
            return
        }
        val params = android.os.Bundle()
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, "jarvis-${System.currentTimeMillis()}")
    }

    private fun normalize(value: String): String = value.lowercase(Locale("ru", "RU"))
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}: ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun scheduleListening(delay: Long) {
        if (!active || speaking) return
        handler.removeCallbacks(restartListening)
        handler.postDelayed(restartListening, delay)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "JARVIS — голосовой режим", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Показывает, что голосовой помощник включён"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun makeNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS активен")
            .setContentText("Слушаю ключевую фразу «Джарвис»")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Выключить", stop).build())
            .build()
    }

    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Exception) { }
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val PREFS = "jarvis_preferences"
        const val KEY_RUNNING = "assistant_running"
        const val ACTION_START = "com.example.jarvisassistant.START"
        const val ACTION_STOP = "com.example.jarvisassistant.STOP"
        private const val CHANNEL_ID = "jarvis_voice_channel"
        private const val NOTIFICATION_ID = 101
    }
}
