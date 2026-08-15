package com.theglinky.fritzdect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch

object TheglinkyTheme {
    val DarkBg = Color(0xFF0A0E27)
    val Cyan = Color(0xFF00D9FF)
    val Purple = Color(0xFF9D4EDD)
    val Pink = Color(0xFFFF006E)
    val CardBg = Color(0xFF1A1F3A)
    val GreenOn = Color(0xFF1B5E3A)
    val RedOff = Color(0xFF5E1B2A)
}

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: FritzViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this).get(FritzViewModel::class.java)
        viewModel.initAndAutoConnect(applicationContext)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TheglinkyTheme.DarkBg),
                    color = TheglinkyTheme.DarkBg
                ) {
                    FritzDectApp(viewModel)
                }
            }
        }
    }
}

@Composable
fun FritzDectApp(viewModel: FritzViewModel) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Setup) }
    val devices by viewModel.devices.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val hasSavedCredentials by viewModel.hasSavedCredentials.collectAsState()

    var wasConnected by remember { mutableStateOf(false) }
    LaunchedEffect(isConnected) {
        if (isConnected && !wasConnected) {
            currentScreen = Screen.Devices
        }
        wasConnected = isConnected
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TheglinkyTheme.DarkBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TheglinkyTheme.CardBg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { currentScreen = Screen.Setup }) {
                Text("\u2699", fontSize = 20.sp, color = TheglinkyTheme.Cyan)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (isConnected) TheglinkyTheme.Cyan else Color.Gray,
                            shape = RoundedCornerShape(50)
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isConnected) "Connected" else "Disconnected",
                    color = if (isConnected) TheglinkyTheme.Cyan else Color.Gray,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.width(48.dp))
        }

        when (currentScreen) {
            Screen.Setup -> SetupScreen(
                viewModel,
                errorMessage,
                logs,
                hasSavedCredentials
            ) { currentScreen = Screen.Devices }
            Screen.Devices -> DevicesScreen(devices, viewModel, isConnected)
        }
    }
}

@Composable
fun SetupScreen(
    viewModel: FritzViewModel,
    errorMessage: String,
    logs: List<LogEntry>,
    hasSavedCredentials: Boolean,
    onConnected: () -> Unit
) {
    var fritzBoxIP by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "FRITZ!BOX SETUP",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TheglinkyTheme.Cyan,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        if (hasSavedCredentials) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TheglinkyTheme.CardBg, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        "Es sind bereits Zugangsdaten gespeichert.",
                        color = TheglinkyTheme.Cyan,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.forgetCredentials() }) {
                        Text(
                            "Gespeicherte Daten loeschen",
                            color = TheglinkyTheme.Pink,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        OutlinedTextField(
            value = fritzBoxIP,
            onValueChange = { fritzBoxIP = it },
            label = { Text("FRITZ!Box IP", color = TheglinkyTheme.Cyan) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TheglinkyTheme.Cyan,
                unfocusedBorderColor = TheglinkyTheme.Purple,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Benutzername", color = TheglinkyTheme.Cyan) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TheglinkyTheme.Cyan,
                unfocusedBorderColor = TheglinkyTheme.Purple,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Passwort", color = TheglinkyTheme.Cyan) },
            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(
                        if (passwordVisible) "Verbergen" else "Anzeigen",
                        color = TheglinkyTheme.Cyan,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TheglinkyTheme.Cyan,
                unfocusedBorderColor = TheglinkyTheme.Purple,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        Text(
            "Wird beim ersten erfolgreichen Verbinden gespeichert.",
            fontSize = 11.sp,
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        Button(
            onClick = {
                isLoading = true
                scope.launch {
                    viewModel.connectToFritzBox(fritzBoxIP, password, username, saveOnSuccess = true)
                    isLoading = false
                    if (viewModel.isConnected.value) {
                        onConnected()
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = TheglinkyTheme.Purple,
                disabledContainerColor = TheglinkyTheme.Purple.copy(alpha = 0.5f)
            ),
            enabled = !isLoading && fritzBoxIP.isNotEmpty()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = TheglinkyTheme.Cyan,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text("CONNECT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }

        if (errorMessage.isNotEmpty()) {
            Text(
                errorMessage,
                color = TheglinkyTheme.Pink,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (logs.isNotEmpty()) {
            val listState = rememberLazyListState()
            LaunchedEffect(logs.size) {
                listState.animateScrollToItem(logs.size - 1)
            }

            Text(
                "Log",
                color = Color.Gray,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(Color(0xFF050810), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(logs) { entry ->
                        val color = when (entry.level) {
                            LogLevel.SUCCESS -> Color(0xFF00FF66)
                            LogLevel.ERROR -> Color(0xFFFF3355)
                            LogLevel.WARNING -> Color(0xFFFFC107)
                            LogLevel.INFO -> Color(0xFF888888)
                        }
                        Text(
                            "${entry.timestamp}  ${entry.message}",
                            color = color,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DevicesScreen(devices: List<FritzDevice>, viewModel: FritzViewModel, isConnected: Boolean) {
    if (isConnected && devices.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Keine FRITZ!DECT Steckdosen gefunden.",
                color = TheglinkyTheme.Cyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Stell sicher, dass mindestens eine Steckdose in der FRITZ!Box eingerichtet ist.",
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(devices) { device ->
            FritzDeviceCard(device, viewModel)
        }
    }
}

@Composable
fun FritzDeviceCard(device: FritzDevice, viewModel: FritzViewModel) {
    val scope = rememberCoroutineScope()
    val bgColor = if (device.isOn) TheglinkyTheme.GreenOn else TheglinkyTheme.RedOff

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                scope.launch {
                    viewModel.toggleDevice(device.ain, !device.isOn)
                }
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${device.power}W  |  ${device.temperature} Grad C",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace
                )
            }

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (device.isOn) "AN" else "AUS",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

sealed class Screen {
    object Setup : Screen()
    object Devices : Screen()
}

