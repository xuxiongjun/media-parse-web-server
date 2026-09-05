import os
import re
import json
from pathlib import Path
from urllib.request import Request, urlopen

ID = "7679053888742286582"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

# 1) inspect og tags from saved html
t = (Path(os.environ["TEMP"]) / "dy_probe" / "0.txt").read_text(encoding="utf-8", errors="ignore")
for m in re.finditer(r'<meta[^>]+>', t):
    tag = m.group(0)
    if "og:" in tag or "video" in tag.lower() or "description" in tag.lower():
        print("META", tag[:250])

print("\n---- ttwid ----")
body = json.dumps({
    "region": "cn",
    "aid": 1768,
    "needFid": False,
    "service": "www.douyin.com",
    "migrate_info": {"ticket": "", "source": "node"},
    "cbUrlProtocol": "https",
    "union": True,
}).encode()
req = Request(
    "https://ttwid.bytedance.com/ttwid/union/register/",
    data=body,
    headers={"User-Agent": UA, "Content-Type": "application/json"},
    method="POST",
)
try:
    with urlopen(req, timeout=20) as resp:
        set_cookie = resp.headers.get_all("Set-Cookie") or []
        print("cookies", set_cookie)
        raw = resp.read().decode("utf-8", "ignore")
        print("body", raw[:300])
        ttwid = None
        for c in set_cookie:
            if c.startswith("ttwid="):
                ttwid = c.split(";", 1)[0]
                break
        if not ttwid and "ttwid" in raw:
            print("raw has ttwid")
except Exception as e:
    print("ttwid err", e)
    ttwid = None

if ttwid:
    print("using", ttwid)
    detail = (
        f"https://www.douyin.com/aweme/v1/web/aweme/detail/"
        f"?device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id={ID}"
        f"&pc_client_type=1&version_code=190500&version_name=19.5.0"
    )
    req2 = Request(detail, headers={
        "User-Agent": UA,
        "Referer": f"https://www.douyin.com/video/{ID}",
        "Cookie": ttwid,
        "Accept": "application/json, text/plain, */*",
    })
    try:
        with urlopen(req2, timeout=20) as resp:
            data = resp.read().decode("utf-8", "ignore")
            print("detail len", len(data))
            print(data[:500])
            (Path(os.environ["TEMP"]) / "dy_detail.json").write_text(data, encoding="utf-8")
            if "play_addr" in data or "aweme_detail" in data:
                print("SUCCESS markers found")
    except Exception as e:
        print("detail err", e)

# try snssdk multi get
print("\n---- snssdk ----")
api = f"https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids={ID}&dytk="
for url in [
    api,
    f"https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?reflow_source=reflow_page&item_ids={ID}&a_bogus=",
]:
    try:
        req = Request(url, headers={"User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15", "Referer": "https://www.douyin.com/"})
        with urlopen(req, timeout=15) as resp:
            b = resp.read()
            print(url[:80], "len", len(b), b[:200])
    except Exception as e:
        print(url[:80], e)
