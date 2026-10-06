package com.shilapi.xcertplay.carlife

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.diplay.carlife.core.CarLife
import com.diplay.carlife.core.CarLifeTransport

/**
 * Wired transport: Android Open Accessory over USB, mirroring Baidu's
 * AOAHostSetup/AOAConnectManager flow.
 *
 *  - scan devices, skip iPhones (0x05AC) and mass-storage
 *  - USB control 51 (GET_PROTOCOL), 52 (identity strings x6), 53 (START)
 *  - after re-enumeration (VID 0x18D1 / PID 0x2D00-0x2D05) claim interface 0,
 *    bulk IN endpoint index 0, bulk OUT index 1
 *  - read loop pushes raw frame bytes into the session splitter; writes split
 *    larger buffers into 16KB bulk transfers, and the 8-byte transport header
 *    goes out as its own transfer ahead of the body (as the reference does)
 */
class AoaTransport(
    private val context: Context,
    private val listener: Listener,
) : CarLifeTransport {
    interface Listener {
        /** Raw frame bytes arrived from the phone. */
        fun onBytes(data: ByteArray, offset: Int, length: Int)

        fun onClosed(reason: String)

        /** Progress for UI: accessory mode switch, permission wait, streaming. */
        fun onState(state: State, detail: String? = null)
    }

    enum class State { SCANNING, REQUESTING_PERMISSION, SWITCHING_ACCESSORY, CONNECTED, CLOSED }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var readThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var currentDevice: UsbDevice? = null

    override val isConnected: Boolean get() = connection != null && running

    private val permissionAction = "$packageName.carlife.USB_PERMISSION"

    private val packageName: String get() = context.packageName

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && !isPhone(device)) scan()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && device.deviceId == currentDevice?.deviceId) close("usb detached")
                }
                permissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (!granted || device == null) {
                        listener.onState(State.CLOSED, "usb permission denied")
                        close("usb permission denied")
                    } else {
                        initDevice(device)
                    }
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(permissionAction)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        scan()
    }

    fun stop() {
        close("stopped")
        runCatching { context.unregisterReceiver(receiver) }
    }

    @SuppressLint("MutableImplicitPendingIntent")
    private fun scan() {
        listener.onState(State.SCANNING)
        val device = usbManager.deviceList.values.firstOrNull { !isPhone(it) } ?: run {
            listener.onState(State.SCANNING, "no usb device")
            return
        }
        currentDevice = device
        listener.onState(State.REQUESTING_PERMISSION)
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(permissionAction).setPackage(packageName),
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
        )
        usbManager.requestPermission(device, intent)
    }

    private fun initDevice(device: UsbDevice) {
        currentDevice = device
        val conn = usbManager.openDevice(device) ?: run {
            close("open device failed")
            return
        }
        val iface = device.getInterface(0)
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            close("claim interface failed")
            return
        }
        connection = conn
        usbInterface = iface

        if (!isAccessoryMode(device)) {
            listener.onState(State.SWITCHING_ACCESSORY)
            val switched = runCatching { switchToAccessoryMode(conn) }.getOrDefault(false)
            // After control 53 the device re-enumerates; the ATTACHED broadcast
            // (or the next scan) picks up the accessory-mode device.
            releaseDevice()
            if (!switched) {
                close("switch to accessory mode failed")
            }
            return
        }

        endpointIn = iface.getEndpoint(0).takeIf { it.direction == UsbConstants.USB_DIR_IN }
        endpointOut = iface.getEndpoint(1).takeIf { it.direction == UsbConstants.USB_DIR_OUT }
        if (endpointIn == null || endpointOut == null) {
            close("bulk endpoints not found")
            return
        }

        running = true
        listener.onState(State.CONNECTED)
        readThread = Thread(this::readLoop, "carlife-aoa-read").apply { start() }
    }

    private fun readLoop() {
        val buffer = ByteArray(READ_CHUNK)
        while (running) {
            val conn = connection ?: break
            val ep = endpointIn ?: break
            val n = conn.bulkTransfer(ep, buffer, buffer.size, READ_TIMEOUT_MS)
            if (n > 0) {
                listener.onBytes(buffer, 0, n)
            } else if (n < 0) {
                close("bulk read failed: $n")
                break
            }
        }
    }

    /** Sends the transport header and body as two separate bulk transfers. */
    override fun writeHeaded(head: ByteArray, body: ByteArray) {
        sendBulk(head, body)
    }

    private fun sendBulk(head: ByteArray, body: ByteArray): Boolean {
        val conn = connection ?: return false
        val ep = endpointOut ?: return false
        if (head.isNotEmpty() && conn.bulkTransfer(ep, head, head.size, WRITE_TIMEOUT_MS) != head.size) return false
        var offset = 0
        while (offset < body.size) {
            val len = minOf(WRITE_CHUNK, body.size - offset)
            val chunk = if (offset == 0 && len == body.size) body else body.copyOfRange(offset, offset + len)
            if (conn.bulkTransfer(ep, chunk, len, WRITE_TIMEOUT_MS) != len) return false
            offset += len
        }
        return true
    }

    override fun write(frame: ByteArray) {
        sendBulk(ByteArray(0), frame)
    }

    private fun switchToAccessoryMode(conn: UsbDeviceConnection): Boolean {
        // 51: GET_PROTOCOL, must report 1 or 2
        val version = ByteArray(2)
        val got = conn.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR,
            CarLife.AOA_GET_PROTOCOL, 0, 0, version, 2, CTRL_TIMEOUT_MS,
        )
        if (got < 0) return false
        val protocol = (version[1].toInt() and 0xFF shl 8) or (version[0].toInt() and 0xFF)
        if (protocol < 1 || protocol > 2) return false

        // 52: identity strings, index 0..5
        val strings = arrayOf(
            CarLife.AOA_MANUFACTURER,
            CarLife.AOA_MODEL_NAME,
            CarLife.AOA_DESCRIPTION,
            CarLife.AOA_VERSION,
            CarLife.AOA_URI,
            CarLife.AOA_SERIAL_NUMBER,
        )
        strings.forEachIndexed { index, s ->
            val bytes = s.toByteArray(Charsets.UTF_8)
            val sent = conn.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
                CarLife.AOA_SEND_IDENT, 0, index, bytes, bytes.size, CTRL_TIMEOUT_MS,
            )
            if (sent < 0) return false
        }

        // 53: START — triggers re-enumeration in accessory mode
        val started = conn.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
            CarLife.AOA_START_ACCESSORY, 0, 0, null, 0, CTRL_TIMEOUT_MS,
        )
        return started >= 0
    }

    private fun releaseDevice() {
        runCatching {
            connection?.releaseInterface(usbInterface)
            connection?.close()
        }
        connection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null
    }

    fun close(reason: String) {
        running = false
        currentDevice = null
        readThread?.let { t -> t.interrupt() }
        readThread = null
        releaseDevice()
        listener.onState(State.CLOSED, reason)
        listener.onClosed(reason)
    }

    override fun close() {
        close("closed")
    }

    companion object {
        private const val TAG = "CarLifeAoa"

        private const val READ_CHUNK = 16 * 1024
        private const val WRITE_CHUNK = 16 * 1024
        private const val READ_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 5000
        private const val CTRL_TIMEOUT_MS = 5000

        private const val VID_IPHONE = 0x05AC
        private const val CLASS_MASS_STORAGE = 8

        fun isPhone(device: UsbDevice): Boolean = device.vendorId == VID_IPHONE

        fun isMassStorage(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == CLASS_MASS_STORAGE }

        fun isAccessoryMode(device: UsbDevice): Boolean =
            device.vendorId == CarLife.AOA_VID_ACCESSORY && device.productId in CarLife.AOA_ACCESSORY_PIDS.toList()

        fun findCarLifeCandidate(deviceList: Collection<UsbDevice>): UsbDevice? =
            deviceList.firstOrNull { !isPhone(it) && !isMassStorage(it) }
    }
}
