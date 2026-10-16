"""Decode candidate subsongs of Weapons.bank to WAV (private cache) and print basic stats."""
import os
import subprocess
import sys

import numpy as np
import soundfile as sf

BANK = r"D:\SteamLibrary\steamapps\common\Ready Or Not\ReadyOrNot\Content\FMOD\Desktop\Weapons.bank"
VGM = r"C:\Modding\tools\vgmstream\vgmstream-cli.exe"
CACHE = r"C:\Modding\ron-to-minecraft\private\sound_cache"
os.makedirs(CACHE, exist_ok=True)
names = dict(l.split("\t") for l in open(r"C:\Modding\ron-to-minecraft\private\scan\weapons_bank_names.txt", encoding="utf-8").read().splitlines())
idx = [int(x) for x in sys.argv[1:]]
for i in idx:
    wav = os.path.join(CACHE, f"{i:04d}.wav")
    if not os.path.exists(wav):
        r = subprocess.run([VGM, "-s", str(i), "-o", wav, BANK], capture_output=True, text=True)
        if r.returncode != 0:
            print(i, names.get(str(i)), "FAIL", r.stderr[-200:])
            continue
    a, sr = sf.read(wav, always_2d=True)
    peak = float(np.abs(a).max())
    rms = float(np.sqrt((a ** 2).mean()))
    onset = int(np.argmax(np.abs(a).max(1) > peak * 0.1)) / sr
    print(f"{i:4d} {names.get(str(i)):34s} {a.shape[0] / sr:5.2f}s ch{a.shape[1]} {sr}Hz peak {peak:.2f} rms {rms:.3f} onset {onset:.3f}s")
