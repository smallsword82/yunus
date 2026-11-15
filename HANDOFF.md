# Devir notu — Yunus (BYD Dolphin, DiLink 3)

Tarih: 31.10.2026 (5. oturum sonu) · Dal: `claude/live-vehicle-data` (bu dosyanın ve kodun dalı) ·
**PR: smallsword82/yunus#1** · Çalışma ağacı temiz · **Hepsi push'lu**.
Push'u kullanıcı açıkça isteyince yap; bu oturumdaki onay sonraki oturumlara geçmez.

**Dikkat — bu oturum bulutta geçti, araca hiç dokunmadı:** 5. oturum Claude Code'un bulut ortamında
çalıştı (Android SDK, ADB, araç bağlantısı yok). Hiçbir şey derlenip kurulmadı, araçta hiçbir şey
test edilmedi. Yalnızca: (a) masaüstü önizlemede 10 sn'lik bir tanıtım videosu kaydedildi — bu video
`claude/live-vehicle-data`'da DEĞİL, ayrı bir oturum dalında (`ccr-c1d74f02-csoecl`,
`media/yunus-tanitim.mp4`) duruyor; (b) kullanıcının sürüşte doğruladığı iki madde not edildi;
(c) Kinex'in kilit mekanizması tersine mühendislikle incelendi ve araçta denenmemiş yeni bir
komut yolu kodda eklendi (aşağıda). **Yeni kodun araçta henüz hiç çalıştırılmadığını unutma.**

## SIRADAKİ (31.10.2026 akşamı, şarj bitmek üzereyken eklendi)
1. **Navigasyonda adres arama Yunus haritasında (MapLibre):** kullanıcı istedi; Yunus haritası Google Maps gibi kasmıyor.
2. **Geri viteste BYD'nin tam ekran 360° penceresi** (`com.byd.avc/.AutoVideoActivity`) Yunus'un 2. penceresine küçültülsün.
   Gömme yöntemi çalışıyor (AppEmbed, `embedApp('side','com.byd.avc',…)` ile denendi, canlı görüntü geldi); R'de sistem onu ana
   ekranda açıyor, o anda yan pencereye alınması denenmedi (R gerekiyor).
3. Kameralar (`CarCamera.kt`, `bmmcamera.jar`, "pano_h" = 4 balık gözü yan yana: arka, sol, sağ, ön): sinyal penceresi
   (dewarp, 3. seçenek) ve yüzen dikiz aynası (arka 55°, aynalı; ön ⇄) araçta kurulu ve push'lu (`63229ff`); dikiz aynası
   sürüşte gerçek trafikle görülmedi. Kamera izni ADB ile verilir: `pm grant app.yunus.launcher android.permission.CAMERA`.
   BYD 360° uygulaması açıkken kamera onda olabilir (görüntü gelmezse `am force-stop com.byd.avc`).

## EN SON DURUM (yeni pencere buradan başlasın)

Araç tarafı 4. oturumun sonundaki halinde, tek ek: `{cmd:"lockViaSetting"}` komutu (madde 6) kullanıcı
tarafından başka bir pencereden derlenip araçta denendi — **reddedildi**, kilit komutu HAL'den
kapandı (ayrıntı madde 6'da). Başka hiçbir şey değişmedi.

**Sonraki oturum evden mi araçtan mı?** Araç verisi, gömülü uygulamalar, BYD panelleri için araç + ADB gerekir
(`adb connect 10.184.30.214:5555`, aynı ağda olmak gerek). Sayfa/tasarım işleri evde masaüstü önizlemede
(`python -m http.server 8765 --directory viewer`, demo modu) ya da emülatörde yapılabilir; araca kurulum sonra.
Bulut oturumunda (ADB/SDK yok) yalnız sayfa/doküman işi yapılabilir, derleme ve araç testi yapılamaz.

**Açık / doğrulanmadı (bir sonraki sürüşte):**
1. **Şarj göstergesi araç yeniden başlatıldıktan sonra** — kullanıcı "veri almıyor" dedi. Sebep büyük olasılıkla yardımcının
   `watch` aboneliğinin kopmasıydı; `3c61d4a` ile her yeni bağlantıda yeniden gönderiliyor (yardımcı öldürülüp açılınca
   değerlerin geri geldiği görüldü). Aracın tam yeniden başlatılmasından sonra şarj göstergesi **henüz görülmedi**.
2. İlk açılışta haritanın takılmadan geldiği soğuk açılışta (araç kapanıp açılınca) henüz görülmedi; Yunus yeniden başlatılınca ölçüldü.
3. **Ekran dışındaki üst çubuğa bağlı başka BYD pencereleri:** sesli asistan gibi kendini durum çubuğu alanına koyan başka bir
   BYD penceresi olursa o da görünmez kalır. Kullanıcı "X ekranda çıkmıyor ama çalışıyor" derse önce bunu düşün
   (`dumpsys window windows` → `mFrame` y<0 mı). 5. oturumda böyle bir uygulamaya **henüz rastlanmadı**.
4. **Şarj portu konumu:** modeldeki soket ön-sol; gerçekte arkaysa `HOT.hCharge`'ı arkaya al.
5. ~~Park freni bırakılınca 0 geldiği ve takılı kemerin 1 döndüğü~~ — **sürüşte doğrulandı, çalışıyor** (kullanıcı, 5. oturum).
6. ~~Kilit komutu~~ — **kapandı, HAL'den mümkün değil.** Kinex'in kendi HAL köprüsü tersine mühendislikle
   incelendi (`androguard`, ayrıntı `KINEX_NOTLARI.md`): Kinex de DL3'te (Dolphin) kilidi yalnız okuyor,
   komut yok; DL5'te `rpcSetDoorState` bir stub. Üçüncü bir yol da eklenip araçta denendi: `Setting`
   cihazının `getDoorLock()`/`setDoorLock(int)` çifti (`{cmd:"lockViaSetting"}`, commit `38db03c`) —
   **reddedildi** (`setDoorLock` → `0x800003E8`, `getDoorLock` → `-10011`, ikisi de imza-korumalı).
   Üç yol da (`Bodywork.set()`, `postEvent()`, `Setting.setDoorLock()`) denendi, üçü de kapalı.
   Gerçek kilit mekanizması yalnız Bluetooth telefon-anahtarı taklidiyle mümkün — bu, BLE paket
   yakalama ve protokol tersine mühendisliği gerektiren ayrı ve çok daha büyük bir iş; istenirse
   sonraki bir oturumda ayrı olarak ele alınabilir.
7. **>>> İLK ARAÇ BAĞLANTISINDA DENE VE KULLANICIYA HATIRLAT <<< — kör nokta sol/sağ (DOW).**
   Kullanıcının isteği (9.11.2026): araca bağlanınca bunu dene ve ona hatırlat. Durum: `ADAS_DOW_WARN_LEFT`
   `0x41800020` ve `ADAS_DOW_WARN_RIGHT` `0x41800022` zaten okunuyor ([ShellHelper.kt:634](android/app/src/main/java/app/yunus/launcher/ShellHelper.kt:634),
   map [launcher.html:5627-5628](viewer/launcher.html:5627) → `dowL`/`dowR`). Ama 8.11 sürüş kayıtlarında (eski oturum
   scratchpad `rec*.json`) **sağ 42 kez geldi, sol 0 kez**. Muhtemel sebep: poll `if (v in 0..15)` filtresi —
   sol bu araçta aralık dışı bir sentinel dönüp eleniyor olabilir. **Test:** `0x41800020`'yi **ham oku** (filtreyi
   atlayarak gerçek dönen int'i gör), Electro'nun sol/sağ kör nokta tetikleyicisini ground truth alıp sol ve sağ
   için ayrı ayrı tetikle. Sol kullanılır bir değer veriyorsa `dowL` çalışır; sentinel veriyorsa DOW-sol bu araçta
   yok, taraf başka kaynaktan gelmeli. NOT: kod yorumu DOW'u "while stopped" (dururken) diyor — Electro'nun
   sürüşteki tetikleyicisiyle örtüşmeyebilir. Ayrıca kanıtlandı: BSD (`0x41800008`) taraf vermiyor (aracın kendi
   `BlindSpotMonitor` logu da bsdState=1/2). Ayrıntı: memory `blind-spot-side-electro`.
   **9.11 canlı sürüş (yardımcı TCP ev-akışı yakalaması, logcat değil):** kullanıcı kör noktada araç olduğunu
   doğruladı. **Yeni "var/yok" sinyali bulundu: `ADAS 0x0D500018` = 2 (araç var) / 1 (temiz), 6 temiz olay** —
   ama taraf vermiyor (sol/sağ aynı; `0x12200046`, `0x32D00034` de aynı). **DOW `0x41800020/22` bu yakalamada
   ateşlemedi** (1'er satır) çünkü akış yardımcının **filtreli poll**'undan (`v in 0..15`) geçiyor; önceki logcat
   sürüşü sağı ham yakalamıştı. **Sonuç: DOW tarafı için logcat kullan (ham), yardımcı TCP akışı değil** — ya da
   ShellHelper'a filtresiz DOW okuması ekle. Hipotez: DOW yalnız o yöne **sinyal verince** ateşliyor; sıradaki
   pasif test = sinyalli sür + DOW'u logcat'ten ham yakala. Radar/Signal/Collision akışta hiç olay yollamadı.
   **15.11 DÜZELTME (tam kayıt + framework tablosu):** `0x0D500018` kör nokta DEĞİL: `ADAS_CMD_CST_SWITCH`
   (konforlu duruş, her ~5 km/s → duruşta 2). `0x12200046` = `ADAS_AVH_STATE` (otomatik tutma). Kör nokta = yalnız
   `0x41800008`=2 (tarafsız). DOW sol/sağ yalnız dururken/P'de ateşliyor. Sürüşte taraf veren sinyal yok; taraf =
   sinyal yönü (sayfa zaten böyle). Madde kapandı. Ayrıntı: INCELEME-BULGULARI.md 2026-11-15 17:00.

## 5. oturumda yapılanlar
- **Tanıtım videosu:** kullanıcı istedi, masaüstü önizlemeden (demo modu, 1920×1080, Playwright/Chromium headless,
  sayfanın kendi saatini/animasyonlarını kare kare ilerletip gerçek görüntüden) 10 sn'lik bir video üretildi:
  açılış → şarj görünümü → sürüş görünümü → kapanış kartı. Gerçek APK'da kurulu değil, çalıştırılamadı da
  (bu oturumda Android SDK/emülatör yok); sayfanın kodu aynı. `media/yunus-tanitim.mp4`, **`ccr-c1d74f02-csoecl`
  dalında** (bu, `claude/live-vehicle-data`'dan FARKLI bir dal — video oraya taşınmadı).
- Kullanıcı sürüşte park freni (bırakılınca 0) ve emniyet kemerini (takılı 1) doğruladı → madde 5 kapandı.
- Sesli asistan dışında görünmez kalan başka bir BYD penceresine henüz rastlanmadı (madde 3 açık kaldı, netleşti).
- Kinex'in üç HAL jar'ı tersine mühendislikle incelendi, yeni bir test komutu eklendi (`lockViaSetting`);
  kullanıcı bunu başka bir pencereden (bu bulut oturumunun bağlanamadığı bir bilgisayar/araçtan) derleyip
  kurdu ve denedi — **reddedildi**. Kilit komutu konusu madde 6'da kapatıldı.

## 4. oturumda yapılanlar (hepsi commit'li, araçta doğrulandı)

**Varsayılan launcher + BYD çubukları**
- Yunus HOME (`cmd package set-home-activity app.yunus.launcher/.MainActivity`; BYD `com.android.launcher3` yedek olarak duruyor).
- `wm overscan 0,-84,0,-90`: BYD üst (84 px) ve alt (90 px) çubukları ekran dışında, açılışlarda kalıcı. Geri al: `wm overscan reset`.
- Üstten çekme yalnız BYD hızlı panelini (BydQSBar) açar: yardımcı `qsbar` → `openCarPanel()` ekran dışındaki durum çubuğunu
  `y=-42`'den tek kaydırmayla çeker (`statusBarTop()` dumpsys'ten `mFrame` okur).
- Yunus › Sistem › Ana ekran: Yunus ↔ BYD launcher geçişi (yardımcı `home yunus|byd`: set-home-activity + overscan aç/sıfırla).
- Başka uygulama öndeyken (`EdgeHome.kt`, yalnız Yunus varsayılan HOME iken): alt kenarda görünmez 22 dp şerit. 1. yukarı kaydırma
  Yunus dock'unu getirir (`launcher.html#dock`, `TYPE_APPLICATION_OVERLAY` WebView; `DOCK_ONLY` modu araç modelini yüklemez),
  dock üstünde 2. kaydırma son uygulamalar (`recents` → KEYCODE_APP_SWITCH). Dock 7 sn sonra / dışarı dokununca gider;
  kartları `YunusNative.dockHome(card)` ile Yunus'u öne alır. İzin: `appops set app.yunus.launcher SYSTEM_ALERT_WINDOW allow`.
- Yunus'un içinde: araç simgesi = ana sayfa (`goHome`), alt kenardan (56 px) yukarı kaydırma = son uygulamalar.
- **BYD sesli asistanı:** kendi çubuğunu durum çubuğunun içine çiziyor (`com.byd.vrassistant`, TYPE_SYSTEM_ERROR, sol üst 24,8 dp —
  `ViewLocationDispatcher.getParams`'ta sabit, kaynakla değiştirilemez), yani ekran dışında kalıyordu. Asistan `Settings.System`
  `KEY_VOICE_DIALOGEVENT`'e `CONVERSATION_BEGIN`/`_END` yazıyor; `MainActivity.voiceLift()` bunu izler, konuşma sürerken yardımcıya
  `voice 1` (`wm overscan 0,0,0,-90`, BYD üst çubuğu asistanla ekranda), bitince `voice 0` gönderir. Yunus'un penceresi kıpırdamaz.
  Elle test: `am broadcast -a com.intent.action.StartVoice_From_Launcher -n com.byd.vrassistant/.receiver.HardKeyReceiver`
  (kapat: `-a com.byd.vrassistant.ACTION_CANCEL`). Telefon görüşmesi sırasında deneme.
- Ayrıntı: memory `byd-navbar-overscan`.

**Klima dock'u** (aracın kendi simgeleri: `viewer/img/byd/` — 208 SystemUI simgesi, `ac_*.svg`, `wind_part_*.png`)
- Sıcaklık/fan, sıcaklığa basılı tut = klima aç/kapa, fana basılı tut = oto (düzen kaymaz, tüm düğmeler eşit aralıklı).
- Hava yönü: aktif oklar, 3 yön birden. İç/dış hava: iki ayrı simge, **animasyonsuz** (kullanıcı istemedi). Arka buz + ayna tek simge.
- Direksiyon ısıtma çalışıyor (`acCommand` `wheelHeat`).
- **Koltuk ısıtma/soğutma:** küçük pop-up, üst üste iki simge (ısıtma, soğutma), yazı yok, **oto yok** (kaldırıldı — araçta
  yanlış çalışıyordu). Her basış: kapalı → 1 → 2 → kapalı. Bardaki tuş son seçilen modu gösterir.
  Yardımcı yalnız **değişen** setter'ı gönderir (ikinci setter birincisini iptal ediyordu → kapanmıyordu); sayfa durumu yalnız
  dokununca gönderir, geç gelen araç raporlarını erteler (`seatSentAt`/`seatLater`).

**Durum çubuğu**
- Biraz büyütüldü; tema aracın gece moduna uyar (`deviceDark`).
- Wi-Fi pop-up'ı: aç/kapa, ağlar, bağlan (yardımcı `wifiCommand`, ServiceManager'dan `IWifiManager`: setWifiEnabled, startScan,
  getScanResults, getConfiguredNetworks, addOrUpdateNetwork, enableNetwork, reconnect).
- Bluetooth pop-up'ı (`BtBridge.kt`, profil 16 HFP client + 11 A2DP sink). Bildirim pop-up'ı (`MediaBridge` listesi; aç/sil/temizle).
- **Pop-up'lar canlı Maps üstünde:** açılınca Maps sayfanın ALTINA iner (`YunusNative.mapUnder` → `web.translationZ = 10`),
  sayfa orada saydam. Kapanınca Maps **280 ms sonra** (solma bitince) öne döner — hemen dönerse pencerenin harita üstündeki kısmı
  birden kaybolur ("parça parça kapanıyor"). `popTouch()` koltuk, Wi-Fi/BT/bildirim ve **apps penceresi** için ortak.
  Kullanıcı Maps'in sürekli altta durmasını SEVMEDİ (dokunma zorlaştı, kastı) ve anlık görüntü yöntemini reddetti — yalnız pop-up açıkken.
- Ayarlar listesi kayar, kart aşağı çekince kapanır.

**Lambalar ve sürüş yardımı (üst çubuk)**
- Vites satırı saatle aynı hizada.
- Park + kısa far simgesi. Far düğmesi `Light 0x13300023`: 1 kapalı, 2 AUTO, 3 park, 4 kısa far. AUTO'da simgede "A".
  Lamba durumları: `Light 0x38A0000A` kısa, `0x38A00008` park, `0x38A0000C` uzun (olay, anında) + `lampSide/Low/High`
  (`getLightStatus` 1/2/3, saniyede bir). AUTO'da kısa farla park lambası da yanar.
- Uzun far/selektör = kısa far simgesi **mavi** (ayrı simge yok). CSS'te `#indLow.high`, `#indLow.auto`'dan **sonra** gelmeli
  (yoksa AUTO'nun yeşili maviyi ezer — bu oturumdaki hata buydu; veri hep geliyordu).
- BSD (`Adas 0x41800008`) ve şerit (`Adas 0x32F0002A`): üst çubuğun altında solda, uyarı ışığı gibi; yalnız açıkken.
  Hız sabitleyici: ayarlı hız (`Instrument 0x2A600008`) sağda pilin altında, `0x29600032` durumu.
- DRL (`Light 0x4FE0000E`: 1 açık, 2 kapalı) 3D modelde yanıyor. 12 V ışığı kaldırıldı. Kemer pop-up'ı yok.

**Performans**
- Takılmanın sebebi 3D araçtı: `window.yunusCar` her araç mesajında `wake(1500)` çağırıyordu → park halinde saniyede ~21 çizim,
  karelerin %71'i takılıyordu. Artık yalnız modelde görünen değerler (`MODEL_KEYS`) çizimi uyandırıyor → park halinde 3D 0/sn,
  takılan kare %8–13 (`dumpsys gfxinfo app.yunus.launcher`; `#fpsMeter` metni "3D x/sn" gösterir).
- Modele yeni bir şey eklersen anahtarını `MODEL_KEYS`'e de ekle, yoksa değişiklik ancak başka bir sebeple çizilince görünür.

**Yardımcı bağlantısı** (`MainActivity` `ShellLink`)
- `shell.watch()`: her yeni bağlantıda `watch` yeniden gönderilir; okuyucu iş parçacığı bitince 1,5 sn sonra yeniden bağlanır.

**Diğer:** radyo kartında RDS/DAB logoları; açılışta seçili navigasyonla (Google Maps) doğrudan başlar; şarj portunun altındaki
"şarj ediliyor" yazısı ve bagaj düğmesi kaldırıldı. Araç arıza kodları (DTC) Yunus'tan silinemez.

## Araç verisi: nasıl okunuyor ve kumanda ediliyor

Kabuk yardımcısı (`ShellHelper.kt`, ADB ile uid 2000, `app_process`) BYD HAL cihazlarını **izin denetimini geçiren bir
`ContextWrapper`** (`CarWatch.Permissive`, Kinex'in `Lb/W;` sınıfının aynısı) ile kuruyor:
`check*Permission` → GRANTED, `enforce*` → boş, `getPackageName`/`getOpPackageName` → `"com.android.shell"`, `getApplicationContext` → this.

**Protokol** (TCP 19340, satır satır): `watch` (abone ol), `cmd <json>` (komut), `qsbar`, `recents`, `home yunus|byd`,
`t`/`launch`/`close` (gömülü uygulamalar). Yanıt: `ev <Dev> 0xID <i|f|d> <değer>` ve `msg <json>` (sayfaya olduğu gibi).
Sayfa girişi `window.yunusCar(m)`; `CAR_HAL_MAP` `'Dev 0xID'` / `'Get anahtar'` → telemetri; `CAR_HAL_KEEP` değerleri
localStorage'da saklar (açılışta hemen görünsün diye).

**`CarWatch.GETTERS`** (saniyede bir, yalnız değişince gönderilir):
- Statistic: `soc`, `range`, `odo`. Bodywork: `steer` (±780°), `winFL/FR/RL/RR`, `shade` (konum kodu: 1 açık, 2 kapalı, 3 yarım, 4 durdu).
- Charging: `chgGun` (2 = tabanca takılı), `chgState`, `chgPower` (0 döner), `restMin` → `[saat, dakika]`.
- Gearbox: `gear` = `getGearboxAutoModeType` (P 1, R 2, N 3, D 4/6), `parkSw` (1 çekili). `getCurrentGear` reddediliyor (EPB
  kontrolü CarSettings'i "android" paketiyle soruyor).
- Setting: `seatH1/H2` (ısıtma), `seatV1/V2` (soğutma). Light: `lampSide/Low/High`, `drl`. SafetyBelt: `belt1` (1 takılı).
- Kilit: getter 0 döner; durum dinleyiciden (`Ota 0x40700008`: 1 açık, 2 kilitli). Kilit KOMUTU HAL'den mümkün değil
  (BT telefon-anahtarı taklidi gerekir). Ayrıntı: memory `door-lock-read-gated-command`.
- Genel okuma: `AbsBYDAutoDevice.get(deviceType, featureId)` — Instrument 1007, Light 1004, ADAS 1038, Gearbox 1011.
  Uyarı ışıkları (`ShellHelper.LIGHTS`) ve yukarıdaki Light/ADAS/Instrument id'leri saniyede bir böyle okunuyor (olay gelmeden de görünsün).
  Bazıları `get`'te 65535 döner → yalnız olayla gelir.

**Komutlar** (`YunusNative.command(json)` → `cmd <json>`):
- Cam `setBodyWindowCtrlState(alan 1..4, durum)` (`WINDOW_CLOSE=2`, `OPEN_FULL=1`, `OPEN_HALF=4`, reflection'la); perde
  `setSunshadeState(0..100)`.
- Klima `acCommand` (AC cihazı, ilk argüman hep kaynak 0): `start/stop`, `setAcTemperature(0, °C tam 17..33, 0, 1)` (iki katı
  verince 33 olur!), `setAcWindLevel(0, 1..7)`, `setAcWindMode(0, 1 yüz 2 yüz+ayak 3 ayak 4 ayak+cam 5 cam 6 hepsi 7 yüz+cam)`,
  `setAcDefrostState(0, 1 ön/2 arka, 0/1)`, `setAcCycleMode`, `setAcControlMode(0, 0 oto)`, `wheelHeat`, `seat`.
  Durum olayları: güç `0x40400010`, fan `0x4040001C`, sıcaklık `0x40400028`, iç hava `0x40400014`, hava yönü `0x40400018`,
  ön buğu `0x40400016`, arka `0x43400031`, dış sıcaklık `0x40400038`. Sayfanın başlangıç bekçisi `acSentAt = -1e9` olmalı (0 iken
  aracın değerleri ilk saniyelerde yok sayılıyordu).
- Feature id → isim: `bridge/bydauto_feature_ids.json` (framework.jar'dan). Kullanıcıya test ettirmeden önce oraya bak.

## Şarj arayüzü
- Tabanca takılı (`chgGun == 2`) → kablo + istasyon + yandan görünüm (`PRESETS.charge`). `showCharging()` yalnız metni günceller.
- Panel `#chargeInfo` (sağ üst, 230 px): çekilen kW + kalan süre. `chargeKw = (5000 − battCurrent) × 0,1 × (battVolt || 420) / 1000`;
  `battCurrent` = `Statistic 0x44A00020`, `battVolt` = `Charging 0x44400008` (`CAR_HAL_KEEP`'te).
- Kablo modeldeki `ChargeSocket` düğümüne hizalı (model soketi ön-sol'da).

## Bilinen tuzaklar (bu oturumda yaşandı)
- **Siyah ekran = sayfada sözdizimi hatası.** Tek satırlık bir işleyicinin sonuna eklenen yorum `});`'yi yuttu; Python ile JS
  dizgisine gerçek satır sonu yazıldı. Değişiklikten sonra önizlemede ya da CDP'de hata olmadığını kontrol et.
- Kotlin'e Python'dan `"\n"` yazarken gerçek satır sonu girmesin (`chr(92)+'n'`); regex için ham `"""…"""` dizgisi.
- WebView localStorage force-stop'tan önce diske yazılmayabilir: yaz, bekle, `location.reload()`.
- Maps'in TextureView'ını taşımak haritayı boşaltıyor → yalnız `translationZ` ile katman değiştir.
- Chrome 74 WebView: `?.`, `??`, `color-mix`, `Promise.any`, `min()`, `backdrop-filter` YOK.
- Embed çözünürlüğünü düşürme (renderScale 0.67) denendi, kullanıcı sevmedi — tekrar önerme.

## Nasıl çalışılıyor
- **İzin dosyası `.claude/settings.local.json`** (kullanıcı yazdı, GEREKLİ): `Bash(./gradlew:*)`, `Bash(adb:*)`, `Bash(cp:*)`,
  `Bash(git:*)`. Olmadan auto-mode derlemeyi engeller (kod izin denetimini aşan bağlam sarıcı içeriyor). Ajan bu dosyayı yazamaz.
- **Derle + kur:** `android/` içinde `./gradlew assembleDebug -q` → `app/build/outputs/apk/debug/yunus-0.1-test.apk` →
  `release/`'e kopyala → `adb install -r` → `adb shell am start -n app.yunus.launcher/.MainActivity`.
- **Yardımcı kodu değiştiyse:** kurulumdan sonra eski yardımcıyı öldür
  (`adb shell "ps -A -o PID,ARGS | grep app.yunus.launcher.ShellHelper"` → `kill -9`), sonra Yunus'u yeniden başlat.
- **Sayfayı incele (CDP):** `PID=$(adb shell pidof app.yunus.launcher); adb forward tcp:9222 localabstract:webview_devtools_remote_$PID`;
  Runtime.evaluate ile `window.YunusCarData` (ham değerler), `window.yunusCar({...})` (telemetri enjekte), DOM.
  `S` modül kapsamında, doğrudan okunamaz.
- **Ekran görüntüsü:** `adb exec-out screencap -p > x.png`. Performans: `adb shell dumpsys gfxinfo app.yunus.launcher [reset]`.
- Framework dexdump: `.../scratchpad/fw/classes*.txt`; cihaz sınıfı `Landroid/hardware/bydauto/<dev>/BYDAuto<Dev>Device;`.
- ADB `input tap` ile denenebilecek şeyi kullanıcıya yaptırma; sonra eski haline getir.

## Çalışma ortamı
- Araç: `adb connect 10.184.30.214:5555` (kablosuz ADB). Yardımcı logu: `adb shell cat /data/local/tmp/yunus_helper.log`.
- Emülatör: `emulator -avd yunus34` (araç verisi yok). Masaüstü önizleme: `python -m http.server 8765 --directory viewer` → `launcher.html`.

## Araçta bilinenler
- Hızlı panel = BYD SystemUI `BydQSBar` (araç düğmeleri BYD imzalı). Yunus'un kendi panelini kullanıcı İSTEMEDİ.
- 360°/yan kamera yalnız BYD kamera uygulamasında (`com.byd.avc`). Lastik basıncı ~214–215 geliyor (birim doğrulanmadı).
- Ön tekerleri döndürme denendi, geri alındı: model dört tekeri tek mesh çiziyor (`tyre_Tire_0`); üçgen bölme sol ön tekeri kaybetti.

## Kullanıcı hakkında
Türkçe konuşuyor. Sonuç görmek ister; koddan çıkarılabilecek şeyler için test yaptırmaktan hoşlanmaz. Kısa, net ilerle.
Tasarımda **aracın kendi simgelerini ve Tesla tarzını** istiyor; abartılı animasyon (büyüyüp küçülme) istemiyor; beğenmediğini
açıkça söyler, birkaç deneme normal. Çok sayıda küçük istek arka arkaya gelir; her birini yapıp araçta kontrol edip commit'le.
