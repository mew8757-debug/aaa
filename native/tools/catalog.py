#!/usr/bin/env python3
import hashlib
import sys
import zipfile
from collections import Counter
from pathlib import PurePosixPath

def sha256_stream(f):
    h = hashlib.sha256()
    for chunk in iter(lambda: f.read(1024 * 1024), b""):
        h.update(chunk)
    return h.hexdigest()

def scan(path):
    rows=[]
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            p=PurePosixPath(info.filename.replace("\\","/"))
            ext=p.suffix.lower()
            with z.open(info) as f:
                digest=sha256_stream(f)
            rows.append((str(p), info.file_size, ext, digest))
    return rows

def main(args):
    if len(args)<2:
        print("usage: catalog.py game1.Zip game2.Zip")
        return 2
    all_rows=[]
    for path in args:
        rows=scan(path)
        print(f"## {path}: {len(rows)} files")
        for name,size,ext,digest in sorted(rows):
            print(f"{size:12d}  {digest}  {name}")
        all_rows.extend(rows)
    print("\n## extension counts")
    for ext,count in Counter(r[2] or "<none>" for r in all_rows).most_common():
        print(f"{ext:12s} {count}")
    return 0

if __name__=="__main__":
    raise SystemExit(main(sys.argv[1:]))
