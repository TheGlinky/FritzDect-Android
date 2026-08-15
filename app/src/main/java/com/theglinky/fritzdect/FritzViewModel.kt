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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
    private var sessionId = ""

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
        sessionId = ""
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
                log("Fordere Session an...", LogLevel.INFO)
                sessionId = fetchSessionId()

                if (sessionId.isEmpty() || sessionId == "0000000000000000") {
                    throw Exception("Anmeldung fehlgeschlagen. Benutzername oder Passwort falsch.")
                }

                log("Session erhalten: ${sessionId.take(8)}...", LogLevel.SUCCESS)

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

    /**
     * AVM Login Version 2 (PBKDF2) mit Fallback auf Version 1 (MD5),
     * falls die FRITZ!Box eine alte Challenge im MD5-Format schickt.
     */
    private fun fetchSessionId(): String {
        val challengeUrl = "http://$fritzBoxIP/login_sid.lua?version=2"
        val challengeRequest = Request.Builder().url(challengeUrl).build()
        val challengeResponse = httpClient.newCall(challengeRequest).execute()
        val challengeXml = challengeResponse.body?.string() ?: throw Exception("Keine Antwort beim Challenge-Abruf.")

        val challenge = extractXmlValue(challengeXml, "Challenge")
        if (challenge.isEmpty()) {
            throw Exception("Konnte Challenge nicht aus FRITZ!Box-Antwort lesen.")
        }

        val response = if (challenge.startsWith("2\$")) {
            computePbkdf2Response(challenge, fritzPassword)
        } else {
            computeMd5Response(challenge, fritzPassword)
        }

        val loginUrl = "http://$fritzBoxIP/login_sid.lua?version=2&username=$fritzUser&response=$response"
        val loginRequest = Request.Builder().url(loginUrl).build()
        val loginResponse = httpClient.newCall(loginRequest).execute()
        val loginXml = loginResponse.body?.string() ?: throw Exception("Keine Antwort beim Login.")

        return extractXmlValue(loginXml, "SID")
    }

    /**
     * Neues PBKDF2-Verfahren (Challenge beginnt mit "2$").
     * Format: 2$iter1$salt1$iter2$salt2
     * hash1 = PBKDF2-HMAC-SHA256(passwort, salt1, iter1) -> 32 Bytes
     * hash2 = PBKDF2-HMAC-SHA256(hash1_hex_bytes... eigentlich hash1 raw, salt2, iter2)
     * Response = salt2 + "$" + hash2_hex
     */
    private fun computePbkdf2Response(challenge: String, password: String): String {
        val parts = challenge.split("$")
        // parts[0] = "2", parts[1] = iter1, parts[2] = salt1, parts[3] = iter2, parts[4] = salt2
        val iter1 = parts[1].toInt()
        val salt1 = hexStringToByteArray(parts[2])
        val iter2 = parts[3].toInt()
        val salt2 = hexStringToByteArray(parts[4])

        val hash1 = pbkdf2HmacSha256(password.toByteArray(Charsets.UTF_8), salt1, iter1, 32)
        val hash2 = pbkdf2HmacSha256(hash1, salt2, iter2, 32)

        val hash2Hex = hash2.joinToString("") { "%02x".format(it) }
        return "${parts[4]}\$$hash2Hex"
    }

    /**
     * PBKDF2-HMAC-SHA256 nach RFC 8018, arbeitet durchgehend mit rohen Bytes
     * (kein Umweg ueber char[]/PBEKeySpec, da der 2. Durchlauf binaere Daten
     * als "Passwort" nutzt, keinen Text).
     */
    private fun pbkdf2HmacSha256(keyBytes: ByteArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val hLen = hmac.macLength

        val blockCount = Math.ceil(keyLengthBytes.toDouble() / hLen).toInt()
        val output = ByteArray(blockCount * hLen)

        for (blockIndex in 1..blockCount) {
            val blockIndexBytes = byteArrayOf(
                (blockIndex ushr 24).toByte(),
                (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(),
                blockIndex.toByte()
            )

            hmac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
            var u = hmac.doFinal(salt + blockIndexBytes)
            val t = u.copyOf()

            for (i in 2..iterations) {
                hmac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
                u = hmac.doFinal(u)
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }

            System.arraycopy(t, 0, output, (blockIndex - 1) * hLen, hLen)
        }

        return output.copyOf(keyLengthBytes)
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * Altes MD5-Verfahren als Fallback (falls Challenge kein "2$" Format hat).
     */
    private fun computeMd5Response(challenge: String, password: String): String {
        val challengeResponseString = "$challenge-$password"
        val md5 = MessageDigest.getInstance("MD5")
        val hashBytes = md5.digest(challengeResponseString.toByteArray(Charsets.UTF_16LE))
        val hashHex = hashBytes.joinToString("") { "%02x".format(it) }
        return "$challenge-$hashHex"
    }

    private fun extractXmlValue(xml: String, tagName: String): String {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var eventType = parser.eventType
        var inTag = false
        var value = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> if (parser.name == tagName) inTag = true
                XmlPullParser.TEXT -> if (inTag) value = parser.text?.trim() ?: ""
                XmlPullParser.END_TAG -> if (parser.name == tagName) return value
            }
            eventType = parser.next()
        }
        return value
    }

    private suspend fun fetchDeviceList(): List<FritzDevice> {
        if (sessionId.isEmpty()) {
            sessionId = fetchSessionId()
        }

        val url = "http://$fritzBoxIP/webservices/homeautoswitch.lua?switchcmd=getdevicelistinfos&sid=$sessionId"
        val request = Request.Builder().url(url).build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: java.net.ConnectException) {
            throw Exception("Keine Verbindung zu $fritzBoxIP moeglich. IP korrekt und im gleichen WLAN?")
        } catch (e: java.net.UnknownHostException) {
            throw Exception("Adresse $fritzBoxIP nicht aufloesbar.")
        } catch (e: java.net.SocketTimeoutException) {
            throw Exception("Zeitueberschreitung - FRITZ!Box antwortet nicht.")
        }

        if (response.code == 403) {
            log("Session evtl. abgelaufen, neu anmelden...", LogLevel.WARNING)
            sessionId = fetchSessionId()
            if (sessionId.isEmpty() || sessionId == "0000000000000000") {
                throw Exception("Anmeldung fehlgeschlagen. Benutzername oder Passwort falsch.")
            }
            val retryUrl = "http://$fritzBoxIP/webservices/homeautoswitch.lua?switchcmd=getdevicelistinfos&sid=$sessionId"
            val retryResponse = httpClient.newCall(Request.Builder().url(retryUrl).build()).execute()
            if (!retryResponse.isSuccessful) {
                throw Exception("HTTP ${retryResponse.code} von der FRITZ!Box (nach Session-Erneuerung).")
            }
            val xml = retryResponse.body?.string() ?: throw Exception("Leere Antwort von FRITZ!Box")
            return parseDeviceListXml(xml)
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
            if (sessionId.isEmpty()) {
                sessionId = fetchSessionId()
            }

            val cmd = if (turnOn) "setswitchon" else "setswitchoff"
            val url = "http://$fritzBoxIP/webservices/homeautoswitch.lua?switchcmd=$cmd&ain=$ain&sid=$sessionId"
            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()

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
                    kotlinx.coroutines.delay(5000)
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
