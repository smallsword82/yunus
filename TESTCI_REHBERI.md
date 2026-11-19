# Yunus 0.2.2 — Test Sürümü Rehberi

Yunus, BYD DiLink 3 araçlar için resmî olmayan, ücretsiz bir ana ekrandır. Bu bir **test sürümüdür**:
hatalar olabilir. Kurmadan önce `DISCLAIMER.md` dosyasını oku; kuran kişi koşulları kabul etmiş sayılır.

Desteklenen modeller: Dolphin, Seal, Atto 3, Atto 2, Seal U, Seal U DM-i, Tang, Han.

## Güvenlik
- Kurulumu, ayar değişikliklerini ve denemeleri **yalnızca araç P'deyken ve dururken** yap.
- Sürüş sırasında ekranla uğraşma.

## Önce şunu kontrol et: kablosuz ADB açılabiliyor mu?
Yunus'un kurulması ve araç verilerini okuması için aracında **kablosuz ADB açık** olmalı.
- BYD'nin yeni yazılımlarında kablosuz ADB **açılamıyor**; bu yazılımlarda BYD üçüncü parti
  uygulama kurulmasına da izin vermiyor (USB'den kurarken "Fail in installation of desktop apps" hatası).
- Kablosuz ADB'yi **eski bir yazılımdayken açmış** olanlarda, yazılım sonradan güncellense de açık kalıyor;
  bu araçlara Yunus kurulabiliyor.
- Kablosuz ADB'si hiç açılmamış, yeni yazılımlı bir araçta Yunus şu an kurulamaz. Bunu aşmanın tek
  bilinen yolu aracın yazılımını eski sürüme düşürmek; bu **garantiyi etkileyebilir ve ekran ünitesini
  bozma riski taşır**. Bu rehber bunu önermez ve anlatmaz; yaparsan tüm risk sana aittir.

## Kurulum
1. **Kablosuz ADB'yi aç:** aracın mühendislik menüsü → Test Tool → kablosuz ADB.
   Yunus araç verilerini (kapılar, camlar, klima, şarj) bu yolla okur; kapalıysa yalnızca ekran açılır.
2. Kur: aynı Wi-Fi ağındaki bir bilgisayardan `adb connect ARACIN_IP:5555` ve ardından
   `adb install yunus-0.2.2.apk`. (BYD'nin USB kurulumu yalnızca üçüncü parti kurulumuna izin veren araçlarda çalışır.)
   Hata alırsan ekrandaki `INSTALL_FAILED_...` satırını geliştiriciye gönder.
3. Yunus'u aç. Araç ekranında **"USB hata ayıklamasına izin verilsin mi?"** sorusu çıkarsa
   "Bu bilgisayara her zaman izin ver" kutusunu işaretleyip **İzin ver**'e bas.
4. **Bütün izinleri ver.** İlk açılışta çıkan her izin penceresinde **İzin ver**'e bas (konum,
   hata ayıklama). Sonra Yunus › Ayarlar › **Sistem** › **İzinler** bölümünde üç satırın da
   (Konum, Müzik kartı, Yardımcı) yeşil olduğunu kontrol et; kırmızı varsa **İzinleri tazele**'ye bas.
5. **Otomatik başlatmaya izin ver.** BYD ayarlarındaki uygulama otomatik başlatma iznini Yunus için aç.
   BYD bu izni her kurulumda ve güncellemede kapatır; her güncellemeden sonra tekrar aç.
6. **Ayarlar › Araç modeli** bölümünden kendi modelini seç (otomatik tanıma henüz yok).
7. Yunus'u ana ekran yapmak için Ayarlar › **Ana ekran uygulaması** › **Varsayılan yap**'a bas.

## BYD ana ekranına geri dönmek
- Ayarlar › Ana ekran uygulaması › **BYD ana ekranına dön**; BYD'nin üst ve alt çubukları da geri gelir.
- Bu da olmazsa: Android Ayarları › Uygulamalar › Yunus › **Kaldır**.

## Kullanım süresi
- İlk 30 gün serbesttir. Kalan gün Ayarlar'da "Kullanım süresi" satırında görünür.
- Süre bitince ekranda **cihaz kodun** yazar (Ayarlar'da "Cihaz kodu" satırında da görünür).
  Bu kodu **smallsword82@gmail.com** adresine gönder; 1 yıl geçerli ücretsiz bir açma kodu alırsın.
  Kodu Ayarlar › **Açma kodu gir** bölümüne yaz.

## Neyi deneyip bildirmelisin
Her madde için "çalıştı / çalışmadı / tuhaf" yazman yeterli:
- [ ] Araç modelin ve DiLink yazılım sürümün
- [ ] Kurulum ve hata ayıklama izni sorunsuz oldu mu
- [ ] 3B araç modeli doğru mu (renk, şekil); kapı, cam, bagaj ve şarj kapağı animasyonları düzgün mü
- [ ] Kapı / cam / bagaj durumu araçla aynı mı gösteriliyor
- [ ] Camlar, klima, sunroof/perde (varsa) komutları çalışıyor mu
- [ ] Hız, vites, batarya, menzil ve şarj bilgileri doğru mu
- [ ] BYD'nin üst/alt çubukları düzgün gizleniyor mu; ekranda kesik ya da kayık yer var mı
- [ ] Haritalar ve diğer uygulamalar açılıyor mu
- [ ] Aracı kapatıp açınca Yunus kendiliğinden geliyor mu, ne kadar sürede

Sorun gördüğün yerin **ekran fotoğrafını** da eklersen çok yardımcı olur.

## Haklar
3B araç modelleri: Ddiaz Design, CC BY-NC-SA 4.0 (ayrıntı `MODEL_CREDITS.md`). Yunus ücretsizdir;
satılamaz, ücretli dağıtılamaz.
