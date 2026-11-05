package com.example.powerampbridge

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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

    companion object {
        private const val AUDIO_PERMISSION_REQUEST = 501
    }

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
            isSingleLine = true
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

        addButton("Сканировать музыкальную библиотеку") {
            requestAudioPermission()
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

    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            scanAudioLibrary()
        } else {
            requestPermissions(
                arrayOf(permission),
                AUDIO_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                scanAudioLibrary()
            } else {
                statusText.text = "Нет разрешения на чтение музыки"
            }
        }
    }

    private fun scanAudioLibrary() {
        statusText.text = "Сканируем музыкальную библиотеку..."

        Thread {
            try {
                val collection = if (Build.VERSION.SDK_INT >= 29) {
                    MediaStore.Audio.Media.getContentUri(
                        MediaStore.VOLUME_EXTERNAL
                    )
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

                val projection = arrayOf(
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM
                )

                val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
                val sortOrder =
                    "${MediaStore.Audio.Media.ARTIST} ASC, " +
                    "${MediaStore.Audio.Media.TITLE} ASC"

                val tracks = mutableListOf<String>()

                contentResolver.query(
                    collection,
                    projection,
                    selection,
                    null,
                    sortOrder
                )?.use { cursor ->

                    val titleIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.TITLE
                    )
                    val artistIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.ARTIST
                    )
                    val albumIndex = cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.ALBUM
                    )

                    while (cursor.moveToNext()) {
                        val title = cursor.getString(titleIndex)
                            ?: "Неизвестный трек"
                        val artist = cursor.getString(artistIndex)
                            ?: "Неизвестный исполнитель"
                        val album = cursor.getString(albumIndex)
                            ?: "Неизвестный альбом"

                        tracks.add("$artist — $title [$album]")
                    }
                }

                val result = if (tracks.isEmpty()) {
                    "Музыкальные композиции не найдены."
                } else {
                    tracks.joinToString("\n")
                }

                runOnUiThread {
                    if (tracks.isNotEmpty()) {
                        val clipboard =
                            getSystemService(CLIPBOARD_SERVICE)
                                as ClipboardManager

                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "Poweramp music library",
                                result
                            )
                        )

                        statusText.text =
                            "Найдено ${tracks.size} композиций. " +
                            "Список скопирован!"
                    } else {
                        statusText.text = result
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Ошибка сканирования: ${e.message}"
                }
            }
        }.start()
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
