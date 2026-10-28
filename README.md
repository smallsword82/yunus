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

`android/` is a WebView app that packs `viewer/` as assets. The `codex/kinex-hal-integration`
branch contains an experimental ADB bootstrap and HAL TCP framing client. The Kinex service
handshake, event subscriptions, and field/unit mapping still need to be verified on the Dolphin;
until then the screen stays in demo mode. Details: [`bridge/PROTOCOL.md`](bridge/PROTOCOL.md).

```bash
cd android
./gradlew assembleDebug    # → app/build/outputs/apk/debug/yunus-0.1-test.apk (~10 MB)
```

Needs JDK 17 and the Android SDK (`android/local.properties`: `sdk.dir=...`). Install on the car
from a USB stick with a file manager, or over ADB: `adb install -r yunus-0.1-test.apk`.
