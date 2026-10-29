# Devir notu — Yunus (BYD Dolphin, DiLink 3)

Tarih: 29.10.2026 · Dal: `claude/live-vehicle-data` · Son commit: `30c03b4`

## Durum özeti

Yunus, araçta native 1920×1080 çalışan Tesla tarzı bir launcher (WebView + `viewer/launcher.html`).
Araç verisi canlı okunuyor, Google Maps harita alanının içinde çalışıyor. Kök/BYD imzası gerekmiyor.

| Konu | Durum |
|---|---|
| Açılış süresi | ✅ Araba ~1.5 sn'de ekranda (önceki açılışın görüntüsü), 3B ~3.9 sn |
| Kapılar, bagaj (3B animasyon) | ✅ araçta doğrulandı |
| Farlar, uzun far, sinyaller, dörtlüler | ✅ araçta doğrulandı |
| Klima açık/kapalı, fan, sıcaklık, direksiyon ısıtma | ✅ araçta doğrulandı (sadece gösterim) |
| Menzil, dış sıcaklık | ✅ canlı |
| Rejen, direksiyon modu, sürücü koltuk ısıtma | ⚠️ eşlendi, Yunus'ta canlı görülmedi |
| Vites, pil % | ⚠️ eşlendi, değişince gelir — sürüşte doğrulanacak |
| Hız, tüketim, ICC, hız tabelası | ❌ sürüşte yakalanıp eşlenecek |
| Kilit, kör nokta | ❌ okunamıyor (BYD imzalı cihazlar) |
| Google Maps harita alanında | ✅ otomatik açılıyor, dokunma/kaydırma çalışıyor |
| Ayarlar | ✅ araba butonu → aracın ayarları; basılı tut → Yunus ayarları |
| Müzik kartı | ❌ hâlâ demo — sıradaki iş |

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

## Sıradaki işler

1. **Müzik kartı (Kinex gibi):** radyo/şarkı adı, kapak, önceki/oynat/sonraki. Radyo uygulamasının
   tamamını gömme — kullanıcı istemedi. Kaynak: medya oturumu (`com.byd.mediacenter` şu an çalıyor
   görünüyor). `NotificationListenerService` + `MediaSessionManager.getActiveSessions` gerekir;
   kullanıcı bir kez **bildirim erişimi** vermeli.
2. **Kart + harita:** harita alanını kapatan bir kart açılınca Maps gizleniyor mu, kapanınca geri
   geliyor mu — kod hazır, araçta görülmedi.
3. **Sürüş kaydı:** hız (motor devrinden hesap, göstergeyle kalibre), pil %, tüketim, ICC, hız
   tabelası, vites değişimi.
4. İsteğe bağlı: küçük gömülü pencerelerde düşük dpi (içerik sığsın).

## Çalışma ortamı

- Araç: `adb connect 10.184.30.214:5555` (kablosuz ADB açık).
- Derleme: `android/` içinde `./gradlew assembleDebug` → `app/build/outputs/apk/debug/yunus-0.1-test.apk`.
- Sayfayı incelemek: `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` + CDP.
- Masaüstü önizleme: `python -m http.server 8765 --directory viewer` → `launcher.html`
  (araç olmadan demo modu, Yunus'un kendi haritası).

## Kullanıcı hakkında

Türkçe konuşuyor. Sonuç görmek istiyor; koddan çıkarılabilecek şeyler için kendisinden test
yapmasını istemekten hoşlanmıyor. Uzun araştırmalarda sabırsızlanıyor — kısa, net ilerle.
