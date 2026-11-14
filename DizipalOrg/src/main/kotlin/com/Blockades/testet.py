# ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.
from cloudscraper import CloudScraper
from parsel import Selector

oturum = CloudScraper()
mainUrl = "https://dizipalorjinal21.com"

def test_bolumler():
    pageUrl = f"{mainUrl}/yeni-eklenen-bolumler?page=1"
    istek = oturum.get(pageUrl)
    secici = Selector(istek.text)
    print("=== Bölümler ===")
    for a in secici.css("a[href^='/bolum/']"):
        print(a.css("::attr(title)").get() or a.css("::text").get())
        print(a.css("::attr(href)").get())
        print()

def test_arama():
    print("=== Arama ===")
    for q in ["test", "breaking"]:
        for path in [f"/arama?q={q}", f"/?s={q}"]:
            r = oturum.get(f"{mainUrl}{path}")
            kart = Selector(r.text).css(".ip-card, .ip-trend-card")
            print(f"{path} -> status={r.status_code}, kart={len(kart)}")

def test_ajax():
    print("=== AJAX ===")
    r = oturum.get(
        f"{mainUrl}/bolum_yukle.php?slug=ornek&sezon=1",
        headers={"X-Requested-With": "XMLHttpRequest"},
    )
    print("status:", r.status_code)
    print(r.text[:300])

if __name__ == "__main__":
    test_bolumler()
    test_arama()
    test_ajax()