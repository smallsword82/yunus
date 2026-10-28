# Yunus köprü protokolü (HAL bridge ↔ launcher sayfası)

Bu belge, araç içi **HAL köprüsü** (`YunusHalBridge.java`, `app_process` ile çalışır) ile
launcher arayüzü (`viewer/launcher.html`) arasındaki veri alışverişini tanımlar.

```
launcher.html  ──(YunusNative.command JSON)──▶  Android app (MainActivity)
      ▲                                                   │  TCP 127.0.0.1:19322
      │                                                   ▼
 window.yunusCar(state)  ◀──(state satırları)──  YunusHalBridge (app_process, shell UID)
                                                          │  reflection
                                                          ▼
                                   android.hardware.bydauto.* + CarStatusProvider
```

Taşıma: **satır tabanlı JSON over TCP**, `127.0.0.1:19322`. Her mesaj tek satır (UTF-8, `\n` ile biter).
Android uygulaması yalnız aktarıcıdır: köprüden gelen durum satırlarını olduğu gibi
`window.yunusCar(<json>)`'a verir; sayfadan gelen komutları olduğu gibi köprüye yazar.

## Köprü → sayfa: durum mesajları

Bağlantı kurulunca köprü **tam durumu** bir kez yollar; sonrasında yalnız **değişen alanları**
içeren kısmi mesajlar (delta) gönderir. Sayfadaki `window.yunusCar(m)` zaten bu "yalnız değişenler"
mantığına göre yazılmıştır. Alanların hiçbiri zorunlu değildir; köprü yalnız okuyabildiğini yollar.

| Alan | Tip | Anlam |
|------|-----|-------|
| `speed` | number (km/s) | Araç hızı |
| `powerKw` | number (kW) | Anlık motor gücü (+ çekiş, − rejeneratif) |
| `gear` | "P"\|"R"\|"N"\|"D" | Vites |
| `soc` | number (0–100) | Batarya yüzdesi |
| `range` | number (km) | Kalan menzil |
| `locked` | bool | Merkezi kilit |
| `doors` | `{FL,FR,RL,RR: bool}` | Kapı açık mı |
| `trunk` | bool | Bagaj açık mı |
| `windows` | `{FL,FR,RL,RR: 0–100}` | Cam açıklık yüzdesi |
| `sunshade` | 0–100 | Panoramik güneşlik/tavan açıklığı |
| `chargePort` | bool | Şarj kapağı açık mı |
| `charging` | bool | Şarj oluyor mu |
| `tyres` | `{FL,FR,RL,RR: {kpa, temp}}` | Lastik basınç/sıcaklık |
| `signal` | "L"\|"R"\|null | Sinyal |
| `hazard` | bool | Dörtlü flaşör |
| `lights` | string\|obj | Far durumu |
| `highBeam` | bool | Uzun far |
| `fog` | bool | Sis farı |
| `acOn` | bool | Klima açık mı |
| `acTemp` | number (°C) | Ayarlı klima sıcaklığı |
| `fan` | number | Fan kademesi |
| `outside` | number (°C) | Dış sıcaklık |
| `seats` | `{driver,passenger,...: {heat, vent}}` | Koltuk ısıtma/havalandırma kademesi |
| `faults` | `{<telltale>: bool}` | Gösterge uyarı ışıkları |
| `maintenance` | `{days, km}` | Bakıma kalan (CarStatusProvider) |

Örnek ilk (tam) mesaj:

```json
{"speed":0,"gear":"P","soc":82,"range":310,"locked":true,"doors":{"FL":false,"FR":false,"RL":false,"RR":false},"acOn":false,"acTemp":22,"outside":18}
```

Örnek delta:

```json
{"speed":37,"powerKw":14.2,"gear":"D"}
```

## Sayfa → köprü: komutlar

Sayfa `YunusNative.command(JSON.stringify({cmd, ...args}))` çağırır; uygulama bunu köprüye
tek satır JSON olarak iletir. Komut kümesi (launcher.html içinden çıkarıldı):

| `cmd` | args | Etki |
|-------|------|------|
| `lock` / `unlock` | — | Merkezi kilit |
| `trunkOpen` / `trunkClose` | — | Bagaj |
| `chargePortOpen` / `chargePortClose` | — | Şarj kapağı |
| `window` | `{pos: "FL"\|"FR"\|"RL"\|"RR", open: 0–100}` | Cam |
| `sunshade` | `{open: 0–100}` | Güneşlik/tavan |
| `acPower` | `{on: bool}` | Klima aç/kapa |
| `acTemp` | `{value: 16–30}` | Klima sıcaklığı |
| `seat` | `{seat, heat: 0–3, vent: 0–3}` | Koltuk ısıtma/havalandırma |

Köprü komutu uyguladıktan sonra, sonucu bir **durum mesajıyla** geri yansıtmalıdır (ör. `lock`
uygulanınca `{"locked":true}` yollanır). Sayfa `fromCar` bayrağıyla kendi tetiklediği değişikliği
araçtan gelenle ayırır; bu yüzden köprünün onayı tek doğruluk kaynağıdır.

## Notlar

- Köprü yalnız `127.0.0.1`'i dinler; dışarıya açık değildir.
- Komut başarısız olursa köprü durum yollamaz (sayfa eski değerinde kalır); istenirse ileride
  `{"error":"..."}` satırı eklenebilir.
- BYD okuma/yazma metot imzaları araç üzerinde doğrulanmalıdır; bkz. `introspect` modu
  (`README.md`).
