import re
from urllib.request import Request, urlopen

# From previous probe
uri = "tos-cn-i-dy/75f156da03c64c39ae9af222af40bea1"
wm = "https://p3-pc-sign.douyinpic.com/tos-cn-i-dy/75f156da03c64c39ae9af222af40bea1~tplv-dy-water-v2:5oqW6Z-z5Y-377yaNTcxMzM1NTk0NjY=:1080:1920.jpeg"
display = "https://p3-pc-sign.douyinpic.com/tos-cn-i-dy/75f156da03c64c39ae9af222af40bea1~tplv-dy-aweme-images:q75.jpeg"

candidates = [
    f"https://p3-pc-sign.douyinpic.com/{uri}~tplv-obj.jpeg",
    f"https://p3-pc-sign.douyinpic.com/{uri}~tplv-obj.webp",
    f"https://p3-pc-sign.douyinpic.com/{uri}~noop.image",
    f"https://p3-pc-sign.douyinpic.com/{uri}~tplv-dy-large.jpeg",
    f"https://p3-pc-sign.douyinpic.com/{uri}",
    re.sub(r"~tplv-dy-water-v2:[^?]+", "~tplv-obj.jpeg", wm),
    re.sub(r"~tplv-dy-water-v2:[^?]+", "~tplv-dy-aweme-images:q100.jpeg", wm),
    display,
    wm,
]

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
for u in candidates:
    try:
        req = Request(u, headers={"User-Agent": UA, "Referer": "https://www.douyin.com/"}, method="HEAD")
        with urlopen(req, timeout=15) as resp:
            print(resp.status, resp.headers.get("Content-Type"), resp.headers.get("Content-Length"), u[:110])
    except Exception as e:
        # try GET small
        try:
            req = Request(u, headers={"User-Agent": UA, "Referer": "https://www.douyin.com/"})
            with urlopen(req, timeout=15) as resp:
                data = resp.read(64)
                print(resp.status, resp.headers.get("Content-Type"), len(data), "GET", u[:110])
        except Exception as e2:
            print("FAIL", type(e2).__name__, str(e2)[:80], u[:90])
