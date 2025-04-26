package com.example.alpha

// Импорты необходимых классов Android и Java
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

// Основной класс Activity для агрегации коробов на паллету (уровень L2)
class AggregationL2PrintActivity : AppCompatActivity() {
    // UI-элементы
    private lateinit var statusIndicator: View  // Индикатор состояния подключения (круг)
    private lateinit var toolbar: Toolbar      // Панель инструментов вверху экрана
    private lateinit var messageView: TextView // Текстовое поле для отображения сообщений
    private lateinit var progressBar: ProgressBar // Прогресс-бар заполнения паллеты
    private lateinit var progressText: TextView   // Текст с прогрессом (например, "5/24")

    // Переменные состояния
    private var isActivityResumedAggL2: Boolean = false // Активна ли Activity в данный момент
    private var title = "Агрегация на паллету"         // Заголовок экрана
    private var infoTaskMessage: String = ""           // Сообщение о текущей задаче от сервера
    private var infoTaskStatus: Int = -1               // Статус текущей задачи (-1, 0 и т.д.)
    private val scannedValues = mutableListOf<String>() // Список отсканированных кодов коробов
    private var boxesPerPallet: Int = 0 // Максимальное количество коробов на паллету (из info_task)
    private var isAggregationError: Boolean = false // Флаг ошибки агрегации
    private var isAddMode: Boolean = true // Текущий режим работы (добавление/удаление)

    // Основной метод создания Activity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Установка макета для L2 агрегации (отличается от L1)
        setContentView(R.layout.activity_aggregation_l2_print)

        // Инициализация Toolbar (верхней панели)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = title // Установка заголовка

        // Инициализация UI-элементов
        statusIndicator = findViewById(R.id.statusIndicator) // Индикатор подключения
        messageView = findViewById(R.id.messageView)         // Поле для сообщений
        progressBar = findViewById(R.id.progressBar)         // Прогресс-бар
        progressText = findViewById(R.id.progressText)       // Текст прогресса

        // Кнопка очистки списка коробов
        val clearButton: ImageButton = findViewById(R.id.clearButton)
        clearButton.setOnClickListener {
            showClearConfirmationDialog() // Показ диалога подтверждения очистки
        }

        // Кнопка отправки данных на сервер
        val aggregateButton: Button = findViewById(R.id.aggregateButton)
        aggregateButton.setOnClickListener {
            handleAggregateButtonClick() // Обработка нажатия кнопки агрегации
        }

        // Группа переключателей для выбора режима (добавление/удаление)
        val modeRadioGroup: RadioGroup = findViewById(R.id.modeRadioGroup)
        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            isAddMode = when (checkedId) {
                R.id.addModeRadioButton -> true   // Режим добавления коробов
                R.id.removeModeRadioButton -> false // Режим удаления коробов
                else -> true
            }
        }

        // Проверка инициализации WebSocketManager
        if (WebSocketManagerInstance.webSocketManager == null) {
            Log.e("AggregationL2PrintActivity", "WebSocketManager не инициализирован")
            showError("WebSocketManager не инициализирован")
            return
        }

        // Подписка на изменения статуса WebSocket соединения
        WebSocketManagerInstance.status.observe(this, Observer { (message, color) ->
            updateStatusIndicator(color) // Обновление индикатора подключения

            // При восстановлении подключения запрашиваем информацию о задании
            if (message == "Сервер подключен") {
                Log.d("AggregationL2PrintActivity", "WebSocket подключен, отправляем сообщение")
                // Отправляем запрос информации о задании (уровень агрегации 2 - L2)
                sendMessageIfConnected(JsonMessageFormatter.createJsonMessageInformationTask(2, "info_task"))
            }
        })

        // Установка обработчика входящих сообщений от WebSocket
        WebSocketManagerInstance.webSocketManager?.setMessageListener { response ->
            handleWebSocketResponse(response) // Обработка всех ответов от сервера
        }

        // Проверка подключения при запуске Activity
        if (isWebSocketConnected()) {
            Log.d("AggregationL2PrintActivity", "WebSocket подключен, отправляем сообщение")
            // Первоначальный запрос информации о задании
            sendMessageIfConnected(JsonMessageFormatter.createJsonMessageInformationTask(2, "info_task"))
        } else {
            Log.e("AggregationL2PrintActivity", "WebSocket не подключен")
            showError("WebSocket не подключен")
        }

        // Получение экземпляра менеджера штрих-кодов
        val barcodeManager = BarcodeManager.getInstance(this)

        // Подписка на события сканирования штрих-кодов
        barcodeManager.addListener { barcodeType, barcodeData ->
            // Все операции с UI должны выполняться в UI-потоке
            runOnUiThread {
                Log.d("AggregationL2PrintActivity", "New barcode scanned: $barcodeType | $barcodeData")

                // Обрабатываем сканирование только если Activity активна
                if (isActivityResumedAggL2) {
                    if (isAddMode) {
                        // Режим добавления коробов
                        if (scannedValues.contains(barcodeData)) {
                            // Если короб уже был отсканирован
                            messageView.text = "Короб уже был сканирован"
                            messageView.setTextColor(Color.RED)
                            vibrate(500) // Вибрация как сигнал ошибки

                            // Возвращаем исходное сообщение через 3 секунды
                            messageView.postDelayed({
                                messageView.text = infoTaskMessage
                                messageView.setTextColor(if (infoTaskStatus == 0) Color.rgb(0, 100, 0) else Color.RED)
                            }, 3000)
                        } else {
                            // Отправка данных о сканированном коде на сервер для проверки
                            val jsonMessage = JsonMessageFormatter.createJsonMessageTaskCode(
                                typeCode = barcodeType,
                                value = barcodeData,
                                mode = 0,
                                aggLevel = 2,
                                code = "code_check",
                            )
                            if (isWebSocketConnected()) {
                                Log.d("AggregationL2PrintActivity", "WebSocket подключен, отправляем сообщение")
                                sendMessageIfConnected(jsonMessage)
                            } else {
                                Log.e("AggregationL2PrintActivity", "WebSocket не подключен")
                                showError("WebSocket не подключен")
                            }
                            Log.d("AggregationL2PrintActivity", "Sent to WebSocket: $jsonMessage")
                        }
                    } else {
                        // Режим удаления коробов
                        if (scannedValues.isEmpty()) {
                            Log.d("AggregationL2PrintActivity", "scannedValues пуст, игнорируем действие")
                        } else if (scannedValues.contains(barcodeData)) {
                            // Удаляем короб из списка
                            scannedValues.remove(barcodeData)
                            progressBar.progress = scannedValues.size
                            progressText.text = "${scannedValues.size}/$boxesPerPallet"
                        } else {
                            // Короб не найден на паллете
                            messageView.text = "Короб отсутствует на паллете"
                            messageView.setTextColor(Color.RED)
                            vibrate(500)

                            // Возвращаем исходное сообщение через 3 секунды
                            messageView.postDelayed({
                                messageView.text = infoTaskMessage
                                messageView.setTextColor(if (infoTaskStatus == 0) Color.rgb(0, 100, 0) else Color.RED)
                            }, 3000)
                        }
                    }
                }
            }
        }
    }

    // Проверка подключения WebSocket
    private fun isWebSocketConnected(): Boolean {
        return WebSocketManagerInstance.webSocketManager != null &&
                WebSocketManagerInstance.webSocketManager?.isConnected() == true
    }

    // Показать сообщение об ошибке
    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // При возобновлении Activity
    override fun onResume() {
        super.onResume()
        isActivityResumedAggL2 = true // Activity снова активна
    }

    // При приостановке Activity
    override fun onPause() {
        super.onPause()
        isActivityResumedAggL2 = false // Activity больше не активна
    }

    // Обновление индикатора состояния подключения
    private fun updateStatusIndicator(color: Int) {
        statusIndicator.backgroundTintList = ColorStateList.valueOf(color)
    }

    // Обработка ответов от WebSocket сервера
    private fun handleWebSocketResponse(response: String) {
        try {
            // Парсинг JSON ответа
            val jsonObject = JSONObject(response)
            val code = jsonObject.optString("code", "") // Тип сообщения
            val message = jsonObject.optString("message", "") // Текст сообщения
            val status = jsonObject.optInt("status", -1) // Статус операции
            val values = jsonObject.optString("values", "") // Данные

            when (code) {
                "info_task" -> {
                    // Обработка информации о задании
                    infoTaskMessage = message
                    infoTaskStatus = status
                    messageView.text = infoTaskMessage

                    when (infoTaskStatus) {
                        -1 -> {
                            // Ошибка задачи - сбрасываем состояние
                            messageView.setTextColor(Color.RED)
                            progressText.text = "0/0"
                            progressBar.progress = 0
                            scannedValues.clear()
                            // Очищаем все текстовые поля
                            findViewById<TextView>(R.id.productNameTextView).text = ""
                            findViewById<TextView>(R.id.gtinTextView).text = ""
                            findViewById<TextView>(R.id.countAggL2TextView).text = ""
                            findViewById<TextView>(R.id.lotTextView).text = ""
                        }
                        0 -> {
                            // Успешное получение информации о задании
                            messageView.setTextColor(Color.rgb(0, 100, 0))
                            val valuesArray = jsonObject.optJSONArray("values")
                            if (valuesArray != null && valuesArray.length() > 0) {
                                // Получаем данные из первого элемента массива
                                val firstValue = valuesArray.getJSONObject(0)

                                // Извлекаем все необходимые данные о продукции и задании
                                boxesPerPallet = firstValue.optString("boxesPerPallet", "0").toInt()
                                val expiration = firstValue.optString("expiration", "")
                                val expirationUnit = firstValue.optString("expirationUnit", "")
                                val gtin = firstValue.optString("gtin", "")
                                val lot = firstValue.optString("lot", "")
                                val productId = firstValue.optString("product_id", "")
                                val productName = firstValue.optString("product_name", "")
                                val taskId = firstValue.optString("task_id", "")
                                val taskName = firstValue.optString("task_name", "")
                                val unitPerBox = firstValue.optString("unitPerBox", "0").toInt()
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

                                // Обновление UI
                                progressBar.max = boxesPerPallet
                                progressText.text = "${scannedValues.size}/$boxesPerPallet"
                                // Заполнение информации о продукте
                                findViewById<TextView>(R.id.productNameTextView).text = productName
                                findViewById<TextView>(R.id.gtinTextView).text = "GTIN: $gtin"
                                findViewById<TextView>(R.id.lotTextView).text = "Партия: $lot"
                                findViewById<TextView>(R.id.countAggL2TextView).text = "Кол-во паллет: $countAggL2"
                            }
                        }
                    }
                }
                "code_check" -> {
                    // Обработка проверки кода короба
                    when (status) {
                        -1 -> {
                            // Ошибка проверки кода
                            messageView.text = message
                            messageView.setTextColor(Color.RED)
                            vibrate(500)
                            // Возврат к исходному сообщению через 3 секунды
                            messageView.postDelayed({
                                messageView.text = infoTaskMessage
                                messageView.setTextColor(if (infoTaskStatus == 0) Color.rgb(0, 100, 0) else Color.RED)
                            }, 3000)
                        }
                        0 -> {
                            if (isAggregationError) {
                                // Блокировка при ошибке агрегации
                                vibrate(500)
                                messageView.text = "Ошибка агрегации. Сканирование заблокировано."
                                messageView.setTextColor(Color.RED)
                                messageView.postDelayed({
                                    messageView.text = infoTaskMessage
                                    messageView.setTextColor(if (infoTaskStatus == 0) Color.rgb(0, 100, 0) else Color.RED)
                                }, 3000)
                            } else {
                                // Успешная проверка - добавляем короб в список
                                scannedValues.add(values)
                                progressBar.progress = scannedValues.size
                                progressText.text = "${scannedValues.size}/$boxesPerPallet"

                                // Автоматическая отправка при заполнении паллеты
                                if (scannedValues.size == boxesPerPallet) {
                                    sendAggregationRequest()
                                }
                            }
                        }
                    }
                }
                "Aggregation" -> {
                    // Обработка результата агрегации
                    when (status) {
                        0 -> {
                            // Успешная агрегация
                            messageView.text = message
                            messageView.setTextColor(Color.rgb(0, 100, 0))
                            scannedValues.clear() // Очищаем список
                            progressBar.progress = 0
                            progressText.text = "0/$boxesPerPallet"
                            isAggregationError = false // Сбрасываем флаг ошибки
                        }
                        -1 -> {
                            // Ошибка агрегации
                            messageView.text = message
                            messageView.setTextColor(Color.RED)
                            vibrate(500)
                            isAggregationError = true
                            messageView.postDelayed({
                                messageView.text = infoTaskMessage
                                messageView.setTextColor(if (infoTaskStatus == 0) Color.rgb(0, 100, 0) else Color.RED)
                            }, 3000)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AggregationL2PrintActivity", "Ошибка парсинга WebSocket ответа", e)
        }
    }

    // Отправка сообщения через WebSocket если подключение установлено
    private fun sendMessageIfConnected(message: String) {
        if (isWebSocketConnected()) {
            WebSocketManagerInstance.webSocketManager?.send(message)
        }
    }

    // Включение вибрации на указанное время
    private fun vibrate(milliseconds: Long) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(milliseconds)
        }
    }

    // Диалог подтверждения очистки списка коробов
    private fun showClearConfirmationDialog() {
        if (scannedValues.isEmpty()) return // Не показываем если нечего очищать

        AlertDialog.Builder(this)
            .setTitle("Очистка наполнения")
            .setMessage("Вы действительно хотите очистить наполнение паллеты?")
            .setPositiveButton("Да") { dialog, _ ->
                scannedValues.clear()
                progressBar.progress = 0
                progressText.text = "0/$boxesPerPallet"
                isAggregationError = false
                dialog.dismiss()
            }
            .setNegativeButton("Нет") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    // Диалог подтверждения частичной агрегации
    private fun showPartialAggregationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Агрегация")
            .setMessage("Агрегировать неполную паллету?")
            .setPositiveButton("Да") { dialog, _ ->
                sendAggregationRequest()
                dialog.dismiss()
            }
            .setNegativeButton("Нет") { dialog, _ -> dialog.dismiss() }
            .create()
            .show()
    }

    // Отправка запроса агрегации на сервер
    private fun sendAggregationRequest() {
        val jsonMessage = JSONObject().apply {
            put("status", 0)
            put("mode", 1)
            put("code", "Aggregation")
            put("AggLevel",2)
            put("values", JSONArray(scannedValues)) // Отправляем все отсканированные короба
        }
        sendMessageIfConnected(jsonMessage.toString())
    }

    // Обработка нажатия кнопки "Агрегировать"
    private fun handleAggregateButtonClick() {
        if (scannedValues.isEmpty()) return // Нечего отправлять

        if (scannedValues.size < boxesPerPallet) {
            // Показываем диалог подтверждения для неполной паллеты
            showPartialAggregationDialog()
        } else {
            // Отправляем полную паллету
            sendAggregationRequest()
        }
    }
}