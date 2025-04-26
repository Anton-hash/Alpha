package com.example.alpha

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Observer
import org.json.JSONObject

// Activity для режима разагрегации короба
class DesAggregationL1Activity : AppCompatActivity() {
    // UI-элементы
    private lateinit var statusIndicator: View         // Индикатор подключения
    private lateinit var toolbar: Toolbar             // Панель инструментов
    private lateinit var infoContainer: LinearLayout  // Контейнер для вывода информации
    private lateinit var btnDesagg: Button            // Кнопка "Разагрегировать"
    private lateinit var btnCancel: Button            // Кнопка "Отмена"

    // Переменные состояния
    private var isActivityResumed = false             // Флаг активности экрана
    private var currentCodeValue: String? = null      // Сохранённый код короба для отправки

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_des_aggregation_l1)
        Log.d("DesAggregationL1Activity", "onCreate: Активность создана")

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Разагрегация короба"

        statusIndicator = findViewById(R.id.statusIndicator)
        infoContainer = findViewById(R.id.infoContainer)
        btnDesagg = findViewById(R.id.btnDesagg)
        btnCancel = findViewById(R.id.btnCancel)

        btnDesagg.visibility = View.GONE
        btnCancel.visibility = View.GONE

        val barcodeManager = BarcodeManager.getInstance(this)
        barcodeManager.addListener { barcodeType, barcodeData ->
            runOnUiThread {
                Log.d("DesAggregationL1Activity", "Сканирован код: $barcodeType | $barcodeData")
                if (isActivityResumed) {
                    sendCodeCheckForDesagg(barcodeType, barcodeData)
                }
            }
        }

        WebSocketManagerInstance.status.observe(this, Observer { (message, color) ->
            updateStatusIndicator(color)
        })

        WebSocketManagerInstance.webSocketManager?.setMessageListener { message ->
            runOnUiThread {
                Log.d("DesAggregationL1Activity", "Получено сообщение от WebSocket: $message")
                handleWebSocketResponse(message)
            }
        }

        btnCancel.setOnClickListener {
            Log.d("DesAggregationL1Activity", "Нажата кнопка 'Отмена'")
            resetScreen()
        }

        btnDesagg.setOnClickListener {
            Log.d("DesAggregationL1Activity", "Нажата кнопка 'Разагрегировать'")
            showDesaggConfirmationDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        Log.d("DesAggregationL1Activity", "onResume: Активность возобновлена")
        showInitialMessage()
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        Log.d("DesAggregationL1Activity", "onPause: Активность приостановлена")
    }

    private fun showInitialMessage() {
        infoContainer.removeAllViews()
        addTextView("Считайте код короба или вложения", Color.DKGRAY)
    }

    private fun sendCodeCheckForDesagg(typeCode: String, value: String) {
        val json = JsonMessageFormatter.createJsonMessageTaskCode(
            typeCode = typeCode,
            value = value,
            mode = 0,
            aggLevel = 1,
            code = "code_check_for_desagg"
        )
        if (isWebSocketConnected()) {
            WebSocketManagerInstance.webSocketManager?.send(json)
            Log.d("DesAggregationL1Activity", "Отправлено сообщение: $json")
        } else {
            showError("WebSocket не подключен")
        }
    }

    /**
     * Обновляет индикатор статуса.
     */
    private fun updateStatusIndicator(color: Int) {
        Log.d("AggregationL1PrintActivity", "Обновление индикатора статуса с цветом: $color")
        statusIndicator.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun handleWebSocketResponse(response: String) {
        try {
            val jsonObject = JSONObject(response)
            val code = jsonObject.optString("code", "")
            val status = jsonObject.optInt("status", -1)
            val message = jsonObject.optString("message", "")

            when (code) {
                "code_check_for_desagg" -> {
                    if (status == -1) {
                        Log.e("DesAggregationL1Activity", "Ошибка проверки кода: $message")
                        showError(message)
                    } else if (status == 0) {
                        Log.d("DesAggregationL1Activity", "Код успешно проверен")
                        displayBoxInfo(jsonObject)
                    }
                }
                "Desaggregation" -> {
                    if (status == -1) {
                        Log.e("DesAggregationL1Activity", "Ошибка разагрегации: $message")
                        showError(message)
                    } else if (status == 0) {
                        Log.d("DesAggregationL1Activity", "Разагрегация успешна: $message")
                        infoContainer.removeAllViews()
                        addTextView(message, Color.rgb(0, 100, 0))
                        btnDesagg.visibility = View.GONE
                        btnCancel.visibility = View.GONE
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DesAggregationL1Activity", "Ошибка парсинга ответа", e)
        }
    }

    private fun displayBoxInfo(json: JSONObject) {
        infoContainer.removeAllViews()
        val values = json.optJSONArray("values")
        if (values != null && values.length() > 0) {
            val box = values.getJSONObject(0)

            currentCodeValue = box.optString("code_value")

            addTextView(box.optString("product_name"))
            addDivider()
            addTextView("Код короба: ${box.optString("code_value")}")
            addDivider()
            addTextView("Статус: ${statusText(box.optString("status"))}")
            addDivider()
            addTextView("Количество вложений: ${box.optString("agg_codes_count")}")
            addDivider()
            addTextView("Партия: ${box.optString("LOT")}")
            addDivider()
            addTextView("Создан: ${box.optString("creation_date")}")
            addDivider()
            addTextView("Номер: ${box.optString("num")}")
            addDivider()
            addTextView("Код паллеты: ${box.optString("up_codes")}")
            addDivider()

            btnDesagg.visibility = View.VISIBLE
            btnCancel.visibility = View.VISIBLE
        }
    }

    private fun statusText(status: String): String {
        return when (status) {
            "4" -> "Агрегирован"
            else -> "Неизвестный статус"
        }
    }

    private fun showDesaggConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Разагрегация")
            .setMessage("Вы уверены, что хотите разагрегировать этот короб?")
            .setPositiveButton("Да") { dialog, _ ->
                sendDesaggRequest()
                dialog.dismiss()
            }
            .setNegativeButton("Нет") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun sendDesaggRequest() {
        val codeValue = currentCodeValue ?: return
        val json = JSONObject().apply {
            put("code", "Desaggregation")
            put("status", 0)
            put("mode", 1)
            put("AggLevel",1)
            put("values", codeValue)
        }
        sendMessageIfConnected(json.toString())
        Log.d("DesAggregationL1Activity", "Отправлен запрос на разагрегацию: $json")
    }

    private fun isWebSocketConnected(): Boolean {
        return WebSocketManagerInstance.webSocketManager != null &&
                WebSocketManagerInstance.webSocketManager?.isConnected() == true
    }

    private fun sendMessageIfConnected(message: String) {
        if (isWebSocketConnected()) {
            WebSocketManagerInstance.webSocketManager?.send(message)
            Log.d("DesAggregationL1Activity", "Сообщение отправлено: $message")
        }
    }

    private fun showError(message: String) {
        vibrate(500)
        infoContainer.removeAllViews()
        addTextView(message, Color.RED)
    }

    private fun vibrate(milliseconds: Long) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(milliseconds)
        }
    }

    private fun resetScreen() {
        Log.d("DesAggregationL1Activity", "Сброс состояния экрана")
        currentCodeValue = null
        btnDesagg.visibility = View.GONE
        btnCancel.visibility = View.GONE
        showInitialMessage()
    }

    private fun addTextView(text: String, color: Int = Color.BLACK) {
        val textView = TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 16f
        }
        infoContainer.addView(textView)
    }

    private fun addDivider() {
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(Color.parseColor("#CCCCCC"))
        }
        infoContainer.addView(divider)
    }
}
