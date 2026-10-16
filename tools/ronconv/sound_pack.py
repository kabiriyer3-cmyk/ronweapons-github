"""Builds private/sound_pack: RoN M4A1 sounds as OGG for Minecraft + sounds.json + cue table.
Needs the WAV cache from sound_probe.py (vgmstream decode of Weapons.bank). Output is RoN audio: private only.

1P sounds stay stereo (played non-positional for the shooter); 3P sounds are mono so Minecraft can attenuate them.
Cue times are seconds into our clips, taken from the weapon bone motion (sound_cues.py): RoN drives this foley
from its FMOD timeline, not from anim notifies."""
import json
import os
import shutil
import subprocess

import numpy as np
import soundfile as sf

BANK = r"D:\SteamLibrary\steamapps\common\Ready Or Not\ReadyOrNot\Content\FMOD\Desktop\Weapons.bank"
VGM = r"C:\Modding\tools\vgmstream\vgmstream-cli.exe"
CACHE = r"C:\Modding\ron-to-minecraft\private\sound_cache"
OUT = r"C:\Modding\ron-to-minecraft\private\sound_pack"
SND = os.path.join(OUT, "assets", "ronweapons", "sounds", "ron", "m4a1")
SR = 48000


def load(i):
    wav = os.path.join(CACHE, f"{i:04d}.wav")
    if not os.path.exists(wav):
        subprocess.run([VGM, "-s", str(i), "-o", wav, BANK], check=True, capture_output=True)
    a, sr = sf.read(wav, always_2d=True, dtype="float32")
    assert sr == SR, (i, sr)
    if a.shape[1] == 1:
        a = np.repeat(a, 2, axis=1)
    return a[:, :2]


def mix(*layers):
    """layers: (array, gain, offset_seconds)"""
    n = max(int(off * SR) + len(a) for a, g, off in layers)
    out = np.zeros((n, 2), np.float32)
    for a, g, off in layers:
        s = int(off * SR)
        out[s:s + len(a)] += a * g
    return out


def write(name, a, mono=False, peak=0.95):
    if mono:
        a = a.mean(axis=1, keepdims=True)
    m = float(np.abs(a).max())
    if m > peak:
        a = a * (peak / m)
    # short fade-out against clicks
    f = min(len(a), int(0.01 * SR))
    a[-f:] *= np.linspace(1, 0, f)[:, None]
    sf.write(os.path.join(SND, name + ".ogg"), a, SR, format="OGG", subtype="VORBIS")
    return f"ronweapons:ron/m4a1/{name}"


def ev(sounds, subtitle=None, replace=True, attenuation=None):
    e = {"replace": replace, "sounds": []}
    for s in sounds:
        d = {"name": s}
        if attenuation:
            d["attenuation_distance"] = attenuation
        e["sounds"].append(d)
    if subtitle:
        e["subtitle"] = subtitle
    return e


def main():
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    os.makedirs(SND)
    events = {}

    # gunshot: core crack (0.24 s) + body (0.73-1.0 s) + tail, like RoN's layered 1P_M4A1_Fire
    core = [1639, 3124, 2831, 2384]
    body = [1958, 1468, 2304, 2243]
    tails = [3255, 873]
    fp, tp = [], []
    for k in range(4):
        shot = mix((load(core[k]), 0.65, 0.0), (load(body[k]), 0.8, 0.0), (load(tails[k % 2]), 1.0, 0.0))
        fp.append(write(f"fire_1p_{k + 1}", shot))
        tp.append(write(f"fire_3p_{k + 1}", mix((load(body[k]), 1.0, 0.0), (load(tails[k % 2]), 1.0, 0.0)), mono=True))
    events["m4a1.fire_1p"] = ev(fp)
    events["m4a1.fire"] = ev(tp, "subtitles.ronweapons.m4a1.fire", attenuation=96)

    events["dryfire"] = ev([write("dryfire", mix((load(2806), 1.0, 0.0), (load(2671), 0.8, 0.04)))], "subtitles.ronweapons.dryfire")
    events["fireselect"] = ev([write(f"select_{n}", load(i)) for n, i in
                               (("auto1", 2896), ("auto2", 1264), ("semi1", 4016), ("semi2", 1250), ("semi3", 3973))])
    # what other players hear when someone reloads (mono)
    events["m4a1.reload"] = ev([write("reload_3p", load(1168), mono=True)], "subtitles.ronweapons.m4a1.reload", attenuation=24)

    # (event, wav index or list, gain); cue lists reference the event names
    foley = {
        "m4a1.draw": [255],
        "m4a1.reload_start": [1966], "m4a1.cloth_start": [948], "m4a1.pouch_grab": [3156],
        "m4a1.tac_magout": [3663], "m4a1.tac_magin": [1168], "m4a1.mag_stow": [1927], "m4a1.cloth_end": [1762],
        "m4a1.empty_start": [3012], "m4a1.empty_magout": [2120], "m4a1.mag_drop": [4101, 1717],
        "m4a1.empty_magin": [1079], "m4a1.bolt_slap": [832], "m4a1.cloth_end2": [3147],
        "m4a1.check_start": [1440], "m4a1.check_magout": [3776], "m4a1.check_inspect": [1354],
        "m4a1.check_magin": [1922], "m4a1.check_done": [1579],
    }
    for e, idx in foley.items():
        base = e.split(".", 1)[1]
        events[e] = ev([write(f"{base}_{n + 1}" if len(idx) > 1 else base, load(i)) for n, i in enumerate(idx)])

    cues = {
        "draw": [[0.0, "m4a1.draw"]],
        "reload_tac": [[0.0, "m4a1.reload_start"], [0.05, "m4a1.cloth_start"], [0.42, "m4a1.pouch_grab"],
                       [0.86, "m4a1.tac_magout"], [1.60, "m4a1.tac_magin"], [2.12, "m4a1.mag_stow"],
                       [2.70, "m4a1.cloth_end"]],
        # these two clips run at ~30 fps (70 frames / 2.30 s, 86 / 2.83 s): times measured on that clock
        "reload_empty": [[0.0, "m4a1.empty_start"], [0.20, "m4a1.empty_magout"], [0.55, "m4a1.mag_drop", 0.7],
                         [0.98, "m4a1.empty_magin"], [1.49, "m4a1.bolt_slap"], [1.95, "m4a1.cloth_end2"]],
        "magcheck": [[0.0, "m4a1.check_start"], [0.52, "m4a1.check_magout"], [0.80, "m4a1.check_inspect"],
                     [1.90, "m4a1.check_magin"], [2.30, "m4a1.check_done"]],
    }
    json.dump(events, open(os.path.join(OUT, "assets", "ronweapons", "sounds.json"), "w"), indent=1)
    json.dump(cues, open(os.path.join(OUT, "cues.json"), "w"), indent=1)
    n = len(os.listdir(SND))
    size = sum(os.path.getsize(os.path.join(SND, f)) for f in os.listdir(SND))
    print(f"sound pack: {len(events)} events, {n} ogg files, {size / 1e6:.2f} MB -> {OUT}")


if __name__ == "__main__":
    main()
