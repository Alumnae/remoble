package ee.nekoko.remoble.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
internal class GattClient(private val context: Context, private val device: BluetoothDevice) : Closeable {
    companion object {
        private val CLIENT_CONFIGURATION = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TIMEOUT_SECONDS = 15L
    }

    private var gatt: BluetoothGatt? = null
    private var connectOperation = PendingOperation()
    private var servicesOperation = PendingOperation()
    private var descriptorOperation: PendingOperation? = null
    private var writeOperation: PendingOperation? = null
    private val notifications = LinkedBlockingQueue<ByteArray>()
    @Volatile private var disconnected = false
    @Volatile var mtu: Int = 23
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != null && g !== gatt) {
                g.close()
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                connectOperation.succeed()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnected = true
                val error = IllegalStateException("BLE disconnected (status=$status)")
                connectOperation.fail(error)
                servicesOperation.fail(error)
                writeOperation?.fail(error)
                descriptorOperation?.fail(error)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            completeStatus(servicesOperation, status, "service discovery")
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            notifications.offer(characteristic.value.copyOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.offer(value.copyOf())
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeOperation?.let { completeStatus(it, status, "characteristic write") }
        }

        @Deprecated("Deprecated in API 33")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorOperation?.let { completeStatus(it, status, "descriptor write") }
        }

    }

    fun connect(attempts: Int = 3) {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                connectOnce()
                return
            } catch (error: Throwable) {
                lastError = error
                closeGatt()
                if (attempt + 1 < attempts) Thread.sleep(350L * (attempt + 1))
            }
        }
        throw IllegalStateException("Unable to connect to BLE reader after $attempts attempts", lastError)
    }

    private fun connectOnce() {
        disconnected = false
        connectOperation = PendingOperation()
        servicesOperation = PendingOperation()
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
        connectOperation.await("connection")
        gatt!!.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        gatt!!.requestMtu(247)
        check(gatt!!.discoverServices()) { "Unable to start BLE service discovery" }
        servicesOperation.await("service discovery")
    }

    fun service(uuid: UUID): BluetoothGattService =
            gatt?.getService(uuid) ?: error("BLE service $uuid not found")

    fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        check(gatt?.setCharacteristicNotification(characteristic, true) == true) {
            "Unable to enable local BLE notifications"
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIGURATION)
                ?: error("Client configuration descriptor not found for ${characteristic.uuid}")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        descriptorOperation = PendingOperation()
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt!!.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt!!.writeDescriptor(descriptor)
        }
        check(started) { "Unable to write notification descriptor" }
        descriptorOperation!!.await("notification setup")
    }

    fun clearNotifications() = notifications.clear()

    fun awaitNotification(
            timeoutSeconds: Long = TIMEOUT_SECONDS,
            predicate: (ByteArray) -> Boolean = { true }
    ): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val value = notifications.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: throw java.util.concurrent.TimeoutException("BLE notification timeout")
            if (predicate(value)) return value
        }
        throw java.util.concurrent.TimeoutException("BLE notification timeout")
    }

    fun writeValue(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            maxChunkSize: Int = 240
    ) {
        val chunkSize = (mtu - 3).coerceIn(20, maxChunkSize)
        value.asList().chunked(chunkSize).forEach { chunk ->
            writeChunk(characteristic, chunk.toByteArray())
        }
    }

    @Synchronized
    fun exchange(
            characteristic: BluetoothGattCharacteristic,
            request: ByteArray,
            expectedLength: (ByteArray) -> Int?
    ): ByteArray {
        check(!disconnected) { "BLE device is disconnected" }
        notifications.clear()
        val chunkSize = (mtu - 3).coerceIn(20, 240)
        request.asList().chunked(chunkSize).forEach { chunk ->
            writeChunk(characteristic, chunk.toByteArray())
        }

        val output = ByteArrayOutputStream()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val part = notifications.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: throw java.util.concurrent.TimeoutException("BLE response timeout")
            output.write(part)
            val bytes = output.toByteArray()
            val expected = expectedLength(bytes)
            if (expected != null && bytes.size >= expected) return bytes.copyOf(expected)
        }
        throw java.util.concurrent.TimeoutException("BLE response timeout")
    }

    private fun writeChunk(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val canWrite = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val canWriteWithoutResponse =
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        check(canWrite || canWriteWithoutResponse) { "BLE characteristic is not writable" }
        val writeType =
                if (!canWrite && canWriteWithoutResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
        characteristic.writeType = writeType
        writeOperation = PendingOperation()
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt!!.writeCharacteristic(characteristic, value, writeType) ==
                    BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt!!.writeCharacteristic(characteristic)
        }
        check(started) { "Unable to start BLE characteristic write" }
        writeOperation!!.await("characteristic write")
    }

    override fun close() {
        disconnected = true
        closeGatt()
    }

    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        gatt?.close()
        gatt = null
    }

    private fun completeStatus(pending: PendingOperation, status: Int, operation: String) {
        if (status == BluetoothGatt.GATT_SUCCESS) pending.succeed()
        else pending.fail(IllegalStateException("BLE $operation failed (status=$status)"))
    }

    private class PendingOperation {
        private val latch = CountDownLatch(1)
        @Volatile private var error: Throwable? = null

        fun succeed() = latch.countDown()
        fun fail(cause: Throwable) {
            error = cause
            latch.countDown()
        }

        fun await(name: String) {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw java.util.concurrent.TimeoutException("BLE $name timeout")
            }
            error?.let { throw it }
        }
    }
}
