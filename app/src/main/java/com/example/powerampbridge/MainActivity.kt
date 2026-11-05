package com.example.powerampbridge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val powerampPackage = "com.maxmpz.audioplayer"
    private val workerUrl =
        "https://poweramp-bridge.kirillromyslov-86f.workers.dev"

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var polling = false

    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var albumText: TextView
    private lateinit var statusText: TextView
    private lateinit var tokenInput: EditText

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.maxmpz.audioplayer.STATUS_CHANGED") return

            titleText.text =
                intent.getStringExtra("title") ?: "Неизвестный трек"
            artistText.text =
                intent.getStringExtra("artist") ?: "Неизвестный исполнитель"
            albumText.text =
                intent.getStringExtra("album") ?: "Неизвестный альбом"

            val paused = intent.getBooleanExtra("paused", true)
            statusText.text = if (paused) "На паузе" else "Воспроизводится"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }

        fun addText(text: String, size: Float): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = size
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 12)
                layout.addView(this)
            }
        }

        titleText = addText("Poweramp Bridge", 24f)
        artistText = addText("Исполнитель", 18f)
        albumText = addText("Альбом", 16f)
        statusText = addText("Ожидание Poweramp...", 16f)

        tokenInput = EditText(this).apply {
            hint = "Ключ телефона"
            singleLine = true
            setText(
                getSharedPreferences("bridge", MODE_PRIVATE)
                    .getString("device_token", "")
            )
            layout.addView(
                this,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        fun addButton(label: String, action: () -> Unit) {
            val button = Button(this).apply {
                text = label
                setOnClickListener { action() }
            }
            layout.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        addButton("Сохранить ключ и подключиться") {
            val token = tokenInput.text.toString().trim()
            if (token.isBlank()) {
                statusText.text = "Введи ключ телефона"
            } else {
                getSharedPreferences("bridge", MODE_PRIVATE)
                    .edit()
                    .putString("device_token", token)
                    .apply()

                startPolling()
            }
        }

        addButton("Открыть Poweramp") {
            val launchIntent =
                packageManager.getLaunchIntentForPackage(powerampPackage)
            if (launchIntent != null) startActivity(launchIntent)
            else statusText.text = "Poweramp не найден"
        }

        addButton("Play / Pause") {
            sendPowerampCommand("TOGGLE_PLAY_PAUSE")
        }

        addButton("Предыдущий трек") {
            sendPowerampCommand("PREVIOUS")
        }

        addButton("Следующий трек") {
            sendPowerampCommand("NEXT")
        }

        setContentView(layout)
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        statusText.text = "Подключаемся к облаку..."

        executor.scheduleWithFixedDelay({
            try {
                val token = getSharedPreferences("bridge", MODE_PRIVATE)
                    .getString("device_token", "") ?: ""

                if (token.isBlank()) return@scheduleWithFixedDelay

                val connection =
                    URL("$workerUrl/device/poll").openConnection()
                        as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty(
                    "Authorization", "Bearer $token"
                )
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val response = connection.inputStream
                    .bufferedReader().use { it.readText() }

                connection.disconnect()

                val data = JSONObject(response)
                val command = data.optJSONObject("command")
                    ?.optString("command", null)

                if (command != null) {
                    runOnUiThread {
                        statusText.text = "Получена команда: $command"
                    }
                    when (command) {
                        "PLAY", "PAUSE", "TOGGLE_PLAY_PAUSE",
                        "NEXT", "PREVIOUS" ->
                            sendPowerampCommand(command)
                    }
                } else {
                    runOnUiThread {
                        statusText.text = "Облако подключено"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Связь: ${e.message}"
                }
            }
        }, 0, 2, TimeUnit.SECONDS)
    }

    private fun sendPowerampCommand(command: String) {
        val intent = Intent(
            "com.maxmpz.audioplayer.API_COMMAND"
        ).apply {
            setPackage(powerampPackage)
            putExtra("cmd", command)
            putExtra("pak", packageName)
        }
        sendBroadcast(intent)
    }

    override fun onStart() {
        super.onStart()

        val filter = IntentFilter(
            "com.maxmpz.audioplayer.STATUS_CHANGED"
        )

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                statusReceiver,
                filter,
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    override fun onDestroy() {
        polling = false
        executor.shutdownNow()
        super.onDestroy()
    }
}
