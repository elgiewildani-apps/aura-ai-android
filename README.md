# AURA AI 24/7 — Asisten Pribadi Android Native (Kotlin)

Aplikasi Android asli (`.apk` ringan ~5 MB, mendukung Android 7.0 hingga Android 14) dengan 5 mesin terintegrasi:
1. **Foreground Service + WakeLock 24/7:** Tetap aktif mendengarkan suaramu di latar belakang walau layar HP terkunci atau sedang membuka aplikasi lain.
2. **Continuous Indonesian Speech-to-Text + Text-to-Speech:** Ngobrol dua arah tanpa sentuh layar.
3. **Dual-Brain AI (Offline Command + Gemini 2.5 Flash):** Perintah HP langsung jalan 0ms offline, dan obrolan bebas 24 jam aktif setelah mengisi Gemini API Key.
4. **Accessibility Service (Kontrol Layar & Otomatisasi):** Membantu mengetik dan menekan tombol di **WhatsApp, KAI Access, Traveloka, Gojek, dan YouTube**, serta otomatis berhenti sebelum tombol bayar demi keamanan saldo.
5. **Notification Listener & Hardware Bridge:** Membacakan notifikasi masuk, memasang alarm/timer sistem asli Android, menyalakan senter LED, mengatur volume, menelepon kontak, dan membuka navigasi Google Maps.

## Cara Mengubah Paket Ini Menjadi File `.apk` Lewat Browser HP (Gratis 2 Menit)
1. Ekstrak file `AURA-Android-Native.zip` di HP kamu.
2. Buka **github.com** di browser HP, login (atau daftar gratis), lalu buat repository baru bernama `aura-ai-android`.
3. Pilih **uploading an existing file**, lalu unggah seluruh isi folder hasil ekstrak (pastikan folder `.github`, `app`, serta file `build.gradle.kts`, `settings.gradle.kts`, dan `gradle.properties` ikut terunggah).
4. Buka tab **Actions** di repository GitHub tersebut -> klik workflow **Build AURA Android APK** -> tunggu sekitar 2 menit hingga centang hijau muncul.
5. Unduh **AURA-Assistant-APK** di bagian **Artifacts** paling bawah halaman workflow, ekstrak file `.apk`-nya, lalu install di HP Android kamu.
