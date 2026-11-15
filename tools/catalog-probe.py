import collections, json, urllib.request
for owner, repo, branch in [("ChurchApps", "WorshipCommonsContent", "main"), ("ChordPro", "chordpro", "master")]:
    url = f"https://api.github.com/repos/{owner}/{repo}/git/trees/{branch}?recursive=1"
    try:
        data = json.load(urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "probe"}), timeout=30))
    except Exception as e:
        print("PROBE", repo, "ERROR", e); continue
    blobs = [t["path"] for t in data.get("tree", []) if t["type"] == "blob"]
    print("PROBE", repo, "blobs", len(blobs), "truncated", data.get("truncated"))
    print("PROBE ext", collections.Counter(p.rsplit(".", 1)[-1].lower() if "." in p.rsplit("/", 1)[-1] else "(none)" for p in blobs).most_common(15))
    print("PROBE names", collections.Counter(p.rsplit("/", 1)[-1] for p in blobs).most_common(15))
    print("PROBE top", collections.Counter(p.split("/")[0] for p in blobs).most_common(12))
    first = next((p for p in blobs if p.startswith("songs/en/")), None)
    if first:
        folder = "/".join(first.split("/")[:3])
        print("PROBE folder", [p for p in blobs if p.startswith(folder + "/")][:40])
    for ext in (".cho", ".chopro", ".chordpro", ".crd", ".pro", ".onsong"):
        hits = [p for p in blobs if p.lower().endswith(ext)]
        print("PROBE", ext, len(hits), hits[:6])
