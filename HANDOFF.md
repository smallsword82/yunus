# Devir notu — Yunus (BYD Dolphin, DiLink 3)

Tarih: 30.10.2026 (üçüncü oturum sonu) · Dal: `claude/live-vehicle-data` · **PR: smallsword82/yunus#1** (push'lu)
APK: `release/yunus-0.1-test.apk` · Son commit: `1c80e5e`

## EN SON DURUM (yeni pencere buradan başlasın)

**Commit'li + push'lu (PR #1):** `14629e6` şarj istasyonu+kablo sağ-ön köşede (kapı açılınca kabloya değmiyor) ·
`c4c213e` müzik kartı kapağı şarkı değişince yenileniyor (`MediaBridge.art()` null'ı önbelleğe almıyor) ·
`1c80e5e` kilit durumu okunuyor.

**COMMIT'SİZ (çalışma ağacında, araçta doğrulandı — önce commit'le):**
- `viewer/launcher.html`: 8 sn'lik MapLibre fallback timer'ı **cihazda kapalı** (`&& !(window.YunusNative && YunusNative.embedApp)`)
  → açılışta önce Yunus haritası sonra Google Maps "çifte yük" bitti.
- `MainActivity.kt` `AppEmbed.launchIfNew`: map slot'unda Maps açıldıktan **4,5 sn sonra my-location (◎) butonuna
  sentetik dokunuş** (`t <display> 0/1 0 x y`, x = w−45dp, y = h−122dp) → araç ortalanır + takip modu. Araçta doğrulandı.
- Embed çözünürlüğünü düşürme (renderScale 0.67) **denendi, kullanıcı SEVMEDİ, geri alındı**. Tekrar önerme.
  Harita display'i zaten pencereyle birebir: `#map` (720,48) 1200×920, k=dpr×vvScale=1.

**Kilit — kapandı:** OKUMA çalışıyor (DoorLock+Ota dinleyicisi, `Ota 0x40700008`: 1 açık / 2 kilitli, `CAR_HAL_KEEP`'te).
KOMUT HAL'den **mümkün değil**: `set()` 0x80000400 döner; çalışan uygulama (`br.com.rory.electro`) BT telefon-anahtarı
taklidiyle kilitliyor, gönderdiği `postEvent(1001, 0x48E00037, 1)` sadece durum yayını — bizden birebir aynısı kilitlemiyor.
Gösterge salt-okunur. (Helper'da `lock`/`post` cmd kancaları duruyor, deney için.) Ayrıntı: memory `door-lock-read-gated-command`.

**Navigasyon:** Google Maps nav modunda yatay düzen (paneller solda, araç sağ-altta) — normal, sorun değil.
Test için `am start --display <id> -a VIEW -d 'google.navigation:q=…'` kullanılabilir.

## BU OTURUMUN ATILIMI: araç verisi gerçekten okunuyor VE kumanda ediliyor

Kinex'in yöntemini Yunus'a ekledik. Eski "cam/perde/kilit için BYD imzası şart" sonucu **çürüdü**.
Kabuk yardımcısı (shell, uid 2000, `app_process`) artık BYD HAL cihazlarını **izin denetimini geçiren
bir `ContextWrapper` ile** kuruyor; imzalı `_GET`/`_SET` getter'ları çalışıyor.

**`ShellHelper.kt` → `CarWatch.Permissive`** (bağlam sarıcı): `ActivityThread.getSystemContext()`'i sarar,
- `checkSelfPermission` / `checkCallingOrSelfPermission` / `checkCallingPermission` / `checkPermission` → GRANTED
- `enforceCallingOrSelfPermission` / `enforceCallingPermission` → boş
- `getPackageName` / `getOpPackageName` → `"com.android.shell"` (uid 2000 ile eşleşsin diye)
- `getApplicationContext` → this
Kinex'in jar'ındaki `Lb/W;` sınıfının aynısı. Bununla Bodywork/DoorLock/Charging/Instrument kuruluyor.

**`CarWatch.GETTERS`** (her saniye okunuyor, yalnız değişince gönderiliyor):
- `soc` `getElecPercentageValue`, `range` `getElecDrivingRangeValue`, `odo` `getTotalMileageValue` (Statistic) ✅
- `steer` `getSteeringWheelValue(1)` (±780 direksiyon derecesi), `winFL/FR/RL/RR` `getWindowOpenPercent(1..4)`,
  `shade` `getSunroofWindowblindPosition` (Bodywork) ✅
- `chgGun` `getChargingGunState`, `chgState` `getChargingState`, `chgPower` `getChargingPower`,
  `restMin` `getChargingRestTime` → `[saat, dakika]` dizisi (Charging) ✅
- `lock` `getDoorLockStatus(1)` → **0 (geçersiz) dönüyor, çalışmıyor** (Kinex dinleyiciyle okuyor, getter'la değil)
- `gear` `getCurrentGear` → **SecurityException "package android ≠ uid 2000"** (getPackageName override çözmedi); vites `P`'de kalıyor
- `poll()` artık IntArray döndüren getter'ları işler (getChargingRestTime).

**Komutlar** (`YunusNative.command(json)` → yardımcı `cmd <json>` → bodywork, aynı bağlamla):
- Cam: `setBodyWindowCtrlState(area, state)`. area 1=FL 2=FR 3=RL 4=RR. state sabitleri **reflection'la** okunuyor:
  `WINDOW_CLOSE=2`, `WINDOW_OPEN_FULL=1`, `WINDOW_OPEN_HALF=4`. **Araçta doğrulandı** (ön sol cam 100→0).
- Perde: `setSunshadeState(yüzde 0..100)` — 0 kapat, 100 aç. **Araçta doğrulandı**.
  ÖNEMLİ: perde YAZMA yüzde, OKUMA ise **konum kodu**: `getSunroofWindowblindPosition` → 1 tam açık, 2 tam kapalı,
  3 yarım, 4 durdu, 0 geçersiz (yüzde DEĞİL). Sayfada `'Get shade'` bunu yüzdeye çeviriyor.

**Sürükleme davranışı:** araçta cam/perde noktasını sürükleyince **sadece nokta** parmağı izler; cam/perde
aracın raporladığı gerçek konuma animasyonla gelir (komut sonrası). Önizlemede eski davranış (cam parmağı izler).

**Aç-kapa düzeltmesi (önemli):** Yunus kapatılıp açılınca yardımcı süreç ayakta kalır; yeniden bağlanan sayfaya
`CarWatch.subscribe` artık değerleri **doğrudan o bağlantıya** gönderir + `getAllStatus`'u yeniden tetikler
(önceden yayınla gönderiyordu, eski bağlantı bozunca eksik gelirdi → "açıp kapatınca hiçbir şey gelmiyor"). Çözüldü.

## Şarj arayüzü (bu oturumda yapıldı, araçta doğrulandı)

- **Tespit:** `getChargingGunState == 2` (tabanca takılı) → kablo/animasyon gösterilir. (Şarj durumu `0x34400018`
  yalnız değişince geldiğinden, araç Yunus açılmadan takılıysa hiç gelmez; o yüzden getter.) `showCharging()`
  artık şarjı `chargeState`'e göre AÇIP KAPATMIYOR (sadece metin) — tabanca durumu belirler.
- **Yandan görünüm:** şarjda `PRESETS.charge` (theta 270, phi 83, r 92), `CHARGE_TARGET (0, 0.75, -0.9)` — araç+kablo+istasyon
  sığar. `onCarLoaded`'da araç yüklenince `S.charging` ise tekrar uygulanır (yoksa varsayılana sıfırlanıyordu).
- **Panel** `#chargeInfo`: sağ üst, **sabit 230px genişlik**, **% yok** (üst çubuk pili gösteriyor),
  ⚡ **çekilen kW** + kalan süre. Üst çubuk pili `#battBox.charging` → şimşek + yeşil çerçeve.
- **kW kalibrasyonu (göstergede 3,0 kW iken):** `chargeKw = (5000 - battCurrent) * 0.1 * (battVolt || 420) / 1000`.
  `battCurrent` = `Statistic 0x44A00020` (STATISTIC_INSTANTANEOUS_CURRENT, ofset 5000, 0,1 A/adım, <5000 = şarj).
  `battVolt` = `Charging 0x44400008` (V; olay, `CAR_HAL_KEEP`'te saklanıyor; gelene kadar nominal 420). `getChargingPower` 0 dönüyor.
- **Kalan süre:** `getChargingRestTime` → `[saat, dakika]`, her saniye okunur → saat kaybolmaz.
- **Kablo konumu:** modeldeki `ChargeSocket` düğümünün dünya konumuna hizalanıyor (`buildChargeGear()`), ama
  **modelde soket ön-sol'da**. Gerçek Dolphin portu arka ise `HOT.hCharge`'ı elle arkaya al.

## Diğer bu oturum (hepsi commit'li)
- **Dörtlü okları:** üst çubukta sol+sağ ok artık dörtlüyle birlikte yanıp söner (`updateSignalArrows`).
- **Müzik kartı "Başlat":** arka planda başlamayan uygulama (BYD Spotify kabuğu `com.vivid.music.byd`, YT Music)
  3. pencerede açılır. Kart kapağı kutuda tam, arka plan bulanık.
- **Sürücü resmi:** `com.byd.diLinkAccount` → `content://com.byd.accountProvider/avatar` (`CarUser.kt`). Ad gelmiyor (BYD beyaz listesi), Yunus › Ekran'dan.
- **Ön tekerler — DENENDİ, GERİ ALINDI (c7bcd82 → fc5b679).** Model dört tekeri tek mesh çiziyor
  (`dolphin_parts.glb`: `tyre_Tire_0`, rim/disk hepsi birleşik). Üçgen bölme **sol ön tekeri kaybetti + dışarı
  parça taşırdı**. Front = **+z** (dünya uzayı; bagaj/far ön). Güvenli yöntem: ön teker için **sıkı sınır
  kutusu** (üç köşesi de içinde) kullan, mudflap/köprü üçgenleri hariç tut. `STEER_RATIO ≈ 15`, `STEER_SIGN`.

## Açık işler (öncelik sana göre; kullanıcı sonuç görmek ister, kısa-net ilerle)

1. **Klima (AC) kontrolü** (kullanıcı çok istedi). Okuma çalışıyor (acOn/acTemp/fan, CarHal). Kontrol: AC setter'ları
   `setAcTemperature(IIII)` (yarım-derece *2, zone/source/unit arg), `setAcWindLevel(II)` (source,level),
   `setAcControlMode(II)`, `setAcWindMode`, `setAcDefrostState(III)`. Kinex `HalAcListener` tam arg eşlemesini içeriyor
   (`denormalizeAcTemp: doubling`). Yardımcıya Permissive bağlamla ekle, her kontrolü araçta tek tek kalibre et.
2. ~~Kilit~~ — kapandı (yukarıda "EN SON DURUM").
3. ~~El freni + kemer~~ — **çözüldü (31.10).** Getter'lar: `Gearbox.getParkBrakeSwitch` (1 çekili) → `tt.epb`, durum
   çubuğunda gösteriliyor; `SafetyBelt.getSafetyBeltStatus(1)` (sürücü; 1 takılı, 0 değil) → `S.belts.drv`. Koltuk doluluğu
   okunamadığından kemer ışığı yalnız **hız ≥ 5 km/s** iken yanar. `Instrument.getELECParkingState/Color` → 65535 (işe yaramaz).
   Park freni bırakılınca 0 geldiği ve takılı kemerin 1 döndüğü araçta henüz görülmedi (araç park/şarjdaydı).
4. **Radyo RDS logosu:** (müzik kapağı düzeldi) radyo (`com.byd.mediacenter`) RDS'ten istasyon logosu veriyor mu?
5. **Harita hızı:** çifte yük + ortalama çözüldü (commit'siz). Kalan jank Yunus'un kendisi (~38 ms/kare, gfxinfo):
   kaynağı **3D araç WebGL'inin sürekli render'ı** — on-demand render / pixelRatio düşürme düşünülebilir. Maps kendisi ~105 ms/kare.
6. **Şarj portu konumu:** modeldeki soket ön-sol; gerçekte arkaysa `HOT.hCharge`'ı arkaya al.
7. **Vites:** `getCurrentGear` paket/uid hatası (çözülmedi); cihazda vites `P` varsayılanda.

## Nasıl çalışılıyor (önemli)

- **İzin dosyası `.claude/settings.local.json`** (kullanıcı oluşturdu, GEREKLİ): `Bash(./gradlew:*)`, `Bash(adb:*)`,
  `Bash(cp:*)`, `Bash(git:*)` izinleri. Olmadan auto-mode sınıflandırıcısı derlemeyi engeller — çünkü kod bir izin-denetimi
  aşan bağlam sarıcı içeriyor ("Security Weaken"). Ajan bu dosyayı **kendisi yazamaz** (Self-Modification); kullanıcı yazar.
- **Derleme:** `android/` içinde `./gradlew assembleDebug` → `app/build/outputs/apk/debug/yunus-0.1-test.apk` → `release/`'e kopyala.
- **Yardımcı kodu değiştiyse** (ShellHelper.kt): kurulumdan sonra **eski yardımcı sürecini öldür** ki yeni kod yüklensin:
  `adb shell "ps -A -o PID,ARGS | grep app.yunus.launcher.ShellHelper" | awk '{print $1}'` → `kill -9`, sonra Yunus'u yeniden başlat.
  Sayfa-only değişiklikte yardımcıyı öldürmeye gerek yok, APK kur + Yunus'u yeniden başlat yeter.
- **Yeniden başlat:** `adb shell am force-stop app.yunus.launcher; adb shell am start -n app.yunus.launcher/.MainActivity`.
- **Sayfayı/veriyi incelemek (CDP):** `PID=$(adb shell pidof app.yunus.launcher); adb forward tcp:9222 localabstract:webview_devtools_remote_$PID`,
  sonra Runtime.evaluate ile `window.YunusCarData` (ham olaylar/getter'lar), `window.yunusCar({...})` (telemetri enjekte, test için),
  `document.getElementById(...)`. `S` modül kapsamında — doğrudan okunamaz; DOM'dan ya da `window.YunusCarData`'dan bak.
- **Framework getter/sabit adları:** `framework.jar` dexdump'ı taşındı — `.../scratchpad/fw/classes*.txt`. Cihaz sınıfı:
  `Landroid/hardware/bydauto/<dev>/BYDAuto<Dev>Device;`. Feature ID → isim: `bridge/bydauto_feature_ids.json`.
- **Kinex jar'ları** (yöntem kaynağı): `kinex_hal_api_service.jar` (bağlam sarıcı `Lb/W;`, HalDoorLockListener…).
- **ADB tap ile test:** ekranda düğmesi olanı ADB `input tap` + `logcat postEvent` ile dene, sonra eski haline getir — kullanıcıyı uğraştırma.
- Chrome 74 (araç tarayıcısı): `color-mix`, flex-`gap` yedeği, `??`, `Promise.any`, `min()`, `backdrop-filter` YOK.

## Çalışma ortamı
- Araç: `adb connect 10.184.30.214:5555` (kablosuz ADB açık; Yunus'un ADB anahtarı araçta onaylı).
- Yardımcı logu: `adb shell cat /data/local/tmp/yunus_helper.log` (yalnız hatalar + "watch: listening to …").
- Emülatör (ev testi): `emulator -avd yunus34` (Android 14, 1920×1080/240dpi; araç verisi/BYD uygulamaları yok).
- Masaüstü önizleme: `python -m http.server 8765 --directory viewer` → `launcher.html` (demo modu).

## Araçta bilinenler (değişmedi)
- Kısayol paneli = BYD SystemUI `BydQSBar`; araç düğmeleri BYD imzalı. Durum şeridini aşağı çekince yardımcı `qsbar`
  ile BYD panelini açıyor (sabit iki kaydırma; `cmd statusbar collapse` kapatır). Yunus'un kendi panelini kullanıcı İSTEMEDİ — önerme.
- Merkezi kilit hiçbir BYD *ekranında* yok; kilit HAL üzerinden (yukarı bak). 360°/yan kamera yalnız BYD kamera uygulamasında (`com.byd.avc`).
- Lastik basıncı ~214–215 geliyor (birim doğrulanacak). Bildirim erişimini Yunus ADB ile kendine verir.

## Kullanıcı hakkında
Türkçe konuşuyor. Sonuç görmek ister; koddan çıkarılabilecek şeyler için test yaptırmaktan hoşlanmaz
(ama kW gibi kalibrasyon gereken değerlerde göstergeden tek bir değeri söyleyebilir). Uzun araştırmada sabırsız — kısa, net ilerle.
Bu oturumda çok sayıda küçük istek arka arkaya geldi; her birini yapıp commit'ledik.
