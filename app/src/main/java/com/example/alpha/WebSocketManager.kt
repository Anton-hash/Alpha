package com.example.alpha

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.alpha.WebSocketManagerInstance.webSocketManager
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WebSocketManager(
    private val ip: String,
    private val port: String,
    private val onStatusChange: (String, Int) -> Unit
) {
    private var webSocketClient: WebSocketClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val isReconnecting = AtomicBoolean(false)
    val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private val isDisposed = AtomicBoolean(false)

    // Поле для хранения слушателя сообщений
    private var messageListener: ((String) -> Unit)? = null

    /**
     * Устанавливает слушатель для входящих сообщений.
     */
    fun setMessageListener(listener: (String) -> Unit) {
        this.messageListener = listener
    }

    /**
     * Возвращает true, если WebSocket подключен.
     */
    fun isConnected(): Boolean {
        return isConnected.get()
    }

    @Synchronized
    fun connect() {
        if (isDisposed.get() || isConnected.get() || isConnecting.get()) {
            Log.d("WebSocketManager", "Подключение уже выполняется или менеджер уничтожен")
            return
        }
        isConnecting.set(true)

        // Закрываем предыдущее соединение без пометки disposed
        closeExistingConnection()

        val uri = URI("ws://$ip:$port")
        Log.d("WebSocketManager", "Попытка подключения к $uri")
        executor.execute {
            webSocketClient = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.d("WebSocketManager", "Соединение установлено")
                    handler.post {
                        isConnected.set(true)
                        isConnecting.set(false)
                        isReconnecting.set(false)
                        onStatusChange("Сервер подключен", Color.rgb(0, 100, 0))
                    }
                }

                override fun onMessage(message: String?) {
                    Log.d("WebSocketManager", "Получено сообщение: $message")
                    handler.post {
                        // Передаем сообщение слушателю
                        messageListener?.invoke(message ?: "")
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d("WebSocketManager", "Закрытие: $code/$reason (remote: $remote)")
                    handler.post {
                        handleCloseEvent(code, reason, remote)
                    }
                }

                override fun onError(ex: Exception?) {
                    Log.e("WebSocketManager", "Ошибка: ${ex?.message}")
                    handler.post {
                        if (!isDisposed.get()) {
                            onStatusChange("Ошибка подключения", Color.RED)
                        }
                    }
                }
            }

            try {
                webSocketClient?.connect()
            } catch (e: Exception) {
                handler.post {
                    handleConnectError()
                }
            }
        }
    }

    private fun handleCloseEvent(code: Int, reason: String?, remote: Boolean) {
        isConnected.set(false)
        isConnecting.set(false)

        if (!isDisposed.get()) {
            onStatusChange("Сервер не подключен", Color.RED)
            if (remote || code != 1000) {
                scheduleReconnect()
            }
        }
    }

    private fun handleConnectError() {
        isConnected.set(false)
        isConnecting.set(false)
        if (!isDisposed.get()) {
            onStatusChange("Ошибка подключения", Color.RED)
            scheduleReconnect()
        }
    }

    fun disconnect(onComplete: (() -> Unit)? = null) {
        isDisposed.set(true)
        executor.submit {
            closeExistingConnection()
            handler.post {
                onStatusChange("Сервер не подключен", Color.RED)
                onComplete?.invoke()
            }
        }
    }

    private fun closeExistingConnection() {
        webSocketClient?.let {
            if (it.isOpen) {
                it.close(1000)
            }
            webSocketClient = null
        }
    }

    private fun scheduleReconnect() {
        if (isReconnecting.get() || isDisposed.get()) return

        isReconnecting.set(true)
        Log.d("WebSocketManager", "Переподключение через 5 сек...")

        executor.submit {
            Thread.sleep(5000)
            if (isDisposed.get()) return@submit

            handler.post {
                onStatusChange("Переподключение...", Color.YELLOW)
            }

            isReconnecting.set(false)
            connect()
        }
    }

    fun send(message: String) {
        webSocketClient?.send(message)
        Log.d("WebSocketManager", "Отправлено сообщение: $message")
    }
}