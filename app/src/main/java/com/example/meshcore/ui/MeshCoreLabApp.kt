package com.example.meshcore.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshcore.fuzz.CampaignMode
import com.example.meshcore.fuzz.CaseOutcome
import com.example.meshcore.fuzz.FuzzCaseRecord
import com.example.meshcore.fuzz.RunnerState
import com.example.meshcore.model.EventDirection
import com.example.meshcore.model.EventType
import com.example.meshcore.model.LogEntry
import com.example.meshcore.model.ProtocolConstants
import com.example.meshcore.model.ProtocolLayer
import com.example.meshcore.transport.ConnectionState
import kotlinx.coroutines.launch

@Composable
fun MeshCoreLabApp(viewModel: MeshCoreLabViewModel) {
    val currentTab by viewModel.currentTab.collectAsState()
    val isArmed by viewModel.isArmedForIsolatedLab.collectAsState()
    val useSim by viewModel.useSimulation.collectAsState()
    val statusMsg by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(statusMsg) {
        statusMsg?.let {
            scope.launch {
                snackbarHostState.showSnackbar(it)
                viewModel.clearStatus()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopSafetyHeader(
                isArmed = isArmed,
                isSimulated = useSim,
                onEmergencyStop = {
                    viewModel.stopFuzzCampaign()
                    viewModel.disconnect()
                }
            )
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag("bottom_nav")) {
                NavigationBarItem(
                    selected = currentTab == AppTab.CONNECT_LOGS,
                    onClick = { viewModel.selectTab(AppTab.CONNECT_LOGS) },
                    icon = { Icon(Icons.Default.Bluetooth, contentDescription = "Connect") },
                    label = { Text("Connect") },
                    modifier = Modifier.testTag("nav_connect")
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.EDITORS,
                    onClick = { viewModel.selectTab(AppTab.EDITORS) },
                    icon = { Icon(Icons.Default.Edit, contentDescription = "Editors") },
                    label = { Text("Editors") },
                    modifier = Modifier.testTag("nav_editors")
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.ROBUSTNESS_FUZZER,
                    onClick = { viewModel.selectTab(AppTab.ROBUSTNESS_FUZZER) },
                    icon = { Icon(Icons.Default.BugReport, contentDescription = "Fuzzer") },
                    label = { Text("Fuzzer") },
                    modifier = Modifier.testTag("nav_fuzzer")
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.SAFETY_EXPORT,
                    onClick = { viewModel.selectTab(AppTab.SAFETY_EXPORT) },
                    icon = { Icon(Icons.Default.Security, contentDescription = "Safety") },
                    label = { Text("Safety") },
                    modifier = Modifier.testTag("nav_safety")
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (currentTab) {
                AppTab.CONNECT_LOGS -> ConnectAndLogsScreen(viewModel)
                AppTab.EDITORS -> EditorsScreen(viewModel)
                AppTab.ROBUSTNESS_FUZZER -> RobustnessFuzzerScreen(viewModel)
                AppTab.SAFETY_EXPORT -> SafetyAndExportScreen(viewModel)
            }
        }
    }
}

@Composable
fun TopSafetyHeader(
    isArmed: Boolean,
    isSimulated: Boolean,
    onEmergencyStop: () -> Unit
) {
    val bannerColor = if (isArmed) Color(0xFFD32F2F) else Color(0xFF2E7D32)
    val statusText = if (isArmed) "LAB ARMED (ISOLATED TESTBENCH ONLY)" else "RF DISARMED (INSPECTION MODE)"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MeshCore Lab",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isSimulated) Color(0xFF1565C0) else Color(0xFF6A1B9A),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isSimulated) "FAKE TRANSPORT (SIM)" else "REAL BLE GATT",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Button(
                onClick = onEmergencyStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                modifier = Modifier.testTag("emergency_stop_button")
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("HALT", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bannerColor, RoundedCornerShape(4.dp))
                .padding(vertical = 4.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = statusText,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ConnectAndLogsScreen(viewModel: MeshCoreLabViewModel) {
    val connState by viewModel.connectionState.collectAsState()
    val devInfo by viewModel.deviceInfo.collectAsState()
    val useSim by viewModel.useSimulation.collectAsState()
    val targetMac by viewModel.targetMacAddress.collectAsState()
    val logs by viewModel.logs.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Fake Node Transport", fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = useSim,
                        onCheckedChange = { viewModel.setSimulationMode(it) },
                        modifier = Modifier.testTag("transport_switch")
                    )
                }

                if (!useSim) {
                    OutlinedTextField(
                        value = targetMac,
                        onValueChange = { viewModel.setTargetMacAddress(it) },
                        label = { Text("Target Radio MAC Address") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mac_address_input")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isConnected = connState == ConnectionState.CONNECTED
                    Button(
                        onClick = {
                            if (isConnected) viewModel.disconnect() else viewModel.connect()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnected) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("connect_toggle_button")
                    ) {
                        Text(if (isConnected) "Disconnect" else "Connect")
                    }

                    OutlinedButton(
                        onClick = { viewModel.clearLogs() },
                        modifier = Modifier.testTag("clear_logs_button")
                    ) {
                        Text("Clear Logs")
                    }
                }

                if (devInfo != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Device: ${devInfo?.name} | FW: ${devInfo?.firmwareVersion}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Driver Injection Support: ${if (devInfo?.supportsResearchFrames == true) "Available" else "UNAVAILABLE (Stock FW)"}",
                        fontSize = 11.sp,
                        color = if (devInfo?.supportsResearchFrames == true) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Audit Event Log (${logs.size} entries)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(6.dp)
                .testTag("log_list")
        ) {
            items(logs, key = { it.id }) { log ->
                LogItemRow(log)
                HorizontalDivider(color = DividerDefaults.color.copy(alpha = 0.3f))
            }
        }
    }
}

@Composable
fun LogItemRow(log: LogEntry) {
    val dirColor = when (log.direction) {
        EventDirection.OUTGOING -> Color(0xFF1E88E5)
        EventDirection.INCOMING -> Color(0xFF43A047)
        EventDirection.INTERNAL -> Color(0xFF757575)
    }

    val typeColor = when (log.type) {
        EventType.ERROR -> Color(0xFFD32F2F)
        EventType.ANOMALY_TIMEOUT -> Color(0xFFF57C00)
        EventType.SAFEGUARD_STOP -> Color(0xFFE64A19)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = log.formattedTimestamp,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .background(dirColor, RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = log.direction.name,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "[${log.layer.name}]",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (log.isSimulated) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "(SIM)",
                    fontSize = 9.sp,
                    color = Color(0xFF1565C0),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            text = log.summary,
            fontSize = 12.sp,
            color = typeColor,
            fontWeight = FontWeight.Medium
        )

        if (log.bytes.isNotEmpty()) {
            Text(
                text = "HEX: ${log.hexString}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun EditorsScreen(viewModel: MeshCoreLabViewModel) {
    val subTab by viewModel.editorSubTab.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = subTab.ordinal,
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(
                selected = subTab == EditorSubTab.COMPANION_COMMAND,
                onClick = { viewModel.selectEditorSubTab(EditorSubTab.COMPANION_COMMAND) },
                text = { Text("1. Companion CMD") },
                modifier = Modifier.testTag("tab_companion")
            )
            Tab(
                selected = subTab == EditorSubTab.STOCK_CUSTOM_PAYLOAD,
                onClick = { viewModel.selectEditorSubTab(EditorSubTab.STOCK_CUSTOM_PAYLOAD) },
                text = { Text("2. Stock Custom") },
                modifier = Modifier.testTag("tab_stock_custom")
            )
            Tab(
                selected = subTab == EditorSubTab.RESEARCH_FRAME,
                onClick = { viewModel.selectEditorSubTab(EditorSubTab.RESEARCH_FRAME) },
                text = { Text("3. Research Frame") },
                modifier = Modifier.testTag("tab_research_frame")
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            when (subTab) {
                EditorSubTab.COMPANION_COMMAND -> CompanionCommandEditor(viewModel)
                EditorSubTab.STOCK_CUSTOM_PAYLOAD -> StockCustomPayloadEditor(viewModel)
                EditorSubTab.RESEARCH_FRAME -> ResearchFrameEditor(viewModel)
            }
        }
    }
}

@Composable
fun CompanionCommandEditor(viewModel: MeshCoreLabViewModel) {
    val opcode by viewModel.companionOpcode.collectAsState()
    val paramsHex by viewModel.companionParamsHex.collectAsState()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Companion Protocol Commands (Host -> Modem)",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Commands sent to NUS RX characteristic. Opcode is Byte 0.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Pre-Configured Commands", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.sendCompanionCommandPreset(ProtocolConstants.CMD_DEVICE_QUERY) },
                            modifier = Modifier.weight(1f).testTag("cmd_device_query")
                        ) {
                            Text("Query (22)", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.sendCompanionCommandPreset(ProtocolConstants.CMD_APP_START) },
                            modifier = Modifier.weight(1f).testTag("cmd_app_start")
                        ) {
                            Text("Start (1)", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.sendCompanionCommandPreset(ProtocolConstants.CMD_GET_DEVICE_TIME) },
                            modifier = Modifier.weight(1f).testTag("cmd_get_time")
                        ) {
                            Text("Time (5)", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.sendCompanionCommandPreset(ProtocolConstants.CMD_SEND_SELF_ADVERT, byteArrayOf(1)) },
                            modifier = Modifier.weight(1f).testTag("cmd_advert")
                        ) {
                            Text("Advert (7)", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Custom Command Composer", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = opcode,
                        onValueChange = { viewModel.setCompanionOpcode(it) },
                        label = { Text("Command Opcode (Hex)") },
                        modifier = Modifier.fillMaxWidth().testTag("opcode_input")
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = paramsHex,
                        onValueChange = { viewModel.setCompanionParamsHex(it) },
                        label = { Text("Parameters (Hex bytes, optional)") },
                        modifier = Modifier.fillMaxWidth().testTag("params_input")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.sendCustomCompanionCommand() },
                        modifier = Modifier.fillMaxWidth().testTag("send_custom_cmd_button")
                    ) {
                        Text("Send Companion Command")
                    }
                }
            }
        }
    }
}

@Composable
fun StockCustomPayloadEditor(viewModel: MeshCoreLabViewModel) {
    val chIdx by viewModel.stockChannelIdx.collectAsState()
    val payloadHex by viewModel.stockPayloadHex.collectAsState()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Stock Custom Payload (PAYLOAD_TYPE_RAW_CUSTOM = 0x0F)",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Payload data is wrapped by stock firmware headers, routing hashes, and CRC before transmission over LoRa.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = chIdx,
                        onValueChange = { viewModel.setStockChannelIdx(it) },
                        label = { Text("Channel Index (0-7)") },
                        modifier = Modifier.fillMaxWidth().testTag("channel_idx_input")
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = payloadHex,
                        onValueChange = { viewModel.setStockPayloadHex(it) },
                        label = { Text("Payload Hex") },
                        modifier = Modifier.fillMaxWidth().testTag("stock_payload_input")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.sendStockCustomPayload() },
                        modifier = Modifier.fillMaxWidth().testTag("send_stock_payload_button")
                    ) {
                        Text("Send Stock Custom Payload")
                    }
                }
            }
        }
    }
}

@Composable
fun ResearchFrameEditor(viewModel: MeshCoreLabViewModel) {
    val frameHex by viewModel.researchFrameHex.collectAsState()
    val parsedPkt by viewModel.parsedResearchPacket.collectAsState()
    val parseErr by viewModel.researchParseError.collectAsState()
    val isArmed by viewModel.isArmedForIsolatedLab.collectAsState()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Complete-Frame Research Interface",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Complete serialized packet [header][transport][path][payload] submitted to radio driver. " +
                        "Requires verified research firmware and lab arming.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Raw Frame Hex", fontWeight = FontWeight.SemiBold)
                        val byteCount = (frameHex.replace(" ", "").length / 2)
                        Text(
                            "$byteCount / ${ProtocolConstants.MAX_LORA_PHY_MTU} B",
                            color = if (byteCount > ProtocolConstants.MAX_LORA_PHY_MTU) Color.Red else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = frameHex,
                        onValueChange = { viewModel.setResearchFrameHex(it) },
                        modifier = Modifier.fillMaxWidth().testTag("research_frame_hex_input")
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text("Presets:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.loadSampleResearchFrame(ProtocolConstants.PAYLOAD_TYPE_TXT_MSG) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("TXT_MSG", fontSize = 10.sp)
                        }
                        OutlinedButton(
                            onClick = { viewModel.loadSampleResearchFrame(ProtocolConstants.PAYLOAD_TYPE_RAW_CUSTOM) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("RAW_CUSTOM", fontSize = 10.sp)
                        }
                        OutlinedButton(
                            onClick = { viewModel.loadSampleResearchFrame(ProtocolConstants.PAYLOAD_TYPE_ADVERT) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ADVERT", fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (parseErr != null) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Local Parser Inspection", fontWeight = FontWeight.Bold)
                    if (parseErr != null) {
                        Text(
                            text = "Malformed: $parseErr",
                            color = Color(0xFFC62828),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (parsedPkt != null) {
                        val p = parsedPkt!!
                        Text("Payload Type: ${p.payloadTypeName()}", fontSize = 12.sp)
                        Text("Route Type: ${p.routeTypeName()}", fontSize = 12.sp)
                        Text("Version: ${p.version}", fontSize = 12.sp)
                        if (p.hasTransportCodes) {
                            Text("Transport Codes: [${p.transportCode0}, ${p.transportCode1}]", fontSize = 12.sp)
                        }
                        Text("Path Size: ${p.path.size} bytes", fontSize = 12.sp)
                        Text("Payload Size: ${p.payload.size} bytes", fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Button(
                onClick = { viewModel.sendResearchFrame() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isArmed) Color(0xFFD32F2F) else Color(0xFF757575)
                ),
                modifier = Modifier.fillMaxWidth().testTag("send_research_frame_button")
            ) {
                Text(if (isArmed) "Transmit Complete Frame (ARMED)" else "Transmit Complete Frame (DISARMED - BLOCKED)")
            }
        }
    }
}

@Composable
fun RobustnessFuzzerScreen(viewModel: MeshCoreLabViewModel) {
    val runnerState by viewModel.fuzzRunnerState.collectAsState()
    val mode by viewModel.fuzzCampaignMode.collectAsState()
    val seed by viewModel.fuzzSeed.collectAsState()
    val maxCases by viewModel.fuzzMaxCases.collectAsState()
    val pacing by viewModel.fuzzPacingMs.collectAsState()
    val currentIdx by viewModel.fuzzCurrentIndex.collectAsState()
    val airtime by viewModel.fuzzAirtimeConsumedMs.collectAsState()
    val cases by viewModel.fuzzCases.collectAsState()
    val stopReason by viewModel.fuzzStopReason.collectAsState()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Automated Robustness & Fuzzing Engine",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Deterministic mutations from user seed. Tests parser bounds and modem resilience.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Campaign Target Mode", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.setFuzzCampaignMode(CampaignMode.LOCAL_PARSER_ROBUSTNESS) },
                            colors = if (mode == CampaignMode.LOCAL_PARSER_ROBUSTNESS) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.weight(1f).testTag("mode_parser")
                        ) {
                            Text("1. Local Parser (Safe/No-RF)", fontSize = 10.sp)
                        }

                        OutlinedButton(
                            onClick = { viewModel.setFuzzCampaignMode(CampaignMode.COMPANION_COMMAND) },
                            colors = if (mode == CampaignMode.COMPANION_COMMAND) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.weight(1f).testTag("mode_companion")
                        ) {
                            Text("2. Companion CMD", fontSize = 10.sp)
                        }

                        OutlinedButton(
                            onClick = { viewModel.setFuzzCampaignMode(CampaignMode.ISOLATED_RESEARCH_FRAME) },
                            colors = if (mode == CampaignMode.ISOLATED_RESEARCH_FRAME) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.weight(1f).testTag("mode_rf_frame")
                        ) {
                            Text("3. Research Driver", fontSize = 10.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = seed,
                            onValueChange = { viewModel.setFuzzSeed(it) },
                            label = { Text("Seed") },
                            modifier = Modifier.weight(1f).testTag("fuzz_seed_input")
                        )
                        OutlinedTextField(
                            value = maxCases,
                            onValueChange = { viewModel.setFuzzMaxCases(it) },
                            label = { Text("Max Cases") },
                            modifier = Modifier.weight(1f).testTag("fuzz_max_cases_input")
                        )
                        OutlinedTextField(
                            value = pacing,
                            onValueChange = { viewModel.setFuzzPacingMs(it) },
                            label = { Text("Pacing (ms)") },
                            modifier = Modifier.weight(1f).testTag("fuzz_pacing_input")
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val isRunning = runnerState == RunnerState.RUNNING
                        Button(
                            onClick = {
                                if (isRunning) viewModel.pauseFuzzCampaign() else viewModel.startFuzzCampaign()
                            },
                            modifier = Modifier.weight(1f).testTag("fuzz_start_button")
                        ) {
                            Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isRunning) "Pause" else "Start Campaign")
                        }

                        OutlinedButton(
                            onClick = { viewModel.stopFuzzCampaign() },
                            modifier = Modifier.weight(1f).testTag("fuzz_stop_button")
                        ) {
                            Text("Halt")
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    val totalTarget = maxCases.toIntOrNull() ?: 30
                    val progress = if (totalTarget > 0) currentIdx.toFloat() / totalTarget.toFloat() else 0f
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Status: $runnerState | Case $currentIdx / $totalTarget | Airtime: $airtime ms",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (stopReason != null) {
                        Text("Stop Reason: $stopReason", fontSize = 11.sp, color = Color(0xFFC62828), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item {
            Text("Campaign Cases (${cases.size})", fontWeight = FontWeight.Bold)
        }

        items(cases) { c ->
            FuzzCaseRow(c)
        }
    }
}

@Composable
fun FuzzCaseRow(record: FuzzCaseRecord) {
    val outcomeColor = when (record.outcome) {
        CaseOutcome.PARSER_VALID,
        CaseOutcome.TRANSPORT_ACCEPTED -> Color(0xFF2E7D32)
        CaseOutcome.PARSER_REJECTED_GRACEFULLY -> Color(0xFF1565C0)
        CaseOutcome.FIRMWARE_REJECTED -> Color(0xFFE65100)
        CaseOutcome.TIMEOUT_ANOMALY -> Color(0xFFD84315)
        CaseOutcome.BLOCKED_DISARMED,
        CaseOutcome.ERROR -> Color(0xFFC62828)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("#${record.caseNumber} - ${record.mutationType.name}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Box(
                    modifier = Modifier
                        .background(outcomeColor, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(record.outcome.name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Text(record.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Input: ${record.hexInput}", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            record.details?.let {
                Text("Result: $it", fontSize = 10.sp, color = outcomeColor, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun SafetyAndExportScreen(viewModel: MeshCoreLabViewModel) {
    val isArmed by viewModel.isArmedForIsolatedLab.collectAsState()
    val context = LocalContext.current

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Laboratory Safeguards & Session Audit Export",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isArmed) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Isolated Lab RF Arming", fontWeight = FontWeight.Bold)
                            Text(
                                "Enables complete-frame transmission in isolated test setups.",
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = isArmed,
                            onCheckedChange = { viewModel.toggleLabArming() },
                            modifier = Modifier.testTag("arm_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFC62828), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Hardware Safeguard Notice: Physical isolation (RF enclosure or dummy load with >=30dB attenuation) is mandatory.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFC62828)
                        )
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Protocol Layer Separation Summary", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("1. BLE Companion Commands: Stock host opcodes (CMD_*)", fontSize = 11.sp)
                    Text("2. Stock Custom Payloads: PAYLOAD_TYPE_RAW_CUSTOM wrapped in stock headers", fontSize = 11.sp)
                    Text("3. Research Driver Frames: Arbitrary complete frames [header][transport][path][payload]", fontSize = 11.sp)
                    Text("4. Radio PHY: Hardware preamble, sync word, CRC, frequency, SF", fontSize = 11.sp)
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Session Audit JSON Export", fontWeight = FontWeight.Bold)
                    Text(
                        "Exports timestamped outgoing/incoming bytes, device metadata, protocol commit, and fuzz case records.",
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val json = viewModel.exportSessionJson()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("MeshCore Lab Audit JSON", json))
                            Toast.makeText(context, "Session JSON copied to clipboard (${json.length} chars)", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().testTag("export_json_button")
                    ) {
                        Text("Copy Complete Session JSON")
                    }
                }
            }
        }
    }
}
