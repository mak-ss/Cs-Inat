#!/usr/bin/env python3
# coding: utf-8

from cloudscraper import CloudScraper
from urllib.parse import urlparse
import os, re, logging

logging.basicConfig(level=logging.INFO, format='[%(levelname)s] %(message)s')
logger = logging.getLogger(__name__)


class MainUrlUpdater:
    def __init__(self, base_dir="."):
        self.base_dir = base_dir
        self.oturum   = CloudScraper()

    @property
    def eklentiler(self):
        try:
            candidates = [
                dosya for dosya in os.listdir(self.base_dir)
                if os.path.isdir(os.path.join(self.base_dir, dosya))
                   and not dosya.startswith(".")
                   and dosya not in {"gradle", "__Temel"}
            ]
            return sorted(candidates)
        except FileNotFoundError:
            logger.error("Base dizin bulunamadı: %s", self.base_dir)
            return []

    def _kt_dosyasini_bul(self, dizin, dosya_adi):
        start = os.path.join(self.base_dir, dizin)
        for kok, alt_dizinler, dosyalar in os.walk(start):
            if dosya_adi in dosyalar:
                return os.path.join(kok, dosya_adi)
        return None

    @property
    def kt_dosyalari(self):
        result = []
        for eklenti in self.eklentiler:
            kt_path = self._kt_dosyasini_bul(eklenti, f"{eklenti}.kt")
            if kt_path:
                result.append(kt_path)
            else:
                logger.debug("KT dosyası bulunamadı: %s/%s.kt", eklenti, eklenti)
        return result

    def _mainurl_bul(self, kt_dosya_yolu):
        try:
            with open(kt_dosya_yolu, "r", encoding="utf-8") as file:
                icerik = file.read()
                if m := re.search(r'override\s+var\s+mainUrl\s*=\s*["\']([^"\']+)["\']', icerik):
                    return m[1]
        except Exception:
            logger.exception("Dosya okunamadı: %s", kt_dosya_yolu)
        return None

    def _mainurl_guncelle(self, kt_dosya_yolu, eski_url, yeni_url):
        if not eski_url:
            logger.warning("[_mainurl_guncelle] eski_url boş, atlandı: %s", kt_dosya_yolu)
            return False
        if not yeni_url:
            logger.warning("[_mainurl_guncelle] yeni_url boş, atlandı: %s", kt_dosya_yolu)
            return False

        try:
            with open(kt_dosya_yolu, "r+", encoding="utf-8") as file:
                icerik = file.read()
                yeni_icerik, adet = re.subn(
                    r'(override\s+var\s+mainUrl\s*=\s*["\'])([^"\']+)(["\'])',
                    lambda m: m.group(1) + yeni_url + m.group(3),
                    icerik,
                    flags=re.IGNORECASE
                )
                if adet == 0:
                    logger.warning("[!] Beklenen atama bulunamadı, fallback replace deneniyor: %s", kt_dosya_yolu)
                    yeni_icerik = icerik.replace(eski_url, yeni_url)

                if yeni_icerik == icerik:
                    logger.info("[i] Değişiklik yok: %s", kt_dosya_yolu)
                    return False

                file.seek(0)
                file.write(yeni_icerik)
                file.truncate()
            return True
        except Exception:
            logger.exception("[!] _mainurl_guncelle başarısız: %s -> %s (%s)", eski_url, yeni_url, kt_dosya_yolu)
            return False

    def _versiyonu_artir(self, build_gradle_yolu):
        try:
            with open(build_gradle_yolu, "r+", encoding="utf-8") as file:
                icerik = file.read()
                if version_match := re.search(r'(^\s*version\s*=\s*)(\d+)(\s*$)', icerik, flags=re.MULTILINE):
                    eski_versiyon = int(version_match[2])
                    yeni_versiyon = eski_versiyon + 1
                    yeni_icerik = icerik.replace(
                        f"{version_match[1]}{eski_versiyon}{version_match[3]}",
                        f"{version_match[1]}{yeni_versiyon}{version_match[3]}"
                    )
                    file.seek(0)
                    file.write(yeni_icerik)
                    file.truncate()
                    return yeni_versiyon
                else:
                    logger.warning("Versiyon satırı bulunamadı (pattern mismatch): %s", build_gradle_yolu)
        except FileNotFoundError:
            logger.warning("build.gradle.kts bulunamadı: %s", build_gradle_yolu)
        except Exception:
            logger.exception("Versiyon arttırılamadı: %s", build_gradle_yolu)
        return None

    def _sadece_domain_al(self, url):
        if not url:
            return None
        try:
            parsed = urlparse(url if re.match(r'^[a-zA-Z]+://', url) else f"http://{url}")
            if not parsed.netloc:
                return None
            return f"{parsed.scheme}://{parsed.netloc}"
        except Exception:
            logger.exception("_sadece_domain_al hata: %s", url)
            return None

    @property
    def mainurl_listesi(self):
        result = {}
        for kt_dosya_yolu in self.kt_dosyalari:
            mainurl = self._mainurl_bul(kt_dosya_yolu)
            if mainurl:
                result[kt_dosya_yolu] = mainurl
            else:
                logger.warning("[!] mainUrl bulunamadı: %s", kt_dosya_yolu)
        return result

    def guncelle(self):
        for dosya, mainurl in self.mainurl_listesi.items():
            try:
                relative_path = os.path.relpath(dosya, self.base_dir)
                eklenti_adi = relative_path.split(os.sep)[0]
            except Exception:
                logger.warning("[!] Eklenti adı belirlenemedi: %s", dosya)
                continue

            logger.info(f"[~] Kontrol Ediliyor : {eklenti_adi}")

            if not mainurl:
                logger.warning("[!] mainUrl boş, atlandı: %s", dosya)
                continue

            mainurl_sadece_domain = self._sadece_domain_al(mainurl)
            if not mainurl_sadece_domain:
                logger.warning("[!] mainUrl parse edilemedi, atlandı: %s -> %s", dosya, mainurl)
                continue

            try:
                istek = self.oturum.get(mainurl_sadece_domain, allow_redirects=True, timeout=15)
                logger.info(f"[+] Kontrol Edildi   : {mainurl_sadece_domain}")
            except Exception:
                logger.exception(f"[!] Kontrol Edilemedi : {mainurl_sadece_domain}")
                continue

            final_url = getattr(istek, "url", None)
            if not final_url:
                try:
                    final_url = istek.geturl()
                except Exception:
                    final_url = None

            if not final_url:
                logger.warning("[!] İstekten final URL alınamadı, atlandı: %s", mainurl_sadece_domain)
                continue

            final_url = final_url.rstrip('/')
            yeni_domain = self._sadece_domain_al(final_url)

            if not yeni_domain:
                logger.warning("[!] Yeni domain belirlenemedi, atlandı: %s", dosya)
                continue

            if mainurl_sadece_domain == yeni_domain:
                logger.debug("[i] Değişiklik yok: %s", mainurl_sadece_domain)
                continue

            try:
                changed = self._mainurl_guncelle(dosya, mainurl, yeni_domain)
                if changed:
                    build_gradle_yolu = os.path.join(self.base_dir, eklenti_adi, "build.gradle.kts")
                    yeni_v = self._versiyonu_artir(build_gradle_yolu)
                    if yeni_v is not None:
                        logger.info(f"[»] {mainurl} -> {yeni_domain} (version -> {yeni_v})")
                    else:
                        logger.info(f"[»] {mainurl} -> {yeni_domain} (version artışı yapılmadı)")
                else:
                    logger.info("[i] Dosya güncellenmedi (değişiklik tespit edilmedi): %s", dosya)
            except Exception:
                logger.exception("[!] Güncelleme sırasında beklenmedik hata: %s", dosya)


if __name__ == "__main__":
    updater = MainUrlUpdater(base_dir=".")
    updater.guncelle()
