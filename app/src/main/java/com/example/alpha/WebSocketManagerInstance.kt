package com.example.alpha

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object WebSocketManagerInstance {
    private val _status = MutableLiveData<Pair<String, Int>>()  // Храним статус WebSocket
    val status: LiveData<Pair<String, Int>> get() = _status

    var webSocketManager: WebSocketManager? = null

    fun initWebSocket(ip: String, port: String) {
        if (webSocketManager == null) {
            webSocketManager = WebSocketManager(ip, port) { message, color ->
                _status.postValue(Pair(message, color))  // Обновляем статус для всех экранов
            }
            webSocketManager?.connect()
        }
    }
}

