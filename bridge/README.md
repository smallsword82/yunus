# Yunus HAL köprüsü

Araç içi veri köprüsü. `app_process` ile **shell yetkisinde** çalışır (uygulama olarak kurulmaz),
böylece sideload bir uygulamanın alamadığı BYD framework sınıflarına erişir (bkz.
`../KINEX_NOTLARI.md`). Veriyi `android.hardware.bydauto.*` + CarStatusProvider'dan okur,
`127.0.0.1:19322` üzerinden satır-JSON olarak sunar; komutları da aynı soketten alır.
Protokol: `PROTOCOL.md`.

> **Durum:** BYD metot imzaları (getter/setter adları) henüz araçta doğrulanmadı. Koddaki her biri
> `TODO[verify-on-car]` ile işaretli bir tahmindir. Önce `introspect` ile gerçek imzaları çıkar,
> sonra `readState()` / `applyCommand()` içindeki aday listelerini düzelt. Kinex'ten kod
> kopyalanmadı; yalnız hangi BYD sınıflarının var olduğu kullanıldı.

## Derleme (PC'de, JDK 17 + Android `d8`)

`app_process` dex çalıştırır, `.class` değil. İki adım: `javac` sonra `d8`.

```bash
cd bridge
javac -source 11 -target 11 -d build YunusHalBridge.java
# d8 Android SDK build-tools içinde (ör. ~/Android/Sdk/build-tools/34.0.0/d8)
d8 --output build build/*.class
# -> build/classes.dex
```

`build.sh` bunu sarmalar: `./build.sh` (gerekirse `D8=/yol/d8 ./build.sh`).

## Araca kurup çalıştırma (ADB)

```bash
adb connect 192.168.10.10:5555          # kablosuz; ya da USB ile adb devices
adb push build/classes.dex /data/local/tmp/yunus-hal.dex
```

Önce gerçek imzaları çıkar (bir kez, araçta):

```bash
adb shell "CLASSPATH=/data/local/tmp/yunus-hal.dex app_process / YunusHalBridge introspect"
```

Bu, her BYD cihaz sınıfının ve dinleyicisinin gerçek metotlarını + CarStatusProvider örneğini
basar. Çıktıdaki gerçek adlara göre `YunusHalBridge.java` içindeki `DEVICES`, `readState()` ve
`applyCommand()` aday listelerini düzelt, yeniden derle/push et.

Köprüyü çalıştır:

```bash
adb shell "CLASSPATH=/data/local/tmp/yunus-hal.dex app_process / YunusHalBridge serve 19322"
```

Hızlı test (veri akıyor mu):

```bash
adb forward tcp:19322 tcp:19322
nc 127.0.0.1 19322        # ilk satır tam durum, sonra delta satırları
```

## Android uygulamasıyla bağ

Launcher sayfası veriyi `window.yunusCar({...})` ile alır, komutu `YunusNative.command(json)` ile
yollar (bkz. `../viewer/launcher.html`). Sıradaki iş: `MainActivity`'nin (1) bu dex'i
`/data/local/tmp`'ye koyup `app_process` ile başlatması, (2) `127.0.0.1:19322`'ye bağlanıp gelen
satırları `web.evaluateJavascript("window.yunusCar(" + line + ")")` ile sayfaya vermesi, (3)
`YunusNative` `@JavascriptInterface` ile komutları sokete yazması. (Bu adım bu commit'te yok.)

## Güvenlik / kapsam

- Köprü yalnız `127.0.0.1`'i dinler, dışa açık değildir.
- Yalnız okuma güvenlidir; yazma komutları (kilit, bagaj, klima…) araca gerçek etki eder —
  araç dururken ve dikkatle doğrula.
