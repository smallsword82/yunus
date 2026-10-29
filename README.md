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
