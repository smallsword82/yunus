# Kilit / imza tavanı — nasıl kırılır (araç dışı hazırlık + araçta test planı)

Amaç: Dolphin'i Yunus'tan kilitlemek/açmak. Engel "yanlış id / yanlış taşıma" değil —
**imza tavanı**, ve aslında **iki ayrı kapı** var. Bu not ikincisini kırmak için.

> **ÖNEMLİ GÜNCELLEME (2026-11-09) — en alttaki "KİNEX KİLİTLİYOR" bölümünü önce oku.**
> Kinex Dolphin'i gerçekten kilitliyor (kullanıcı test etti). Yani kilit shell'den
> ERİŞİLEBİLİR; "saf imza tavanı, imkânsız" çerçevesi yanlış. Bizim `setDoorLock`
> doğrudan çağrımız reddedildi (`0x800003E8`) ama Kinex başka (şifreli) bir metot/değerle
> aynı shell-daemon'dan geçiyor. Aşağıdaki eski plan hâlâ geçerli ama artık asıl iş,
> Kinex'in tam çağrısını bir logcat'le yakalamak.

## Kapı haritası (şu an bildiğimiz)

İki katman var, karıştırma:

1. **Android izin katmanı — `BYDAUTO_*_GET/SET` imza izinleri.** Normal sideload uygulama alamaz
   ("PM refused this grant ... signature/OEM policy", `KINEX_NOTLARI.md:43`).
   **Bunu zaten aştık:** `ShellHelper.kt`'deki `Permissive` ContextWrapper
   (`ShellHelper.kt:398`) tüm `check*/enforce*`'u GRANTED döndürüyor ve paketi
   `com.android.shell`/uid 2000 gösteriyor. **Okumalar VE cam/tavan yazmaları
   (`setBodyWindowCtrlState`) bu yüzden çalışıyor.** Bu katman kilit için de aşılmış durumda.

2. **Servis-içi ikinci kapı — `0x800003E8`.** `setting.BYDAutoSettingDevice.setDoorLock(int)`
   Permissive context'e RAĞMEN `0x800003E8` döndürüyor (`ShellHelper.kt:756` lockViaSetting,
   araçta denendi). Yani bu bir Android izin reddi DEĞİL (o aşıldı); BYD HAL servisinin
   kendi içinde, çağıranı tekrar süzen ayrı bir kontrol. `getDoorLock` de gizli (`-10011`).
   **Kırılacak olan budur.**

Niçin cam çalışıp kilit çalışmıyor: cam `bodywork` cihazında ve ikinci kapının arkasında
**değil**; kilit yalnız `setting` cihazında ve ikinci kapının arkasında
(`introspect-dilink3.txt`: bodywork setter'larında kilit yok; `setDoorLock` yalnız setting'de, satır 1414).

## 0x adımı — ÖNCE bunu yap (belirleyici, gerisi buna bağlı)

`0x800003E8`'i **ne** fırlatıyor, onu koddan oku. `setDoorLock`'un gerçek gövdesi BYD framework
servisinde (araçta `framework.jar` / `/system/framework/android.hardware.bydauto*.jar` ya da bir
sistem APK'sı; feature-id tablomuz da framework.jar'dan çıkmıştı — memory `bydauto-feature-id-table`).

Araçta (kablosuz ADB açıkken):
```bash
adb shell 'ls -la /system/framework | grep -i bydauto'
adb shell 'pm path com.byd.car.server; pm list packages | grep -i byd'
# aday jar/apk'leri çek:
adb pull /system/framework/<bulduğun bydauto jar>
# masaüstünde:
#   d2j-dex2jar / baksmali ile aç, ara:
#     setDoorLock, 0x800003E8 / 2147484648, "signature", getPackageInfo, GET_SIGNATURES,
#     checkSignatures, Binder.getCallingUid, PackageManager
```
Çıkan guard üç şeyden biridir — hangisi olduğu saldırıyı seçer:

- **(a) Gerçek APK imza kontrolü** (servis `callingUid`'in imzasını sistemden sorguluyor):
  Permissive context bunu KANDIRAMAZ, çünkü servis bizim context'imizi değil sistemi soruyor.
- **(b) Paket allowlist** (çağıran paket `com.byd.*` / Remote Link servisi mi diye bakıyor):
  yine gerçek uid'den çözülür, sahte `getOpPackageName` yetmez.
- **(c) Önkoşul/değer** (araç durumu, BT anahtar varlığı, ya da value 0/1 dışında bir kod):
  en ucuzu; bu olursa iş neredeyse bitti.

## Saldırı yolları (ucuzdan pahalıya; 0x adımı ne çıkarsa ona göre seç)

1. **Değer/önkoşul taraması (bedava, önce bu).** Yeni eklenen komutlar araçta denensin:
   - `{cmd:"lockOff", value:…}` → `setting.setLockOff(int)` (introspect 1457)
   - `{cmd:"remoteUnlock", value:…}` → `setting.setRemoteCtlUnlockingState(int)` (introspect 1484)
   - `{cmd:"lockViaSetting", value:0|1|2|…}` farklı value'larla.
   Biri `0x800003E8` dışında bir şey döndürürse iz oradadır.

2. **uidّyi yükselt (ucuz).** Şu an helper **shell/uid 2000** olarak koşuyor
   (`Permissive.getPackageName()=com.android.shell`). İkinci kapı güvenilir bir uid
   istiyorsa, helper'ı **system/uid 1000** ya da BYD'nin beklediği paket adıyla koştur.
   Deneme: `app_process`'i farklı uid ile başlat, ya da Permissive'de op-package'ı
   0x adımında çıkan izinli pakete çevir (yalnız (b) saf paket-adı kontrolüyse işe yarar).

3. **Sistem/priv-app olarak kur (orta — root/yazılabilir system gerekir).**
   Yunus'u `/system/priv-app/`'e koy + `privapp-permissions` XML'ine imza iznini ekle, reboot.
   İmzayı değiştirmez ama güven seviyesini değiştirir; (a) katı imza kontrolüyse yetmeyebilir.

4. **Platform imzası (pahalı, kesin — eğer (a) ise).** Yunus'u ROM'un platform anahtarıyla
   (`platform.pk8` / `platform.x509.pem`) imzala. Anahtar firmware imajından çıkarılabiliyorsa
   gerçek imza izinlerini tutarsın ve ikinci kapı da açılır. Memory `kinex-hal-signature-ceiling`.

5. **Güvenilir süreci iş gördür (eğer (a)/(b) ve yukarıdakiler olmazsa).**
   - BYD HAL servisinin Binder/AIDL arayüzünü bulup kilit transaction'ını doğrudan çağır
     (ama izin kontrolü yine bizim uid'imize bakar → aynı kapı, 2. yolla birleştir).
   - Ya da **Kinex'in kendi yoluna bin**: Remote Link relay komut-döngüsü
     (`GET /commands/next` → HAL, `KINEX_NOTLARI.md:91`) güvenilir süreçte çalışıyorsa, ona
     yerelden `lock` komutu enjekte et. Not: Kinex'in paket-içi köprüsünde yerel kilit çağrısı
     YOK (doğrulandı), yani bu yol relay'in araç tarafını tersine gerektirir.

## Dikkat
- Kilit testi araç **P'de ve hız 0** iken (memory `install-only-in-park`).
- `0x800003E8` araçta zaten denenmiş olan tek çağrıydı; yukarıdaki 1. yolun iki komutu
  (`lockOff`, `remoteUnlock`) HENÜZ denenmedi — araca geçince ilk onları çalıştır.
- Eğer 0x adımı guard'ı **gerçek imza kontrolü (a)** çıkarsa: kendi-imzalı APK'dan hiçbir
  metot ismi/argüman kombinasyonu çözmez; iş platform imzasına (4) ya da güvenilir sürece (5)
  düşer. Zamanı boşa harcamamak için **0x adımını 1. yolla paralel** yap.

---

# KİNEX KİLİTLİYOR — mekanizma (tam APK analizi, 2026-11-09)

Kullanıcının tam Kinex APK'sı (`~/Downloads/kinex.apk`, Flutter) decompile edildi (jadx).
Kinex Dolphin'i kilitliyor; kilit yolu bulundu. Önceki "Kinex'te kilit yok" sonucu **yanlıştı**
— string obfuscation'a kanmıştı.

## Kilit yolu (zincir)
1. **Dart** (`libapp.so`): `"lock"` / `"unlock"` komutları (power on/off ile birlikte) bir
   platform-channel'dan native'e gidiyor.
2. **Native** (`classes.dex`, paket `p045m1` = ilk paste'teki `m1`):
   - `C0344a.java` method-channel handler: `case "lock"` → `new C0344a(ctx, 1)`,
     `case "unlock"` → kod r8, `powerOn/powerOff` ayrı kodlar; hepsi bir Runnable olarak çalışıyor.
   - `n0.java`: `AbstractC0485f.a("setting", <metod>, argsJSON, 8000ms)` ile **HAL daemon'una RPC**
     atıyor (zaman aşımı "hal_rpc_timeout"; shell çıktısında "permission denial"/"securityexception"
     kontrol ediliyor → demek daemon **shell olarak** çalışıyor). `n0.n()` ayrıca
     `BYDAutoSettingDevice.getInstance`'ı sarmalı context `h0` (bizim `Permissive` gibi) ile alıyor.
3. **HAL daemon** (`kinex_hal_api_service.jar`, `/data/local/tmp`'de `app_process` ile **shell/uid 2000**):
   gelen `("setting", metod, args)`'ı `BYDAutoSettingDevice` üzerinde reflection'la çağırıyor.

**Sonuç: Kinex'in mimarisi bizim Yunus köprümüzle aynı** (shell daemon + Permissive context +
reflection). Yani kilit bizden de yapılabilir — eksik olan tek şey Kinex'in kullandığı **tam metot
adı + değer**.

## Neden statik grep kaçırdı (ve "kilit yok" dedim)
Kinex tüm HAL metot/sınıf adlarını şifreliyor:
- `assets/kav.bin` (365 bayt): başlık `"KAV1"` + 2 bayt adet (**27 string**) + 16 bayt IV +
  AES/CTR/NoPadding şifreli gövde.
- Çalışma anında `AbstractC0488j.c()` blob'u okuyor, `AbstractC0488j.d(key)` AES-CTR ile çözüp
  27 string'lik tabloyu kuruyor; kod her yerde `AbstractC0488j.f(index)` ile isim alıyor.
- `setDoorLock` vb. bu yüzden **düz metin yok**.

**AES anahtarı koddan sabit DEĞİL:** `MainActivity.java:5679` → `Base64.decode(obj2)`, `obj2` bir
method-channel argümanı, yani **Dart'tan** (libapp.so) geliyor (belki relay'den). Bu yüzden sadece
APK ile `kav.bin`'i offline açıp metot adlarını dökemiyorum. (Deneme değer: ilk paste'teki
`0x5=lock / 0x4=unlock / 0x7=read` kodları muhtemelen gerçek değerler — biz `setDoorLock`'u 1/0 ile
denedik; doğru metot farklı ve/veya değer 5/4 olabilir.)

## Kilidi çıkarmak için — araçta, iki yol
1. **Logcat (en kesin, en hızlı).** Kinex kilitlerken daemon metodu + resultCode'u düz metin
   basıyor (`HalDaemon*`, `KinexOverlay`, `KxB3` etiketleri):
   ```bash
   adb logcat -c
   # Kinex'ten arabayı KİLİTLE (sonra AÇ)
   adb logcat -d > kinex_lock.txt
   ```
   `kinex_lock.txt` içinde `setting` + metot adı + value + resultCode görünür. Aynı daemon'u biz de
   çalıştırdığımız için komutu birebir `ShellHelper`'a koyarız.
2. **AES anahtarını Dart'tan çıkar (daha derin, araçsız olabilir).** `libapp.so`'daki key-derivation'ı
   çöz ya da frida ile `AbstractC0488j.d(key)`'i hook'layıp anahtarı/çözülmüş 27 string'i al →
   `kav.bin`'i çöz → metot adlarını dök. Anahtar relay'den geliyorsa offline imkânsız; frida ile
   çalışırken kesin.

## IKEY izi — önceki not + bugünkü APK bulgusu birleşiyor
Önceki kilit devir notu (`~/Downloads/KILIT-DEVIR-NOTU.md`, 31.10) ve bugünkü APK analizi aynı yeri
gösteriyor:
- Bugün: Kinex'in kilidi **Setting cihazına** daemon RPC'siyle, **şifreli** metot adıyla gidiyor.
- `introspect-dilink3.txt`: aynı Setting cihazında **IKEY** (BYD'nin Bluetooth keyless-entry alt
  sistemi) metotları var — `getBTMacAddr()` (1243), `getIKEYBTLowPowerMode()` (1284),
  `sendBTMacToIKEY(byte[])` (1387), `onIKEYBTLowPowerModeChanged` (1566).
- Düz `setDoorLock` daemon'dan bile reddediliyor (`0x800003E8`) ama Kinex daemon'dan geçip
  **başarıyor** → Kinex `setDoorLock` DEĞİL, başka bir Setting metodu kullanıyor. En güçlü aday **IKEY**.
- **Hipotez:** merkezî kilit reddediliyor çünkü "güvenilir" bir telefon-anahtarı kayıtlı değil;
  önce `sendBTMacToIKEY(mac)` ile bir MAC kaydedilince kilit kabul ediliyor olabilir.

Araçta iki ucuz yakalama bunu kesin ayırır:
1. **logcat** Kinex kilitlerken → metot + resultCode (daemon düz metin basar: `HalDaemon*`, `KxB3`).
2. **Bluetooth HCI snoop** (`btsnoop_hci.log`, Android'in yerleşik özelliği, özel donanım yok):
   `adb shell settings put global bluetooth_hci_log 1` → BT kapat/aç → Kinex'ten kilitle → log'u
   Wireshark'ta aç. GATT yazması çıkarsa **BLE/IKEY**, hiç çıkmazsa **HAL/CAN**.

## Bizim tarafta hazır olanlar
- `ShellHelper.kt` komutları: `lock` (set 0x18000009), `post` (postEvent), `lockViaSetting`
  (setDoorLock), `lockOff`, `remoteUnlock`, ve **`ikey`** — `getBTMacAddr` + `getIKEYBTLowPowerMode`
  okur, `sendBTMacToIKEY(mac)` ile MAC kaydeder (`{"cmd":"ikey","mac":"aa:bb:cc:dd:ee:ff"}`, mac
  verilmezse aracın kendi BT MAC'i), `{"retry":true}` ile hemen ardından `setDoorLock`'u tekrar dener.
  Logcat'ten gerçek metot/değer çıkınca doğru olanı ekle/ayarla.
- Araca geçince ilk sıra: (a) Kinex kilitlerken logcat + btsnoop, (b) `{cmd:"ikey","retry":true}`.
