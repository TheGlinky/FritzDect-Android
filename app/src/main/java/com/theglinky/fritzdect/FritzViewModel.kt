package com.theglinky.fritzdect

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Credentials
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
    val ain: String,
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

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage

    private var fritzBoxIP = ""
    private var fritzUser = ""
    private var fritzPassword = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val timerConfigs = mutableMapOf<String, TimerConfig>()
    private var pollingJob: kotlinx.coroutines.Job? = null

    fun connectToFritzBox(ip: String, password: String, user: String = "") {
        fritzBoxIP = ip
        fritzPassword = password
        fritzUser = user

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _errorMessage.emit("")
                // Test connection by fetching device list
                val devices = fetchDeviceList()
                _devices.emit(devices)
                _isConnected.emit(true)

                Log.d("FritzViewModel", "Connected, found ${devices.size} devices")

                startPolling()
            } catch (e: Exception) {
                Log.e("FritzViewModel", "Connection failed", e)
                _isConnected.emit(false)
                _errorMessage.emit(e.message ?: "Connection failed")
            }
        }
    }

    /**
     * Uses the FRITZ!Box HomeAutomation Switch API (webservices/homeautoswitch.lua)
     * This is the simple HTTP API AVM provides - much easier than full TR-064 SOAP
     */
    private fun buildAuthHeader(): String? {
        return if (fritzUser.isNotEmpty() && fritzPassword.isNotEmpty()) {
            Credentials.basic(fritzUser, fritzPassword)
        } else null
    }

    private suspend fun fetchDeviceList(): List<FritzDevice> {
        val url = "http://$fritzBoxIP/webservices/homeautoswitch.lua?switchcmd=getdevicelistinfos"

        val requestBuilder = Request.Builder().url(url)
        buildAuthHeader()?.let { requestBuilder.addHeader("Authorization", it) }

        val response = httpClient.newCall(requestBuilder.build()).execute()

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: Konnte FRITZ!Box nicht erreichen. Prüfe IP und ob 'Anmeldung im Heimnetz ohne Kennwort' bzw. dein FRITZ!Box-Passwort korrekt ist.")
        }

        val xml = response.body?.string() ?: throw Exception("Leere Antwort von FRITZ!Box")
        return parseDeviceListXml(xml)
    }

    private fun parseDeviceListXml(xml: String): List<FritzDevice> {
        val devices = mutableListOf<FritzDevice>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))

        var eventType = parser.eventType
        var currentAin = ""
        var currentName = ""
        var currentSwitchState = false
        var currentPower = 0
        var currentTemp = 0
        var insideDevice = false
        var insideName = false
        var insideSwitchState = false
        var insidePower = false
        var insideTemperature = false
        var insideSwitch = false
        var insidePowermeter = false
        var insideTemperatureBlock = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "device" -> {
                            insideDevice = true
                            currentAin = parser.getAttributeValue(null, "identifier") ?: ""
                            currentName = ""
                            currentSwitchState = false
                            currentPower = 0
                            currentTemp = 0
                        }
                        "name" -> if (insideDevice) insideName = true
                        "switch" -> insideSwitch = true
                        "state" -> if (insideSwitch) insideSwitchState = true
                        "powermeter" -> insidePowermeter = true
                        "power" -> if (insidePowermeter) insidePower = true
                        "temperature" -> insideTemperatureBlock = true
                        "celsius" -> if (insideTemperatureBlock) insideTemperature = true
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        when {
                            insideName -> currentName = text
                            insideSwitchState -> currentSwitchState = text == "1"
                            insidePower -> currentPower = (text.toIntOrNull() ?: 0) / 1000 // mW -> W
                            insideTemperature -> currentTemp = (text.toIntOrNull() ?: 0) / 10 // 0.1°C -> °C
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "device" -> {
                            if (insideDevice && currentAin.isNotEmpty()) {
                                devices.add(
                                    FritzDevice(
                                        deviceId = currentAin,
                                        ain = currentAin,
                                        name = currentName.ifEmpty { "Steckdose $currentAin" },
                                        isOn = currentSwitchState,
                                        power = currentPower,
                                        temperature = currentTemp
                                    )
                                )
                            }
                            insideDevice = false
                        }
                        "name" -> insideName = false
                        "switch" -> insideSwitch = false
                        "state" -> insideSwitchState = false
                        "powermeter" -> insidePowermeter = false
                        "power" -> insidePower = false
                        "temperature" -> insideTemperatureBlock = false
                        "celsius" -> insideTemperature = false
                    }
                }
            }
            eventType = parser.next()
        }

        return devices
    }

    suspend fun toggleDevice(ain: String, turnOn: Boolean) {
        try {
            val cmd = if (turnOn) "setswitchon" else "setswitchoff"
            val url = "http://$fritzBoxIP/webservices/homeautoswitch.lua?switchcmd=$cmd&ain=$ain"

            val requestBuilder = Request.Builder().url(url)
            buildAuthHeader()?.let { requestBuilder.addHeader("Authorization", it) }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            Log.d("FritzViewModel", "Toggle response: ${response.code}")

            // Instant UI feedback
            updateDeviceState(ain, turnOn)
        } catch (e: Exception) {
            Log.e("FritzViewModel", "toggleDevice failed", e)
            _errorMessage.emit("Schalten fehlgeschlagen: ${e.message}")
        }
    }

    fun setTimer(ain: String, onMinutes: Int, pauseHours: Int, isRepeat: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                timerConfigs[ain] = TimerConfig(ain, onMinutes, pauseHours, isRepeat)

                toggleDevice(ain, true)

                val onMillis = onMinutes * 60L * 1000L
                val pauseMillis = pauseHours * 3600L * 1000L

                Log.d("FritzViewModel", "Timer set: ON for $onMinutes min, PAUSE for $pauseHours hours, repeat=$isRepeat")

                if (isRepeat) {
                    while (timerConfigs[ain] != null) {
                        kotlinx.coroutines.delay(onMillis)
                        if (timerConfigs[ain] == null) break
                        toggleDevice(ain, false)

                        kotlinx.coroutines.delay(pauseMillis)
                        if (timerConfigs[ain] == null) break
                        toggleDevice(ain, true)
                    }
                } else {
                    kotlinx.coroutines.delay(onMillis)
                    toggleDevice(ain, false)
                    timerConfigs.remove(ain)
                }
            } catch (e: Exception) {
                Log.e("FritzViewModel", "setTimer failed", e)
            }
        }
    }

    fun cancelTimer(ain: String) {
        timerConfigs.remove(ain)
    }

    private suspend fun updateDeviceState(ain: String, isOn: Boolean) {
        val currentDevices = _devices.value.toMutableList()
        val index = currentDevices.indexOfFirst { it.ain == ain }
        if (index >= 0) {
            currentDevices[index] = currentDevices[index].copy(isOn = isOn)
            _devices.emit(currentDevices)
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    kotlinx.coroutines.delay(3000)
                    val devices = fetchDeviceList()
                    _devices.emit(devices)
                } catch (e: Exception) {
                    Log.e("FritzViewModel", "Polling error", e)
                    // Don't kill polling on transient errors
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}
