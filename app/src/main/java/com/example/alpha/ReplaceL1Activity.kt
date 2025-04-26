// ReplaceL1Activity — режим замены вложений в коробе
package com.example.alpha

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Observer
import org.json.JSONArray
import org.json.JSONObject

class ReplaceL1Activity : AppCompatActivity() {
    // UI компоненты
    private lateinit var toolbar: Toolbar
    private lateinit var statusIndicator: View
    private lateinit var messageView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var btnSave: Button
    private lateinit var btnCancel: Button
    private var clearMessageRunnable: Runnable? = null
    private val handler = android.os.Handler()

    // Состояние текущего режима
    private var isAddMode = false
    private var isActivityResumed = false

    // Данные по коробу
    private var unitPerBox = 0
    private val currentItems = mutableListOf<String>()
    private var currentCodeValue: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_replace_l1)
        Log.d("ReplaceL1Activity", "onCreate: активити создана")

        // Инициализация UI
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Замена в коробе"

        statusIndicator = findViewById(R.id.statusIndicator)
        messageView = findViewById(R.id.messageView)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        modeRadioGroup = findViewById(R.id.modeRadioGroup)
        btnSave = findViewById(R.id.btnSave)
        btnCancel = findViewById(R.id.btnCancel)

        // Слушатель переключения режима (добавление / удаление)
        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            isAddMode = (checkedId == R.id.addModeRadioButton)
        }

        // Обработка кнопок Сохранить и Отмена
        btnCancel.setOnClickListener { showCancelConfirmation() }
        btnSave.setOnClickListener { showSaveConfirmation() }

        // Подписка на изменение статуса WebSocket
        WebSocketManagerInstance.status.observe(this, Observer { (message, color) ->
            updateStatusIndicator(color)
        })

        // Подписка на сообщения от WebSocket
        WebSocketManagerInstance.webSocketManager?.setMessageListener {
            runOnUiThread { handleWebSocketResponse(it) }
        }

        // Обработка сканирования штрих-кода
        BarcodeManager.getInstance(this).addListener { type, value ->
            runOnUiThread {
                if (isActivityResumed) handleBarcodeScanned(type, value)
            }
        }

        // Начальное сообщение
        showInitialMessage()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        Log.d("ReplaceL1Activity", "onResume: активити возобновлена")
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
    }

    // Обновление цвета индикатора WebSocket
    private fun updateStatusIndicator(color: Int) {
        statusIndicator.backgroundTintList = ColorStateList.valueOf(color)
    }

    // Показывает начальное приглашение к сканированию
    private fun showInitialMessage() {
        messageView.text = "Просканируйте код короба или вложения"
        messageView.setTextColor(Color.DKGRAY)
        progressText.text = "0/0"
        progressBar.max = 0
        progressBar.progress = 0
        currentItems.clear()
        currentCodeValue = null
        setRadioButtonsEnabled(false, false)
    }

    // Обработка сканирования кода
    private fun handleBarcodeScanned(type: String, value: String) {
        if (currentCodeValue == null) {
            // Сначала проверяем короб — отправляем code_check_for_replace
            val msg = JsonMessageFormatter.createJsonMessageTaskCode(
                typeCode = type,
                value = value,
                mode = 0,
                aggLevel = 11,
                code = "code_check_for_desagg"
            )
            sendMessageIfConnected(msg)
        } else {
            // Работаем с вложениями в зависимости от режима
            if (isAddMode) {
                if (currentItems.contains(value)) {
                    showMessage("Код уже содержится в коробе", Color.RED, true)
                    return
                }
                if (currentItems.size >= unitPerBox) {
                    showMessage("Короб заполнен", Color.RED, true)
                    return
                }
                // Проверяем код перед добавлением — code_check с aggLevel = 1
                val msg = JsonMessageFormatter.createJsonMessageTaskCode(
                    typeCode = type,
                    value = value,
                    mode = 0,
                    aggLevel = 1,
                    code = "code_check"
                )
                sendMessageIfConnected(msg)
            } else {
                if (!currentItems.contains(value)) {
                    showMessage("Код не является вложением", Color.RED, true)
                } else {
                    currentItems.remove(value)
                    updateProgress()
                    validateRadioAvailability() // 🔧 добавлена строка, чтобы заново проверить доступность режима добавления
                }
            }
        }
    }

    // Обработка ответов от WebSocket
    private fun handleWebSocketResponse(response: String) {
        try {
            val json = JSONObject(response)
            val code = json.optString("code")
            val status = json.optInt("status")
            val message = json.optString("message")

            when (code) {
                "code_check_for_desagg" -> {
                    if (status == -1) {
                        showMessage(message, Color.RED, true)
                    } else {
                        val values = json.optJSONArray("values")?.getJSONObject(0) ?: return
                        currentCodeValue = values.optString("code_value")
                        unitPerBox = values.optInt("unitPerBox")
                        val codes = values.optJSONArray("agg_codes")

                        currentItems.clear()
                        if (codes != null) {
                            for (i in 0 until codes.length()) {
                                currentItems.add(codes.getString(i))
                            }
                        }
                        updateProgress()
                        showMessage(message, Color.rgb(0, 100, 0), true)
                        setRadioButtonsEnabled(true, true)
                        validateRadioAvailability()
                    }
                }
                "code_check" -> {
                    if (status == 0) {
                        val value = json.optString("values")
                        currentItems.add(value)
                        updateProgress()
                        validateRadioAvailability()
                    } else {
                        showMessage(message, Color.RED, true)
                    }
                }
                "Replace" -> {
                    if (status == 0) {
                        showMessage("Изменения успешно сохранены", Color.rgb(0, 100, 0), true)
                        showInitialMessage()
                    } else {
                        showMessage(message, Color.RED, true)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ReplaceL1Activity", "Ошибка парсинга JSON", e)
        }
    }

    // Обновление прогресс-бара
    private fun updateProgress() {
        progressBar.max = unitPerBox
        progressBar.progress = currentItems.size
        progressText.text = "${currentItems.size}/$unitPerBox"
    }

    // Показ сообщения пользователю с вибрацией
    private fun showMessage(text: String, color: Int, autoClear: Boolean = true) {
        messageView.text = text
        messageView.setTextColor(color)
        vibrate(300)

        // Автоматически очищаем сообщение через 3 секунды, если включено
        clearMessageRunnable?.let { handler.removeCallbacks(it) }
        if (autoClear) {
            clearMessageRunnable = Runnable {
                messageView.text = ""
            }.also { handler.postDelayed(it, 3000) }
        }
    }

    // Вибрация устройства
    private fun vibrate(ms: Long) {
        val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vib.vibrate(ms)
        }
    }

    // Включение/отключение переключателей режима
    private fun setRadioButtonsEnabled(addEnabled: Boolean, removeEnabled: Boolean) {
        findViewById<RadioButton>(R.id.addModeRadioButton).isEnabled = addEnabled
        findViewById<RadioButton>(R.id.removeModeRadioButton).isEnabled = removeEnabled
    }

    // Проверка, можно ли добавлять/удалять вложения в текущем состоянии
    private fun validateRadioAvailability() {
        val addButton = findViewById<RadioButton>(R.id.addModeRadioButton)
        val removeButton = findViewById<RadioButton>(R.id.removeModeRadioButton)

        addButton.isEnabled = currentItems.size < unitPerBox
        removeButton.isEnabled = currentItems.isNotEmpty()

        if (!addButton.isEnabled && isAddMode) removeButton.isChecked = true
        if (!removeButton.isEnabled && !isAddMode) addButton.isChecked = true
    }

    // Подтверждение отмены изменений
    private fun showCancelConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Отмена")
            .setMessage("Все изменения в коробе будут удалены. Вы уверены?")
            .setPositiveButton("Да") { d, _ -> showInitialMessage(); d.dismiss() }
            .setNegativeButton("Нет", null)
            .show()
    }

    // Подтверждение сохранения изменений
    private fun showSaveConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Сохранение")
            .setMessage("Вы уверены что хотите сохранить изменения в коробе?")
            .setPositiveButton("Да") { d, _ -> sendReplaceRequest(); d.dismiss() }
            .setNegativeButton("Нет", null)
            .show()
    }

    // Отправка итогового запроса Replace
    private fun sendReplaceRequest() {
        val json = JSONObject().apply {
            put("code", "Replace")
            put("status", 0)
            put("values", JSONArray(currentItems))
            put("code_value", currentCodeValue ?: "")
        }
        sendMessageIfConnected(json.toString())
    }

    // Отправка сообщения через WebSocket
    private fun sendMessageIfConnected(msg: String) {
        if (WebSocketManagerInstance.webSocketManager?.isConnected() == true) {
            WebSocketManagerInstance.webSocketManager?.send(msg)
            Log.d("ReplaceL1Activity", "Отправлено сообщение: $msg")
        } else {
            showMessage("WebSocket не подключен", Color.RED, true)
        }
    }
}
