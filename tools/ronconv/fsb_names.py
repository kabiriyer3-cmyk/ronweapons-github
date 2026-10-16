"""List subsong names of an FMOD .bank (FSB5 inside). Index is 1-based like vgmstream's -s."""
import re
import struct
import sys

bank = sys.argv[1]
pattern = re.compile(sys.argv[2], re.I) if len(sys.argv) > 2 else None
out = sys.argv[3] if len(sys.argv) > 3 else None
data = open(bank, "rb").read()
pos = data.find(b"FSB5")
assert pos >= 0, "no FSB5 inside"
ver, n, shdr, nsize, dsize, mode = struct.unpack_from("<6I", data, pos + 4)
names_at = pos + 0x3C + shdr
offs = struct.unpack_from(f"<{n}I", data, names_at)
names = []
for o in offs:
    s = names_at + o
    names.append(data[s:data.index(b"\0", s)].decode("utf-8", "replace"))
print("fsb5 at", pos, "version", ver, "samples", n, "mode", mode)
rows = [(i + 1, nm) for i, nm in enumerate(names) if pattern is None or pattern.search(nm)]
if out:
    open(out, "w", encoding="utf-8").write("\n".join(f"{i}\t{nm}" for i, nm in enumerate(names, 1)))
print("matches", len(rows))
for i, nm in rows[:400]:
    print(i, nm)
