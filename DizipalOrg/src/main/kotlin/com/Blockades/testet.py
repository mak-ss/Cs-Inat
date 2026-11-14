# ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
import re
from cloudscraper import CloudScraper
from parsel import Selector

oturum = CloudScraper()
mainUrl = "https://dizipalorjinal21.com"

def test_ana_sayfa():
    print("=== ANA SAYFA ===")
    r = oturum.get(f"{mainUrl}/filmler")
    sel = Selector(r.text)
    filmler = sel.css("a[href^='/filmler/']")
    diziler = sel.css("a[href^='/diziler/']")
    print(f"status={r.status_code} film={len(filmler)} dizi={len(diziler)}")

def test_film_detay(slug="kavgacilar"):
    print("\n=== FİLM DETAY ===")
    r = oturum.get(f"{mainUrl}/filmler/{slug}")
    sel = Selector(r.text)
    iframe = sel.css("iframe::attr(src)").get()
    poster = sel.css("img[src*='/storage/posters/']::attr(src)").get()
    h1 = sel.css("h1::text").get()
    print(f"status={r.status_code}")
    print(f"h1={h1}")
    print(f"poster={poster}")
    print(f"iframe={iframe}")
    return iframe

def test_player(player_url):
    print("\n=== PLAYER ===")
    if not player_url:
        print("player URL yok")
        return
    r = oturum.get(player_url, headers={"Referer": f"{mainUrl}/"})
    print(f"status={r.status_code} len={len(r.text)}")

    m3u8 = re.findall(r'https?://[^\s"\'<>\\]+\.m3u8[^\s"\'<>\\]*', r.text)
    print(f"m3u8 sayısı: {len(m3u8)}")
    for x in m3u8[:3]:
        print(f"  {x[:120]}")

    sd = re.findall(r'https?://[a-z0-9.-]+\.cfd/\d+/master\.m3u8[^\s"\'<>\\]*', r.text)
    print(f"storagedomain master: {len(sd)}")
    for x in sd[:2]:
        print(f"  {x[:120]}")

    api = list(set(re.findall(r'["\'](/api/[^"\']+)["\']', r.text)))
    print(f"API endpoints: {api[:5]}")

    # script src'lerini de tara
    for s in sel_script_srcs(r.text)[:5]:
        full = s if s.startswith("http") else f"https://videoplays.cfd{s}"
        js = oturum.get(full, headers={"Referer": player_url}).text
        js_m3u8 = re.findall(r'https?://[^\s"\'<>\\]+\.m3u8[^\s"\'<>\\]*', js)
        if js_m3u8:
            print(f"  JS'de m3u8: {js_m3u8[0][:120]}")

def sel_script_srcs(html):
    return re.findall(r'<script[^>]+src="([^"]+)"', html)

if __name__ == "__main__":
    test_ana_sayfa()
    iframe = test_film_detay()
    test_player(iframe)
