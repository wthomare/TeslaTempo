package org.teslatempo.drive

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One connection and one serialized GATT operation at a time; no addresses or payloads in logs. */
@SuppressLint("MissingPermission") // Entry points require runtime SCAN + CONNECT; revocations are caught by the session.
class TeslaBleTransport(private val context: Context) : AutoCloseable {
    private var gatt: BluetoothGatt? = null
    private var write: BluetoothGattCharacteristic? = null
    private var mtu = 23
    @Volatile private var closed = false
    private val events = Channel<Pair<String, Int>>(32)
    private val frames = Channel<ByteArray>(16)
    private val decoder = BleFrames()
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) event("connect", status)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) fail()
        }
        override fun onMtuChanged(g: BluetoothGatt, size: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = size.coerceIn(23,517)
            event("mtu", BluetoothGatt.GATT_SUCCESS) // 23-byte default remains usable if negotiation fails.
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { event("services", status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { event("descriptor", status) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { event("write", status) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != READ || closed) return
            try { decoder.append(value).forEach { if (!frames.trySend(it).isSuccess) fail() } }
            catch (_: Exception) { fail() }
        }
    }
    private fun event(name: String, status: Int) { if (!closed && !events.trySend(name to status).isSuccess) fail() }
    private fun fail() {
        val error = DriveFailure("Connexion BLE interrompue. Rapprochez le téléphone et reconnectez-vous.")
        events.close(error); frames.close(error)
    }
    private suspend fun await(name: String) = withTimeout(12_000) {
        val result = events.receive()
        if (result.first != name || result.second != BluetoothGatt.GATT_SUCCESS) throw DriveFailure("Opération Bluetooth refusée. Réessayez à proximité de la voiture.")
    }
    suspend fun connect(vin: String) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw DriveFailure("Bluetooth indisponible sur ce téléphone.")
        if (!adapter.isEnabled) throw DriveFailure("Activez le Bluetooth du téléphone.")
        val scanner = adapter.bluetoothLeScanner ?: throw DriveFailure("Recherche Bluetooth indisponible.")
        val device = withTimeout(20_000) {
            suspendCancellableCoroutine<BluetoothDevice> { continuation ->
                val scan = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (result.scanRecord?.deviceName != advertisementName(vin)) return
                        runCatching { scanner.stopScan(this) }
                        if (continuation.isActive) continuation.resume(result.device)
                    }
                    override fun onScanFailed(code: Int) {
                        if (continuation.isActive) continuation.resumeWithException(DriveFailure("Recherche BLE refusée par Android. Réessayez dans quelques instants."))
                    }
                }
                continuation.invokeOnCancellation { runCatching { scanner.stopScan(scan) } }
                try { scanner.startScan(listOf(ScanFilter.Builder().setDeviceName(advertisementName(vin)).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan) }
                catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        }
        if (closed) throw CancellationException()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw DriveFailure("Impossible d’ouvrir la connexion BLE.")
        val g = gatt!!
        await("connect")
        if (g.requestMtu(247)) await("mtu")
        if (!g.discoverServices()) throw DriveFailure("Services BLE indisponibles.")
        await("services")
        val service = g.getService(SERVICE) ?: throw DriveFailure("Service Tesla BLE introuvable.")
        write = service.getCharacteristic(WRITE) ?: throw DriveFailure("Canal BLE Tesla absent.")
        val read = service.getCharacteristic(READ) ?: throw DriveFailure("Lecture BLE Tesla absente.")
        if (!g.setCharacteristicNotification(read, true)) throw DriveFailure("Notifications BLE refusées.")
        val descriptor = read.getDescriptor(CCC) ?: throw DriveFailure("Notifications BLE non disponibles.")
        val value = if (read.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (g.writeDescriptor(descriptor, value) != BluetoothStatusCodes.SUCCESS) throw DriveFailure("Notifications BLE refusées.")
        await("descriptor")
    }
    suspend fun send(bytes: ByteArray) {
        val g = gatt ?: throw DriveFailure("Bluetooth non connecté.")
        val characteristic = write ?: throw DriveFailure("Bluetooth non prêt.")
        val framed = BleFrames.encode(bytes)
        for (chunk in framed.asList().chunked((mtu - 3).coerceAtMost(244))) {
            if (g.writeCharacteristic(characteristic, chunk.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) != BluetoothStatusCodes.SUCCESS)
                throw DriveFailure("Écriture BLE refusée.")
            await("write")
        }
    }
    suspend fun response(session: TeslaSession, handshake: Boolean = false): ByteArray = withTimeout(12_000) {
        var result: ByteArray
        do { result = frames.receive() } while (!session.matches(result, handshake))
        result
    }
    override fun close() {
        closed = true
        runCatching { gatt?.disconnect() }; runCatching { gatt?.close() }
        gatt = null; write = null; events.close(); frames.close()
    }
    companion object {
        private val SERVICE = UUID.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e")
        private val WRITE = UUID.fromString("00000212-b2d1-43f0-9b88-960cebf8b91e")
        private val READ = UUID.fromString("00000213-b2d1-43f0-9b88-960cebf8b91e")
        private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
