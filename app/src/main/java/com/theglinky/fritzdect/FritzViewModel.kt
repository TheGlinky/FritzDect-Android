package com.theglinky.fritzdect

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

enum class LogLevel { INFO, SUCCESS, ERROR, WARNING }

data class LogEntry(
    val timestamp: String,
    val message: String,
    val level: LogLevel
)

class FritzViewModel : ViewModel() {
    private val _devices = MutableStateFlow<List<FritzDevice>>(emptyList())
    val devices: StateFlow<List<FritzDevice>> = _devices

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private var fritzBoxIP = ""
    private var fritzUser = ""
    private var fritzPassword = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val timerConfigs = mutableMapOf<String, TimerConfig>()
    private var pollingJob: kotlinx.coroutines.Job? = null

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun log(message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(timestamp(), message, level)
        _logs.value = _logs.value + entry
        when (level) {
            LogLevel.ERROR -> Log.e("FritzViewModel", message)
            LogLevel.WARNING -> Log.w("FritzViewModel", message)
            else -> Log.d("FritzViewModel", message)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun connectToFritzBox(ip: String, password: String, user: String = "") {
        fritzBoxIP = ip.trim()
        fritzPassword = password
        fritzUser = user.trim()

        viewModelScope.launch(Dispatchers.IO) {
            _errorMessage.emit("")
            log("Verbindungsversuch zu $fritzBoxIP gestartet...", LogLevel.INFO)

            if (fritzUser.isEmpty() || fritzPassword.isEmpty()) {
                log("Benutzername oder Passwort fehlt.", LogLevel.WARNING)
            }

            try {
                log("Sende Anfrage: getdevicelistinfos", LogLevel.INFO)
                val devices = fetchDeviceList()

                log("Antwort erhalten, ${devices.size} Gerät(e) gefunden", LogLevel.SUCCESS)
                _devices.emit(devices)
                _isConnected.emit(true)

                if (devices.isEmpty()) {
                    log("Verbindung ok, aber keine FRITZ!DECT Steckdosen in der FRITZ!Box gefunden.", LogLevel.WARNING)
                } else {
                    devices.forEach { d ->
                        log("Gerät erkannt: ${d.name} (${d.ain}) - ${if (d.isOn) "AN" else "AUS"}", LogLevel.INFO)
                    }
                }

                log("Verbindung erfolgreich hergestellt ✓", LogLevel.SUCCESS)
                startPolling()
            } catch (e: Exception) {
                log("FEHLER: ${e.javaClass.simpleName}: ${e.message}", LogLevel.ERROR)
                _isConnected.emit(false)
                _errorMessage.emit(e.message ?: "Connection failed")
            }
        }
    }

    private fun buildAuthHeader(): String? {
        return if (fritzUser.isNotEmpty() && fritzPassword.isNotEmpty()) {
            Credentials.basic(fritzUser, fritzPassword)
        } else null
    }

    private suspend fun fetchDeviceList(): List<FritzDevice> {
        val url = "http://$fritzBoxIP/webservices/homeautoswitch.lua?switchcmd=getdevicelistinfos"

        val requestBuilder = Request.Builder().url(url)
        buildAuthHeader()?.let { requestBuilder.addHeader("Authorization", it) }

        val response = try {
            httpClient.newCall(requestBuilder.build()).execute()
        } catch (e: java.net.ConnectException) {
            throw Exception("Keine Verbindung zu $fritzBoxIP möglich. Ist die IP korrekt und bist du im selben WLAN/VPN?")
        } catch (e: java.net.UnknownHostException) {
            throw Exception("Adresse $fritzBoxIP nicht auflösbar. IP-Format prüfen (z.B. 192.168.178.1).")
        } catch (e: java.net.SocketTimeoutException) {
            throw Exception("Zeitüberschreitung - FRITZ!Box antwortet nicht unter $fritzBoxIP.")
        }

        log("HTTP Status: ${response.code}", if (response.isSuccessful) LogLevel.INFO else LogLevel.ERROR)

        if (response.code == 403) {
            throw Exception("HTTP 403: Zugriff verweigert. Benutzername/Passwort falsch oder Benutzer hat keine Smart-Home-Berechtigung.")
        }
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: Unerwartete Antwort von der FRITZ!Box.")
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
                            insidePower -> currentPower = (text.toIntOrNull() ?: 0) / 1000
                            insideTemperature -> currentTemp = (text.toIntOrNull() ?: 0) / 10
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
            log("Schalte $ain -> ${if (turnOn) "AN" else "AUS"}...", LogLevel.INFO)

            val url = "http://$fritzBoxIP/webservices/homeautoswitch.lua?switchcmd=$cmd&ain=$ain"

            val requestBuilder = Request.Builder().url(url)
            buildAuthHeader()?.let { requestBuilder.addHeader("Authorization", it) }

            val response = httpClient.newCall(requestBuilder.build()).execute()

            if (response.isSuccessful) {
                log("Schalten erfolgreich (${response.code}) ✓", LogLevel.SUCCESS)
                updateDeviceState(ain, turnOn)
            } else {
                log("Schalten fehlgeschlagen: HTTP ${response.code}", LogLevel.ERROR)
                _errorMessage.emit("Schalten fehlgeschlagen: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            log("FEHLER beim Schalten: ${e.message}", LogLevel.ERROR)
            _errorMessage.emit("Schalten fehlgeschlagen: ${e.message}")
        }
    }

    fun setTimer(ain: String, onMinutes: Int, pauseHours: Int, isRepeat: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                timerConfigs[ain] = TimerConfig(ain, onMinutes, pauseHours, isRepeat)
                log("Timer gestartet für $ain: ${onMinutes}min AN, ${pauseHours}h Pause, repeat=$isRepeat", LogLevel.INFO)

                toggleDevice(ain, true)

                val onMillis = onMinutes * 60L * 1000L
                val pauseMillis = pauseHours * 3600L * 1000L

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
                    log("Timer für $ain abgeschlossen ✓", LogLevel.SUCCESS)
                }
            } catch (e: Exception) {
                log("FEHLER im Timer: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    fun cancelTimer(ain: String) {
        timerConfigs.remove(ain)
        log("Timer für $ain abgebrochen", LogLevel.WARNING)
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
                    log("Polling-Fehler: ${e.message}", LogLevel.WARNING)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}
