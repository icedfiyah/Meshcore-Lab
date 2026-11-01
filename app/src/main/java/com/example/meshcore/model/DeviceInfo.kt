package com.example.meshcore.model

data class DeviceInfo(
    val name: String,
    val address: String,
    val firmwareVersion: String,
    val hardwareModel: String,
    val supportsResearchFrames: Boolean = false,
    val isSimulated: Boolean = false
)
