import os
from pathlib import Path
from urllib.request import Request, urlopen

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
base = "https://p3-pc-sign.douyinpic.com/tos-cn-i-dy/75f156da03c64c39ae9af222af40bea1"
# Need real signed query - fetch again quickly via detail is heavy; use pattern from probe truncated.
# Re-get full urls from detail quickly

import json, re
from urllib.request import Request, urlopen as uo

SHARE = "https://v.douyin.com/33ULpLV7X-Q/"
req = Request(SHARE, headers={"User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15"})
with uo(req, timeout=20) as resp:
    final = resp.geturl()
vid = re.search(r"/(\d+)", final.split("?")[0]).group(1)
body = b'{"region":"cn","aid":1768,"needFid":false,"service":"www.douyin.com","migrate_info":{"ticket":"","source":"node"},"cbUrlProtocol":"https","union":true}'
req = Request("https://ttwid.bytedance.com/ttwid/union/register/", data=body, headers={"User-Agent": UA, "Content-Type": "application/json"}, method="POST")
ttwid=None
with uo(req, timeout=20) as resp:
    for c in resp.headers.get_all("Set-Cookie") or []:
        if c.startswith("ttwid="):
            ttwid=c.split(";",1)[0]
api = f"https://www.douyin.com/aweme/v1/web/aweme/detail/?device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id={vid}&pc_client_type=1&version_code=190500&version_name=19.5.0"
req = Request(api, headers={"User-Agent": UA, "Referer": f"https://www.douyin.com/note/{vid}", "Cookie": ttwid or ""})
with uo(req, timeout=30) as resp:
    detail = json.loads(resp.read().decode())["aweme_detail"]
img = detail["images"][0]
url_list = img["url_list"]
dl_list = img["download_url_list"]
print("url0", url_list[0])
print("dl0", dl_list[0])

def fetch(u, name):
    req = Request(u, headers={"User-Agent": UA, "Referer": "https://www.douyin.com/"})
    with uo(req, timeout=20) as resp:
        data = resp.read()
        out = Path(os.environ["TEMP"]) / f"dy_{name}"
        out.write_bytes(data)
        print(name, resp.status, resp.headers.get("Content-Type"), len(data), out)

# 1) display aweme-images
fetch(url_list[-1] if url_list else url_list[0], "aweme.jpeg")
# 2) water download
fetch(dl_list[-1] if dl_list else dl_list[0], "water.jpeg")
# 3) replace water template keep query
import re as _re
src = dl_list[-1]
nowm = _re.sub(r"~tplv-dy-water-v2:[^.?]+", "~tplv-obj", src)
# fix extension
nowm = nowm.replace("~tplv-obj.webp", "~tplv-obj.jpeg").replace("~tplv-obj.jpeg", "~tplv-obj.jpeg")
if "~tplv-obj." not in nowm:
    nowm = _re.sub(r"~tplv-dy-water-v2:[^?]+", "~tplv-obj.jpeg", src)
print("nowm candidate", nowm)
try:
    fetch(nowm, "obj.jpeg")
except Exception as e:
    print("obj fail", e)
    # try replace with aweme-images high quality keeping host/query from signed water url
    nowm2 = _re.sub(r"~tplv-dy-water-v2:[^?]+", "~tplv-dy-aweme-images:q100.jpeg", src)
    print("nowm2", nowm2)
    try:
        fetch(nowm2, "q100.jpeg")
    except Exception as e2:
        print("q100 fail", e2)
