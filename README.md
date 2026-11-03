# Kedai Malaya — APK web2app

Aplikasi Android (WebView) yang membuka `https://kasir.vallsite.my.id/kasir/`.
Nama aplikasi, warna, dan URL bisa diganti di:

| Yang diganti | File |
|---|---|
| URL kasir | `app/build.gradle.kts` → `START_URL` |
| Nama aplikasi | `app/src/main/res/values/strings.xml` |
| Warna | `app/src/main/res/values/colors.xml` |
| Ikon | `app/src/main/res/drawable/ic_launcher_foreground.xml` |
| ID aplikasi | `applicationId` di `app/build.gradle.kts` |

## Cara membuat APK

### Opsi A — GitHub Actions (tanpa install apa pun)
1. Buat repo baru di GitHub, unggah seluruh isi folder ini.
2. Buka tab **Actions** → **Build APK** → **Run workflow** (atau cukup push ke `main`).
3. Setelah selesai, unduh `kedai-malaya-apk` di bagian **Artifacts**, ekstrak, lalu pasang `app-release.apk` di HP.

### Opsi A2 — GitHub dari HP (unggah zip)
Browser HP tidak bisa mengunggah folder, jadi zip diunggah apa adanya dan diekstrak oleh GitHub.
Buat file `.github/workflows/build.yml` di repo dengan isi:

```yaml
name: Build APK

on:
  push:
    branches: [main]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Ekstrak zip terbaru
        run: |
          ZIP=$(git log -1 --diff-filter=AM --name-only --pretty=format: -- '*.zip' | grep -m1 '\.zip$')
          echo "Memakai: $ZIP"
          unzip -q -o "$ZIP"

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.9"

      - name: Build APK
        working-directory: kasir-apk
        run: gradle assembleRelease --no-daemon

      - uses: actions/upload-artifact@v4
        with:
          name: kedai-malaya-apk
          path: kasir-apk/app/build/outputs/apk/release/*.apk
```

Untuk update aplikasi cukup unggah zip yang baru (nama apa saja); workflow memakai zip yang paling baru diunggah.

### Opsi B — Android Studio
1. **File → Open** → pilih folder ini, tunggu sync selesai.
2. **Build → Build APK(s)**.
3. Hasil: `app/build/outputs/apk/release/app-release.apk` (pakai varian *release*).

### Opsi C — Terminal (sudah ada Android SDK dan Gradle 8.9+)
```
gradle assembleRelease
```

APK release ditandatangani dengan kunci debug supaya langsung bisa dipasang. Untuk Play Store, buat keystore sendiri.

## Fitur
- Membuka kasir layar penuh, login dan data lokal tetap tersimpan.
- Tombol Kembali mundur di dalam situs; tekan dua kali untuk keluar.
- Layar tetap menyala selama kasir dibuka (hapus `FLAG_KEEP_SCREEN_ON` di `MainActivity.kt` kalau tidak mau).
- Halaman "Tidak bisa terhubung" dengan tombol Coba lagi kalau internet putus.
- Tautan di luar domain kasir (WhatsApp, telepon, dll.) dibuka di aplikasi lain.
- Unggah file/foto dari situs berfungsi.
- Bluetooth untuk cetak struk (di bawah).

## Bluetooth

WebView Android tidak punya Web Bluetooth (`navigator.bluetooth`). Aplikasi ini menambahkannya sendiri:
`assets/bluetooth-shim.js` membuat `navigator.bluetooth` di dalam aplikasi, dan `WebBluetoothBridge.kt`
mengerjakannya di sisi Android. **Kode Bluetooth di situs tidak perlu diubah**: `requestDevice`,
`gatt.connect()`, `getPrimaryService`, `getCharacteristic`, `writeValue`, `writeValueWithoutResponse`
dan event `gattserverdisconnected` bekerja seperti di Chrome.

Yang didukung:
- **Printer BLE** (kebanyakan printer thermal yang dipakai lewat Web Bluetooth): dipindai, disambungkan lewat GATT,
  data ditulis per 20 byte.
- **Printer Bluetooth klasik (SPP)** yang sudah dipasangkan di Pengaturan Bluetooth HP: muncul di daftar,
  datanya dikirim lewat soket RFCOMM. Di sisi situs, printer ini tampak seperti satu service/characteristic
  tulis, sehingga UUID apa pun yang diminta kode situs akan cocok.

Cara pakai: ketuk tombol cetak/sambung printer di situs → muncul daftar printer (yang terakhir dipakai
di paling atas, bertanda ★) → pilih printer.

Izin: Android 12+ meminta "Perangkat di sekitar"; Android 11 ke bawah meminta Lokasi, dan **Lokasi harus
aktif** agar printer BLE terdeteksi.

Batasan: `readValue()` dan notifikasi belum didukung (tidak dibutuhkan untuk mencetak). Jika situs hanya
memakai `window.print()`, itu tidak memakai Bluetooth dan tidak terpengaruh jembatan ini.

### Jembatan sederhana `AndroidPrinter` (opsional)
Untuk situs yang mau memanggil printer langsung tanpa Web Bluetooth (hanya printer klasik/SPP):

| Fungsi | Kegunaan |
|---|---|
| `AndroidPrinter.choose()` | Tampilkan daftar printer terpasang; pilihan diingat |
| `AndroidPrinter.printText(teks)` | Cetak teks biasa (Latin-1) |
| `AndroidPrinter.printBase64(b64)` | Kirim byte ESC/POS mentah |
| `AndroidPrinter.savedName()` | Nama printer yang tersimpan (kosong kalau belum dipilih) |
| `AndroidPrinter.isConnected()` | Status koneksi |
| `AndroidPrinter.listPaired()` | JSON daftar printer terpasang |

Semua fungsi cetak mengembalikan `"ok"` atau `"error: ..."`. Deteksi aplikasi: `window.AndroidPrinter`
ada, dan user agent memuat `KasirApp`.

## Catatan
- `minSdk 26` (Android 8.0+).
- Situs harus memakai HTTPS (lalu lintas HTTP biasa diblokir).
