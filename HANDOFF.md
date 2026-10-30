# Devir notu — Yunus (BYD Dolphin, DiLink 3)

Tarih: 30.10.2026 · Dal: `claude/live-vehicle-data`

## Durum özeti

APK: `release/yunus-0.1-test.apk` (30.10.2026, emülatörde baştan sona denendi: açılış 0,8 sn,
~110 MB bellek, sayfada hata yok, ANR yok). Kontrol sırasında: aracın Chrome 74'ünde tanınmayan
CSS (`color-mix`, iki eksik flex-gap yedeği) düzeltildi; kullanılmayan HalBridge kodu silindi ve
Kinex'in jar'ı, kullanılmayan fontlar/görseller ve Draco çözücü APK'dan çıkarıldı.

| Konu | Durum |
|---|---|
| 3B araç: kapılar, bagaj, farlar, sinyaller | ✅ araçta doğrulandı |
| Klima, fan, sıcaklık, direksiyon ısıtma | ✅ araçta (gösterim; dokununca aracın ekranı açılır) |
| Menzil, dış sıcaklık, vites, pil % | ✅ canlı (park halinde SOC/menzil için yardımcı getter'ı bekleniyor) |
| Hız, güç çubuğu (gaz/rejen), toplam km | ⚠️ eşlendi; hız motor devrinden (katsayı sürüşte ayarlanacak) |
| Şarj durumu + Tesla tarzı şarj görünümü | ⚠️ eşlendi, emülatörde taklitle doğrulandı |
| Uyarı lambaları (20), lastik basınçları | ⚠️ gösterge verisinden eşlendi, araçta görülecek |
| Camlar, perde, direksiyon açısı, kilit durumu, park halinde SOC | ❌ araçta denendi (30.10): kabuk + sistem bağlamı da reddedildi (BODYWORK/DOOR_LOCK/SPEED/GEARBOX/STATISTIC izinleri yok). Cam yüzdesi 0x38800018 yalnız BYD imzalı uygulamalara gidiyor. Tekrar deneme. |
| Kilitleme (komut), kör nokta, gerçek yan kamera | ❌ BYD imzası / BYD uygulaması gerekiyor (plan aşağıda) |
| Harita alanı: Google Maps / Yunus Harita, 3B binalar, şarj istasyonları, navigasyon | ✅ |
| 3 pencere, sürükleyerek boyut, 3. pencerede gerçek uygulamalar, kapatma | ✅ emülatörde |
| Dikey mod (alt alta pencereler, 2 sıra alt bar, üstte durum şeridi) | ✅ emülatörde |
| Durum şeridi: kilit, saat, sıcaklık, sürücü, konum, BT, Wi-Fi, şebeke, zil | ✅ (sürücü adı şimdilik ayarlardan) |
| Müzik kartı (Android tarzı), son kullanılan uygulamalar, tepsi | ✅ emülatörde |
| Ses çubuğu (pencerelerin üstünde, yerel çizim; direksiyon tuşunda yalnız BYD'ninki) | ✅ araçta |

## Bilinmesi gerekenler

**Araç verisi nereden geliyor:** `MainActivity.kt` → `CarHal`. 16 adet `BYDAUTO_*_COMMON` izniyle
HAL cihazları kuruluyor, dinleyicilere gelen olaylar sayfaya `{carHal:{dev,type,int…}}` olarak gidiyor.
Sayfada `CAR_HAL_MAP` bunları telemetriye çeviriyor.

- **ID'leri deneyerek bulma.** Tamamı BYD framework'ünden çıkarıldı:
  `bridge/bydauto_feature_ids.json` (isim → ID). Değer anlamları cihaz sınıflarının sabitlerinde
  (ör. vites `GEARBOX_AUTO_MODE_P=1`).
- Olaylar sadece **değişince** geliyor; yavaş değerler `localStorage`'da tutuluyor (`CAR_HAL_KEEP`).
- `Speed`, `Bodywork`, `Adas` cihazları BYD imzası istiyor → okunamaz. Kapılar göstergenin
  (`Instrument`) uyarı kopyasından okunuyor.
- Yunus araca **komut gönderemez** (`_SET` izinleri imzalı). Klima/fan/direksiyon butonları aracın
  klima ekranını açıyor (`YunusNative.open`).
- Ekranda butonu olan şeyleri test etmek için kullanıcıyı uğraştırma: ADB ile `input tap` yapıp
  logcat'teki `postEvent` satırlarını oku, sonra her şeyi eski haline getir.

**Google Maps nasıl gömülü:** Kinex'in yöntemi (Kinex APK'sı açılarak bulundu).
- `AppEmbed` (MainActivity.kt): harita alanı üstünde `TextureView` + özel `VirtualDisplay`.
- `ShellHelper.kt`: Yunus'un kendi ADB bağlantısıyla (127.0.0.1:5555) shell olarak `app_process`
  ile çalışıyor; uygulamayı `am start --display` ile o ekranda başlatıyor, dokunuşları enjekte ediyor.
- Yardımcı **bilerek dar**: komut çalıştırmıyor; sadece `launch` ve dokunma, sadece Yunus'a ait
  sanal ekranlarda, yalnız 127.0.0.1 ve gizli anahtar ile. Genel komut çalıştıran ilk sürüm
  güvenlik denetimince durduruldu — öyle geri getirme.
- Yunus'un ADB anahtarı araçta kalıcı olarak onaylandı. Yardımcı logu: `/data/local/tmp/yunus_helper.log`.
- Denenip olmayanlar (tekrar deneme): bölünmüş ekran / serbest pencere (ünite desteklemiyor),
  `wm overscan` (BYD uygulamalarını bozuyor, Maps yok sayıyor).

## Araçta bulunanlar (30.10.2026)

- **Kısayol paneli** = BYD SystemUI (`com.android.systemui`, pencere `BydQSBar`, kütüphane
  `com.byd.bydqsmodeslib`). Düğmeler: dilink/ Bluetooth, Bağlı cihaz, Veri, 5G, Sessiz, Mod,
  Ekran döndürme kilidi, Ekranı kapat, Ekran görüntüsü, Wi-Fi; sahne/ Bebek, Ekran temizleme,
  Kamp, Şekerleme; araç/ Ön cam buğu, AVH, Ses efekti, Otomatik park, Kör nokta, Çocuk izleme,
  Isıtma-soğutma, Özel ayar, DMS, Kapı (= iç tavan lambasının kapı modu, KİLİT DEĞİL), Sürüş
  kaydedici, ESP, Park freni, Rejen, HMA, HUD, Ortam ışığı, Şerit uyarısı, Masaj, Enerji, Arka
  panel kilidi, Buzdolabı, SLW, Koltuk ayarı, Simülatör, Tavan perdesi, Dik yokuş, Direksiyon
  ısıtma, Tavan camı, Trafik işareti, Bagaj, Bagaj lambası, Kablosuz şarj, Taşıma modu.
  Araç düğmeleri BYD imzalı izinlerle çalışıyor; Yunus'ta yalnız Android olanlar (BT, Wi-Fi,
  veri, ekran kapat/döndür, ekran görüntüsü) gerçek iş yapabilir.
- **Merkezi kilit hiçbir BYD ekranında yok:** panelde, araç ayarlarında (`CarSetting.apk`:
  yalnız hız kilidi, uzaktan açma ayarı gibi ayarlar) ve DoorLock cihazında (sadece
  `getDoorLockStatus`) kilitle/aç komutu yok. "BYD ekranına gizlice dokunma" ile kilit yapılamaz.
- **Kameralar:** Android'e tek kamera açık (arka). 360°/yan kameralar yalnız BYD kamera
  uygulamasında (gömme yolu kalıyor).
- **Lastik:** değerler 214–215 geliyor (kPa varsayımıyla 2,15 bar) — aracın lastik ekranıyla
  karşılaştırılacak.
- **Sürücü adı/resmi:** `com.byd.accountservice` içinde yok (oyun merkezi + harita token'ı).
- **Bildirim erişimi:** BYD ayar ekranı "UnsupportActivity"; Yunus ADB ile kendine veriyor.

## Sıradaki işler

**Yeni pencerede buradan başla (30.10.2026, araçtaydık):**

- **Çift ses popup'ı (düzeltildi, araca kuruldu, kullanıcı görmedi):** direksiyon tuşları/aracın
  çubuğu BYD'nin kendi ses diyaloğunu açıyor; Yunus da `systemVolume()` → `setVol(v, true)` ile
  kendi çubuğunu açıyordu. Artık cihazda sistemden gelen değişiklikte yalnız dock'taki ses tuşu
  güncelleniyor (`setVol`, launcher.html). Yunus'un dock tuşlarında yalnız Yunus'un çubuğu çıkıyor
  (araçta ekran görüntüsüyle doğrulandı). Kullanıcıya direksiyon tuşuyla denetmesini söyle.
- **Yunus kontrol paneli (kullanıcı onayladı: "Evet, yap") — yerel kısım bitti, sayfa yok:**
  - Hazır (MainActivity.kt): JS `qsState()` → sayfaya `{qs:{wifi, bt, mute, brightness (0–255),
    autoBrightness, rotationLocked, write}}`; `qsSet(key, value)` anahtarlar bt / mute /
    brightness / autoBrightness / rotationLocked (0/1). Parlaklık ve döndürme WRITE_SETTINGS
    ister: `fixPermissions()` bunu ADB'den `appops set app.yunus.launcher WRITE_SETTINGS allow`
    ile veriyor; `perms.write` de izin listesinde.
  - Yapılacak (launcher.html): durum şeridinden aşağı çekince tüm pencerelerin üstünde açılan
    panel (uygulama tepsisi gibi, gömülü pencereler `trayOpen` gibi gizlenir). Parlaklık ve ses
    kaydırıcıları; Wi-Fi (durum, dokununca `com.byd.carsettings` Wi-Fi ekranı — Android 10'da
    uygulama Wi-Fi'yi açıp kapatamaz), Bluetooth, sessiz, döndürme kilidi, otomatik parlaklık
    düğmeleri; araç kısayolları: klima (AC ekranı), araç ayarları
    (`com.byd.carsettings/.MainActivity`), 360° kamera (`com.byd.avc/.AutoVideoActivity`).
    `m.qs` işleyicisi ve `permBlock()`'a "Sistem ayarları (parlaklık)" satırı (`perms.write`).
    Aracın kendi paneli SystemUI `BydQSBar`; içindeki "kapı" düğmesi kabin lambası, kilit komutu yok.
  - Sonra: araca kur, CDP ile dene, commit, `release/`'e kopyala.
- Chrome 74 kuralları unutulmasın: `color-mix`, flex `gap`, `??`, `Promise.any`, `min()` yok.

0. **Araçta ilk iş — kabuk yardımcısının araç okuması (Kinex yöntemi):** Yunus açılınca yardımcıya
   `watch` gönderir; yardımcı sistem bağlamıyla Bodywork/Speed'i dinler ve her saniye hız, vites,
   SOC, menzil, km, direksiyon açısı, 4 cam ve perde getter'larını çağırır. Araçta
   `adb shell cat /data/local/tmp/yunus_helper.log` → "watch: listening to Bodywork/Speed" mi,
   yoksa her getter için SecurityException mı? Çalışıyorsa: hız gerçek değerden gelir (motor
   devri tahmini devre dışı kalır), camlar/perde 3B modelde hareket eder, `S.steerAngle` dolar
   (yol görünümünü kıvırmak için kullanılabilir). Reddediliyorsa Kinex'in daemon'unu
   (kinex_hal_api_service.jar, `HalDaemonBodywork`) daha ayrıntılı incele.
   Ayrıca araçta doğrulanacak: lastik basıncı birimi (250 = 2,5 bar varsayıldı), şarj gücü kW mi,
   motor devri→hız katsayısı (`kmhPerRpm`), uyarı lambası kodları, kısayol paneli (uiautomator
   dump), yan kamera (kamera listesi / com.byd.avc gömme), SmartTube 3. pencere siyah ekran.
   Sürücü adı/resmi: BYD hesap uygulaması `com.byd.accountservice`'in verisini bul
   (`dumpsys package com.byd.accountservice` → provider'lar; ad + avatar) ve sayfaya
   `{user:{name, photo}}` gönder (sayfa hazır; şimdilik ad Yunus › Ekran'dan, resim yerine baş harf).

1. **Müzik kartı araçta:** kod hazır ve emülatörde doğrulandı (`MediaBridge.kt`: başlık, sanatçı,
   kapak, ilerleme, önceki/oynat/sonraki, kaynak seçici). Araçta Yunus'a bir kez **bildirim erişimi**
   verilmeli (karttaki yazıya dokun → "Yunus müzik kartı"). Radyonun (`com.byd.mediacenter`) kapak
   ve RDS metni nasıl geldiğine bak.
2. **Kart + harita:** harita alanını kapatan bir kart açılınca Maps gizleniyor mu, kapanınca geri
   geliyor mu — kod hazır, araçta görülmedi.
3. **Sürüş kaydı:** hız (motor devrinden hesap, göstergeyle kalibre), pil %, tüketim, ICC, hız
   tabelası, vites değişimi.
4. **Klimayı Yunus'tan kontrol (fikir, denenmedi):** BYD klima uygulamasını
   (`com.byd.airconditioning/.mainactivity.FullScreenMainActivity`) 1920×1080'lik **görünmez** bir
   Yunus sanal ekranında aç ve ShellHelper'ın dokunma enjeksiyonuyla oradaki düğmelere bas
   (OFF ≈ 415,267; sıcaklık sütunları solda/sağda; fan çubuğu ≈ 720–1200,905 — araç ekran
   görüntüsünden). Yardımcının kapsamı içinde kalır (sadece Yunus'un ekranı). Kırılgan olabilir.
5. **Bluetooth müzik:** araçta telefon bağlıyken müzik kartında görünmeli (medya oturumu).
6. İsteğe bağlı: küçük gömülü pencerelerde düşük dpi (içerik sığsın).

## Çalışma ortamı

- **Evde test:** `yunus34` emülatörü (Android 14, Google Play'li, 1920×1080 / 240 dpi — araç gibi):
  `emulator -avd yunus34`. Kullanıcı Play Store'a kendi hesabıyla girdi, YouTube Music / YouTube
  güncel. Yunus'a orada bildirim erişimi verildi. (Araç verisi ve BYD uygulamaları emülatörde yok.)

- Araç: `adb connect 10.184.30.214:5555` (kablosuz ADB açık).
- Derleme: `android/` içinde `./gradlew assembleDebug` → `app/build/outputs/apk/debug/yunus-0.1-test.apk`.
- Sayfayı incelemek: `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` + CDP.
- Masaüstü önizleme: `python -m http.server 8765 --directory viewer` → `launcher.html`
  (araç olmadan demo modu, Yunus'un kendi haritası).

## Kullanıcı hakkında

Türkçe konuşuyor. Sonuç görmek istiyor; koddan çıkarılabilecek şeyler için kendisinden test
yapmasını istemekten hoşlanmıyor. Uzun araştırmalarda sabırsızlanıyor — kısa, net ilerle.
