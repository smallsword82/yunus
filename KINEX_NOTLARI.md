# Kinex 0.5.4 incelemesi — launcher için çıkarılan bilgiler

Kişisel kullanım içindir. Model dosyası (`viewer/dolphin.glb`) Kinex APK'sından alındı;
kaynağı bir Sketchfab modeli (`Sketchfab_model` / `FINAL_MODEL_24D.fbx` düğümleri), Kinex README'si
Ddiaz Design'a teşekkür ediyor. Başkasıyla paylaşma.

## Uygulama yapısı
- Flutter uygulaması (`libapp.so`, `libflutter.so`), harita MapLibre (`libmaplibre.so`),
  sesli komut sherpa-onnx, Android Auto alıcısı `libgalreceiver_jni.so`.
- 14 araç modeli `assets/flutter_assets/assets/models/*.glb` (glTF-Transform ile sıkıştırılmış,
  Draco geometri + WebP doku).

## Dolphin modeli (`2024_byd_dolphin.glb`, 2.2 MB)
- ~113 bin üçgen, 34 materyal, 26 doku, animasyon yok.
- Kapılar/bagaj **ayrı hareketli parça değil** (tek parça gövde). Kinex kapı/bagaj/cam durumunu
  model animasyonuyla değil, kamera odak noktaları (hotspot) ve işaretlerle gösteriyor.
- Boya: `bodypaint_A_CarPaint_1_0` meshinin materyali değiştirilerek renk veriliyor.
  `upper_bodypaint_PCrPaint_2_0` = siyah tavan/üst kısım.
- Koyu cam olarak zorlanan meshler: `f_gls_winsows_0`, `b_gls_winsows_0`, `sunroof_D_Glass_0`.
- Gerekli glTF uzantıları: `KHR_draco_mesh_compression`, `EXT_texture_webp`,
  `KHR_materials_clearcoat`, `KHR_materials_specular`, `KHR_materials_transmission`.
  Android'de SceneView/Filament ile yüklerken Draco ve WebP desteği gerekiyor.

## Kamera mantığı (`kinex_car_models.json`, Dolphin girdisi)
Küresel koordinat: `theta` yatay açı (0 = ön, 90 = sol, 180 = arka, 270 = sağ),
`phi` dikeyden açı (1 = tam üstten), `radiusPercent` uzaklık.

| Ön ayar | theta | phi | radius |
|---|---|---|---|
| varsayılan | 20 | 70 | 70 |
| sol | 90 | 83 | 55 |
| sağ | 270 | 83 | 55 |
| üstten | 270 | 1 | 90 |
| sürüş (arkadan) | 180 | 64 | 174 |
| bekleme | 31.94 | 82.96 | 55 |

Hotspot konumları (model uzayı): ön camlar z=+0.35, arka camlar z=-0.45, sol x≈+0.74,
sağ x≈-0.75, bagaj (0, 0.95, -2.35). Lastik etiketleri: FL (1.05, 0.85, 1.9),
FR (-1.05, 0.85, 1.9), RL (1.2, 0.6, -2.2), RR (-1.2, 0.6, -2.2).
Dolphin için: pencere kontrolü var, hassas pencere kontrolü yok, tavan kontrolü var, platform `dl3`.

## Araç verileri nasıl okunuyor (en önemli kısım)
Normal (sideload) bir uygulama BYD izinlerini (`BYDAUTO_*_GET`) alamıyor — Kinex'in kendi log
mesajı: "PM refused this grant ... signature/OEM policy". Çözümü:

1. Araçta **kablosuz ADB** açılıyor (mühendislik menüsü → Test Tool).
2. Uygulama kendi içindeki ADB istemcisiyle `127.0.0.1:5555`'e bağlanıyor
   (kendi RSA anahtarı: `kinex_adb_private.key`).
3. Gömülü `kinex_hal_api_service.jar`'ı `/data/local/tmp/`'ye koyup **shell yetkisiyle**
   `app_process` ile çalıştırıyor:
   `CLASSPATH=/data/local/tmp/kinex_hal_api_service.jar app_process / com.lexwah.kinex.hal.HalApiServiceMain 19322`
4. Bu süreç BYD'nin gizli framework sınıflarını kullanıyor ve verileri
   `127.0.0.1:19322` üzerinden TCP ile uygulamaya aktarıyor.
   Gösterge paneli için ayrı bir servis (`kinex_cluster_service.jar`, port 19324) var.

### DiLink 3 (Dolphin) — `android.hardware.bydauto.*`
Cihaz sınıfları (`getInstance(context)` ile alınıyor) ve dinleyiciler:
- `ac.BYDAutoAcDevice` / `AbsBYDAutoAcListener` — klima
- `bodywork.BYDAutoBodyworkDevice` / `AbsBYDAutoBodyworkListener` — kapı, cam, bagaj, batarya
- `doorlock` / `AbsBYDAutoDoorLockListener` — kilit
- `charging` / `AbsBYDAutoChargingListener` — şarj
- `gearbox.BYDAutoGearboxDevice` / `AbsBYDAutoGearboxListener` — vites
- `speed.BYDAutoSpeedDevice` / `AbsBYDAutoSpeedListener` — hız
- `tyre.BYDAutoTyreDevice` / `AbsBYDAutoTyreListener` — lastik basınç/sıcaklık
- `light` / `AbsBYDAutoLightListener` — farlar
- `instrument` / `AbsBYDAutoInstrumentListener` — gösterge
- `statistic.BYDAutoStatisticDevice` / `AbsBYDAutoStatisticListener` — menzil, tüketim, km
- `pm2p5.BYDAutoPM2p5Device` — hava kalitesi
- `setting.BYDAutoSettingDevice`, `multimedia.BYDAutoMultimediaDevice`
- Klima sıcaklığı bazı araçlarda yarım derece kodlamalı (Kinex'te "half-degree HAL encoding").

### DiLink 5 (Sealion 7 vb.) — `com.ts.lib.caradapter.*`
`CarAdapterManager`, `body.CarBodyManager`, `sensor.CarSensorAdapterManager`,
`general.CarGeneralAdapterManager`. Dolphin için gerekmiyor.

## Bizim proje için sonuç
- Tasarım aynı: Tesla mantığı + gerçek `dolphin.glb` + Kinex'in kamera ön ayarları.
- Araç verisi için aynı mimari: launcher (UI) + ADB/shell ile başlatılan küçük bir Java
  "HAL köprüsü" süreci + localhost TCP. Köprüyü kendimiz yazacağız; Kinex'in kodu kopyalanmayacak,
  yalnızca hangi BYD sınıflarının kullanıldığı biliniyor.
- Bu sınıfların metot imzaları araç üzerinde (`adb shell` ile framework jar'larından) doğrulanmalı.

## Remote Link (telefondan uzaktan kontrol)
`libapp.so` içindeki metinlerden çıkarıldı (kod çözülmedi; akış metinlerden ve uç nokta adlarından okunuyor).
- Ayrı bir telefon uygulaması var: **Kinex Remote**. Araçta Ayarlar'da "Enable Remote Link" açılıp
  ekranda bir **QR kod** gösteriliyor; telefon kodu okuyunca eşleşiyor. Hesap gerekmiyor.
  QR, `https://kinex.lexwah.com/remote/` adresine gidiyor (uygulama yoksa kurulum sayfası).
- Arada Kinex'in **aktarma sunucusu (relay)** var: `https://kinexapi.lexwah.com`.
  - Araç kendini `carToken` ile tanıtıyor (`Authorization: Bearer ...`), kimliği `kinex_remote_car_id`.
  - Eşleştirme: `/pairings`, `/pairings/<id>` (`pairingId`, `sessionToken`, `pairedAt`).
    Eşli telefonlar araçta `kinex_remote_phones_json` içinde tutuluyor.
  - Araç sunucuyu sürekli yokluyor: `GET /commands/next` ("command loop"), gelen komutu HAL
    köprüsüyle uyguluyor; durumu `/state` ile gönderiyor (telefondaki canlı durum ekranı).
  - Kapatınca sunucudaki kayıt siliniyor, tüm telefonların erişimi bitiyor.
- Görülen komut/durum adları: `power_on`, `power_off`, `lock`/`unlock`, `ac_off`,
  `setAcTemperature`, `seat_heat_driver/passenger`, `seat_vent_driver/passenger`, `trunkOpen`,
  cam adları (`rightFrontWindow` …).
- Araç kapalıyken erişim için isteğe bağlı "head unit'i uyanık tut" seçeneği: 12V aküyü yavaşça
  tüketiyor, akü zayıflarsa kendisi kapatıyor.
- Uyarı metni: eşli telefonu açabilen herkes **aracı anahtarsız çalıştırıp sürebilir**; uygulamayı
  Face ID / parmak izi / PIN ile korumak öneriliyor.
- Varsayılan launcher için Kinex, sistemin ana ekran seçim penceresini açıyor
  ("Open the system home app chooser to set Kinex or another launcher as the default.").

## Kilitleme: Kinex'in gömülü köprüsünde komut YOK (tersine mühendislik, bu oturum)
Repodaki üç Kinex jar'ı (`kinex_hal_api_service.jar`, `kinex_shell_helper.jar`, `kinex_cluster_service.jar`)
`classes.dex` içeriyor; `androguard` ile sınıf/metot/string listesi çıkarıldı (bkz. `scratchpad/dex/`, bu
oturumun geçici klasörü — tekrarlamak gerekirse aynı adımlar).

- **DiLink 3 (Dolphin) tarafı** (`hal/classes.dex`, paket `com.lexwah.kinex.hal`, sınıflar R8 ile
  `a/`, `b/` altında tek harfe kısaltılmış): string havuzunda `android.hardware.bydauto.doorlock.*`
  için yalnızca **`AbsBYDAutoDoorLockListener`** geçiyor — okuma. `BYDAutoDoorLockDevice` adı hiç
  geçmiyor. Tüm `set*` metot adları (`setAcTemperature`, `setBodyWindowCtrlState`, `setSunshadeState`,
  `setSeatHeatingState`, `setSteeringWheelHeatingState`, ... — tam liste bu oturumun notlarında) arasında
  kilitle ilgili **tek bir tane yok**. Yani Kinex, Dolphin'i HAL üzerinden kilitlemiyor/açmıyor —
  yalnızca durumu okuyor (bizim de yaptığımız gibi).
- **DiLink 5 tarafı** (`HalDl5Bodywork`, `com.ts.lib.caradapter.body.CarBodyManager` sarmalıyor):
  `rpcSetDoorState` var ama gövdesi **stub** — `CarBodyManager.getDoorState(int)` okuyup
  `"method":"dl5_door_state_read_only"`, `"note":"DL5 door control mechanism needs device validation"`
  döndürüyor; hiçbir setter çağırmıyor. Kinex'in DL5 kapı kontrolü de üretimde değil.
- **Sonuç:** Remote Link'in telefon tarafında görünen `lock`/`unlock` komut adları muhtemelen ya başka
  bir donanım yoluyla (telefon-anahtarı BLE) ya da hiç tamamlanmamış bir özellik. Kinex'in paket içi
  HAL köprüsü kilit/açma KOMUTU içermiyor — bizim `1c80e5e`'de bulduğumuzla (set() reddediliyor,
  postEvent kilitlemiyor, gerçek kilit BT telefon-anahtarı taklidiyle oluyor) aynı duvara çarpıyor.

**Yeni, henüz denenmemiş iz** (Kinex'te değil, aracın kendi `introspect-dilink3.txt` dökümünde):
`android.hardware.bydauto.setting.BYDAutoSettingDevice` sınıfında ayrı bir çift var:
`int getDoorLock()` / `int setDoorLock(int)` + dinleyicide `onDoorLockChanged(int)`. Bu, okunan
`doorlock.BYDAutoDoorLockDevice` (salt okunur) ve denenmiş `Bodywork.set()`/`postEvent()`'ten
(ikisi de başarısız) FARKLI bir üçüncü yol — Setting cihazının diğer set'leri (`setSteeringWheelHeatingState`
gibi) düz `getMethod` ile çalışıyor, imza kontrolü onlarda engel değil. `ShellHelper.kt`'ye
`{cmd:"lockViaSetting", value:0|1}` komutu eklendi (bu oturum), bir sonraki araç ziyaretinde denenmeli;
sonucu `adb shell cat /data/local/tmp/yunus_helper.log`'da `cmd lockViaSetting ... -> ...` satırı verecek.
