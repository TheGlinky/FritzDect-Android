                
package com.theglinky.fritzdect

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

data class FritzDevice(
    val deviceId: String,
    val name: String,
    val isOn: Boolean,
    val power: Int = 0,
    val temperature: Int = 0,
    val timerActive: Boolean = false,
    val timerInfo: String = ""
)

data class TimerConfig(
    val deviceId: String,
    val onMinutes: Int,
    val pauseHours: Int,
    val isRepeat: Boolean
)

class FritzViewModel : ViewModel() {
    private val _devices = MutableStateFlow<List<FritzDevice>>(emptyList())
    val devices: StateFlow<List<FritzDevice>> = _devices

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private var fritzBoxIP = ""
    private var fritzPassword = ""
    private var sessionId = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val timerConfigs = mutableMapOf<String, TimerConfig>()

    fun connectToFritzBox(ip: String, password: String) {
        fritzBoxIP = ip
        fritzPassword = password

        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionId = getSessionId()
                Log.d("FritzViewModel", "Connected with sessionId: $sessionId")

                _isConnected.emit(true)

                loadDevices()
                startPolling()
            } catch (e: Exception) {
                Log.e("FritzViewModel", "Connection failed", e)
                _isConnected.emit(false)
            }
        }
    }

    private suspend fun getSessionId(): String {
        return try {
            val mediaType = "text/xml".toMediaType()
            val requestBody = buildGetSessionXML().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("http://$fritzBoxIP:49000/upnp/control/deviceconfig")
                .post(requestBody)
                .addHeader("SOAPAction", "urn:dslforum-org:service:DeviceConfig:1#GetSessionID")
                .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(body))

            var sessionId = ""
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "SessionID") {
                    sessionId = parser.nextText()
                    break
                }
                eventType = parser.next()
            }

            sessionId
        } catch (e: Exception) {
            Log.e("FritzViewModel", "getSessionId failed", e)
            ""
        }
    }

    private suspend fun loadDevices() {
        try {
            val mediaType = "text/xml".toMediaType()
            val requestBody = buildGetDeviceListXML().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("http://$fritzBoxIP:49000/upnp/control/homeautomation")
                .post(requestBody)
                .addHeader("SOAPAction", "urn:dslforum-org:service:DeviceConfig:1#GetDeviceListPath")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            val devices = parseDevices(body)
            _devices.emit(devices)

            Log.d("FritzViewModel", "Loaded ${devices.size} devices")
        } catch (e: Exception) {
            Log.e("FritzViewModel", "loadDevices failed", e)
        }
    }

    private fun parseDevices(xmlResponse: String): List<FritzDevice> {
        val devices = mutableListOf<FritzDevice>()

        try {
            Log.d("FritzViewModel", "Parsing devices from response")
        } catch (e: Exception) {
            Log.e("FritzViewModel", "Parse error", e)
        }

        return devices
    }

    suspend fun toggleDevice(deviceId: String, turnOn: Boolean) {
        try {
            val mediaType = "text/xml".toMediaType()
            val requestBody = buildToggleXML(deviceId, turnOn).toRequestBody(mediaType)

            val request = Request.Builder()
                .url("http://$fritzBoxIP:49000/upnp/control/homeautomation")
                .post(requestBody)
                .addHeader("SOAPAction", "urn:dslforum-org:service:DeviceConfig:1#SetSwitch")
                .build()

            val response = httpClient.newCall(request).execute()
            Log.d("FritzViewModel", "Toggle response: ${response.code}")

            updateDeviceState(deviceId, turnOn)
        } catch (e: Exception) {
            Log.e("FritzViewModel", "toggleDevice failed", e)
        }
    }

    suspend fun setTimer(deviceId: String, onMinutes: Int, pauseHours: Int, isRepeat: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                timerConfigs[deviceId] = TimerConfig(deviceId, onMinutes, pauseHours, isRepeat)

                toggleDevice(deviceId, true)

                val onSeconds = onMinutes * 60L
                val pauseSeconds = pauseHours * 3600L

                Log.d("FritzViewModel", "Timer set: ON for $onMinutes min, PAUSE for $pauseHours hours")

                if (isRepeat) {
                    while (true) {
                        kotlinx.coroutines.delay(onSeconds * 1000)
                        toggleDevice(deviceId, false)

                        kotlinx.coroutines.delay(pauseSeconds * 1000)
                        toggleDevice(deviceId, true)
                    }
                } else {
                    kotlinx.coroutines.delay(onSeconds * 1000)
                    toggleDevice(deviceId, false)
                }
            } catch (e: Exception) {
                Log.e("FritzViewModel", "setTimer failed", e)
            }
        }
    }

    private suspend fun updateDeviceState(deviceId: String, isOn: Boolean) {
        val currentDevices = _devices.value.toMutableList()
        val index = currentDevices.indexOfFirst { it.deviceId == deviceId }
        if (index >= 0) {
            currentDevices[index] = currentDevices[index].copy(isOn = isOn)
            _devices.emit(currentDevices)
        }
    }

    private fun startPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    loadDevices()
                    kotlinx.coroutines.delay(2000)
                } catch (e: Exception) {
                    Log.e("FritzViewModel", "Polling error", e)
                }
            }
        }
    }

    private fun buildGetSessionXML(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
    <s:Body>
        <u:GetSessionID xmlns:u="urn:dslforum-org:service:DeviceConfig:1"/>
    </s:Body>
</s:Envelope>"""
    }

    private fun buildGetDeviceListXML(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
    <s:Body>
        <u:GetDeviceListPath xmlns:u="urn:dslforum-org:service:DeviceConfig:1">
            <NewSessionID>$sessionId</NewSessionID>
        </u:GetDeviceListPath>
    </s:Body>
</s:Envelope>"""
    }

    private fun buildToggleXML(deviceId: String, turnOn: Boolean): String {
        val switchState = if (turnOn) "1" else "0"
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
    <s:Body>
        <u:SetSwitch xmlns:u="urn:dslforum-org:service:DeviceConfig:1">
            <NewSessionID>$sessionId</NewSessionID>
            <NewDeviceId>$deviceId</NewDeviceId>
            <NewSwitchState>$switchState</NewSwitchState>
        </u:SetSwitch>
    </s:Body>
</s:Envelope>"""
    }
}
