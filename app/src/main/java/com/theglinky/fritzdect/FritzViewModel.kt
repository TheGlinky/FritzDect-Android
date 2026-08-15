package com.theglinky.fritzdect

import android.content.Context
import android.content.SharedPreferences
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
    val ain: String,
    val name: String,
    val isOn: Boolean,
    val power: Int = 0,
    val temperature: Int = 0
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

    private val _hasSavedCredentials = MutableStateFlow(false)
    val hasSavedCredentials: StateFlow<Boolean> = _hasSavedCredentials

    private var fritzBoxIP = ""
    private var fritzUser = ""
    private var fritzPassword = ""

    private var prefs: SharedPreferences? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var pollingJob: kotlinx.coroutines.Job? = null

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun log(message: String, level: LogLevel = LogLevel.INFO) {
        _logs.value = _logs.value + LogEntry(timestamp(), message, level)
        when (level) {
            LogLevel.ERROR -> Log.e("FritzViewModel", message)
            LogLevel.WARNING -> Log.w("FritzViewModel", message)
            else -> Log.d("FritzViewModel", message)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun initAndAutoConnect(context: Context) {
        prefs = context.getSharedPreferences("fritz_prefs", Context.MODE_PRIVATE)

        val savedIp = prefs?.getString("ip", "") ?: ""
        val savedUser = prefs?.getString("user", "") ?: ""
        val savedPassword = prefs?.getString("password", "") ?: ""

        if (savedIp.isNotEmpty() && savedUser.isNotEmpty() && savedPassword.isNotEmpty()) {
            _hasSavedCredentials.value = true
            log("Gespeicherte Zugangsdaten gefunden, verbinde automatisch...", LogLevel.INFO)
            connectToFritzBox(savedIp, savedPassword, savedUser, saveOnSuccess = false)
        }
    }

    private fun saveCredentials(ip: String, user: String, password: String) {
        prefs?.edit()
            ?.putString("ip", ip)
            ?.putString("user", user)
            ?.putString("password", password)
            ?.apply()
        _hasSavedCredentials.value = true
        log("Zugangsdaten gespeichert", LogLevel.INFO)
    }

    fun forgetCredentials() {
        prefs?.edit()?.clear()?.apply()
        _hasSavedCredentials.value = false
        _isConnected.value = false
        pollingJob?.cancel()
        log("Gespeicherte Zugangsdaten geloescht", LogLevel.WARNING)
    }

    fun connectToFritzBox(ip: String, password: String, user: String = "", saveOnSuccess: Boolean = true) {
        fritzBoxIP = ip.trim()
        fritzPassword = password
        fritzUser = user.trim()

        viewModelScope.launch(Dispatchers.IO) {
            _errorMessage.emit("")
            log("Verbinde zu $fritzBoxIP...", LogLevel.INFO)

            try {
                val devices = fetchDeviceList()
                log("${devices.size} Geraet(e) gefunden", LogLevel.SUCCESS)
                _devices.emit(devices)
                _isConnected.emit(true)

                if (saveOnSuccess) {
                    saveCredentials(fritzBoxIP, fritzUser, fritzPassword)
                }

                startPolling()
            } catch (e: Exception) {
                log("Fehler: ${e.message}", LogLevel.ERROR)
                _isConnected.emit(false)
                _errorMessage.emit(e.message ?: "Verbindung fehlgeschlagen")
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
            throw Exception("Keine Verbindung zu $fritzBoxIP moeglich. IP korrekt und im gleichen WLAN?")
        } catch (e: java.net.UnknownHostException) {
            throw Exception("Adresse $fritzBoxIP nicht aufloesbar.")
        } catch (e: java.net.SocketTimeoutException) {
            throw Exception("Zeitueberschreitung - FRITZ!Box antwortet nicht.")
        }

        if (response.code == 403) {
            throw Exception("Zugriff verweigert. Benutzername/Passwort falsch oder keine Smart-Home-Berechtigung.")
        }
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code} von der FRITZ!Box.")
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

            if (response.isSuccessful) {
                log("${if (turnOn) "Eingeschaltet" else "Ausgeschaltet"}: $ain", LogLevel.SUCCESS)
                updateDeviceState(ain, turnOn)
            } else {
                log("Schalten fehlgeschlagen: HTTP ${response.code}", LogLevel.ERROR)
                _errorMessage.emit("Schalten fehlgeschlagen: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            log("Fehler beim Schalten: ${e.message}", LogLevel.ERROR)
            _errorMessage.emit("Schalten fehlgeschlagen: ${e.message}")
        }
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
