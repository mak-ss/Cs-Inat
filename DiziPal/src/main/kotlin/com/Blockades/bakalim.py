# ! Bu araç @Blockades tarafından | @Cs-Inat için yazılmıştır.

from cloudscraper import CloudScraper
from parsel import Selector
from re import findall

oturum = CloudScraper()

mainUrl = "https://dizipal2134.com"
pageUrl = f"{mainUrl}/diziler?kelime=&durum=&tur=1&type=&siralama="

istek = oturum.get(pageUrl)
secici = Selector(istek.text)

def icerik_ver(secici: Selector):
    son_date = ""

    for icerik in secici.css("ul.content-grid li, article.type2 ul li"):
        title = icerik.css("h3::text, span.title::text").get()
        href = icerik.css("a::attr(href)").get()
        img = icerik.css("img::attr(data-src), img::attr(src)").get()
        date = icerik.css("a::attr(data-date)").get()

        if title and href:
            print(f"Başlık: {title.strip()}")
            print(f"URL: {href}")
            print(f"Görsel: {img}")
            print(f"Tarih: {date}\n")

        if date:
            son_date = date

    return son_date

son_date = icerik_ver(secici)

def devam_ver(son_date) -> str:
    if not son_date:
        return ""

    tur_match = findall(r"tur=([\d]+)", pageUrl)
    tur_val = tur_match[0] if tur_match else ""

    istek = oturum.post(
        url=f"{mainUrl}/api/load-series",
        data={
            "date": son_date,
            "tur": tur_val,
            "durum": "",
            "kelime": "",
            "type": "",
            "siralama": ""
        }
    )
    veri = istek.json()
    if not veri.get("html"):
        return ""

    devam_html = "<ul class='content-grid'>" + veri["html"] + "</ul>"
    return icerik_ver(Selector(devam_html))

# İlk sayfadan sonraki yükleme
if son_date:
    son_date = devam_ver(son_date)
