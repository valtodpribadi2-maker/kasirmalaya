package id.my.vallsite.kasir

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sisi native dari `assets/bluetooth-shim.js`. Shim membuat `navigator.bluetooth` di dalam WebView,
 * lalu semua perintahnya sampai ke sini lewat objek `AndroidBle`.
 *
 * - Printer BLE (GATT): dipindai, disambungkan, dan ditulis per potongan 20 byte.
 * - Printer Bluetooth klasik (SPP): ditampilkan sebagai satu "service virtual" supaya kode Web Bluetooth
 *   di situs tetap jalan; datanya dikirim lewat [BluetoothPrinter].
 *
 * Setiap fungsi yang bisa dipanggil JavaScript mengambil `reqId` dan hasilnya dikirim balik
 * ke `window.__kasirBle.done(reqId, ok, payload)`.
 */
@SuppressLint("MissingPermission") // izin diminta lewat MainActivity.withBlePermissions()
class WebBluetoothBridge(
    private val activity: MainActivity,
    private val webView: WebView,
    private val spp: BluetoothPrinter
) {

    private class Session(val device: BluetoothDevice, val classic: Boolean) {
        @Volatile var gatt: BluetoothGatt? = null
        @Volatile var connected = false
        @Volatile var servicesJson: String? = null
        @Volatile var connectReq: String? = null
        @Volatile var writeLatch: CountDownLatch? = null
        @Volatile var writeStatus = BluetoothGatt.GATT_SUCCESS
    }

    private class Found(val address: String, val name: String)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("webble", Context.MODE_PRIVATE)

    // ---- Dipanggil dari JavaScript ----------------------------------------------------------

    @JavascriptInterface
    fun requestDevice(reqId: String) {
        activity.runOnUiThread {
            val bt = adapter()
            if (bt == null) {
                fail(reqId, "NotFoundError", "Perangkat ini tidak punya Bluetooth")
                return@runOnUiThread
            }
            activity.withBlePermissions(
                onGranted = {
                    if (!bt.isEnabled) {
                        fail(reqId, "NetworkError", "Bluetooth mati, nyalakan dulu")
                    } else {
                        showPicker(reqId, bt)
                    }
                },
                onDenied = {
                    fail(
                        reqId, "SecurityError",
                        "Izin Bluetooth ditolak. Izinkan di Pengaturan > Aplikasi > Kedai Malaya."
                    )
                }
            )
        }
    }

    @JavascriptInterface
    fun connect(reqId: String, address: String) {
        activity.runOnUiThread {
            val bt = adapter()
            if (bt == null || !bt.isEnabled) {
                fail(reqId, "NetworkError", "Bluetooth mati, nyalakan dulu")
                return@runOnUiThread
            }
            val old = sessions[address]
            if (old != null && old.connected && old.servicesJson != null) {
                succeed(reqId, old.servicesJson!!)
                return@runOnUiThread
            }
            if (old != null) {
                sessions.remove(address, old)
                closeSession(old)
            }
            val device = try {
                bt.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                fail(reqId, "NetworkError", "Alamat printer tidak valid")
                return@runOnUiThread
            }
            if (device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                connectClassic(reqId, device)
            } else {
                connectGatt(reqId, device)
            }
        }
    }

    @JavascriptInterface
    fun disconnect(address: String) {
        activity.runOnUiThread {
            sessions.remove(address)?.let { closeSession(it) }
        }
    }

    /** mode: "any" | "response" | "noresponse" (sama seperti writeValue / WithResponse / WithoutResponse). */
    @JavascriptInterface
    fun write(
        reqId: String,
        address: String,
        serviceUuid: String,
        charUuid: String,
        base64: String,
        mode: String
    ) {
        io.execute {
            val s = sessions[address]
            if (s == null || !s.connected) {
                fail(reqId, "NetworkError", "GATT Server tidak terhubung")
                return@execute
            }
            val data = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                fail(reqId, "TypeError", "Data base64 tidak valid")
                return@execute
            }

            if (s.classic) {
                val r = spp.printBase64(base64)
                if (r == "ok") succeed(reqId, "") else fail(reqId, "NetworkError", r.removePrefix("error: "))
                return@execute
            }

            val gatt = s.gatt
            val ch = try {
                gatt?.getService(UUID.fromString(serviceUuid))?.getCharacteristic(UUID.fromString(charUuid))
            } catch (e: IllegalArgumentException) {
                null
            }
            if (gatt == null || ch == null) {
                fail(reqId, "NotFoundError", "Characteristic tidak ditemukan")
                return@execute
            }

            val canWrite = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            val canNoResp = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val type = when {
                mode == "noresponse" && canNoResp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                canWrite -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                canNoResp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else -> {
                    fail(reqId, "NotSupportedError", "Characteristic ini tidak bisa ditulis")
                    return@execute
                }
            }

            var offset = 0
            while (offset < data.size) {
                val n = minOf(CHUNK, data.size - offset)
                val chunk = data.copyOfRange(offset, offset + n)
                val latch = CountDownLatch(1)
                s.writeLatch = latch
                s.writeStatus = BluetoothGatt.GATT_SUCCESS

                if (!writeChunk(gatt, ch, chunk, type)) {
                    fail(reqId, "NetworkError", "Gagal mengirim data ke printer")
                    return@execute
                }
                val answered = latch.await(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
                if (!s.connected) {
                    fail(reqId, "NetworkError", "Koneksi ke printer terputus saat mencetak")
                    return@execute
                }
                if (!answered && type == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                    fail(reqId, "NetworkError", "Printer tidak merespons")
                    return@execute
                }
                if (s.writeStatus != BluetoothGatt.GATT_SUCCESS) {
                    fail(reqId, "NetworkError", "Printer menolak data (kode ${s.writeStatus})")
                    return@execute
                }
                if (type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) Thread.sleep(NO_RESPONSE_GAP_MS)
                offset += n
            }
            succeed(reqId, "")
        }
    }

    fun shutdown() {
        sessions.values.forEach { closeSession(it) }
        sessions.clear()
        io.shutdownNow()
    }

    // ---- Memilih printer ---------------------------------------------------------------------

    private fun showPicker(reqId: String, adapter: BluetoothAdapter) {
        val entries = ArrayList<Found>()
        val labels = ArrayList<String>()
        val listAdapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, labels)
        val last = prefs.getString(KEY_LAST, null)

        fun add(address: String, name: String) {
            if (entries.any { it.address == address }) return
            val label = (if (address == last) "★ " else "") + name + "  (" + address + ")"
            if (address == last) {
                entries.add(0, Found(address, name))
                labels.add(0, label)
            } else {
                entries.add(Found(address, name))
                labels.add(label)
            }
            listAdapter.notifyDataSetChanged()
        }

        // Perangkat yang sudah dipasangkan di pengaturan HP (printer klasik biasanya ada di sini).
        adapter.bondedDevices?.forEach { d ->
            val major = d.bluetoothClass?.majorDeviceClass
            if (major == BluetoothClass.Device.Major.AUDIO_VIDEO || major == BluetoothClass.Device.Major.PHONE) {
                return@forEach
            }
            add(d.address, d.name ?: d.address)
        }

        // Pemindaian BLE untuk printer yang belum dipasangkan.
        val scanner = adapter.bluetoothLeScanner
        var scanning = false
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                activity.runOnUiThread { add(result.device.address, name) }
            }

            override fun onScanFailed(errorCode: Int) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Pemindaian Bluetooth gagal (kode $errorCode)", Toast.LENGTH_LONG).show()
                }
            }
        }

        fun stopScan() {
            if (!scanning) return
            scanning = false
            try {
                scanner?.stopScan(callback)
            } catch (_: Exception) {
            }
        }

        if (scanner != null) {
            scanning = true
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            try {
                scanner.startScan(null, settings, callback)
                main.postDelayed({ stopScan() }, SCAN_MS)
            } catch (e: Exception) {
                scanning = false
            }
        }

        var picked = false
        AlertDialog.Builder(activity)
            .setTitle("Pilih printer Bluetooth")
            .setAdapter(listAdapter) { _, which ->
                picked = true
                stopScan()
                val f = entries[which]
                prefs.edit().putString(KEY_LAST, f.address).apply()
                succeed(reqId, JSONObject().put("id", f.address).put("name", f.name).toString())
            }
            .setNegativeButton("Batal", null)
            .setOnDismissListener {
                stopScan()
                if (!picked) fail(reqId, "NotFoundError", "Tidak ada printer yang dipilih")
            }
            .create()
            .show()

        Toast.makeText(activity, "Mencari printer… pastikan printer menyala", Toast.LENGTH_SHORT).show()
    }

    // ---- Sambungan ---------------------------------------------------------------------------

    private fun connectClassic(reqId: String, device: BluetoothDevice) {
        io.execute {
            val r = spp.connect(device.address)
            if (r == "ok") {
                val s = Session(device, classic = true)
                s.connected = true
                s.servicesJson = VIRTUAL_SERVICES
                sessions[device.address] = s
                succeed(reqId, VIRTUAL_SERVICES)
            } else {
                fail(reqId, "NetworkError", r.removePrefix("error: "))
            }
        }
    }

    private fun connectGatt(reqId: String, device: BluetoothDevice) {
        val s = Session(device, classic = false)
        s.connectReq = reqId
        sessions[device.address] = s
        s.gatt = device.connectGatt(activity, false, GattCallback(s), BluetoothDevice.TRANSPORT_LE)
        main.postDelayed({
            if (s.connectReq == reqId) {
                connectFailed(s, "Waktu habis saat menghubungi printer. Pastikan printer menyala dan dekat.")
            }
        }, CONNECT_TIMEOUT_MS)
    }

    private fun finishConnect(s: Session, json: String) {
        val req = synchronized(s) {
            val r = s.connectReq
            s.connectReq = null
            r
        } ?: return
        succeed(req, json)
    }

    /** Gagal menyambung lewat GATT. Perangkat berjenis ganda (dual) dicoba lagi lewat Bluetooth klasik. */
    private fun connectFailed(s: Session, message: String) {
        val req = synchronized(s) {
            val r = s.connectReq
            s.connectReq = null
            r
        } ?: return
        sessions.remove(s.device.address, s)
        closeSession(s)
        if (s.device.type == BluetoothDevice.DEVICE_TYPE_DUAL) {
            connectClassic(req, s.device)
        } else {
            fail(req, "NetworkError", message)
        }
    }

    private fun closeSession(s: Session) {
        s.connected = false
        s.writeLatch?.countDown()
        try {
            s.gatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            s.gatt?.close()
        } catch (_: Exception) {
        }
        s.gatt = null
        if (s.classic) spp.disconnect()
    }

    private inner class GattCallback(private val s: Session) : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                s.connected = true
                // Jeda singkat sebelum membaca layanan, supaya stabil di banyak HP.
                main.postDelayed({
                    if (!gatt.discoverServices()) {
                        connectFailed(s, "Gagal membaca layanan printer")
                    }
                }, 300)
            } else {
                val wasReady = s.connected && s.connectReq == null
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
                if (s.connectReq != null) {
                    connectFailed(
                        s,
                        "Koneksi ke printer gagal (kode $status). Matikan lalu nyalakan printer, lalu coba lagi."
                    )
                } else {
                    sessions.remove(s.device.address, s)
                    closeSession(s)
                    if (wasReady) notifyDisconnected(s.device.address)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectFailed(s, "Gagal membaca layanan printer (kode $status)")
                return
            }
            val json = servicesToJson(gatt.services)
            s.servicesJson = json
            finishConnect(s, json)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            s.writeStatus = status
            s.writeLatch?.countDown()
        }
    }

    // ---- Util --------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun writeChunk(
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        chunk: ByteArray,
        type: Int
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(ch, chunk, type) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = type
            ch.value = chunk
            gatt.writeCharacteristic(ch)
        }
    }

    private fun servicesToJson(services: List<BluetoothGattService>): String {
        val arr = JSONArray()
        for (svc in services) {
            val chars = JSONArray()
            for (c in svc.characteristics) {
                val p = c.properties
                chars.put(
                    JSONObject()
                        .put("uuid", c.uuid.toString())
                        .put("read", (p and BluetoothGattCharacteristic.PROPERTY_READ) != 0)
                        .put("write", (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
                        .put("writeNoResp", (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                        .put("notify", (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                        .put("indicate", (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                )
            }
            arr.put(JSONObject().put("uuid", svc.uuid.toString()).put("characteristics", chars))
        }
        return arr.toString()
    }

    private fun adapter(): BluetoothAdapter? =
        (activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun succeed(reqId: String, payload: String) = deliver(reqId, true, payload)

    private fun fail(reqId: String, name: String, message: String) {
        if (name != "NotFoundError") {
            activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
        }
        deliver(reqId, false, JSONObject().put("name", name).put("message", message).toString())
    }

    private fun deliver(reqId: String, ok: Boolean, payload: String) {
        val js = "window.__kasirBle&&window.__kasirBle.done(" +
            JSONObject.quote(reqId) + "," + ok + "," + JSONObject.quote(payload) + ")"
        activity.runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    private fun notifyDisconnected(address: String) {
        val js = "window.__kasirBle&&window.__kasirBle.disconnected(" + JSONObject.quote(address) + ")"
        activity.runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    private companion object {
        const val KEY_LAST = "last_address"
        const val CHUNK = 20
        const val SCAN_MS = 20_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val WRITE_TIMEOUT_S = 3L
        const val NO_RESPONSE_GAP_MS = 8L

        // Printer klasik (SPP) ditampilkan sebagai satu service/characteristic "virtual".
        const val VIRTUAL_SERVICES =
            "[{\"uuid\":\"00001101-0000-1000-8000-00805f9b34fb\",\"virtual\":true,\"characteristics\":" +
                "[{\"uuid\":\"00001101-0000-1000-8000-00805f9b34fb\",\"write\":true," +
                "\"writeNoResp\":true,\"virtual\":true}]}]"
    }
}
