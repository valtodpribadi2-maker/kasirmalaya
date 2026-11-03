package id.my.vallsite.kasir

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Jembatan cetak untuk situs. Di dalam WebView, Web Bluetooth tidak tersedia,
 * jadi situs memanggil objek `window.AndroidPrinter` ini.
 *
 * Mendukung printer thermal Bluetooth klasik (SPP/RFCOMM) yang sudah dipasangkan
 * di Pengaturan Bluetooth HP. Semua fungsi mengembalikan "ok" atau "error: ...".
 */
@SuppressLint("MissingPermission") // izin dicek lewat hasPermission()
class BluetoothPrinter(private val activity: Activity) {

    private val prefs = activity.getSharedPreferences("printer", Context.MODE_PRIVATE)
    private val lock = Any()
    private var socket: BluetoothSocket? = null

    // ---- Bisa dipanggil dari JavaScript -------------------------------------------------

    @JavascriptInterface
    fun isAvailable(): Boolean = adapter() != null

    /** JSON: [{"name":"...","address":"AA:BB:..."}] untuk printer yang sudah dipasangkan. */
    @JavascriptInterface
    fun listPaired(): String {
        val arr = JSONArray()
        pairedDevices().forEach {
            arr.put(JSONObject().put("name", it.name ?: it.address).put("address", it.address))
        }
        return arr.toString()
    }

    /** Menampilkan daftar printer terpasang untuk dipilih. Pilihan diingat untuk seterusnya. */
    @JavascriptInterface
    fun choose() {
        activity.runOnUiThread {
            if (!hasPermission()) {
                toast("Izinkan akses Bluetooth untuk aplikasi ini di pengaturan HP")
                return@runOnUiThread
            }
            val devices = pairedDevices()
            if (devices.isEmpty()) {
                toast("Belum ada printer terpasang. Pasangkan dulu di Pengaturan Bluetooth HP.")
                return@runOnUiThread
            }
            val names = Array<CharSequence>(devices.size) { devices[it].name ?: devices[it].address }
            AlertDialog.Builder(activity)
                .setTitle("Pilih printer")
                .setItems(names) { _, index -> connectInBackground(devices[index]) }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    @JavascriptInterface
    fun connect(address: String): String = connectTo(address)

    @JavascriptInterface
    fun disconnect() {
        synchronized(lock) { closeQuietly() }
    }

    @JavascriptInterface
    fun isConnected(): Boolean = synchronized(lock) { socket?.isConnected == true }

    @JavascriptInterface
    fun savedName(): String = prefs.getString(KEY_NAME, "") ?: ""

    /** Cetak teks biasa (Latin-1), diakhiri sedikit baris kosong. */
    @JavascriptInterface
    fun printText(text: String): String =
        send(INIT + text.toByteArray(Charsets.ISO_8859_1) + FEED)

    /** Kirim byte ESC/POS mentah (base64), mis. untuk logo, huruf besar, atau potong kertas. */
    @JavascriptInterface
    fun printBase64(data: String): String {
        val bytes = try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return "error: data base64 tidak valid"
        }
        return send(bytes)
    }

    // ---- Internal -----------------------------------------------------------------------

    private fun adapter(): BluetoothAdapter? =
        (activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun pairedDevices(): List<BluetoothDevice> {
        if (!hasPermission()) return emptyList()
        val bonded = adapter()?.bondedDevices ?: return emptyList()
        return bonded.sortedBy { it.name ?: it.address }
    }

    private fun connectInBackground(device: BluetoothDevice) {
        Thread {
            val result = connectTo(device.address)
            toast(if (result == "ok") "Printer terhubung: ${device.name ?: device.address}" else result)
        }.start()
    }

    private fun connectTo(address: String): String {
        return synchronized(lock) {
            if (!hasPermission()) return "error: izin Bluetooth belum diberikan"
            val bt = adapter() ?: return "error: perangkat ini tidak punya Bluetooth"
            if (!bt.isEnabled) return "error: Bluetooth mati, nyalakan dulu"
            val device = try {
                bt.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                return "error: alamat printer tidak valid"
            }

            closeQuietly()
            try {
                openSocket(device, secure = true)
            } catch (e: IOException) {
                closeQuietly()
                try {
                    openSocket(device, secure = false)
                } catch (e2: IOException) {
                    closeQuietly()
                    return "error: gagal terhubung ke printer (${e2.message ?: "tidak ada respons"})"
                }
            }

            prefs.edit()
                .putString(KEY_ADDRESS, address)
                .putString(KEY_NAME, device.name ?: address)
                .apply()
            "ok"
        }
    }

    private fun openSocket(device: BluetoothDevice, secure: Boolean) {
        val s = if (secure) {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } else {
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        }
        socket = s
        s.connect()
    }

    private fun ensureConnected(): String {
        if (socket?.isConnected == true) return "ok"
        val address = prefs.getString(KEY_ADDRESS, null)
            ?: return "error: printer belum dipilih, panggil AndroidPrinter.choose() dulu"
        return connectTo(address)
    }

    private fun send(data: ByteArray): String {
        synchronized(lock) {
            // Percobaan kedua: sambung ulang otomatis kalau koneksi sempat putus.
            for (attempt in 0..1) {
                val ready = ensureConnected()
                if (ready != "ok") return ready
                try {
                    val out = socket!!.outputStream
                    var offset = 0
                    while (offset < data.size) {
                        val n = minOf(CHUNK, data.size - offset)
                        out.write(data, offset, n)
                        offset += n
                    }
                    out.flush()
                    return "ok"
                } catch (e: IOException) {
                    closeQuietly()
                }
            }
            return "error: gagal mengirim ke printer, pastikan printer menyala dan dekat"
        }
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    private fun toast(message: String) {
        activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
        const val CHUNK = 512
        val INIT = byteArrayOf(0x1B, 0x40) // ESC @  (reset printer)
        val FEED = byteArrayOf(0x1B, 0x64, 0x03) // ESC d 3  (maju 3 baris)
    }
}
