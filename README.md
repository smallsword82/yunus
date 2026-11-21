# Yunus

BYD Dolphin (DiLink 3) için Tesla benzeri launcher. Adı Dolphin'in Türkçesinden. Şu an tasarım prototipi: tek sayfalık
HTML + Three.js arayüz (`viewer/launcher.html`). Android (Kotlin) sürümü sonra gelecek.

> Kişisel kullanım içindir, depo gizli kalmalı. Araç modeli ve bazı görseller Kinex APK'sından
> alındı (bkz. `KINEX_NOTLARI.md`); başkasıyla paylaşma.

## Çalıştırma

```bash
python -m http.server 8765 --directory viewer
```

Sonra tarayıcıda `http://localhost:8765/launcher.html` aç. Geliştirme için
`launcher.html#debug` sahneyi `window.__dbg` altında açar.

## Araç modelleri (DiLink 3)

Yunus artık tek bir modele sabit değil. Modele özgü her şey (3D model, kamera açıları,
hotspot/şarj-soketi konumları, boya/cam materyal adları, yetenekler, klima kodlaması) bir **araç
profili**ndedir (`viewer/profiles/`). Dolphin profili sayfaya gömülüdür (`DOLPHIN_PROFILE`), bu
yüzden **Dolphin deneyimi birebir aynıdır**; diğer DiLink 3 modelleri için `viewer/profiles/<id>.json`
eklenir — kod değil veri işi.

- **Seçim:** Ayarlar › Sistem › *Araç modeli*. "Otomatik" aracın `getAutoModelName()`/VIN'inden
  profili kendiliğinden seçer (`ShellHelper` bağlanınca `carInfo` yollar); aksi halde listeden elle.
  Model değişince Yunus bir kez yeniden başlar. Seninki "Otomatik" → Dolphin olarak kalır.
- **Yeni model ekleme:** `viewer/profiles/README.md` (3D modeli çıkar, profili doldur, kayıt
  defterine ekle). Masaüstü deneme: `launcher.html?profileTest=<id>` (localStorage'a dokunmaz).
- **Kapsam:** yalnız DiLink 3 (Dolphin'le aynı HAL). DiLink 5 (Sealion 7 vb.) yapılmadı.

## Klasörler

- `viewer/launcher.html` — arayüzün tamamı (3D araç, harita, kartlar, ayarlar, klima, uyarılar)
- `viewer/dolphin_parts.glb` — kapı, cam, bagaj ve şarj kapağı ayrılmış model
  (`tools/split_parts.py` ile Blender'da üretildi); yoksa `dolphin.glb` yüklenir
- `tools/` — Blender betikleri: `blender -b --factory-startup -P tools/split_parts.py -- viewer/dolphin.glb viewer/dolphin_parts.glb`
- `KINEX_NOTLARI.md` — Kinex incelemesi (araç verisi HAL köprüsü, kamera, model notları)

## Test APK (Android)

`android/` is a WebView app that packs `viewer/` as assets and shows it full screen at the car's
native resolution. It reads **live vehicle data** in-process through the BYDAuto HAL: with the
grantable `BYDAUTO_*_COMMON` permissions it constructs the HAL devices (instrument, charging,
statistic, ota, ...) and registers listeners, and the car pushes real values through
`onDataEventChanged` — e.g. 12V voltage, temperature, battery cell voltages, seatbelt state. This
is the same data channel BYD Road Computer and the electro reader use, and needs no root and no
BYD signature. The signature-gated `_GET`/`_SET` getters (exact SOC%, range, speedometer on demand)
stay locked to BYD-signed apps; those fields populate from the push stream while driving. Received
events are visible at `window.YunusCarData` on the page.

```bash
cd android
./gradlew assembleDebug    # → app/build/outputs/apk/debug/yunus-0.1-test.apk (~10 MB)
```

Needs JDK 17 and the Android SDK (`android/local.properties`: `sdk.dir=...`). Install on the car
from a USB stick with a file manager, or over ADB: `adb install -r yunus-0.1-test.apk`.

## Kişisel (şifreli/imzalı) sürüm

Android APK'ları düz kurulur; dosya-düzeyinde "şifreleme" yoktur. "Şifreli + bana özel" pratikte:
**R8 ile kod karartma** (tersine mühendisliği zorlaştırır) + **kendi özel anahtarınla imzalama**
(Yunus release'ini yalnız sen imzalayıp güncelleyebilirsin). İkisi de `release` derlemesinde kurulu.

1. **Anahtarı bir kez üret** (`android/` içinde):
   ```bash
   keytool -genkeypair -v -keystore yunus-release.jks -alias yunus -keyalg RSA -keysize 2048 -validity 10000
   ```
2. **`keystore.properties.example`'ı kopyala → `keystore.properties`** ve parolaları yaz. Bu dosya ve
   `*.jks` repoya girmez (`.gitignore`). Anahtarı kaybetme: aynı anahtar olmadan güncelleme imzalanamaz.
3. **Release derle:**
   ```bash
   cd android
   ./gradlew assembleRelease    # → app/build/outputs/apk/release/yunus-0.1-test.apk (R8 + senin imzan)
   ```
`keystore.properties` yoksa release imzasız çıkar — o durumda `assembleDebug` kullan. R8 yalnız ada
göre erişilen üç şeyi korur (app_process'in başlattığı `ShellHelper`, WebView JS köprüsü, HAL proxy'leri;
bkz. `android/app/proguard-rules.pro`). Release araçta ilk kez denendiğinde beklenmedik bir davranış
olursa, `app/build.gradle.kts` içinde `isMinifyEnabled = false` ile R8'i elimine edip tekrar dene.

## Sorumluluk

Yunus kişisel, resmî olmayan bir çalışmadır; BYD ile bağlantısı ve onayı yoktur. Uygulama **hiçbir
garanti verilmeden sunulur**; kuran/kullanan tüm riski kendisi üstlenir ve sahibi/geliştiricisi
doğabilecek hiçbir zarardan sorumlu değildir. Ayrıntı: [DISCLAIMER.md](DISCLAIMER.md). Uygulama ilk
açılışta bu bilgilendirmeyi bir kez gösterir ve kabul istenir.

## Lisans

Yunus açık kaynak **değildir**; tüm hakları saklıdır. Kod incelenmek için açıktır, uygulama kişisel ve
ticari olmayan kullanım için ücretsizdir. Satmak, ücretli dağıtmak, yeniden yayımlamak ya da kodu/tasarımı
başka bir üründe kullanmak yazılı izin olmadan yasaktır. Üçüncü taraf bileşenler kendi lisanslarına
tabidir (3B modeller: CC BY-NC-SA 4.0). Ayrıntı: [LICENSE](LICENSE).
