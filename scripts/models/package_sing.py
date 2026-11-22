#!/usr/bin/env python3
"""Build verified, reproducible downloadable Sing model packages (stdlib only)."""
import argparse, hashlib, json, os, shutil, sys, tarfile, tempfile, urllib.request, zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parent
SOURCES = ROOT / "sources.json"
PACKAGES = ROOT / "packages.json"
STAMP = (1980, 1, 1, 0, 0, 0)
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
UA = "vinz-sing-packager/1.0 (+https://github.com/nastechtest/Vinz)"

def sha(data): return hashlib.sha256(data).hexdigest()
def read_json(path):
    with open(path, encoding="utf-8") as f: return json.load(f)
def dump_json(path, value):
    with open(path, "w", encoding="utf-8", newline="\n") as f: json.dump(value, f, indent=2, sort_keys=True); f.write("\n")
def fail(message): raise ValueError(message)
def source_bytes(source_dir, spec):
    path = source_dir / spec["filename"]
    if not path.is_file():
        if not spec.get("download_if_missing"): fail("missing source: " + str(path))
        req = urllib.request.Request(spec["url"], headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30) as r, open(path, "wb") as out: shutil.copyfileobj(r, out)
    data = path.read_bytes()
    if len(data) != spec["bytes"] or sha(data) != spec["sha256"]: fail("source checksum mismatch: " + spec["filename"])
    return data

def safe_name(name):
    p = PurePosixPath(name)
    return bool(name) and not p.is_absolute() and ".." not in p.parts and "\\" not in name and len(p.parts) == 1

def zip_entries(data, wanted):
    result = {}
    with zipfile.ZipFile(__import__("io").BytesIO(data)) as z:
        names = {x.filename for x in z.infolist() if not x.is_dir()}
        if not wanted <= names: fail("runtime archive missing required entries")
        for name in wanted:
            result[name] = z.read(name)
    return result

def tar_entries(data):
    result = {}
    with tarfile.open(fileobj=__import__("io").BytesIO(data), mode="r:bz2") as t:
        members = {m.name: m for m in t.getmembers()}
        wanted = {"sherpa-onnx-spleeter-2stems-fp16/vocals.fp16.onnx", "sherpa-onnx-spleeter-2stems-fp16/accompaniment.fp16.onnx"}
        if not wanted <= members.keys(): fail("Spleeter archive missing required entries")
        for name in wanted:
            m = members[name]
            if not m.isfile() or m.issym() or m.islnk(): fail("unsafe Spleeter archive entry")
            result[PurePosixPath(name).name] = t.extractfile(m).read()
    return result

def make_zip(path, entries):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9, strict_timestamps=True) as z:
        for name in sorted(entries):
            if not safe_name(name): fail("unsafe output name: " + name)
            info = zipfile.ZipInfo(name, STAMP); info.compress_type = zipfile.ZIP_DEFLATED; info.external_attr = 0o100644 << 16
            z.writestr(info, entries[name], compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)

def details(path, payloads):
    with zipfile.ZipFile(path) as z:
        infos = [i for i in z.infolist() if not i.is_dir()]
        if [i.filename for i in infos] != sorted(i.filename for i in infos): fail("zip not sorted")
        for i in infos:
            if i.date_time != STAMP or (i.external_attr >> 16) != 0o100644 or not safe_name(i.filename): fail("zip is not reproducible/safe")
    raw = path.read_bytes()
    return {"filename": path.name, "sha256": sha(raw), "download_bytes": len(raw), "unpacked_bytes": sum(len(x) for x in payloads.values()), "file_list": sorted(payloads), "original_payload_hashes": {k: sha(v) for k,v in sorted(payloads.items())}}

def build(source_dir, output_dir):
    specs = read_json(SOURCES)["sources"]
    by_id = {s["id"]: s for s in specs}
    spleeter = tar_entries(source_bytes(source_dir, by_id["spleeter_archive"]))
    runtime = zip_entries(source_bytes(source_dir, by_id["onnxruntime_aar"]), {"jni/%s/libonnxruntime.so" % a for a in ABIS})
    deezer = source_bytes(source_dir, by_id["deezer_mit"])
    sherpa = source_bytes(source_dir, by_id["sherpa_apache"])
    ort = source_bytes(source_dir, by_id["onnxruntime_mit"])
    notices = source_bytes(source_dir, by_id["onnxruntime_third_party"])
    output_dir.mkdir(parents=True, exist_ok=True)
    results = []
    e = dict(spleeter); e["LICENSE-DEEZER-MIT.txt"] = deezer; e["LICENSE-SHERPA-ONNX-APACHE-2.0.txt"] = sherpa
    p = output_dir / "sing-spleeter-2stems-fp16.zip"; make_zip(p, e); results.append(details(p, e))
    for abi in ABIS:
        e = {"libonnxruntime.so": runtime["jni/%s/libonnxruntime.so" % abi], "LICENSE-ONNXRUNTIME-MIT.txt": ort, "ThirdPartyNotices.txt": notices}
        p = output_dir / ("onnxruntime-1.31.0-%s.zip" % abi); make_zip(p, e); results.append(details(p, e))
    return {"format": 1, "provenance": {"sources_manifest": "sources.json", "weights_license": "Source attribution only; trained-weight licence has not been independently validated."}, "packages": results}

def check_source(modelstore, manifest):
    import re
    text = Path(modelstore).read_text(encoding="utf-8")
    for item in manifest["packages"]:
        pattern = (r'"' + re.escape(item["filename"]) +
                   r'",\s*"([0-9a-f]{64})",\s*([0-9_]+),\s*tr\([^)]*\),\s*([0-9_]+)')
        match = re.search(pattern, text)
        actual = None if not match else (match[1], int(match[2].replace("_", "")), int(match[3].replace("_", "")))
        expected = (item["sha256"], item["download_bytes"], item["unpacked_bytes"])
        if actual != expected: fail("ModelStore enum metadata differs: " + item["filename"])

def main(argv=None):
    ap = argparse.ArgumentParser(); ap.add_argument("--source-dir", type=Path, required=True); ap.add_argument("--output-dir", type=Path, required=True); ap.add_argument("--write-manifest", action="store_true"); ap.add_argument("--check-source", type=Path)
    a = ap.parse_args(argv); a.source_dir.mkdir(parents=True, exist_ok=True); generated = build(a.source_dir, a.output_dir)
    if a.write_manifest: dump_json(PACKAGES, generated)
    elif not PACKAGES.is_file() or read_json(PACKAGES) != generated: fail("generated packages differ from committed manifest; use --write-manifest only for intentional bootstrap")
    if a.check_source: check_source(a.check_source, generated)
    print(json.dumps(generated, indent=2, sort_keys=True))
if __name__ == "__main__":
    try: main()
    except (ValueError, OSError, zipfile.BadZipFile, tarfile.TarError) as e: print("package_sing: " + str(e), file=sys.stderr); raise SystemExit(2)
