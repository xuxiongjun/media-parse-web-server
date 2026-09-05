import json
import os
import re
from pathlib import Path
from urllib.request import Request, urlopen

SHARE = "https://v.douyin.com/33ULpLV7X-Q/"
UA_M = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

# expand
req = Request(SHARE, headers={"User-Agent": UA_M})
with urlopen(req, timeout=20) as resp:
    final = resp.geturl()
    html = resp.read().decode("utf-8", "ignore")
print("final", final)
m = re.search(r"/(?:video|note|share/video)/(\d+)", final) or re.search(r"/(?:video|note|share/video)/(\d+)", html)
vid = m.group(1) if m else None
print("id", vid)

# ttwid
body = b'{"region":"cn","aid":1768,"needFid":false,"service":"www.douyin.com","migrate_info":{"ticket":"","source":"node"},"cbUrlProtocol":"https","union":true}'
req = Request("https://ttwid.bytedance.com/ttwid/union/register/", data=body, headers={"User-Agent": UA_PC, "Content-Type": "application/json"}, method="POST")
ttwid = None
with urlopen(req, timeout=20) as resp:
    for c in (resp.headers.get_all("Set-Cookie") or []):
        if c.startswith("ttwid="):
            ttwid = c.split(";", 1)[0]
            break
print("ttwid", bool(ttwid))

api = (
    "https://www.douyin.com/aweme/v1/web/aweme/detail/"
    f"?device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id={vid}"
    "&pc_client_type=1&version_code=190500&version_name=19.5.0"
)
req = Request(api, headers={"User-Agent": UA_PC, "Referer": f"https://www.douyin.com/video/{vid}", "Cookie": ttwid or "", "Accept": "application/json"})
with urlopen(req, timeout=30) as resp:
    data = json.loads(resp.read().decode("utf-8", "ignore"))

detail = data.get("aweme_detail") or {}
print("aweme_type", detail.get("aweme_type"))
print("keys", sorted(detail.keys())[:40])
print("has images", "images" in detail, "image_infos" in detail, "image_post_info" in detail)
images = detail.get("images") or []
print("images len", len(images))
if images:
    first = images[0]
    print("image0 keys", list(first.keys())[:20])
    urls = first.get("url_list") or []
    print("url0", (urls[0][:120] if urls else None))
    # also download_url_list / display_image
    for k in ["download_url_list", "url_list", "display_image", "largest"]:
        if k in first:
            print("has", k)

video = detail.get("video") or {}
print("video play", bool((video.get("play_addr") or {}).get("url_list")))
out = Path(os.environ["TEMP"]) / "dy_images.json"
out.write_text(json.dumps({"aweme_type": detail.get("aweme_type"), "images": images[:2], "img_count": len(images)}, ensure_ascii=False, indent=2), encoding="utf-8")
print("saved", out)
