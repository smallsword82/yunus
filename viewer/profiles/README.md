# Araç profilleri (`viewer/profiles/`)

Modele özgü her şey (3D model, kamera açıları, hotspot/şarj-soketi, boya/cam materyal adları,
yetenekler, klima kodlaması, yandan silüet ikonu) bir **profil** dosyasındadır. Model eklemek
**kod değil veri** işidir. Yalnız **DiLink 3** ailesi (Dolphin'le aynı HAL).

**Tam reçete ve şema: deponun kökündeki [`MODEL_EKLEME.md`](../../MODEL_EKLEME.md).** Orada 3D model
gereksinimleri, alan alan profil şeması, Kinex `kinex_car_models.json` → Yunus kamera eşlemesi ve
doğrulama anlatılır. Şema otoritesi `launcher.html` içindeki `DOLPHIN_PROFILE`'dır; şemayı
genişletirsen `MODEL_EKLEME.md`'yi de güncelle.

## Bu klasördeki dosyalar
- `index.json` — kayıt defteri: model seçicide görünen liste (`models`) + araçtan otomatik algılama
  eşlemesi (`detect.modelName` = `getAutoModelName()` int kodu, `detect.vin` = VIN başı deseni).
- `dolphin.json` — Dolphin profilinin referans kopyası. **Sayfa Dolphin için `launcher.html`'deki
  gömülü `DOLPHIN_PROFILE`'ı kullanır** (birebir aynı kalsın diye); bu dosya yalnız şema örneğidir.
- `generic.json` — tanınmayan DiLink 3 aracı için güvenli yedek (Dolphin gövdesi + nötr yetenekler).
- `_template.json` — yeni profil şablonu (kopyala → `<id>.json`, bilmediğin alanı sil → Dolphin'e düşer).

## Hızlı
1. `viewer/<id>.glb` ekle (Kinex'ten çıkarılmış model).
2. `_template.json`'ı kopyala → `<id>.json`, doldur.
3. `index.json`'un `models`'ine bir satır (+ biliniyorsa `detect` eşlemesi) ekle.
4. Masaüstü deneme: `launcher.html?profileTest=<id>` (localStorage'a dokunmaz).
