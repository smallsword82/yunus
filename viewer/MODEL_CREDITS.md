# 3B araç modelleri — kaynak ve lisans

`viewer/` içindeki araç modelleri Sketchfab'de ücretsiz indirilebilen **Ddiaz Design**
(https://sketchfab.com/ddiaz-design) modelleridir. Lisans: **CC BY-NC-SA 4.0**
(https://creativecommons.org/licenses/by-nc-sa/4.0/).

Lisansın şartları (Sketchfab'in kendi özeti): yazar belirtilmeli, **ticari kullanım yok**,
değiştirilmiş sürümler aynı lisansla paylaşılmalı. Yunus ücretli dağıtılacaksa bu modeller
kullanılamaz; yazardan izin almak ya da başka model koymak gerekir.

| Dosya | Sketchfab'deki model | Değişiklik |
|---|---|---|
| `dolphin.glb` | 2024 BYD Dolphin | Draco + WebP sıkıştırma |
| `dolphin_parts.glb` | 2024 BYD Dolphin | kapı/cam/bagaj/şarj kapağı ayrıldı (türev eser, aynı lisans) |
| `byd_seal.glb` | 2024 BYD Seal — https://sketchfab.com/3d-models/2e82d2aaaa3d47debf39b1160d3fe376 | sıkıştırma |
| `byd_atto_3.glb` | 2024 BYD Atto 3 — https://sketchfab.com/3d-models/f7ef899186d445d48e4781ddfc84fddc | sıkıştırma |
| `byd_atto_2.glb` | 2024 BYD Atto 2 — https://sketchfab.com/3d-models/2e04b67017ed49e982a1f0737eb7f0ae | sıkıştırma |
| `byd_seal_u.glb` | 2024 BYD Seal U — https://sketchfab.com/3d-models/dda5d96fa79e42c290a279168126f247 | sıkıştırma, boya malzemesinin metalikliği 0,35 yapıldı |
| `byd_seal_u_dmi.glb` | 2024 BYD Seal U DM-i — https://sketchfab.com/3d-models/e3a2ef09dcc547ccafe1687328ec05f0 | sıkıştırma, boya malzemesinin metalikliği 0,35 yapıldı |
| `byd_tang.glb` | 2024 BYD Tang — https://sketchfab.com/3d-models/0af7ffe7b3b64d7fb9e31d4f1bac10c1 | sıkıştırma |
| `byd_han.glb` | 2022 BYD Han EV (facelift) — https://sketchfab.com/3d-models/98b9fdf30fc742f39b8600f2cd3f4b49 | sıkıştırma |

`byd_*_parts.glb` dosyaları yukarıdaki modellerin türev eserleridir (aynı lisans): kapılar, camlar,
bagaj kapağı, şarj kapağı ve ön tekerlekler ayrıldı (`tools/split_model*.py`, ayarlar
`tools/parts/<id>.json`). Uygulamaya yalnız bunlar girer; sıkıştırılmış `byd_*.glb` dosyaları
APK'ya alınmaz.

Not: dosyalar Sketchfab'den değil, aynı modelleri sıkıştırılmış halde taşıyan Kinex uygulamasından
alındı (adlar, materyal adları ve üçgen sayıları Sketchfab'deki modellerle örtüşüyor; dosyaların
birebir aynı kaynaktan geldiği ayrıca doğrulanmadı).
