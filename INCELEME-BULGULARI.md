# Yunus kod incelemesi ve akıcılık ölçümü (2026-11-09)

Araç bağlı değilken yapıldı. Hiçbir şey düzeltilmedi, hiçbir dosya değiştirilmedi; bu dosya yalnızca bulgu listesidir.

Durum etiketleri:
- **[D]** kodda kendim okuyup doğruladım
- **[Ö]** bilgisayardaki önizlemede ölçtüm
- **[R]** inceleme raporundan, kendim ayrıca doğrulamadım
- Hiçbiri araçta denenmedi.

Satır numaraları `viewer/launcher.html` içindir (aksi yazmıyorsa), çalışma kopyasının bugünkü hâline göre.

---

## Durum özeti (2026-11-09 akşamı)

> **Araca kuruldu: 2026-11-09 17:35 (P'de, vites ve hız okunarak).** Commit edilmedi. Araçta ölçülenler:
> - Açılış: araba 3,11 / 3,21 / 3,17 / 3,14 sn'de (sabah 3,3-3,4). Açılış sırasında ikinci bir HOME isteği gelirse 4,5 sn'ye çıkıyor (ölçüm hatası olarak görüldü, gerçek kullanımda kaynağı aranmadı).
> - Park, dokunulmamış: 0 çizim, döngü saniyede ~9 tik (sakin döngü devrede).
> - Hiçbir şeyi değiştirmeyen dokunuş: 18 çizim (tasarım gereği önceden 90).
> - Sinyal sütunu (sütun düzeni, yan pencerede medya merkezi): değişmedi. Eski sürüm aynı gün: sinyalde 13 kare > 50 ms, en uzun 134 ms. Yeni sürüm: 16 ve 15 kare, en uzun 133 ve 150 ms. Çizim yükünü azaltmak bu takılmayı gidermedi; neden sayfanın dışında.
> - Alt bar simgeleri gerçek ekran görüntüsünde net ve koyu; sayfa hatası yok.
> - Saate dokunmak BYD'nin tarih ayarını açıyor (ölçümde yanlışlıkla açıldı); hata değil.
> - Kullanıcıyla denendi (17:40-17:47): cam/güneşlik — yardımcı kaydında yalnızca üç komut (güneşlik 60, güneşlik 100, cam 2), sürüklemesiz dokunuş komut göndermedi. Müzik — karttan YT Music ReVanced başlatıldı (süreç 17:46:29'da açıldı, gizli ekran 17:46:42'de kapandı, zorla durdurma yok), 17:50'de hâlâ çalıyordu.
> - Sürüş kaydı 17:48:47'de başlatıldı (araçta, adb'den bağımsız): `/data/local/tmp/drive_1109_1748_ev.log` (yardımcının olay akışı, her satır zaman damgalı) ve `drive_1109_1748_logcat.log`. Durdurmak için: `rm /data/local/tmp/yunus_drive_rec.on` ve `yunus_drive_rec.pids` içindeki süreçler + `nc`, `sleep 14399`.
> - Kullanıcı bildirdi (sürüşte, ~17:49): R'ye alınca 360 açıldı, D'ye geçince harita kayboldu. Kayıt R→D geçişinden sonra başladığı için o an kayıtta yok. 17:50'deki ekran görüntüsünde harita yerinde, Maps sanal ekranda çalışıyor. Neden bulunamadı; P'de R→D ile yeniden denenecek.
> - **Harita R→D'den sonra kayboluyor — neden bulundu (18:04-18:05 kaydı), kodda düzeltildi, 18:10'da araca kuruldu (P'de); gerçek R→D ile henüz DENENMEDİ.** (P'de taklit: öne BYD ayarları getirilip sayfaya R, D gönderildi; harita kutusu görünür kaldı. Ancak bu taklitte sayfa 0 boyuta düşmedi — yalnızca `document.hidden` oldu — yani 360 görüntüsündeki koşulun aynısı değil.) R'de BYD'nin 360 görüntüsü (`com.byd.avc`) öne gelince Yunus sayfası 0×0 ölçüyle yerleşiyor (`stageScale` 0; kayıtta `embed map 0x0 skipped`). O sırada gelen vites olayı `setGear → closeCard → syncEmbedNow` ile harita alanını 0 ölçüp `embedVisible('map', false)` çağırıyor; Yunus öne dönünce (18:05:00) kimse yeniden sığdırmıyor. Yerel görünümde harita kutusu GONE, sayfa ise `mapsIn` sanıyor; P'ye alınca geri geldi. Düzeltme (`viewer/launcher.html`): `pageOut()` (gizli ya da ölçek 0) iken `syncEmbedNow`/`embedSide` hiçbir şey yapmıyor; öne dönüşte (`redraw` olayı ve `visibilitychange`) `syncEmbedSoon()`. Bugünkü değişikliklerden mi önceden mi var, bilinmiyor.
> - YouTube denemeleri (P'de, 18:13-18:19): (a) alt bardan YouTube ReVanced üçüncü pencerede açılıyor, soğuk açılış 6-9 sn bekleme halkası, sonra düzgün. (b) Uygulama arka planda `force-stop` edilince üçüncü pencerede son karesi donuk kalıyor, Yunus fark etmiyor (en az 7 sn izlendi). (c) Alt bardaki uygulama sırası son açılana göre değişiyor (aynı koordinata ikinci dokunuş başka uygulamayı açtı). (d) Uygulamalar listesinden YouTube Morphe üçüncü pencerede değil ana ekranda TAM EKRAN açıldı: ~2 sn siyah + Yunus alt barı, sonra ~5-9 sn tamamen siyah, 11 sn'de YouTube; açıkken Yunus'un alt barı görünmüyor (35 sn izlendi). Kodda arandı (2026-11-14): üçü de tasarım gereği, hata değil — Uygulamalar listesi (`trayGrid` → `launchApp` → `YunusNative.openApp`) her uygulamayı aracın kendi launcher'ı gibi tam ekran açar, üçüncü pencereye yalnızca alt bar/son kullanılanlar (`openInSide`) açar; siyah bekleme Morphe'nin kendi siyah açılış teması + soğuk açılışı (Yunus'un payı yok); başka uygulama öndeyken Yunus'un alt barı `EdgeHome` ile yalnızca birkaç saniye (PEEK_MS) görünür, sonra çekilir, alt kenardan yukarı kaydırma geri getirir.
> - **2026-11-15, kullanıcı isteğiyle değiştirildi ve 14:12'de araca kuruldu (P'de):** uygulamanın nerede açılacağı bir kurala bağlandı (`opensFull`): `com.byd.*` ve sistem bölümündeki uygulamalar (`MediaBridge.launcherApps` artık `sys` alanı veriyor) tam ekran, diğerleri üçüncü pencere; Uygulamalar listesi, alt bar ve son kullanılanlar aynı kuralı kullanıyor (`openApp`). Listede basılı tutma menüsüne "Pencerede aç / Tam ekran aç" eklendi, seçim `S.set.appOpen[pkg]` olarak kalıcı ve kuralı ezer. Pencerede başlatılamayan (`sideRefused`) uygulama tam ekrana düşer. Araçta denendi: YouTube Morphe listeden üçüncü pencerede açıldı; Ayarlar (`com.byd.carsettings`) tam ekran açıldı; HOME ile Yunus'a dönüşte pencere ve harita yerindeydi. Menü satırı ve `sideRefused` düşüşü araçta denenmedi. Commit edilmedi.
> - Deneme sırasında benim hatam: listeye gitmesi gereken bir dokunuş alt bardaki arka cam buğu çözücüsüne gitti; iki dokunuşla kapatıldı, araç verisi (`Ac 0x43400031`) 0 okundu. (e) Ana ekrana dönüş (HOME, 18:19:43): Yunus ~5 sn içinde öndeydi, Morphe arkada açık kaldı, üçüncü pencerede YouTube ReVanced duruyordu, alt bar sırasına Morphe eklendi. O sırada ekranın 353 px yukarı kaymış görünmesi Yunus hatası değil, benim deneme komutumdan (`scrollIntoView` `#stage`'i kaydırdı); `stage.scrollTop = 0` ile geri alındı. Not: `#stage` kodla kaydırılabiliyor (taşan içerik var); bir giriş alanına odaklanma aynı kaymayı yapabilir mi, bakılmadı.
> - Aynı sürüşte görülen, dokunulmayan: 18:00'de üçüncü pencerede YouTube ReVanced siyah, video sağ altta küçük kutuda; üç sütunda uzun far simgesi menzil yazısına değiyor; dikiz kamerası penceresi haritanın üstünde (kullanıcı sürüklemiş olabilir, sorulmadı); 17:50-17:59 arası dokuz `ASR: No room in socket buffer` (sayfa sesi takıldı). 18:02'de araç kapatılıp açıldı (quickboot Yunus'u da durdurur, çökme değil).
> - Henüz denenmedi: dock sayfası, geri viteste sakin döngü.

Hepsi yalnızca kodda; **araca kurulmadı, commit edilmedi**. Kotlin derleniyor, sayfa sözdizimi temiz.

**Düzeltildi ve önizlemede sınandı:** bölüm 1'in 1, 2, 3'ü; bölüm 2'nin 6, 7 (sayfa tarafı), 10, 12, 14, 20'si; akıcılık A, B, C, D; medya kartının her olayda yeniden kurulması; dock kaydırıcılarının her harekette çalışması; şarjdayken çekmece açıkken boşa dönen döngü.

**Düzeltildi, yalnızca araçta sınanabilir:** bölüm 1'in 4'ü; bölüm 2'nin 5, 7 (uygulama tarafı), 8, 9, 13, 15, 23, 28, 29'u; harita tam ekranken gizli sahnenin çizilmemesi; bölücü sürüklemesinde döngünün uyanık kalması ve tek `fitCard` zamanlayıcısı; dock seçicisinin yükseklik sınırı (19'un yarısı); `inset:0`.

**Ek olarak düzeltildi:** çekmece açıkken değişen bir şey (çıkarılan kablo, kapanan kapı) çekmece kapanınca çizilmiyordu; dokunuş sonrası çizimi kısaltınca ortaya çıktı, `heldDraw` ile giderildi.

**Son tur (aynı akşam), önizlemede sınandı:** kontroller kartı her çizimde plaka resmini yeniden kodluyordu (akıcılık F'nin nedeni; açılış bilgisayarda 14-18 ms → ~1 ms, kart açıkken yarım saniyede bir tekrarlanıyordu); sıcak noktalar ve cam noktaları `left/top` yerine `transform` ile taşınıyor; saklanan araç değerleri bellekte tutulup 3 sn sonra tek seferde yazılıyor; dock sayfası yansıma haritasını üretmiyor, arıza ve yolculuk zamanlayıcılarını çalıştırmıyor.

**Alt bar simgeleri (aynı akşam):** klima simgeleri 90 piksellik resimden 80 piksele küçültülüyordu, şimdi kendi boyutunda bire bir çiziliyor (daha net, sekizde bir daha büyük); kapalı durumdaki simgeler %78 yerine tam koyulukta; hava yönünün kullanılmayan parçaları %30 → %50; kapalı klimanın sıcaklık ve fan değeri %35 → %50; ses çubuğunun ve koltuk kademe çizgilerinin zemini açık temada da görünen gri. Ayrıca önizleme düzeltmesi: Chrome 74 için yazılmış boşluk kuralları yeni tarayıcıda `gap` ile birlikte iki kat uygulanıyordu (alt bar taşıyor, ses tuşları ekran dışında kalıyordu); kurallar artık yalnızca `gap` bilmeyen tarayıcıda geçerli, araçta hiçbir şey değişmiyor. Netlik ancak araçta görülür.

**Uygulama simgeleri:** kare karoya çevirme denendi ve kullanıcının isteğiyle geri alındı; simgeler sistemin verdiği şekliyle (yuvarlak) kalıyor, ek işlem yok.

**Son tur, yalnızca araçta sınanabilir:** 17 (araba tam ekranken son uygulama; önizlemede son uygulama yok); geri viteste döngünün sakinleşmesi (sakin döngü yalnızca araçta devrede).

**Bilerek yapılmadı:** 21 (kamera önizlemesi; bilgisayardaki yol araçtakiyle aynı değil).

**Geri alındı, araçta karar gerekiyor:** 11 (vites değişiminde yakınlaştırma). Düzeltince sürüşte araba daha büyük ve çizgiler daha yukarıda görünüyor; araçta alışılan görüntü büyük olasılıkla eskisi. Hangisinin istendiğine araçta bakılmalı.

**Dokunulmadı (araç ya da tasarım kararı gerekiyor):** bölüm 2'nin 16, 17, 18, 21, 22, 24, 25, 26, 27, 30'u; 19'un harita altında kalma kısmı; flex `gap`, `overflow:clip`, `accent-color`; akıcılık E, F, G ve bölüm 5'in kalanları (geri viteste döngü, sonsuz CSS animasyonları, ızgara geçişleri, `postToPage`, bildirimler, yoklamalar, `localStorage` yazımları, dock sayfasının tüm modülü çalıştırması, kare başına bellek ayırma).

---

## 1. Gerçek araca komut giden hatalar

> **1 ve 2 kodda düzeltildi (2026-11-09), araca kurulmadı, commit edilmedi.** Bilgisayar önizlemesinde sahte komut köprüsüyle sınandı: dokunma ve 4 px'lik hareket komut göndermiyor; gerçek sürükleme tek komut gönderiyor; iptal, başka parmak, ikinci parmak ve vitesin P'den çıkması komutsuz bitiyor. Araçta park hâlinde bir kez denenmeli.
>
> **3 ve 4 de kodda düzeltildi (2026-11-09), araca kurulmadı, commit edilmedi.** 3: perde düğmesi canlıda araca komut gönderiyor, ekran aracın bildirdiğini izliyor (önizlemede sınandı). 4: 3 saniyeden uzun beklemiş araç komutu gönderilmiyor; Wi-Fi ve yoklama satırları beklemeye devam ediyor (derleniyor, yalnızca araçta sınanabilir).

1. **Cama dokunup hiç sürüklemeden bırakmak komut gönderiyor [D]** — 3422, 3492.
   `g.target = g.from` ile başlıyor, bırakınca koşulsuz `carCmd('window', …)` gidiyor. Yardımcı süreç yüzdeyi üç duruma çeviriyor (`ShellHelper.kt:714`: ≤5 kapat, ≥70 tam aç, arası yarım). %30 açık bir camın üstünden arabayı döndürmek için dokunmak camı yarıma, %80 açık olanı tam açığa götürür. Bugünkü araç testindeki `cmd window 1 -> 1` satırı bununla uyumlu.
   Düzeltme: yalnızca hedef değiştiyse ve parmak küçük bir eşiği geçtiyse gönder.
2. **Cam sürüklemesi iptal edilemiyor, bırakışta vites bakılmıyor [D]** — 3472-3496.
   `pointercancel` yolu yok (dosyadaki diğer bütün sürüklemelerde var). Dokunma iptal olursa `glassDrag` takılı kalır: araba döndürülemez, döngü sakinleşmez, sayfanın herhangi bir yerindeki sonraki parmak kaldırışı, hangi viteste olursa olsun, cam ya da perde komutu gönderir. Vites yalnızca başlangıçta (`glassAt`, 3385) bakılıyor; nokta yolunda (3464) hiç bakılmıyor.
   Düzeltme: tek bir bitiş fonksiyonu; `pointerup` yalnızca P'de gönderir, `pointercancel` / `blur` / vites değişimi göndermeden bitirir.
3. **Tavan perdesi düğmesi canlıda yalnızca ekranı değiştiriyor [R]** — 3364-3371, 5227.
   `setShade` içinde `S.live` dalı yok (kilit, bagaj, şarj kapağında var). Ekran "Perde %100" der, araca bir şey gitmez, sonraki sürükleme yanlış değerden başlar.
4. **Bağlantı kopukken verilen komut 30-90 sn sonra çalışabiliyor [R]** — `MainActivity.kt:1023-1080`.
   Yardımcıya giden satırlar sınırsız kuyrukta bekliyor; cam / perde / klima komutu yardımcı geri geldiğinde geç uygulanır. Düzeltme: bağlantı yokken hemen başarısız ol, 2 sn'den eski `cmd` satırlarını at.

## 2. Diğer hatalar (önem sırasıyla)

> **5, 6, 7 ve 8 kodda düzeltildi (2026-11-09), araca kurulmadı, commit edilmedi.** Kotlin derleniyor. Önizlemede sınananlar: kırmızı arıza bildirimi (6) ve dock sayfasının ayar kaydetmemesi ile müziği başlatmaması (7'nin sayfa tarafı). Yalnızca araçta sınanabilecekler: gizli ekran kapanınca müziğin çalmaya devam etmesi (5), eski olayların yeniden oynatılmaması (7'nin uygulama tarafı), dinleyicilerin sökülmesi (8). 7'den kalan: son uygulamalar listesi iki sayfa arasında hâlâ ezilebilir.

5. **Karttan başlatılan kapalı müzik uygulaması ~3 sn sonra durduruluyor [D]** — `ShellHelper.kt:175-186`, `MainActivity.kt:442`, `MediaBridge.kt:340`.
   Gizli ekrandaki "uyku" aynı `close` fiilini kullanıyor; `close` 7eaf1cf'den beri o ekrandaki paketleri `force-stop` ediyor. Aynı gizli ekrana park edilen BYD medya merkezi de durdurulur.
   Düzeltme: gizli ekran için yalnızca görevi kaldıran ayrı fiil; sistem paketlerine force-stop yok.
6. **Kapatılan kırmızı arıza, sonraki kırmızı arızaları da gizliyor [D]** — 1530-1532, 1555-1556.
   `find` hep listedeki ilk kırmızıyı döndürür; o yanık kaldıkça (araçta genelde "ana uyarı") banner ya genel metni gösterir ya da kapatıldıktan sonra hiçbir yeni kırmızı arıza için açılmaz.
7. **Dock sayfası (diğer uygulamaların üstündeki ikinci WebView) ayarları eski hâliyle geri yazıyor ve otomatik müziği yeniden başlatıyor [D]** — 6009, 6040; `MainActivity.kt:446-453, 670-681`.
   Dock yüklenince önbellekteki her mesaj, tek seferlik olaylar dahil (`rearClosed`, `back`, `openCard`…), yeniden oynatılıyor. Sayfa tarafında `DOCK_ONLY` koruması yok. Müzik duraklatılmışken başka uygulama ilk kez açılınca müzik kendiliğinden devam edebilir; dikiz kapatma olayı ayarları eski kopyayla ezer.
8. **CarHal dinleyicileri hiç sökülmüyor, olaylar ikiye katlanıyor [D]** — `MainActivity.kt:523, 1417`.
   Her izin sonucunda yeni dinleyiciler kaydediliyor, eskiler kalıyor. Her araç olayı iki kez üretilir.
9. **`play()` her oynatma durumu geri çağrısında yeniden gönderiliyor [R]** — `MediaBridge.kt:250, 337-342`. Oynatamayan bir uygulamada 12 sn boyunca ana iş parçacığı ve iki WebView mesaja boğulabilir.
10. **Vites, model yüklenmeden gelirse sürüşte park kamerası kalıyor [R]** — 1996-1998. Sayfa D'de yeniden yüklenirse yol çizgileri park açısından çizilir, döndürme kilitli kalır.
11. **Vites değişiminde görüntü kaydırma ve yakınlaştırma yeniden uygulanmıyor [D]** — 2514-2525, 3501-3522. P'den D'ye geçişte park yakınlaştırması kalır; sürüşteki ilk boyut değişiminde görüntü ~%29 sıçrar.
12. **"30 km/s altında" kör nokta modu yalnızca sinyal ya da vites değişince değerlendiriliyor [R]** — 3581, 6093. 50'de sinyal verip 15'e inince kamera açılmaz.
13. **Rotadan çıkınca yeniden rota yalnızca bir kez deneniyor [R]** — 4293.
14. **Kart açıkken canlı veri, yazılan metin kutusunu ve sürüklenen kaydırıcıyı sıfırlıyor [R]** — 6171, 4960-4967.
15. **Wi-Fi parolası yazılırken panel yeniden kuruluyor [R]** — 6023, 5686.
16. **Sekmeli düzende üçüncü pencere uygulaması açık kartın üstüne çıkıyor [R]** — 3119, 4716.
17. **Araba tam ekranken son uygulamalardan birini açmak hiçbir şey yapmıyor [R]** — 4732, 4667.
18. **Çekmece açıkken üçüncü pencere gizleniyor ve geri gelmiyor [R]** — 4716, 5008-5018.
19. **Dock "+" seçicisi gömülü haritanın altında kalıyor, yükseklik sınırı yok [R]** — 4889, 935.
20. **Şarkı adı ve uygulama adı `innerHTML` içine kaçışsız yazılıyor [R]** — 2815-2818, 4709, 4995. Bluetooth telefondan gelen özel hazırlanmış bir parça adı sayfada kod çalıştırabilir; sayfanın elinde araç komut köprüsü var. `escHtml` zaten mevcut (4015).
21. **Kamera önizlemesini kapatınca sütun, panel ve araç kamerası açık kalıyor [R]** — 5157, 3653 (deneme ayarı `sigEdge` açıkken).
22. **Sinyal sütunu, kamera penceresi açıkken bir sinyal boyunca kapalı kalabiliyor [R]** — 3532-3533.
23. **Durdurulan navigasyondan sonra eski rota çizgisi kalabiliyor [R]** — 4100-4120.
24. **CarCamera: `cam` iki iş parçacığı arasında kilitsiz; açılış yavaşsa kamera sızabilir ya da pencere siyah kalır [R]** — `CarCamera.kt:189-203, 307-328`.
25. **Etkinlik yeniden yaratılırsa kenar şeridi ve dock WebView'ı yetim kalıyor [R]** — `MainActivity.kt:646`, `EdgeHome.kt`, manifest `configChanges` eksikleri.
26. **Yardımcı: iptal edilmeyen `dumpsys` yoklayıcıları; yavaş fiiller dokunma satırlarını bekletiyor [R]** — `ShellHelper.kt:107-160`.
27. **Ekranda-tut izleyicisi tek bir istisnada kalıcı olarak bitiyor [R]** — `ShellHelper.kt:236`.
28. **Sesli yönlendirme ilk denemede başarısız olursa bir daha denenmiyor [R]** — `MainActivity.kt:497-508`.
29. **Son uygulamalar tuşundaki tıklama yutucu, hareket bittikten sonra kalabiliyor [R]** — 2612-2618.
30. **2,5 sn'yi aşan resim çözme iki kez yapılıyor, ~52 MB bitmap bırakılmıyor [R]** — 1820 (yalnızca yavaş yolda).

## 3. Chrome 74'ün (araçtaki WebView) yok saydığı CSS

- **`overflow:clip` [D satırlar var]** — 205, 406, 725, 731. `#mapWrap` ve `#sidePanel` araçta `visible` kalıyor.
- **`inset:0` [R]** — 733. "Harita hazırlanıyor…" notu ortalanmıyor.
- **Flex kapsayıcıda `gap` [R]** — 61 kural; ızgaradaki `gap` çalışıyor. Bilgisayar önizlemesi ile araç arasındaki boşluk farklarının kaynağı.
- **`accent-color` [R]** — 592, 753, 854.
- Betik tarafı temiz: `?.`, `??`, `replaceAll`, `.at()` vb. yok.

## 4. Akıcılık: bilgisayarda ölçülenler [Ö]

Yöntem: sayfa bilgisayarda açıldı; WebGL çağrıları, kare aralıkları, kare başına betik süresi, uzun görevler ve DOM yazımları sayıldı. Bilgisayarın ekran kartı araçtakinden çok hızlı olduğu için süreler araca taşınmaz; **sayılar** (çizim sayısı, derleme sayısı, gereksiz kare sayısı) taşınır.

| Senaryo | Araba çizimi | Not |
|---|---|---|
| Park, dokunulmamış | 0 | Sakin |
| Hiçbir şeyi değiştirmeyen bir yere dokunma (saat) | 3 sn'de 90 | Gereksiz |
| Sinyal açma, parkta | ilk 1,5 sn'de ~45 | Gereken: yanıp sönme başına 1 |
| Far / kapı / bagaj değişimi | her birinde ~45 | |
| Şarj (dolmamış) | 15 / sn | Tasarlandığı gibi |
| Şarj (dolu) | 0 | |
| Sürüş | 30 / sn | Tasarlandığı gibi |

Her araba çizimi: **158 çizim çağrısı, 137 bin üçgen** (sürüşte 215 çağrı).

> **A, B, C ve D kodda düzeltildi (2026-11-09), araca kurulmadı, commit edilmedi.** Önizlemede yeniden ölçüldü: boş dokunuş 90 → 18 çizim; sinyal (4 sn) 47 → 19; far 45 → 10; kapı 45 → 14 (yerine oturana kadar); ilk şarj takışı 2 → 0, ilk D 1 → 0 shader derlemesi; sürüşte çizim başına 215 → 191 çağrı. Çizim artık sabit süre yerine "bir şey hâlâ hareket ediyor mu" bayrağına (`sceneMoving`) bağlı. Dikkat edilecek tek şey: dokunuştan 0,6 sn'den sonra, hiçbir şey hareket etmeden arabanın görüntüsünü değiştiren bir işlem varsa resim bir sonraki dokunuşta güncellenir; böyle bir işlem bulmadım ama her tuşu tek tek denemedim.

Bulgular:
- **A. Her dokunuş 3 sn boyunca arabayı 30 kare/sn çizdiriyor** — 3264-3265. Çekmece hariç her dokunuşta, arabayla ilgisi olmasa da. Araçta her dokunuşun başlattığı animasyon (kart, pencere) ekran kartını bu 90 çizimle paylaşıyor. Düzeltme: sabit süre yerine "gerçekten hareket eden bir şey var mı" denetimi (parçalar, kamera, lambalar yerine oturunca dur).
- **B. Sinyal / far / kapı olayı 1,5 sn tam hız çizdiriyor** — 6174. Sinyal sütununun açıldığı, kameranın başladığı ana denk geliyor; bu sabahki sütun takılmasına sayfa tarafından katkı veren tek yük bu. Kanıtlanmadı, ama denemesi ucuz.
- **C. İlk D'de 1, ilk şarj takışında 2 shader çalışma anında derleniyor** — 1861-1863. Araçta her biri ~130 ms, tam kamera uçuşunun başladığı karede. Düzeltme: yol çizgisini ve şarj donanımını açılıştaki ısıtma listesine ekle.
- **D. Yol çizgileri: 134 üçgen için 57 çizim çağrısı** (önizlemede 4 şerit; araçta 2 şerit, yaklaşık yarısı) — 1691-1716. Görünmeyen (tamamen saydam) parçalar da çiziliyor. Düzeltme: solma aralığı dışındakileri gizle ya da şerit başına tek örneklenmiş ağ.
- **E. Araba 158 ayrı parça olarak çiziliyor**; 96 parça 500 üçgenin altında. Hareket etmeyen parçaları malzemeye göre birleştirmek çağrı sayısını kabaca üçte bire indirir. En büyük kazanç burada ama en büyük iş de bu.
- **F. Kontroller kartı her açılışta bilgisayarda 14-18 ms, ilkinde 51 ms** eşzamanlı iş (araçta kabaca 5 katı). Diğer kartlar 1-9 ms.
- **G. Bölücü çift dokunuşunda tuval geçiş boyunca 26 kez yeniden boyutlanıyor** (her kare, sınırsız). Yunus'un kendi haritası açıksa o da 34 kez.
- Temiz çıkanlar: sinyal, far, kapı, bagaj, vites P/R, düzen değişiminde çalışma anında shader derlemesi yok; hiçbir senaryoda doku yeniden yüklenmiyor; medya ve telemetri mesajları kare başına DOM yazımına yol açmıyor (saniyede 1-6 yazım); 50 ms'yi aşan görev yalnızca kontroller kartının ilk açılışında.

## 5. Akıcılık: koddan okunanlar

- **Harita tam ekranken gizli 3B sahne sürüşte 30 kare/sn çizilmeye devam ediyor [R]** — 3216, 3233. Gömülü Haritalar'dan ekran kartı zamanı alır.
- **Şarjdayken çekmece açıksa sayfa her vsync'te kare istiyor [R]** — 3257.
- **Geri viteste döngü hiç çizmeden her vsync'te tüm işini yapıyor [D]** — 3184, 3233.
- **3 sn'den uzun bölücü sürüklemesinde döngü 10 kare/sn'ye düşüyor; her `pointermove` bir `fitCard` zamanlayıcısı kuruyor [R]** — 3134-3138, 3102.
- **Dock kaydırıcıları (ses, sıcaklık, fan) her `pointermove`'da tam ayarlayıcıyı çalıştırıyor [R]** — 5767. Fan sıfırdayken her harekette klima kapatma komutu gidiyor.
- **Her medya olayında kart yeniden kuruluyor ve kapak yeniden çözülüyor [R]** — 2782-2806; yerel tarafta her olayda tüm simgeler ve kapak base64 olarak yeniden gönderiliyor (`MediaBridge.kt:418-432`), ana iş parçacığında.
- **`postToPage` her mesajı ana iş parçacığında bir kez daha ayrıştırıyor, iki WebView'da çalıştırıyor [D satırlar var]** — `MainActivity.kt:664-681`.
- **Her bildirimde tüm bildirimler ana iş parçacığında çekiliyor [R]** — `MediaBridge.kt:158-202`.
- **Sonsuz CSS animasyonları (fan pervanesi, müzik dalgası) sürüş boyunca her vsync'te kare ürettiriyor [R]** — 1013, 462.
- **Kısa ama pahalı geçişler: ızgara sütunları, kart yüksekliği [R]** — 199, 823.
- **Kısa parçaların sakinleşmeden önce yerine oturmaması [R]** — 2039-2059: perde / bagaj son %2-10'da çizim durabilir, sonraki dokunuşta sıçrar.
- **Yoklamalar hiç durmuyor [R]** — `ShellHelper.kt:598-640`, `StatusBridge.kt:25`.
- **Sık `localStorage` yazımı [R]** — 6083, 5260-5269, 5388.
- **Dock sayfası tüm modülü çalıştırıyor: ikinci WebGL bağlamı, aralıklı zamanlayıcılar [R]** — 1647-1662.
- **Lamba ve sıcak nokta yolunda kare başına bellek ayırma; noktalar `left/top` ile taşınıyor [R]** — 2199-2263, 2484-2495.

## 6. Önerilen sıra

1. Bölüm 1'in 1 ve 2 numarası (gerçek cam hareketi).
2. Bölüm 2'nin 5, 6, 7, 8 numarası.
3. Akıcılık A, B, C, D (küçük değişiklikler, ölçülebilir kazanç).
4. Kalanlar; E ayrı bir iş.

- 2026-11-15 14:25: Haritalar'ın konumu göstermemesinin nedeni bulundu, Yunus dışı: ReVanced Haritalar (25.16) konumu microG'den `app.revanced.android.location.internal.GoogleLocationManagerService.START` adıyla istiyor; yüklü ReVanced GmsCore 0.3.13.2.250932 yalnızca `com.google.android.location.internal…START` adını tanıyor (`cmd package query-services` ile iki ad da sorgulandı), bağlantı logcat'te "not found". GPS'in kendisi 4 m fiks veriyor. Çözüm: MicroG-RE (MorpheApp, `${basePackageName}.android.location.internal…START` tanımlıyor), 7.3.1 (2026-11-14), arm64 APK: https://github.com/MorpheApp/MicroG-RE/releases/download/7.3.1/microg-7.3.1-icon-arm64-v8a.apk — imza farklı olabilir, üzerine kurulamazsa kaldır-kur gerekir (YouTube hesabı düşer). Kullanıcının isteğiyle `MainActivity.kt`'deki 4,5 s'lik "konumum" dokunuşu kaldırıldı, 14:28 derlemesi P'de kuruldu.
- 2026-11-15 14:31: ReVanced GmsCore kaldırıldı, MicroG-RE 7.3.1 (arm64) kuruldu (P'de), konum izinleri `pm grant` ile verildi (FINE/COARSE/BACKGROUND). Yunus yeniden başlatıldı; Haritalar ilk açılışta mavi noktayla konumu canlı gösterdi, dokunuş yok. Beklenen yan etki: ReVanced YouTube/YT Music Google oturumu düştü.
- 2026-11-15 14:45: microG'ye Google hesabı eklendi. İlk deneme "Google sunucularıyla iletişimde sorun": cihaz kaydı (checkin) Google tarafından `invalid hardware identifier: "DiLink3.0"` (400) ile reddediliyor; microG Ayarları → Cihaz Kaydı → Profil seç → Google Pixel 5 (Android 13) seçilince geçti. Kullanıcı şifreyi kendisi girdi. ReVanced YouTube oturumu açık; microG pil optimizasyonu `dumpsys deviceidle whitelist +app.revanced.android.gms` ile kapatıldı. Kalan: ReVanced YouTube her açılışta "MicroG GmsCore'nin yeni sürümü 0.3.13.2 mevcut, 7.3.1 kullanıyorsunuz" diye uyarıyor (ReVanced'ın sürüm kontrolü MicroG-RE'yi tanımıyor; İptal ile geçiliyor, çözümü YouTube'u Morphe yamalarıyla yeniden yamalamak).
- 2026-11-15 14:55: GmsCore sürüm uyarısı kapatıldı: ReVanced YouTube ve YT Music'te ReVanced ayarları → Çeşitli → GmsCore → "GmsCore güncellemelerini denetle" kapalı (anahtar `revanced_gms_core_check_updates`); ikisi de yeniden başlatıldı, uyarı yok, oturumlar açık. Haritalar: hesap eklendikten sonra yeniden başlatılınca oturumu aldı (profil + Ev kısayolu). Not: Yunus yeniden başladıktan sonra CDP'de iki ana sayfa hedefi görünebiliyor, biri hız verisi taşımıyor; gate.py artık tüm sayfaları okuyor.
- 2026-11-15 15:05: "Harita hazırlanıyor"da takılma. Neden: aynı anda iki Yunus vardı. Yunus çalışmıyorken (kurulum ya da force-stop sonrası) `am start -n app.yunus.launcher/.MainActivity` ile açılan kopya ana ekran yığınının dışında (StackId≠0) kalıyor; sonraki HOME ikinci bir kopya açıyor. Haritalar arka plandaki kopyanın sanal ekranında kalınca öndeki Yunus onu alamıyor (yardımcı: "in Yunus's display …, left there"). İki kopya, benim 14:28 ve 14:47'deki yeniden başlatmalarımdan çıktı; normal kullanımda (araç açılışı HOME ile) beklenmiyor. Denenen ve geri alınan: yardımcıda "süreç ölünce yeniden başlat" izlemesi. Gereksiz çıktı, çünkü Android 10 görev kaydı duran Haritalar'ı ekran görünür olunca kendisi yeniden açıyor (`am kill` ile denendi). Ayrıca son uygulamalardan kapatılan uygulamayı geri açabilirdi. Kurulu sürüm: yalnızca "konumum" dokunuşunun kaldırıldığı sürüm (15:03 derlemesi, P'de, HOME ile açıldı).
- 2026-11-15 15:20: Enerji ve yolculuk kartları. Yardımcıya `getTotalElecConValue` (toplam kWh; 3120,4) ve `getTotalElecConPHMValue` (kWh/100 km; 15,1) eklendi; `getDrivingTimeValue` 63,6 okudu, birimi belirsiz, gösterilmiyor. Yolculuk kartına 3. sayfa "Tüm zamanlar" (km, kWh, kWh/100 km ortalama, 1 kWh ile km); tüketim birimi her yerde kWh/100 km. Sayfa yeni anahtarları `telemetryKeys` süzgecinde atıyordu, eklendi; `CAR_HAL_KEEP` ile yeniden başlatmada hemen görünüyor. Müzik kartının bulanık kapağı enerji kartına 84 px taşıyordu (gölge): `.mmCard { overflow:hidden; }`. Tüm zamanlar ve gölge düzeltmesi araçta kurulu ve görüldü (P). Enerji grafiğinin yerine dört sayı (şu an, kW, son 45 sn, aracın ortalaması) + birim açıklaması: araçta kuruldu (P, 15:25) ve ekranda görüldü.
- 2026-11-15 15:35: Kullanıcı grafiği istedi (dört sayı değil, yalnızca görsel iyileştirme): grafik geri geldi. Yeni tasarım: çizginin altı dolgulu, sıfırın altı yeşil (geri kazanım), ölçek (0/20/40) sağ kenarda küçük, aracın ortalaması kesikli çizgi, en yeni nokta sağda; başlıkta anlık kWh/100 km ya da "Duruyor · x kW"; altta "Son 45 sn ort. · aracın ort. · yeşil: geri kazanım"; dururken grafikte açıklama satırı. Araçta P'de kuruldu, duruyor hâli görüldü; sürüşteki çizgi yalnızca masaüstü önizlemesinde görüldü.
- 2026-11-15 15:50: Enerji grafiği artık mevcut sürüşün başından şimdiye kadarını çiziyor (yolculukla birlikte saklanan 5 s'lik dilimler, 120'de ikişer birleşir; altında "başlangıç" ve süre; alt satırda "Bu sürüş ort."). Araçta P'de kuruldu, sürüşte görülmedi. Harita ortalama: Haritalar ilk karesini çizdikten 2,5 s sonra tek "konumum" dokunuşu eklenip kuruldu, kullanıcı dokunuş istemediği için aynı gün geri alındı. Google Haritalar navigasyon dışında aracı yalnızca konumum düğmesine basıldıktan sonra (takip modu) ortada tutar; harita sürüklenince takip biter. Her açılış soğuk başladığı için takip kapalı gelir.
- 2026-11-15 16:05: Sinyal kamerası: sütun (setSigCol: harita/araç kaydırma, gömülü uygulamaları kaydırma) kapatıldı; iki taraf da durum çubuğunun altında 520 px genişlikte, aynı boyda yüzen pencere (#sigSpot top:56px her iki tarafta; önizleme paneli de yüzer). Yerel pencerenin kayışı 150 → 100 ms. Kamerayı sürüşte açık tutma (camWarm) bilerek kapalı: bir çekirdek yiyip kastırıyordu; soğuk açılış süresi bu yüzden aynı. Haritalar konumum dokunuşu ilk kareden 1 s sonra (2,5 s doğrulandı, 1 s doğrulanmadı). P'de kuruldu, sinyalle denenmedi.
- 2026-11-15 16:12: Yan yana düzende harita ile üçüncü pencere yer değiştirebiliyor: üst çubuktaki düzen seçicide ⇄ düğmesi (yalnızca yan yana, yatay), ayar `swapSide`; sınıf `swapped` sütunları car | üçüncü pencere | harita yapar; yan tutamak üçüncü pencerenin harita kenarında, sürükleme yönü ters. Masaüstü önizlemede denendi, araçta P'de kuruldu, araçta denenmedi.
- 2026-11-15 16:20: Tek pencere: yan yana düzende uygulama açıkken düzen seçicide "haritayı kapat" düğmesi; basınca üçüncü pencere haritanın yerini alır (sideW = sideRoom, harita genişliği 0, Maps gizlenir), tekrar basınca üçte birlere döner. Uygulamanın ✕'i haritayı geri getirir. Önizlemede denendi, araçta P'de kuruldu, araçta denenmedi.
- 2026-11-15 17:00 (araç çevrimdışı, kayıttan): 9-10 Kasım yardımcı olay kaydı (`drive_1109_1748_ev.log`, 590 bin satır) tarandı. **Düzeltme:** `ADAS 0x0D500018` kör nokta değil, `ADAS_CMD_CST_SWITCH` (konforlu duruş): her seferinde ~5 km/s'den duruşa geçerken 2 olup 1-2 sn sonra 1'e dönüyor. `ADAS 0x12200046` = `ADAS_AVH_STATE` (otomatik tutma, dururken 2). Kör noktanın gerçek sinyali `ADAS 0x41800008` (2 = araç var), 84 olay, 13-64 km/s; çoğu sinyalsiz, taraf vermiyor. Gösterge uyarısı `Instrument 0x26F00028` = 6 bununla birlikte geliyor. DOW `0x41800020/22` (sol/sağ) yalnızca dururken/P'de ateşliyor (kapı açma uyarısı), sürüşte kör nokta tarafı vermiyor. `Instrument 0x1440002E` sinyal sesi (1/2 tik-tak, 3 bitti), taraf yok. Sonuç: taraf yalnızca sinyal yönünden gelir; sayfanın mevcut kodu (iki çizgi uyarır, sinyal verilen kırmızı) doğru, değişiklik yok.
- 2026-11-15 17:00: Hız tabelası: araç tabela değerini yayınlamıyor. Logcat'te yalnızca `tsrState` 0/1 (tanıma açık/kapalı) ve haritanın araca yazdığı `setIsaMapSpeedLimit` var; okunacak bir değer yok. Şerit: `0x3160000C/2A` (sol/sağ çizgi) zaten kullanılıyor.
- 2026-11-15 17:00: `getDrivingTimeValue` (63,6) = `STATISTIC_DRIVING_TIME` 0x4A508024, saat (ondalıklı), framework'te tavanı CAN-FD araçta 99,9 sa. 19 bin km için tüm zamanlar ~500 sa olurdu: bu değer sıfırlamadan beri süre, tüm zamanlar değil. Araçta tüm zamanların sürüş süresi tutulmuyor; 'Tüm zamanlar' sekmesine eklenmedi. Alternatifler (araçta okunabilirliği denenmedi): son şarjdan beri `STATISTICS_POST_RECHARGE_TRAVEL_TIME_HOUR/MINUTE` 0x4A40D026/30, mevcut yolculuk `INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_TIME` 0x4A50B012 (kayıtta 0,0-0,5 sa görüldü).
- 2026-11-15 17:00: Temizlik: kullanılmayan sinyal sütunu kodu (setSigCol, sigCamReady, sigPending, sigW, SIG_COL, .sigAnim) silindi; `--sigL/--sigR` sütunları 0 px kalıyor (diğer sütunların numaraları değişmesin). `sideCamReady` mesajı artık yok sayılıyor. Önizlemede sol sinyal açılıp kapandı, hata yok. Uygulama pencerede açılamazsa (sideRefused) tam ekrana geçiyor ve artık bu uygulama için 'tam ekran' hatırlanıyor (sürücü basılı tutma menüsünden seçim yapmadıysa); her açılışta pencereyi deneyip düşmüyor. Kayıt betiği `tools/drive_rec.sh` olarak projeye alındı, logcat 4 × 20 MB ile sınırlandı. APK derlendi (release/), araca KURULMADI (araç çevrimdışı).
- Sonraki bağlantıda: eski kayıt betiği hâlâ çalışıyorsa silinen logcat dosyası açık kalıp yer tutuyor olabilir: `cat /data/local/tmp/yunus_drive_rec.pids`, `ls /data/local/tmp/yunus_drive_rec.on`, `df /data` ile bak.
- 2026-11-16 12:40: 16 dk'lık sürüş kaydı (12:24-12:40, `drive_1116_1224_*`, logcat sınırı çalıştı: 2 dosya, 41 MB). 15 sinyal (8 sol, 7 sağ), her biri Yunus'a `Light 0x38A0002C` ile 2-25 sn ulaştı. Kamera D'de bir kez açıldı (12:24:53) ve sürüş boyunca açık kaldı: dikiz aynası ayarı (`rearMirror`) açık, yan pencereler aynı kameradan çiziliyor, bu yüzden sinyalde görüntü beklenmiyor. Logcat'te Yunus için hata, çökme, ANR, kare atlama yok. R'ye hiç geçilmedi (R→D denenmedi). Kör nokta `0x41800008`=2 34 kez, tarafsız (önceki bulguyla aynı).
- 2026-11-16 12:44: Yük: araçta yük ortalaması 18,9 idi. Yunus sürüşte 32 bin araç olayı aldı (33/sn, ana iş parçacığında). En çok gelenler: Sensor `SENSOR_AX_STATUS_223` 9 900, Statistic anlık akım 8 100 (kullanılıyor), SafetyBelt 2 400. Sayfa Sensor, SafetyBelt, PM2.5, Power cihazlarının hiçbir olayını kullanmıyor; bu dinleyiciler kaldırıldı (CarHal.deviceClasses). Kuruldu (P, gate [1,0]); P'de 20 sn'de 121 olay (çoğu istatistik).
- 2026-11-16 12:46: Haritanın açılışta konum düğmesine 1 sn sonra bir kez basması araçta doğrulandı: kurulumdan sonraki soğuk açılışta konum haritanın tam ortasında, düğme takip modunda. Nokta gri (otoparkta GPS zayıf).
- 2026-11-16: Araçtan okundu (probe, Statistic 1014): son şarjdan beri / sıfırlamadan beri / Trip A / Trip B sürüş süreleri 1023 sa 63 dk = 'değer yok'. Bu araç bu süreleri vermiyor.
- 2026-11-16 12:54: Haritalar canlı konumu göstermiyordu (gri nokta, sürüşte takip yok). GPS iyiydi (27 uydu); microG işlemi hiç çalışmıyordu. Neden BYD'nin 'ilişkili başlatma' denetimi (`persist.sys.relatestart`, ActiveServices `ssc_skip`): her yeni kurulan uygulama `content://appops/settings` içinde 1 (başkaları başlatamaz) olarak işaretleniyor; microG kapalıyken Haritalar'ın bağlanması 'ignored' (sürüşte 4 kez), kabuktan startservice de engelli. Kullanıcı BYD ayarlarında microG için otomatik başlatmaya izin verdi; yeniden başlatmadan sonra microG açılışta kendiliğinden başladı (12:53:41), konum mavi ve ortada. Yeni kurulan / güncellenen her uygulamada bu izin yeniden kapanır (PACKAGE_REPLACED → 1): microG güncellenirse ayar tekrar verilmeli.
