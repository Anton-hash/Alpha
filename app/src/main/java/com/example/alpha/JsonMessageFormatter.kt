package com.example.alpha

import android.util.Log
import org.json.JSONObject

class JsonMessageFormatter {

    companion object {

        // Data-класс для хранения данных из JSON-ответа
        data class WebSocketResponse(
            val status: Int,
            val message: String,
            val code: String,
            val values: List<JSONObject>?
        )

        fun createJsonMessageInformation(typeCode: String, value: String, mode: Int = 0, code: String = "info"): String {
            return JSONObject().apply {
                put("type_code", typeCode)
                put("value", value)
                put("mode", mode)
                put("code", code)
            }.toString()
        }

        fun createJsonMessageTaskCode(
            typeCode: String,
            value: String,
            mode: Int,
            code: String,
            aggLevel: Int
        ): String {
            return JSONObject().apply {
                put("type_code", typeCode)
                put("value", value)
                put("mode", mode)
                put("AggLevel", aggLevel)
                put("code", code)
            }.toString()
        }

        fun createJsonMessageInformationTask(mode: Int, code: String): String {
            return JSONObject().apply {
                put("mode", mode)
                put("code", code)
            }.toString()
        }

        fun parseAndValidateJson(jsonString: String): JSONObject? {
            return try {
                val jsonObject = JSONObject(jsonString)
                if (jsonObject.optString("code") == "info") {
                    Log.i("JsonMessageFormatter", jsonObject.toString(),)
                    jsonObject
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("JsonMessageFormatter", "Error parsing JSON", e)
                null
            }
        }

        fun parseWebSocketResponse(jsonString: String): WebSocketResponse? {
            return try {
                val jsonObject = JSONObject(jsonString)
                val status = jsonObject.optInt("status", -1)
                val message = jsonObject.optString("message", "")
                val code = jsonObject.optString("code", "")

                // Парсинг массива values
                val values = jsonObject.optJSONArray("values")?.let { array ->
                    mutableListOf<JSONObject>().apply {
                        for (i in 0 until array.length()) {
                            val item = array.optJSONObject(i)
                            if (item != null) {
                                add(item)
                            }
                        }
                    }
                }

                WebSocketResponse(status, message, code, values)
            } catch (e: Exception) {
                Log.e("JsonMessageFormatter", "Error parsing WebSocket response", e)
                null
            }
        }
    }
}