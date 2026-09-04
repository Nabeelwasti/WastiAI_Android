package com.example.data.device

enum class IoTProtocol {
    MQTT,
    MATTER,
    HOME_ASSISTANT_REST,
    ZIGBEE_BRIDGE,
    OCTOPRINT_3D_PRINTER,
    ROS2_ROBOTICS
}

data class IoTDeviceEndpoint(
    val deviceId: String,
    val name: String,
    val protocol: IoTProtocol,
    val endpointUri: String,
    val isOnline: Boolean = true
)

object IoTPhysicalWorldAdapter {

    private val registeredDevices = mutableListOf(
        IoTDeviceEndpoint("printer_01", "3D Printer Core", IoTProtocol.OCTOPRINT_3D_PRINTER, "http://localhost:5000/api"),
        IoTDeviceEndpoint("home_hub", "Home Assistant Controller", IoTProtocol.HOME_ASSISTANT_REST, "http://homeassistant.local:8123/api"),
        IoTDeviceEndpoint("mqtt_mesh", "Wasti IoT MQTT Broker", IoTProtocol.MQTT, "tcp://localhost:1883"),
        IoTDeviceEndpoint("robot_base", "ROS2 Robotic Navigation Base", IoTProtocol.ROS2_ROBOTICS, "ros2://localhost:11311")
    )

    fun getConnectedDevices(): List<IoTDeviceEndpoint> = registeredDevices.toList()

    fun dispatchCommand(deviceId: String, commandPayload: String): Boolean {
        val dev = registeredDevices.find { it.deviceId == deviceId }
        return dev != null && dev.isOnline
    }
}
