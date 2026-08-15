package com.theglinky.fritzdect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
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
    val GradientBrush = Brush.linearGradient(
        colors = listOf(Cyan, Purple, Pink)
    )
}

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: FritzViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this).get(FritzViewModel::class.java)

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
    var showTerminal by remember { mutableStateOf(true) }

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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = TheglinkyTheme.GradientBrush,
                    shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                )
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "THEGLINKY FRITZ!DECT",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFamily = FontFamily.Monospace
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(TheglinkyTheme.CardBg)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = if (isConnected) TheglinkyTheme.Cyan else Color.Gray,
                                shape = RoundedCornerShape(50)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isConnected) "Connected" else "Disconnected",
                        color = if (isConnected) TheglinkyTheme.Cyan else Color.Gray,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Button(
                    onClick = { currentScreen = Screen.Setup },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TheglinkyTheme.Purple
                    ),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Settings", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }

        // Terminal-Toggle-Leiste
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF050810))
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .let { it },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "> TERMINAL (${logs.size})",
                color = Color(0xFF00FF66),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Row {
                TextButton(onClick = { viewModel.clearLogs() }) {
                    Text("Clear", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = { showTerminal = !showTerminal }) {
                    Text(
                        if (showTerminal) "^ Verbergen" else "v Anzeigen",
                        color = TheglinkyTheme.Cyan,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (showTerminal) {
            val listState = rememberLazyListState()
            LaunchedEffect(logs.size) {
                if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(Color(0xFF000000))
                    .padding(1.dp)
                    .background(Color(0xFF0A0A0A))
            ) {
                if (logs.isEmpty()) {
                    Text(
                        "// Noch keine Logs. Druecke CONNECT um zu starten.",
                        color = Color(0xFF444444),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(logs) { entry ->
                            val color = when (entry.level) {
                                LogLevel.SUCCESS -> Color(0xFF00FF66)
                                LogLevel.ERROR -> Color(0xFFFF3355)
                                LogLevel.WARNING -> Color(0xFFFFC107)
                                LogLevel.INFO -> Color(0xFF00D9FF)
                            }
                            val prefix = when (entry.level) {
                                LogLevel.SUCCESS -> "[OK]"
                                LogLevel.ERROR -> "[FEHLER]"
                                LogLevel.WARNING -> "!"
                                LogLevel.INFO -> ">"
                            }
                            Text(
                                "[${entry.timestamp}] $prefix ${entry.message}",
                                color = color,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }

        when (currentScreen) {
            Screen.Setup -> SetupScreen(viewModel, errorMessage) { currentScreen = Screen.Devices }
            Screen.Devices -> DevicesScreen(devices, viewModel, isConnected)
        }
    }
}

@Composable
fun SetupScreen(viewModel: FritzViewModel, errorMessage: String, onConnected: () -> Unit) {
    var fritzBoxIP by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "FRITZ!BOX SETUP",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TheglinkyTheme.Cyan,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        OutlinedTextField(
            value = fritzBoxIP,
            onValueChange = { fritzBoxIP = it },
            label = { Text("FRITZ!Box IP", color = TheglinkyTheme.Cyan) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TheglinkyTheme.Cyan,
                unfocusedBorderColor = TheglinkyTheme.Purple,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace
            )
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("FRITZ!Box Benutzername", color = TheglinkyTheme.Cyan) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TheglinkyTheme.Cyan,
                unfocusedBorderColor = TheglinkyTheme.Purple,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace
            )
        )

        var passwordVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("FRITZ!Box Passwort", color = TheglinkyTheme.Cyan) },
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
            ),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                fontFamily = FontFamily.Monospace
            )
        )

        Text(
            "Benutzername + Passwort findest du in der FRITZ!Box unter System -> FRITZ!Box-Benutzer",
            fontSize = 11.sp,
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (errorMessage.isNotEmpty()) {
            Text(
                errorMessage,
                color = TheglinkyTheme.Pink,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 16.dp),
                fontFamily = FontFamily.Monospace
            )
        }

        Button(
            onClick = {
                isLoading = true
                scope.launch {
                    viewModel.connectToFritzBox(fritzBoxIP, password, username)
                    isLoading = false
                    // Nur weiterleiten wenn Connect wirklich erfolgreich war
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
    var showTimerDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TheglinkyTheme.CardBg)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        device.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TheglinkyTheme.Cyan,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        device.ain,
                        fontSize = 11.sp,
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Button(
                    onClick = {
                        scope.launch {
                            viewModel.toggleDevice(device.ain, !device.isOn)
                        }
                    },
                    modifier = Modifier.size(56.dp, 44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (device.isOn) TheglinkyTheme.Pink else TheglinkyTheme.Purple
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        if (device.isOn) "AN" else "AUS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Divider(color = Color(0xFF2A3050), modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Leistung: ${device.power}W",
                    fontSize = 12.sp,
                    color = TheglinkyTheme.Cyan,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Temp: ${device.temperature} Grad C",
                    fontSize = 12.sp,
                    color = TheglinkyTheme.Cyan,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (device.timerActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A3050), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        "[T] Timer: ${device.timerInfo}",
                        fontSize = 11.sp,
                        color = TheglinkyTheme.Pink,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = { showTimerDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TheglinkyTheme.Purple
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("[TIMER] TIMER EINSTELLEN", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }

    if (showTimerDialog) {
        TimerDialog(
            device = device,
            viewModel = viewModel,
            onDismiss = { showTimerDialog = false }
        )
    }
}


Composable
fun TimerDialog(device: FritzDevice, viewModel: FritzViewModel, onDismiss: () -> Unit) {
    var onMinutes by remember { mutableStateOf("10") }
    var pauseHours by remember { mutableStateOf("3") }
    var isRepeat by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "TIMER: ${device.name}",
                color = TheglinkyTheme.Cyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp
            )
        },
        text = {
            Column {
                Text("AN-Dauer (Minuten)", color = TheglinkyTheme.Cyan, fontSize = 12.sp)
                OutlinedTextField(
                    value = onMinutes,
                    onValueChange = { onMinutes = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TheglinkyTheme.Cyan
                    )
                )

                Text("Pause-Dauer (Stunden)", color = TheglinkyTheme.Cyan, fontSize = 12.sp)
                OutlinedTextField(
                    value = pauseHours,
                    onValueChange = { pauseHours = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TheglinkyTheme.Cyan
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isRepeat,
                        onCheckedChange = { isRepeat = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = TheglinkyTheme.Pink,
                            uncheckedColor = TheglinkyTheme.Purple
                        )
                    )
                    Text(
                        "Wiederholen",
                        color = TheglinkyTheme.Cyan,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        containerColor = TheglinkyTheme.CardBg,
        confirmButton = {
            Button(
                onClick = {
                    viewModel.setTimer(
                        device.ain,
                        onMinutes.toIntOrNull() ?: 10,
                        pauseHours.toIntOrNull() ?: 3,
                        isRepeat
                    )
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = TheglinkyTheme.Pink)
            ) {
                Text("START", fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3050))
            ) {
                Text("ABBRECHEN", fontFamily = FontFamily.Monospace)
            }
        }
    )
}

sealed class Screen {
    object Setup : Screen()
    object Devices : Screen()
}