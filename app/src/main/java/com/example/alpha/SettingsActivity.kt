package com.example.alpha

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var btnSave: Button
    private lateinit var preferences: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        btnSave = findViewById(R.id.btnSave)

        preferences = PreferenceManager.getDefaultSharedPreferences(this)

        // Загружаем сохраненные настройки
        etIp.setText(preferences.getString("ip", "192.168.1.1"))
        etPort.setText(preferences.getString("port", "8080"))

        btnSave.setOnClickListener {
            val newIp = etIp.text.toString()
            val newPort = etPort.text.toString()

            val oldIp = preferences.getString("ip", "192.168.1.1")
            val oldPort = preferences.getString("port", "8080")

            if (newIp != oldIp || newPort != oldPort) {
                // 1. Разрываем соединение
                WebSocketManagerInstance.webSocketManager?.disconnect()
                WebSocketManagerInstance.webSocketManager = null

                // 2. Сохраняем новые настройки
                preferences.edit().putString("ip", newIp).apply()
                preferences.edit().putString("port", newPort).apply()

                // 3. Обновляем глобальный WebSocketManager
                WebSocketManagerInstance.webSocketManager = null
            }

            finish() // Закрываем экран (MainActivity сама переподключится)
        }
    }
}
