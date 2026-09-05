import json
import re
from urllib.request import Request, urlopen

SHARE = "https://v.douyin.com/33ULpLV7X-Q/"
UA_M = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

req = Request(SHARE, headers={"User-Agent": UA_M})
with urlopen(req, timeout=20) as resp:
    final = resp.geturl()
m = re.search(r"/(?:video|note|share/video|share/note)/(\d+)", final)
vid = m.group(1)
print("id", vid)

body = b'{"region":"cn","aid":1768,"needFid":false,"service":"www.douyin.com","migrate_info":{"ticket":"","source":"node"},"cbUrlProtocol":"https","union":true}'
req = Request("https://ttwid.bytedance.com/ttwid/union/register/", data=body, headers={"User-Agent": UA_PC, "Content-Type": "application/json"}, method="POST")
ttwid = None
with urlopen(req, timeout=20) as resp:
    for c in (resp.headers.get_all("Set-Cookie") or []):
        if c.startswith("ttwid="):
            ttwid = c.split(";", 1)[0]
            break

api = (
    "https://www.douyin.com/aweme/v1/web/aweme/detail/"
    f"?device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id={vid}"
    "&pc_client_type=1&version_code=190500&version_name=19.5.0"
)
req = Request(api, headers={"User-Agent": UA_PC, "Referer": f"https://www.douyin.com/note/{vid}", "Cookie": ttwid or "", "Accept": "application/json"})
with urlopen(req, timeout=30) as resp:
    data = json.loads(resp.read().decode("utf-8", "ignore"))

detail = data.get("aweme_detail") or {}
images = detail.get("images") or []
print("count", len(images), "aweme_type", detail.get("aweme_type"))
if not images:
    raise SystemExit("no images")

img = images[0]
print("keys", sorted(img.keys()))

def show(name, node):
    if node is None:
        print(name, "None")
        return
    if isinstance(node, dict):
        urls = node.get("url_list") or []
        print(name, "uri=", node.get("uri"), "n=", len(urls))
        for u in urls[:2]:
            print(" ", u[:140])
    elif isinstance(node, list):
        print(name, "list n=", len(node))
        for u in node[:2]:
            if isinstance(u, str):
                print(" ", u[:140])
            else:
                print(" ", type(u), str(u)[:100])

for k in ["url_list", "download_url_list", "display_image", "owner_watermark_image", "thumbnail", "video", "largest", "clip"]:
    show(k, img.get(k))

# dump all string urls under first image
found = []
def walk(o, p=""):
    if isinstance(o, dict):
        for k,v in o.items():
            walk(v, p+"."+k)
    elif isinstance(o, list):
        for i,v in enumerate(o[:5]):
            walk(v, f"{p}[{i}]")
    elif isinstance(o, str) and o.startswith("http"):
        found.append((p, o))
walk(img)
print("all http fields:")
for p,u in found:
    mark = "WM?" if ("watermark" in u or " douyin" in u.lower()) else ""
    print(p, mark)
    print(" ", u[:160])
