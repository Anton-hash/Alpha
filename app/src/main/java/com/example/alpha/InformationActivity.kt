package com.example.alpha

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import android.graphics.Color
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject

class InformationActivity : AppCompatActivity() {
    private lateinit var statusIndicator: View
    private lateinit var toolbar: Toolbar
    private lateinit var infoContainer: LinearLayout
    private lateinit var btnClear: Button
    private var isActivityResumed: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_information)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportActionBar?.title = "Информация"

        infoContainer = findViewById(R.id.infoContainer)
        btnClear = findViewById(R.id.btnClear)

        // Изначально кнопка "Очистить" неактивна
        setClearButtonEnabled(false)

        val barcodeManager = BarcodeManager.getInstance(this)

        // Подписываемся на события сканирования
        barcodeManager.addListener { barcodeType, barcodeData ->
            runOnUiThread {
                Log.d("InformationActivity", "New barcode scanned: $barcodeType | $barcodeData")

                if (isActivityResumed) {
                    val jsonMessage = JsonMessageFormatter.createJsonMessageInformation(
                        typeCode = barcodeType,
                        value = barcodeData
                    )
                    // Проверяем, подключен ли WebSocket при запуске активности
                    if (isWebSocketConnected()) {
                        Log.d("InformationActivity", "WebSocket подключен, отправляем сообщение")
                        sendMessageIfConnected(jsonMessage)
                    } else {
                        Log.e("InformationActivity", "WebSocket не подключен")
                        showError("WebSocket не подключен")
                    }
                    Log.d("InformationActivity", "Sent to WebSocket: $jsonMessage")
                } else {
                    Log.d("InformationActivity", "Activity is not active. Ignoring barcode event.")
                }
            }
        }

        statusIndicator = findViewById(R.id.statusIndicator)

        // Подписываемся на статус WebSocket
        WebSocketManagerInstance.status.observe(this, Observer { (message, color) ->
            updateStatusIndicator(color)
        })

        // Обработчик кнопки "Очистить"
        btnClear.setOnClickListener {
            infoContainer.removeAllViews() // Очищаем контейнер с информацией
            setClearButtonEnabled(false) // Делаем кнопку неактивной после очистки
        }

        // Подписываемся на входящие сообщения от WebSocket
        WebSocketManagerInstance.webSocketManager?.setMessageListener { message ->
            runOnUiThread {
                val jsonObject = JsonMessageFormatter.parseAndValidateJson(message)
                if (jsonObject != null) {
                    displayInformation(jsonObject)
                    setClearButtonEnabled(true) // Делаем кнопку активной при получении данных
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        Log.d("InformationActivity", "Activity is resumed")
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        Log.d("InformationActivity", "Activity is paused")
    }

    private fun updateStatusIndicator(color: Int) {
        Log.d("InformationActivity", "Updating status indicator with color: $color")
        statusIndicator.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun isWebSocketConnected(): Boolean {
        return WebSocketManagerInstance.webSocketManager != null &&
                WebSocketManagerInstance.webSocketManager?.isConnected() == true
    }

    private fun sendMessageIfConnected(message: String) {
        val isConnected = WebSocketManagerInstance.webSocketManager?.isConnected() == true
        Log.d("WebSocketManager", "Состояние подключения: $isConnected")

        if (isConnected) {
            WebSocketManagerInstance.webSocketManager?.send(message)
            Log.d("WebSocketManager", "Сообщение отправлено: $message")
        } else {
            Log.d("WebSocketManager", "WebSocket не подключен, сообщение не отправлено")
        }
    }

    private fun displayInformation(jsonObject: JSONObject) {
        infoContainer.removeAllViews() // Очищаем контейнер перед добавлением новых данных

        val status = jsonObject.optInt("status", -1) // Получаем статус, по умолчанию -1
        val message = jsonObject.optString("message", "") // Получаем сообщение

        when (status) {
            0 -> {
                // Если статус 0, выводим все данные
                addTextView(message, Color.rgb(0, 100, 0)) // Темно-зеленый цвет для сообщения
                addDivider() // Добавляем разделитель

                val valuesArray = jsonObject.optJSONArray("values")
                if (valuesArray != null && valuesArray.length() > 0) {
                    val firstValue = valuesArray.optJSONObject(0)
                    if (firstValue != null) {
                        // Определяем текст в зависимости от значения "type"
                        val typeText = when (firstValue.optString("type")) {
                            "0" -> "Единица"
                            "1" -> "Короб"
                            "2" -> "Паллета"
                            else -> "Неизвестный тип"
                        }
                        val statusText = when (firstValue.optString("status")) {
                            "1" -> "Сериализован"
                            "3" -> "Загружен"
                            "4" -> "Агрегирован"
                            "7" -> "Отбракован"
                            else -> "Неизвестный статус"
                        }

                        val items = listOf(
                            "${firstValue.optString("product_name")}",
                            "GTIN: ${firstValue.optString("GTIN")}",
                            "Тип: $typeText", // Добавляем текст типа
                            "Статус: $statusText",
                            "Код: ${firstValue.optString("code_value")}",
                            "Партия: ${firstValue.optString("LOT")}",
                            "Создан: ${firstValue.optString("creation_date")}",
                            "Номер: ${firstValue.optInt("num")}"
                        )

                        items.forEachIndexed { index, item ->
                            addTextView(item)
                            if (index < items.size - 1) {
                                addDivider() // Добавляем разделитель, если это не последний элемент
                            }
                        }

                        val taskUnitsPerBox = firstValue.optInt("task_need_units_per_box")
                        if (taskUnitsPerBox != 0) {
                            addTextView("Короб задание: $taskUnitsPerBox")
                            addDivider() // Добавляем разделитель
                        }

                        val taskBoxesPerPallet = firstValue.optInt("task_need_boxes_per_pallet")
                        if (taskBoxesPerPallet != 0) {
                            addTextView("Паллета задание: $taskBoxesPerPallet")
                            addDivider() // Добавляем разделитель
                        }

                        val aggCodesCount = firstValue.optInt("agg_codes_count")
                        if (aggCodesCount != 0) {
                            addTextView("Агрегировано: $aggCodesCount")
                            addDivider() // Добавляем разделитель
                        }

                        val upCodesArray = firstValue.optJSONArray("up_codes")
                        if (upCodesArray != null) {
                            for (i in 0 until upCodesArray.length()) {
                                val upCode = upCodesArray.optString(i)
                                if (upCode.isNotEmpty()) {
                                    addTextView("Код агрегации ${i + 1}: $upCode")
                                    if (i < upCodesArray.length() - 1) {
                                        addDivider() // Добавляем разделитель, если это не последний элемент
                                    }
                                }
                            }
                        }
                    }
                }
            }
            -1 -> {
                // Если статус -1, выводим только сообщение красным цветом
                addTextView(message, Color.RED)
                addDivider() // Добавляем разделитель
            }
            else -> {
                // Для других статусов выводим сообщение по умолчанию
                addTextView("Неизвестный статус: $status", Color.RED)
                addDivider() // Добавляем разделитель
            }
        }
    }

    // Метод для добавления TextView с указанным цветом текста
    private fun addTextView(text: String, color: Int = Color.BLACK) {
        val textView = TextView(this)
        textView.text = text
        textView.setTextColor(color)
        textView.textSize = 16f
        infoContainer.addView(textView)
    }

    // Метод для добавления горизонтального разделителя
    private fun addDivider() {
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, // Ширина
                1.dpToPx() // Высота (1dp)
            )
            setBackgroundColor(Color.parseColor("#CCCCCC")) // Цвет линии (серый)
        }
        infoContainer.addView(divider)
    }

    // Утилита для преобразования dp в пиксели
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    // Метод для управления состоянием кнопки "Очистить"
    private fun setClearButtonEnabled(enabled: Boolean) {
        btnClear.isEnabled = enabled
        val color = if (enabled) {
            ContextCompat.getColor(this, R.color.ubuntu_purple) // Фиолетовый
        } else {
            ContextCompat.getColor(this, R.color.ubuntu_light_purple) // Светло-фиолетовый
        }
        btnClear.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}