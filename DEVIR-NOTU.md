# Devir notu — yeni pencere buradan başlasın (9.11.2026)

Repo: `smallsword82/yunus` · Dal: `claude/live-vehicle-data` · HEAD push'lu: `55b2bb1`.
Kullanıcı Türkçe, kısa/net ilerlemeyi sever, riskli adımdan önce onay ister. **Sürerken komut giremez
(tek başına, yolcu yok).** Ayrıntı: `HANDOFF.md` (genel), `KINEX_NOTLARI.md`, `KILIT_IMZA_TAVANI.md`,
memory `blind-spot-side-electro`.

## Ortam
- Araç ADB: `adb connect 10.26.49.214:5555` — **IP her ağda değişir**, bağlanamazsan kullanıcıdan güncelini iste.
- Yardımcı: `app.yunus.launcher.ShellHelper`, port **19340**, token `f4293617-166a-40c1-8dcd-f3e566cfe4f9-1794171455402`
  (sabit). Olay akışı: 127.0.0.1:19340'a bağlan → token satırı gönder → `ok` → `watch` → `ev <Dev> 0x<id> <i|f|d> <val>`.
- `/data/local/tmp/yunus_rec` sentinel VAR → yardımcı start()'ta TÜM cihazlara abone (ADAS, Radar, Signal,
  Collision, Motor, VehicleData...). Ek cihaz aboneliği yalnız start()'ta okunur → değişince yardımcıyı yeniden başlat.
- **GÜVENLİK (memory `install-only-in-park`):** install/restart/ekran değişiminden önce vites P (`ev Get gear d 1.0`)
  ve hız 0 oku; okuyamazsan DUR. Yunus HOME olduğu için `am force-stop` sonrası sistem onu yeniden başlatır.

## Konu 1 — KİLİT (açık, sıradaki: araçta logcat)
**Kinex arabayı GERÇEKTEN kilitliyor** (kullanıcı test etti). Tam APK analizi (`~/Downloads/kinex.apk`, Flutter) bu oturumda yapıldı:
- Kilit yolu: Dart `"lock"/"unlock"` → native `m1` (`C0344a`) → `AbstractC0485f.a("setting", <metod>, args)` →
  **shell HAL daemon'una RPC** → `BYDAutoSettingDevice` reflection. **Bizim köprüyle aynı mimari.**
- Düz `setDoorLock` daemon'dan bile reddediliyor (`0x800003E8`) → Kinex **başka** bir Setting metodu kullanıyor.
  Metot adları `assets/kav.bin`'de **AES şifreli** (anahtar Dart'tan, offline çözülemedi) — grep bu yüzden kaçırıyor.
- **En güçlü aday: IKEY** (BYD'nin BT keyless-entry'si). `introspect-dilink3.txt`'te Setting'de:
  `sendBTMacToIKEY(byte[])` 1387, `getBTMacAddr()` 1243, `getIKEYBTLowPowerMode()` 1284.
- **Denenip reddedilenler** (ShellHelper komutları, hepsi duruyor): `lock` (set 0x18000009→reddedildi),
  `post` (postEvent 0x48E00037→yayın, kilitlemez), `lockViaSetting` (setDoorLock→0x800003E8).
- **Bu oturumda eklenen, HENÜZ araçta denenmemiş** (commit 55b2bb1): `lockOff`, `remoteUnlock`, **`ikey`**
  (`{"cmd":"ikey","mac":"aa:bb:cc:dd:ee:ff","retry":true}` → sendBTMacToIKEY + setDoorLock retry).

**Sıradaki adım (kesin):** araçta **Kinex kilitlerken logcat** al (`adb logcat -c` → Kinex'ten kilitle →
`adb logcat -d > kinex_lock.txt`); daemon metot+resultCode'u düz metin basar. + istersen btsnoop. Tam plan: `KILIT_IMZA_TAVANI.md`.

## Konu 2 — KÖR NOKTA sol/sağ (açık, sıradaki: sinyalli sürüş + logcat)
Amaç: Yunus kör nokta uyarısını doğru tarafta göstersin. Kinex/Electro tarafı veriyor, biz henüz ayıramadık.
- **9.11 canlı sürüş (yardımcı TCP akışı):** kullanıcı kör noktada araç olduğunu doğruladı.
  **Var/yok sinyali bulundu: `ADAS 0x0D500018` = 2 (araç var) / 1 (temiz), 6 temiz olay.** Ama **tarafsız**
  (`0x12200046`, `0x32D00034` de aynı; hepsi aktif→temiz, sol/sağ fark etmez).
- **DOW `0x41800020` (sol) / `0x41800022` (sağ)** bu yakalamada ATEŞLEMEDİ — çünkü akış yardımcının **filtreli
  poll**'undan geçiyor ([ShellHelper.kt:634](android/app/src/main/java/app/yunus/launcher/ShellHelper.kt:634),
  `v in 0..15`). Önceki logcat sürüşü sağı (0x41800022) **21 kez** ham yakalamıştı.
- **DERS: DOW tarafı için LOGCAT kullan (ham), yardımcı TCP akışı değil** — ya da ShellHelper'a filtresiz DOW oku ekle.
- Radar/Signal/Collision akışta hiç olay yollamadı. BSD `0x41800008` sadece 1↔2 toggle (tarafsız, kesin elendi).
- **Hipotez:** DOW şerit-değiştirme asistanı, yalnız o yöne **sinyal verince** ateşler.
  **Sıradaki pasif test:** kullanıcı sinyalli sürer (yanında araç varken), DOW'u **logcat'ten ham** yakala → taraf id'den belli olur.
- Ham drive1 log'u: origin-session scratchpad `dow_rec_drive1.log` (geçici). Ayrıntı: memory `blind-spot-side-electro`, HANDOFF madde 7.

## Commit durumu
- `55b2bb1` (lockOff/remoteUnlock/ikey + `KILIT_IMZA_TAVANI.md`) → push'lu.
- `HANDOFF.md` ve `DEVIR-NOTU.md` (bu dosya): **commit edilmemiş** (kullanıcı "bırak" dedi). HANDOFF'ta fork
  oturumundan gelen başka commit edilmemiş değişiklikler de var — sormadan commit etme.
- memory dosyaları repo dışı, kendiliğinden kalıcı.

## Kullanıcı sürüşe geçince
"hazırla" derse: temiz kayıt kur. Kilit için Kinex-logcat, kör nokta için sinyalli sürüş + DOW logcat — ikisi de
araçta, sürerken kullanıcıdan komut beklemeden.
