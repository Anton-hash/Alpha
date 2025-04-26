package com.example.alpha

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Observer
import org.json.JSONObject
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import org.json.JSONArray
import android.app.AlertDialog
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioGroup

class AggregationL1PrintActivity : AppCompatActivity() {
    private lateinit var statusIndicator: View
    private lateinit var toolbar: Toolbar
    private lateinit var messageView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private var isActivityResumedAggL1: Boolean = false
    private var title = "Агрегация в короб"
    private var infoTaskMessage: String = "" // Сообщение из info_task
    private var infoTaskStatus: Int = -1 // Статус из info_task
    private val scannedValues = mutableListOf<String>() // Массив для хранения значений values
    private var unitPerBox: Int = 0 // Максимальное количество элементов (из info_task)
    private var isAggregationError: Boolean = false // Флаг для блокировки сканирования при ошибке
    private var isAddMode: Boolean = true // По умолчанию режим "Добавление"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aggregation_l1_print)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportActionBar?.title = title

        statusIndicator = findViewById(R.id.statusIndicator)
        messageView = findViewById(R.id.messageView)

        // Инициализация ProgressBar и TextView
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)

        // Инициализация кнопки
        val clearButton: ImageButton = findViewById(R.id.clearButton)

        // Обработка нажатия на кнопку
        clearButton.setOnClickListener {
            showClearConfirmationDialog()
        }

        // Инициализация кнопки
        val aggregateButton: Button = findViewById(R.id.aggregateButton)

        // Обработка нажатия на кнопку
        aggregateButton.setOnClickListener {
            handleAggregateButtonClick()
        }

         //Инициализация RadioGroup
            val modeRadioGroup: RadioGroup = findViewById(R.id.modeRadioGroup)

        // Обработка изменения режима
        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            isAddMode = when (checkedId) {
                R.id.addModeRadioButton -> true
                R.id.removeModeRadioButton -> false
                else -> true
            }
            Log.d("ModeChange", "isAddMode: $isAddMode") // Логирование для отладки
        }


        // Проверяем, инициализирован ли WebSocketManager
        if (WebSocketManagerInstance.webSocketManager == null) {
            Log.e("AggregationL1PrintActivity", "WebSocketManager не инициализирован")
            showError("WebSocketManager не инициализирован")
            return
        }

        // Подписываемся на статус WebSocket
        WebSocketManagerInstance.status.observe(this, Observer { (message, color) ->
            updateStatusIndicator(color)

            // Проверяем, было ли восстановлено подключение
            if (message == "Сервер подключен") {
                Log.d("AggregationL1PrintActivity", "WebSocket подключен, отправляем сообщение")
                sendMessageIfConnected(JsonMessageFormatter.createJsonMessageInformationTask(1, "info_task"))
            }
        })

        // Подписываемся на ответ от WebSocket
        WebSocketManagerInstance.webSocketManager?.setMessageListener { response ->
            handleWebSocketResponse(response)
        }

        // Проверяем, подключен ли WebSocket при запуске активности
        if (isWebSocketConnected()) {
            Log.d("AggregationL1PrintActivity", "WebSocket подключен, отправляем сообщение")
            sendMessageIfConnected(JsonMessageFormatter.createJsonMessageInformationTask(1, "info_task"))
        } else {
            Log.e("AggregationL1PrintActivity", "WebSocket не подключен")
            showError("WebSocket не подключен")
        }

        val barcodeManager = BarcodeManager.getInstance(this)

        // Подписываемся на события сканирования
        barcodeManager.addListener { barcodeType, barcodeData ->
            runOnUiThread {
                Log.d("AggregationL1PrintActivity", "New barcode scanned: $barcodeType | $barcodeData")

                if (isActivityResumedAggL1) {
                    if (isAddMode) {
                        // Проверяем, есть ли код уже в массиве
                        if (scannedValues.contains(barcodeData)) {
                            // Если код уже есть, вызываем вибрацию и выводим сообщение
                            messageView.text = "Код уже был сканирован"
                            messageView.setTextColor(Color.RED) // Устанавливаем красный цвет текста
                            vibrate(500) // Вибрация на 0,5 секунды

                            // Возвращаем исходное состояние через 3 секунды
                            messageView.postDelayed({
                                messageView.text = infoTaskMessage // Восстанавливаем сообщение из info_task
                                messageView.setTextColor(
                                    if (infoTaskStatus == 0) Color.rgb(
                                        0,
                                        100,
                                        0
                                    ) else Color.RED
                                ) // Восстанавливаем цвет
                            }, 3000) // Задержка 3 секунды
                        }
                        else {
                            // Режим "Добавление": отправляем данные в WebSocket
                            val jsonMessage = JsonMessageFormatter.createJsonMessageTaskCode(
                                typeCode = barcodeType,
                                value = barcodeData,
                                mode = 0,
                                aggLevel = 1,
                                code = "code_check"
                            )
                            if (isWebSocketConnected()) {
                                Log.d("AggregationL1PrintActivity", "WebSocket подключен, отправляем сообщение")
                                sendMessageIfConnected(jsonMessage)
                            } else {
                                Log.e("AggregationL1PrintActivity", "WebSocket не подключен")
                                showError("WebSocket не подключен")
                            }
                            Log.d("AggregationL1PrintActivity", "Sent to WebSocket: $jsonMessage")
                        }

                    } else {
                        // Режим "Удаление": проверяем наличие кода в scannedValues
                        if (scannedValues.isEmpty()) {
                            // Если scannedValues пуст, игнорируем действие
                            Log.d("AggregationL1PrintActivity", "scannedValues пуст, игнорируем действие")
                        } else if (scannedValues.contains(barcodeData)) {
                            // Если код найден в scannedValues, удаляем его
                            scannedValues.remove(barcodeData)
                            progressBar.progress = scannedValues.size
                            progressText.text = "${scannedValues.size}/$unitPerBox"
                            Log.d("AggregationL1PrintActivity", "Код удален: $barcodeData")
                        } else {
                            // Если код не найден, показываем сообщение и включаем вибрацию
                            messageView.text = "Код отсутствует в контейнере"
                            messageView.setTextColor(Color.RED)
                            vibrate(500)

                            // Возвращаем исходное состояние через 3 секунды
                            messageView.postDelayed({
                                messageView.text = infoTaskMessage
                                messageView.setTextColor(if (infoTaskStatus == 0) Color.rgb(0, 100, 0) else Color.RED)
                            }, 3000)
                            Log.d("AggregationL1PrintActivity", "Код отсутствует: $barcodeData")
                        }
                    }
                } else {
                    Log.d("AggregationL1PrintActivity", "Activity is not active. Ignoring barcode event.")
                }
            }
        }
    }

    /**
     * Проверяет, подключен ли WebSocket.
     */
    private fun isWebSocketConnected(): Boolean {
        return WebSocketManagerInstance.webSocketManager != null &&
                WebSocketManagerInstance.webSocketManager?.isConnected() == true
    }

    /**
     * Показывает сообщение об ошибке пользователю.
     */
    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumedAggL1 = true
        Log.d("AggregationL1PrintActivity", "Activity resumed")
    }

    override fun onPause() {
        super.onPause()
        isActivityResumedAggL1 = false
        Log.d("AggregationL1PrintActivity", "Activity paused")
    }

    /**
     * Обновляет индикатор статуса.
     */
    private fun updateStatusIndicator(color: Int) {
        Log.d("AggregationL1PrintActivity", "Обновление индикатора статуса с цветом: $color")
        statusIndicator.backgroundTintList = ColorStateList.valueOf(color)
    }

    /**
     * Обрабатывает ответ от WebSocket.
     */
    private fun handleWebSocketResponse(response: String) {
        try {
            // Парсим JSON-ответ
            val jsonObject = JSONObject(response)
            val code = jsonObject.optString("code", "")
            val message = jsonObject.optString("message", "")
            val status = jsonObject.optInt("status", -1)
            val values = jsonObject.optString("values", "")

            when (code) {

                "info_task" -> {
                    // Сохраняем сообщение и статус из info_task
                    infoTaskMessage = message
                    infoTaskStatus = status

                    // Обновляем UI в зависимости от статуса
                    messageView.text = infoTaskMessage

                    when (infoTaskStatus) {
                        -1 -> {
                            messageView.setTextColor(Color.RED) // Красный цвет для статуса -1
                            progressText.text = "0/0" // Обновляем progressText
                            progressBar.progress = 0
                            scannedValues.clear()
                            findViewById<TextView>(R.id.productNameTextView).text = ""
                            findViewById<TextView>(R.id.gtinTextView).text = ""
                            findViewById<TextView>(R.id.countAggL1TextView).text = ""
                            findViewById<TextView>(R.id.lotTextView).text = ""

                        }
                        0 -> {
                            messageView.setTextColor(Color.rgb(0, 100, 0)) // Зеленый цвет для статуса 0

                            // Извлекаем массив values
                            val valuesArray = jsonObject.optJSONArray("values")
                            if (valuesArray != null && valuesArray.length() > 0) {
                                // Извлекаем первый элемент массива
                                val firstValue = valuesArray.getJSONObject(0)

                                // Извлекаем данные из первого элемента массива
                                val boxesPerPallet = firstValue.optString("boxesPerPallet", "")
                                val expiration = firstValue.optString("expiration", "")
                                val expirationUnit = firstValue.optString("expirationUnit", "")
                                val gtin = firstValue.optString("gtin", "")
                                val lot = firstValue.optString("lot", "")
                                val productId = firstValue.optString("product_id", "")
                                val productName = firstValue.optString("product_name", "")
                                val taskId = firstValue.optString("task_id", "")
                                val taskName = firstValue.optString("task_name", "")
                                unitPerBox = firstValue.optString("unitPerBox", "0").toInt() // Сохраняем unitPerBox
                                val weight = firstValue.optString("weight", "")
                                val weightUnit = firstValue.optString("weight_unit", "")
                                val countUnit = firstValue.optString("countUnit","0").toInt()
                                val countAggL1 = firstValue.optString("countAggL1","0").toInt()
                                val countAggL2 = firstValue.optString("countAggL2","0").toInt()

                                // Теперь вы можете использовать эти переменные по своему усмотрению
                                Log.d("AggregationL1PrintActivity", "boxesPerPallet: $boxesPerPallet")
                                Log.d("AggregationL1PrintActivity", "expiration: $expiration")
                                Log.d("AggregationL1PrintActivity", "expirationUnit: $expirationUnit")
                                Log.d("AggregationL1PrintActivity", "gtin: $gtin")
                                Log.d("AggregationL1PrintActivity", "lot: $lot")
                                Log.d("AggregationL1PrintActivity", "productId: $productId")
                                Log.d("AggregationL1PrintActivity", "productName: $productName")
                                Log.d("AggregationL1PrintActivity", "taskId: $taskId")
                                Log.d("AggregationL1PrintActivity", "taskName: $taskName")
                                Log.d("AggregationL1PrintActivity", "unitPerBox: $unitPerBox")
                                Log.d("AggregationL1PrintActivity", "weight: $weight")
                                Log.d("AggregationL1PrintActivity", "weightUnit: $weightUnit")
                                Log.d("AggregationL1PrintActivity", "countUnit: $countUnit")
                                Log.d("AggregationL1PrintActivity", "countAggL1: $countAggL1")
                                Log.d("AggregationL1PrintActivity", "countAggL2: $countAggL2")

                                progressBar.max = unitPerBox
                                progressText.text = "${scannedValues.size}/$unitPerBox" // Обновляем progressText
                                // Обновляем TextView с информацией о продукте
                                findViewById<TextView>(R.id.productNameTextView).text = productName
                                findViewById<TextView>(R.id.gtinTextView).text = "GTIN: $gtin"
                                findViewById<TextView>(R.id.lotTextView).text = "Партия: $lot"
                                findViewById<TextView>(R.id.countAggL1TextView).text = "Кол-во коробов: $countAggL1"
                            }
                        }
                        else -> {
                            // Другие статусы, если необходимо
                        }
                    }
                }
                "code_check" -> {
                    when (status) {
                        -1 -> {
                            // Включаем вибрацию и выводим сообщение только если status: -1
                            messageView.text = message
                            messageView.setTextColor(Color.RED) // Устанавливаем красный цвет текста
                            vibrate(500) // Вибрация на 0,5 секунды

                            // Возвращаем исходное состояние через 3 секунды
                            messageView.postDelayed({
                                messageView.text = infoTaskMessage // Восстанавливаем сообщение из info_task
                                messageView.setTextColor(if (infoTaskStatus == 0) Color.rgb(0, 100, 0) else Color.RED) // Восстанавливаем цвет
                            }, 3000) // Задержка 3 секунды
                        }
                        0 -> {
                            if (isAggregationError) {
                                // Если флаг ошибки установлен, блокируем добавление и включаем вибрацию
                                vibrate(500) // Вибрация на 0,5 секунды
                                messageView.text = "Ошибка агрегации. Сканирование заблокировано."
                                messageView.setTextColor(Color.RED) // Устанавливаем красный цвет текста

                                // Возвращаем исходное состояние через 3 секунды
                                messageView.postDelayed({
                                    messageView.text = infoTaskMessage // Восстанавливаем сообщение из info_task
                                    messageView.setTextColor(if (infoTaskStatus == 0) Color.rgb(0, 100, 0) else Color.RED) // Восстанавливаем цвет
                                }, 3000) // Задержка 3 секунды
                            } else {
                                // Если кода нет в массиве и ошибки нет, добавляем его
                                scannedValues.add(values)
                                progressBar.progress = scannedValues.size // Обновляем прогресс-бар
                                progressText.text = "${scannedValues.size}/$unitPerBox" // Обновляем progressText

                                // Проверяем, достигнут ли лимит сканирования
                                if (scannedValues.size == unitPerBox) {
                                    sendAggregationRequest()
                                }
                            }
                        }
                        else -> {
                            // Игнорируем другие статусы
                            Log.d("AggregationL1PrintActivity", "Игнорировано сообщение с status: $status")
                        }
                    }
                }
                "Aggregation" -> {
                    when (status) {
                        0 -> {
                            // Если операция прошла успешно
                            messageView.text = message
                            messageView.setTextColor(Color.rgb(0, 100, 0)) // Зеленый цвет для успешного статуса

                            // Очищаем массив и сбрасываем прогресс-бар
                            scannedValues.clear()
                            progressBar.progress = 0
                            progressText.text = "0/$unitPerBox"
                            isAggregationError = false // Сбрасываем флаг ошибки
                        }
                        -1 -> {
                            // Если операция прошла с ошибкой
                            messageView.text = message
                            messageView.setTextColor(Color.RED) // Красный цвет для ошибки
                            vibrate(500) // Вибрация на 0,5 секунды
                            isAggregationError = true // Устанавливаем флаг ошибки

                            // Возвращаем исходное состояние через 3 секунды
                            messageView.postDelayed({
                                messageView.text = infoTaskMessage // Восстанавливаем сообщение из info_task
                                messageView.setTextColor(if (infoTaskStatus == 0) Color.rgb(0, 100, 0) else Color.RED) // Восстанавливаем цвет
                            }, 3000) // Задержка 3 секунды
                        }
                        else -> {
                            // Игнорируем другие статусы
                            Log.d("AggregationL1PrintActivity", "Игнорировано сообщение с status: $status")
                        }
                    }
                }
                else -> {
                    // Игнорируем сообщения с другим code
                    Log.d("AggregationL1PrintActivity", "Игнорировано сообщение с code: $code")
                }
            }
        } catch (e: Exception) {
            Log.e("AggregationL1PrintActivity", "Ошибка парсинга WebSocket ответа", e)
        }
    }

    /**
     * Отправляет сообщение на WebSocket, если подключение установлено.
     */
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

    /**
     * Включает вибрацию на указанное количество миллисекунд.
     */
    private fun vibrate(milliseconds: Long) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(milliseconds)
        }
    }

    /**
     *  Диалоговое окно
     */
    private fun showClearConfirmationDialog() {
        if (scannedValues.isEmpty()) {
            // Если массив пуст, ничего не делаем
            return
        }
        val alertDialog = AlertDialog.Builder(this)
            .setTitle("Очистка наполнения")
            .setMessage("Вы действительно хотите очистить наполнение?")
            .setPositiveButton("Да") { dialog, _ ->
                // Очистка массива и сброс прогресс-бара
                scannedValues.clear()
                progressBar.progress = 0
                progressText.text = "0/$unitPerBox"
                isAggregationError = false
                dialog.dismiss()
            }
            .setNegativeButton("Нет") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        alertDialog.show()
    }

    private fun showPartialAggregationDialog() {
        val alertDialog = AlertDialog.Builder(this)
            .setTitle("Агрегация")
            .setMessage("Агрегировать неполный короб?")
            .setPositiveButton("Да") { dialog, _ ->
                // Если пользователь согласен, отправляем данные на сервер
                sendAggregationRequest()
                dialog.dismiss()
            }
            .setNegativeButton("Нет") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        alertDialog.show()
    }

    private fun sendAggregationRequest() {
            // Создаем JSON-сообщение
            val jsonMessage = JSONObject().apply {
                put("status", 0)
                put("mode", 1)
                put("code", "Aggregation")
                put("AggLevel",1)
                put("values", JSONArray(scannedValues))
            }

            // Отправляем сообщение на сервер
            sendMessageIfConnected(jsonMessage.toString())
    }

    private fun handleAggregateButtonClick() {
        if (scannedValues.isEmpty()) {
            // Если массив пуст, ничего не делаем
            return
        }

        if (scannedValues.size < unitPerBox) {
            // Если массив неполный, показываем диалоговое окно
            showPartialAggregationDialog()
        } else {
            // Если массив полный, отправляем данные на сервер
            sendAggregationRequest()
        }
    }

}