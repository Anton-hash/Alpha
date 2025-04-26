package com.example.alpha

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.preference.PreferenceManager

class MainActivity : ComponentActivity() {

    private lateinit var tvStatus: TextView

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)

        // Инициализация BarcodeManager
        val barcodeManager = BarcodeManager.getInstance(this)

        // Подписываемся на события сканирования
        barcodeManager.addListener { barcodeType, barcodeData ->
            runOnUiThread {
                // Обновляем UI с последним отсканированным штрих-кодом
                //textViewBarcodes.text = "$barcodeType | $barcodeData"
            }
            Log.d("HoneywellScanner", "Scanned: $barcodeType | $barcodeData")
        }

        // Подписываемся на статус WebSocket
        WebSocketManagerInstance.status.observe(this) { (message, color) ->
            tvStatus.text = message
            tvStatus.setTextColor(color)
        }

        // Кнопка для открытия InformationActivity
        val btnInformation: Button = findViewById(R.id.btnInformation)
        btnInformation.setOnClickListener {
            startActivity(Intent(this, InformationActivity::class.java))
        }

        val btnAggregationL1Print: Button = findViewById(R.id.btnAggregationL1Print)
        btnAggregationL1Print.setOnClickListener {
            startActivity((Intent(this, AggregationL1PrintActivity::class.java)))
        }

        val btnAggregationL2Print: Button = findViewById(R.id.btnAggregationL2Print)
        btnAggregationL2Print.setOnClickListener {
            startActivity((Intent(this, AggregationL2PrintActivity::class.java)))
        }

        val btnDesAggregationL1: Button = findViewById(R.id.btnDesAggregationL1)
        btnDesAggregationL1.setOnClickListener {
            startActivity((Intent(this, DesAggregationL1Activity::class.java)))
        }

        val btnDesAggregationL2: Button = findViewById(R.id.btnDesAggregationL2)
        btnDesAggregationL2.setOnClickListener {
            startActivity((Intent(this, DesAggregationL2Activity::class.java)))
        }

        val btnReplaceL1: Button = findViewById(R.id.btnReplaceL1)
        btnReplaceL1.setOnClickListener {
            startActivity((Intent(this, ReplaceL1Activity::class.java)))
        }

        val btnReplaceL2: Button = findViewById(R.id.btnReplaceL2)
        btnReplaceL2.setOnClickListener {
            startActivity((Intent(this, ReplaceL2Activity::class.java)))
        }


        // Инициализируем WebSocket (используем IP и порт из настроек)
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val ip = preferences.getString("ip", "192.168.1.1") ?: "192.168.1.1"
        val port = preferences.getString("port", "8080") ?: "8080"
        WebSocketManagerInstance.initWebSocket(ip, port)

        val btnSettings: ImageButton = findViewById(R.id.btnSettings)
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        reconnectWebSocket()
    }

    private fun reconnectWebSocket() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val ip = preferences.getString("ip", "192.168.1.1") ?: "192.168.1.1"
        val port = preferences.getString("port", "8080") ?: "8080"

        if (WebSocketManagerInstance.webSocketManager == null) {
            WebSocketManagerInstance.webSocketManager = WebSocketManager(ip, port) { status, color ->
                runOnUiThread {
                    tvStatus.text = status
                    tvStatus.setTextColor(color)
                }
            }
            WebSocketManagerInstance.webSocketManager?.connect()
        }
    }
}