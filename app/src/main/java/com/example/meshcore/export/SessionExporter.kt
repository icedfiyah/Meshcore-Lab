package com.example.meshcore.export

import com.example.meshcore.fuzz.FuzzCaseRecord
import com.example.meshcore.model.DeviceInfo
import com.example.meshcore.model.LogEntry
import com.example.meshcore.model.ProtocolConstants
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SessionExporter {

    fun generateSessionJson(
        deviceInfo: DeviceInfo?,
        isArmed: Boolean,
        logs: List<LogEntry>,
        fuzzCases: List<FuzzCaseRecord>,
        stopReason: String?
    ): String {
        val root = JSONObject()

        // Metadata
        val meta = JSONObject()
        meta.put("app_name", "MeshCore Lab")
        meta.put("app_version", "1.0")
        meta.put("protocol_baseline_commit", ProtocolConstants.PROTOCOL_BASELINE_COMMIT)
        meta.put("export_timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
        meta.put("safety_warning", "CONFIDENTIAL TEST AUDIT: Raw packets may contain sensitive radio telemetry, keys, or device hashes.")
        meta.put("rf_armed_during_session", isArmed)
        meta.put("stop_reason", stopReason ?: "Normal termination")
        root.put("metadata", meta)

        // Device Info
        val devObj = JSONObject()
        if (deviceInfo != null) {
            devObj.put("device_name", deviceInfo.name)
            devObj.put("device_address", deviceInfo.address)
            devObj.put("firmware_version", deviceInfo.firmwareVersion)
            devObj.put("hardware_model", deviceInfo.hardwareModel)
            devObj.put("supports_research_frames", deviceInfo.supportsResearchFrames)
            devObj.put("is_simulated", deviceInfo.isSimulated)
        } else {
            devObj.put("status", "Not connected")
        }
        root.put("device", devObj)

        // Audit Logs
        val logsArray = JSONArray()
        for (log in logs) {
            val item = JSONObject()
            item.put("id", log.id)
            item.put("timestamp_ms", log.timestampMillis)
            item.put("formatted_time", log.formattedTimestamp)
            item.put("direction", log.direction.name)
            item.put("layer", log.layer.name)
            item.put("event_type", log.type.name)
            item.put("summary", log.summary)
            item.put("hex_bytes", log.hexString)
            log.intendedHexString?.let { item.put("intended_hex_bytes", it) }
            item.put("is_simulated", log.isSimulated)
            log.detail?.let { item.put("detail", it) }
            logsArray.put(item)
        }
        root.put("audit_logs", logsArray)

        // Fuzz Cases
        val fuzzArray = JSONArray()
        for (fuzz in fuzzCases) {
            val c = JSONObject()
            c.put("case_number", fuzz.caseNumber)
            c.put("seed", fuzz.seed)
            c.put("mutation_type", fuzz.mutationType.name)
            c.put("description", fuzz.description)
            c.put("hex_input", fuzz.hexInput)
            c.put("outcome", fuzz.outcome.name)
            c.put("latency_ms", fuzz.latencyMs)
            fuzz.details?.let { c.put("details", it) }
            fuzzArray.put(c)
        }
        root.put("fuzz_campaign_cases", fuzzArray)

        return root.toString(2)
    }
}
