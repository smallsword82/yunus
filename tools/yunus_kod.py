"""Yunus açma kodu üretir (deneme süresi dolan kullanıcıya mail ile gönderilir).

    python tools/yunus_kod.py CIHAZKODU            # bugünden 1 yıl
    python tools/yunus_kod.py CIHAZKODU --gun 30   # bugünden 30 gün

CIHAZKODU: Yunus'un "Deneme süresi doldu" ekranında ve Ayarlar > Sistem'de görünen 8 karakter.
Gizli anahtar android/license.properties'ten okunur (repoda yok). Uygulamadaki karşılığı: License.kt.
"""
import argparse, datetime, hashlib, hmac, pathlib, sys

ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
EPOCH = datetime.date(2024, 1, 1)


def base32(data: bytes, chars: int) -> str:
    bits = "".join(f"{b:08b}" for b in data)
    return "".join(ALPHABET[int(bits[i * 5:i * 5 + 5], 2)] for i in range(chars))


def secret() -> bytes:
    f = pathlib.Path(__file__).resolve().parent.parent / "android" / "license.properties"
    if not f.exists():
        sys.exit(f"Gizli anahtar dosyası yok: {f}")
    for line in f.read_text(encoding="utf-8").splitlines():
        if line.strip().startswith("secret="):
            s = line.split("=", 1)[1].strip()
            if s and s != "DEGISTIR":
                return s.encode()
    sys.exit("license.properties içinde 'secret=' satırı boş.")


def code_for(device: str, day: int, key: bytes) -> str:
    sig = hmac.new(key, f"{device}:{day}".encode(), hashlib.sha256).digest()
    return base32(bytes([day >> 8, day & 255]) + sig, 12)


def main():
    ap = argparse.ArgumentParser(description="Yunus açma kodu üretir.")
    ap.add_argument("cihaz", help="kullanıcının gönderdiği 8 karakterlik cihaz kodu")
    ap.add_argument("--gun", type=int, default=365, help="kaç gün geçerli olsun (varsayılan 365)")
    a = ap.parse_args()
    device = "".join(c for c in a.cihaz.upper() if c in ALPHABET)
    if len(device) != 8:
        sys.exit("Cihaz kodu 8 karakter olmalı (I, O, 0 ve 1 kullanılmaz).")
    until = datetime.date.today() + datetime.timedelta(days=a.gun)
    code = code_for(device, (until - EPOCH).days, secret())
    print(f"Cihaz      : {device[:4]}-{device[4:]}")
    print(f"Açma kodu  : {code[:4]}-{code[4:8]}-{code[8:]}")
    print(f"Geçerlilik : {until.isoformat()} gününe kadar")


if __name__ == "__main__":
    main()
