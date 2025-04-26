package com.example.alpha

import android.content.Context
import com.honeywell.aidc.AidcManager
import com.honeywell.aidc.BarcodeFailureEvent
import com.honeywell.aidc.BarcodeReadEvent
import com.honeywell.aidc.BarcodeReader
import com.honeywell.aidc.TriggerStateChangeEvent

class BarcodeManager private constructor(context: Context) : BarcodeReader.BarcodeListener, BarcodeReader.TriggerListener {

    private var barcodeReader: BarcodeReader? = null
    private var lastBarcode: Pair<String, String>? = null // Храним последний отсканированный штрих-код

    companion object {
        @Volatile
        private var instance: BarcodeManager? = null

        fun getInstance(context: Context): BarcodeManager {
            return instance ?: synchronized(this) {
                instance ?: BarcodeManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        initializeScanner(context)
    }

    private fun initializeScanner(context: Context) {
        AidcManager.create(context, object : AidcManager.CreatedCallback {
            override fun onCreated(manager: AidcManager?) {
                if (manager != null) {
                    barcodeReader = manager.createBarcodeReader()
                    barcodeReader?.addBarcodeListener(this@BarcodeManager)
                    barcodeReader?.addTriggerListener(this@BarcodeManager)
                    barcodeReader?.setProperty(
                        BarcodeReader.PROPERTY_TRIGGER_CONTROL_MODE,
                        BarcodeReader.TRIGGER_CONTROL_MODE_AUTO_CONTROL
                    )
                    barcodeReader?.claim()
                }
            }
        })
    }

    override fun onBarcodeEvent(event: BarcodeReadEvent) {
        val barcodeData = event.barcodeData
        val barcodeType = event.aimId

        // Перезаписываем последний отсканированный штрих-код
        lastBarcode = Pair(barcodeType, barcodeData)

        // Уведомляем подписчиков о новых данных
        notifyListeners(barcodeType, barcodeData)
    }

    override fun onFailureEvent(event: BarcodeFailureEvent?) {
        // Обработка ошибок сканирования
    }

    override fun onTriggerEvent(event: TriggerStateChangeEvent?) {
        // Обработка событий триггера
    }

    // Метод для получения последнего отсканированного штрих-кода
    fun getLastBarcode(): Pair<String, String>? {
        return lastBarcode
    }

    private val listeners = mutableListOf<(String, String) -> Unit>()

    fun addListener(listener: (String, String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String, String) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(barcodeType: String, barcodeData: String) {
        listeners.forEach { it(barcodeType, barcodeData) }
    }

    fun release() {
        barcodeReader?.removeBarcodeListener(this)
        barcodeReader?.release()
    }
}