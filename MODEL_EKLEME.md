# Yunus'a yeni BYD modeli ekleme — üretim şartnamesi / prompt

Bu dosya iki işi birden görür: (1) başka bir pencerede bir modelin 3D aracını + profil verisini
üretmesi için **yapıştırılabilir bir prompt**, (2) kalıcı **model ekleme reçetesi**. Bir model
eklemek **kod değil veri** işidir: bir `.glb` + bir `profiles/<id>.json` + `index.json`'a bir satır.
`launcher.html` ve `ShellHelper.kt`'ye **dokunulmaz**.

> Kapsam: **yalnız DiLink 3 ailesi** (Dolphin ile aynı HAL). Yunus'un Dolphin yolu birebir korunur;
> her yeni model kendi profilini üstüne biner. Tanınmayan model güvenli bir genel profile düşer.

---

## BAŞKA PENCEREYE VERİLECEK PROMPT (aynen yapıştır)

> **Görev:** Yunus launcher'ına **Kinex'in desteklediği bütün DiLink-3 BYD modelleri** için 3D araç +
> profil verisi üret. Depo: `teslauncher`. Referans olarak mevcut Dolphin profilini (`launcher.html`
> içindeki `DOLPHIN_PROFILE` ve bu dosyadaki `dolphin.json` örneği) birebir takip et.
> **Hangi modeller (ŞİMDİLİK yalnız Türkiye'de satılanlar):** aşağıdaki **"Üretilecek modeller"**
> bölümündeki **7 model** — `byd_seal`, `byd_atto_3`, `byd_atto_2`, `byd_seal_u`, `byd_seal_u_dmi`,
> `byd_tang`, `byd_han` (Dolphin zaten var). Diğer `dl3` modelleri (Seagull, M6, Destroyer, Seal 06 GT)
> ve `dl5` olanları (Sealion 7, Shark) **şimdilik üretme**. `<id>` = Kinex'in kendi model anahtarı.
> Her model için üreteceğin şey yalnızca:
> 1. `viewer/<id>.glb` — aracın 3D modeli (aşağıdaki **glb gereksinimleri**ne uygun).
> 2. `viewer/profiles/<id>.json` — aşağıdaki **profil şeması**na uygun, tüm alanlar dolu (silüet ve
>    çift-bölge klima bayrağı dahil).
> 3. `viewer/profiles/index.json`'a modelin satırı (model kodu/VIN → `<id>`).
> `launcher.html`, `ShellHelper.kt` veya başka kodu **değiştirme**. Her modelden sonra
> `?model=<id>.glb#debug` ile yüklendiğini, yönün doğru (ön = +z) ve kamera ön ayarlarının aracı
> düzgün çerçevelediğini göster.
> **Model kaynağı:** Kinex APK'sındaki `assets/flutter_assets/assets/models/*.glb` (glTF-Transform ile
> Draco+WebP sıkıştırılmış) ve kamera ön ayarları `kinex_car_models.json`'daki o modelin girdisi.
> Kinex kodunu kopyalama; yalnız model dosyasını ve kamera/hotspot sayılarını al. Kinex APK'sı depoda
> yoktur — sana ayrıca verilecek; yoksa iste.

---

## Üretilecek modeller (hedef liste)

**Kesin liste** — Kinex APK'sındaki `assets/flutter_assets/assets/config/kinex_car_models.json`'dan
birebir çıkarıldı (14 model: 12 `dl3` + 2 `dl5`). `<id>` = Kinex'in kendi model anahtarı (aynen kullan).

**ŞİMDİLİK yalnız Türkiye'de satılan modeller üretilecek** (kullanıcı kararı). Türkiye'de satılan +
`dl3` olan + Dolphin dışındaki **7 model**:

| `<id>` | Model (label) | glb (`modelAsset`) | precise cam | fineAc | yakıt |
|---|---|---|---|---|---|
| `byd_seal` | Seal | `2024_byd_seal.glb` | **evet** | evet | EV |
| `byd_atto_3` | Atto 3 (Yuan Plus) | `2024_byd_atto3.glb` | hayır | hayır | EV |
| `byd_atto_2` | Atto 2 (Yuan Pro) | `2024_byd_atto_2.glb` | hayır | hayır | EV |
| `byd_seal_u` | Seal-U (Song Plus) EV | `2024_byd_seal_u.glb` | hayır | hayır | EV |
| `byd_seal_u_dmi` | Seal-U (Song Plus) DM-i | `2024_byd_seal_u_dmi.glb` | hayır | hayır | PHEV |
| `byd_tang` | Tang | `2024_byd_tang.glb` | hayır | evet | EV |
| `byd_han` | Han | `2022_byd_han.glb` | hayır | evet | EV |

`byd_dolphin` (Dolphin) **zaten var — üretme** (TR'de satılıyor, referans profil).

**ŞİMDİLİK ÜRETME (TR lineup'ta yok):** `byd_seagull`, `byd_m6`, `byd_destroyer`, `byd_seal_06_gt`.
**KAPSAM DIŞI (`dl5`):** `byd_sealion_7` (TR'de satılsa da farklı HAL), `byd_shark`.
TR lineup genişlerse bu modeller aynı yöntemle sonradan eklenir (hepsi `dl3`, glb'leri Kinex'te mevcut).

### kinex_car_models.json → Yunus profili alan eşlemesi
Her modelin girdisi profilin neredeyse tamamını verir; çevir:

| Kinex alanı | Yunus profili |
|---|---|
| `modelAsset` | `glb` (ayrık parça yoksa `fallbackGlb` = aynısı) |
| `label` | `name` (index.json `models` satırı) |
| `camera` (theta/phi/radiusPercent ön ayarları) | `presets` (`r` = radiusPercent); eksik `actions/rear/roof/charge`'ı Dolphin'den al, `#debug`'da düzelt |
| `hotspots` | `hot` (hTrunk/hCharge/hShade) + şarj soketi node'u |
| `materials` | `nodes` (paintMat/roofMat/darkGlass/sunroof) |
| `supportsWindowControls` | `caps.window` |
| `preciseWindowControlsEnabled` | `caps.preciseWindow` |
| `supportsRoofControls` | `caps.sunroof` |
| `supportsFineAcControls` | ipucu (yarım-derece/ince klima); `acHalfDegree`/`caps.dualClimate`'i araçta teyit et |
| `windowAreas`, `tirePositions` | referans (cam/lastik etiketleri) |

> Not: Modelin glb'si Kinex'te **tek parça gövde**dir (kapı/cam/bagaj ayrık değil) — animasyon
> çalışmaz, Kinex de çalıştırmıyor; sorun değil. İstersen parçaları ayırıp konvansiyon adlarını
> (Door_FL vb.) ver. Materyal adlarını glb'den oku (Kinex'in `materials` alanı da yardımcı olur).

---

## Dosya yerleşimi

| Dosya | Yer | Not |
|---|---|---|
| Model | `viewer/<id>.glb` | `launcher.html`'in yanına; profil `glb` alanı sadece dosya adı |
| Profil | `viewer/profiles/<id>.json` | Aşağıdaki şema |
| Eşleme | `viewer/profiles/index.json` | `{ "byModelName": {...}, "byVin": [...] }` |

---

## glb gereksinimleri

- **Biçim:** glTF 2.0 binary (`.glb`). Kinex gibi **Draco geometri + WebP doku** ideal (Dolphin 2.2 MB,
  ~113 bin üçgen). Yunus yükleyicisi şu uzantıları destekler: `KHR_draco_mesh_compression`,
  `EXT_texture_webp`, `KHR_materials_clearcoat`, `KHR_materials_specular`,
  `KHR_materials_transmission` (transmission sayfada otomatik kapatılıp tonlu saydam cama çevrilir).
- **Yön ve ölçek:** **ön = +z**, üst = +y, arka = −z. Ölçek metre olmalı ama birebir kritik değil —
  sayfa modeli yeniden merkezler (x/z ortalanır, y tabana oturur) ve kamerayı sınırlayan kutudan
  (`baseRadiusFactor`) otomatik ölçekler. **Yön yanlışsa kamera ön ayarları ters döner.**
- **Boya/cam adları (profilde bildirilir, yani glb'de serbest):** Modelin
  - gövde boyası materyalinin adı → `nodes.paintMat`
  - iki-ton üst/tavan materyali → `nodes.roofMat` (yoksa gövdeyle aynı adı ver, iki-ton çalışmaz, sorun değil)
  - koyultulacak cam mesh'lerini yakalayan regex → `nodes.darkGlass`
  - cam tavan mesh'ini yakalayan regex → `nodes.sunroof`
  İsimleri glb'yi açıp (ör. Blender / glTF viewer / `gltf-transform inspect`) oku ve profile yaz.
  **Sayfa isimleri profilden okur; glb'deki adları değiştirmene gerek yok.**

### Hareketli parçalar (OPSİYONEL — kapı/cam/bagaj animasyonu)
Kinex modelleri tek parça gövdedir ve kapıları **animasyonla değil** kamera odaklarıyla gösterir;
Yunus da böyle bir modelde kapı/cam/bagaj açılışını **sessizce atlar** (Dolphin gibi ayrık parçalı
olmadıkça). İstersen (ileri seviye) parçaları ayırıp şu **sabit adlandırmayı** kullan, sayfa
animasyonu otomatik bağlar:
`Door_FL/FR/RL/RR`, `Window_FL/FR/RL/RR`, `Wheel_FL`, `Wheel_FR`, `Tailgate` (yoksa çalışma-zamanı
bölmesi), `ChargeFlap`, `ChargeSocket` (şarj soketi — varsa kablo otomatik oraya hizalanır),
`Trunk*` (bagaj iç mesh'leri). Bunlar profile yazılmaz; ada göre otomatik bulunur.

---

## Profil şeması (`profiles/<id>.json`)

Tüm alanlar zorunlu (eksik alan Dolphin varsayılanına düşer ama hepsini doldur). Birimler: açılar
derece, uzunluklar metre, koordinatlar `[x, y, z]` (merkezlenmiş model uzayı, ön = +z).

```jsonc
{
  "id": "atto3",                      // dosya adıyla aynı, kebab-case
  "name": "BYD Atto 3",               // seçicide görünecek ad
  "platform": "dl3",                  // sadece dl3

  "glb": "atto3_parts.glb",           // viewer/ içindeki dosya adı (ayrık parçalıysa <id>_parts.glb, değilse <id>.glb)
  "fallbackGlb": "atto3.glb",         // yüklenemezse; tek dosyan varsa glb ile aynısı, emin değilsen "dolphin.glb"
  "baseRadiusFactor": 4,              // kamera uzaklık çarpanı (Dolphin 4; gerekirse 3.5–4.5)

  "presets": {                        // kamera ön ayarları — Kinex kinex_car_models.json'dan
    "default":  { "theta": 20,  "phi": 70, "r": 50 },
    "actions":  { "theta": 20,  "phi": 70, "r": 45 },
    "left":     { "theta": 90,  "phi": 83, "r": 55 },
    "right":    { "theta": 270, "phi": 83, "r": 55 },
    "overhead": { "theta": 270, "phi": 1,  "r": 90 },
    "rear":     { "theta": 160, "phi": 72, "r": 60 },
    "drive":    { "theta": 180, "phi": 58, "r": 120 },
    "roof":     { "theta": 25,  "phi": 38, "r": 60 },
    "charge":   { "theta": 270, "phi": 83, "r": 92 }
  },

  "chargeTarget": [0, 0.75, 0.7],     // şarj görünümünde kameranın baktığı nokta
  "driveTarget":  [0, 0.4, 3.2],      // sürüş görünümünde (yolun ilerisi)

  "hot": {                            // hotspot konumları (merkezlenmiş model uzayı)
    "hTrunk":  [0, 1.25, -2.45],      // bagaj düğmesi (arka üst)
    "hCharge": [-0.95, 1.02, 1.12],   // şarj portu — ChargeSocket node'u varsa otomatik ezilir
    "hShade":  [0, 1.75, -0.4]        // cam tavan perdesi (tavan ortası üstü)
  },
  "chargeNode": "ChargeSocket",       // şarj soketi node adı (yoksa hCharge kullanılır)

  "nodes": {                          // glb'deki materyal/mesh adları (yukarıya bak)
    "paintMat": "CarPaint_1",
    "roofMat": "PCrPaint_2",
    "darkGlass": "^(f_gls|b_gls|sunroof|Window_)",
    "sunroof": "^sunroof",
    "tailgate": "Tailgate"
  },

  "caps": {                           // aracın yapabildikleri; false olan kontrol gizlenir
    "window": true,                   // cam aç/kapa
    "preciseWindow": true,            // kademeli cam konumu (yoksa yalnız aç/yarım/kapa)
    "sunroof": true,                  // cam tavan + perde
    "rearDefrost": true,              // arka buğu çözme
    "dualClimate": false,             // çift bölgeli klima (sürücü/yolcu ayrı sıcaklık); Dolphin tek bölge
    "rearClimate": false,             // arka klima paneli (fan + oto + arka sıcaklık)
    "rearSeats": false                // arka koltuk ısıtma/havalandırma
  },

  "acHalfDegree": false,              // klima sıcaklığı yarım-derece kodluysa true (bazı modeller)

  "silhouette": {                     // OPSİYONEL: sol alt köşedeki araç düğmesinin yandan silüeti
    "viewBox": "0 0 48 20.3",         // yoksa Dolphin silüeti kullanılır (aşağıdaki bölüme bak)
    "strokeWidth": 2.1,               // opsiyonel çizgi kalınlığı
    "svg": "<path d=\"...\"/><circle cx=\"9.8\" cy=\"15\" r=\"2.7\"/><circle cx=\"37.4\" cy=\"14.9\" r=\"2.7\"/>"
  }
}
```

### Kamera ön ayarı — Kinex → Yunus eşlemesi
Kinex `kinex_car_models.json`'daki o modelin girdisi küresel koordinat verir:
`theta` (0 = ön, 90 = sol, 180 = arka, 270 = sağ), `phi` (1 = tam üstten), `radiusPercent`.
Bunlar Yunus'un `theta`/`phi`/`r` alanlarıyla **birebir** aynıdır. Kinex'in `default`, `left`,
`right`, `overhead`, `standby` değerlerini doğrudan al; Yunus'un eklediği `actions`, `rear`, `roof`,
`charge` ve ince ayarlı `drive` için yukarıdaki Dolphin değerlerinden başla, `#debug`'da gözle
düzelt. Kinex'te hotspot konumları da (ön cam z≈+0.35, arka z≈−0.45, sol x≈+0.74, sağ x≈−0.75,
bagaj, lastikler) var — model farklı boyuttaysa oradan ölçekle.

---

## Sol alt köşe araç simgesi (yandan silüet)

Sayfanın sol alt köşesindeki araç düğmesi (ana sayfa tuşu) her model için **o aracın yandan
silüetini** gösterir. Üret: modelin tam yan görünüşünden (sol profil) ince çizgili, içi boş bir
**SVG** çiz — Dolphin örneğindeki gibi gövde dış hattı + iki tekerlek dairesi + (istersen) cam/kapı
çizgisi. Tek renk `currentColor`, `fill="none" stroke="currentColor"`, viewBox araç oranında
(Dolphin `0 0 48 20.3`). Profile `silhouette: { viewBox, svg }` olarak yaz; `svg` o viewBox içindeki
`<path>`/`<circle>` işaretlemesidir. **Vermezsen Dolphin silüeti yedek kalır** (çalışır ama yanlış
araç). Dolphin'in mevcut path'i `launcher.html`'deki `carBtn` düğmesinde örnek olarak durur.

## En donanımlı trim — ekstra konfor caps'ları

Amaç: her modelin **en donanımlı trim'inin** tüm ekstralarını açmak. Üç caps bayrağı (hepsi
caps-kapılı, Dolphin'de `false`, altyapı ana pencerede kurulu):

| caps | Ne | Açıksa sayfa | HAL (ana pencere kullanır) |
|---|---|---|---|
| `dualClimate` | Çift bölge: sürücü/yolcu ayrı sıcaklık | dock'ta 2. sıcaklık + kartta iki bölge | `setAcTemperature(area 1 sürücü / 2 yolcu)` |
| `rearClimate` | Arka klima paneli (fan + oto + arka sıcaklık) | arka klima kontrolü | `setRearAcWindLevel` / `setRearAcControlMode`; arka sıcaklık `setAcTemperature(area 3)` |
| `rearSeats` | Arka koltuk ısıtma/havalandırma | arka koltuk kontrolü | `setSeatHeatingState/VentilatingState(area 3 arka-sol / 4 arka-sağ)` |

**Kural:** O trim'de **gerçekten** varsa `true` yap; **yoksa `false` bırak** — olmayan donanımın
kontrolü boşuna görünür. Araç bağlanınca `carInfo` (acType, acTempCtrlMode, moonRoof, antiPinch,
rearDefrost, tyreType) bu bayrakları gerçeğiyle teyit/düzeltir, ama profildeki değer doğru başlasın.

### TR modelleri için önerilen değerler (en donanımlı trim)
| `<id>` | dualClimate | rearClimate | rearSeats |
|---|---|---|---|
| `byd_han` (Han) | ✓ | ✓ | ✓ |
| `byd_tang` (Tang) | ✓ | ✓ | ✓ |
| `byd_seal` (Seal) | ✓ | ✗ | ✗ |
| `byd_seal_u` (Seal-U EV) | ✓ \* | ✗ \* | ✗ |
| `byd_seal_u_dmi` (Seal-U DM-i) | ✓ \* | ✗ \* | ✗ |
| `byd_atto_3` (Atto 3) | ✗ | ✗ | ✗ |
| `byd_atto_2` (Atto 2) | ✗ | ✗ | ✗ |

\* = TR'deki üst trim'de teyit et (Song Plus/Seal-U üst donanımda çift bölge olasıdır; arka panel
varsa `rearClimate` da aç). Emin değilsen `false`. `byd_dolphin` üçü de `false` (korunur).

---

## `index.json` (model otomatik algılama eşlemesi)

`viewer/profiles/index.json` zaten vardır (kayıt defteri). Her yeni model için **iki yere** dokun:
1. `models` listesine bir satır → model seçicide görünür (sıra korunur).
2. `detect`'e (biliniyorsa) bir kural → araç bağlanınca otomatik seçilir. Bilmiyorsan boş bırak;
   model seçiciden elle seçilir, ilk araç bağlantısında gerçek değer loglanıp buraya işlenir.

Araç HAL'den `getAutoModelName()` (int) ve `getAutoVIN()` (string) verir. Mevcut biçim:

```jsonc
{
  "models": [
    { "id": "auto", "name": "Otomatik (araçtan algıla)" },
    { "id": "dolphin", "name": "BYD Dolphin" },
    { "id": "generic", "name": "BYD (tanınmayan model)" },
    { "id": "atto3", "name": "BYD Atto 3" }        // ← yeni modeli buraya ekle
  ],
  "detect": {
    "modelName": { "17": "atto3" },                 // getAutoModelName() int kodu → profil id (int araçta doğrulanır)
    "vin": { "LC0C..": "atto3" }                    // VIN'in başındaki desen (büyük harf) → profil id (yedek)
  }
}
```

> Dolphin sayfada gömülüdür (`dolphin.json` yalnız referans), yine de `models`'ta listelenir.
> Tanınmayan model `generic` profiline düşer (`profiles/generic.json`).

---

## Doğrulama

1. **Hızlı geometri/yön testi (profil gerekmez):**
   `python -m http.server 8765 --directory viewer` → `launcher.html?model=<id>.glb#debug`.
   Araç geliyor mu, ön +z'ye mi bakıyor (varsayılan kamera arabanın önünü/yanını görüyor), ölçek
   makul mü? (Bu testte materyal adları Dolphin profilinden okunur, boya yanlış olabilir — normal.)
2. **Tam profil testi:** tarayıcı konsolunda
   ```js
   localStorage.setItem('activeCarModel','<id>');
   fetch('profiles/<id>.json').then(r=>r.json()).then(p=>{localStorage.setItem('activeCarProfile',JSON.stringify(p));localStorage.setItem('activeCarGlb',p.glb);location.reload();});
   ```
   Sonra boya/cam, kamera ön ayarları (sol/sağ/üst/şarj), hotspot'lar doğru mu bak. Dolphin'e dönmek
   için: `localStorage.removeItem('activeCarModel'); location.reload();`
3. Konsolda hata olmamalı (`read_console_messages`).

Model seçici arayüzü (Ayarlar › Sistem) hazır olduğunda 2. adım tek dokunuşla yapılır; o zamana
kadar yukarıdaki konsol yöntemi geçerli.
