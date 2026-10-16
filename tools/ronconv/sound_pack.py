"""Builds private/sound_pack: RoN weapon sounds as OGG for Minecraft + sounds.json + cue table (all weapons).
Decodes Weapons.bank subsongs with vgmstream (cache in private/sound_cache). Output is RoN audio: private only.

1P sounds stay stereo (non-positional for the shooter); 3P sounds are mono so Minecraft can attenuate them.
Cue times are seconds into our clips, measured from weapon bone motion (sound_cues.py / pistol_cues.py):
RoN drives reload foley from its FMOD timeline, not from anim notifies."""
import json
import os
import shutil
import subprocess

import numpy as np
import soundfile as sf

BANK = r"D:\SteamLibrary\steamapps\common\Ready Or Not\ReadyOrNot\Content\FMOD\Desktop\Weapons.bank"
VGM = r"C:\Modding\tools\vgmstream\vgmstream-cli.exe"
PRIV = r"C:\Modding\ron-to-minecraft\private"
CACHE = os.path.join(PRIV, "sound_cache")
OUT = os.path.join(PRIV, "sound_pack")
SR = 48000
NAMES = {}
for line in open(os.path.join(PRIV, "scan", "weapons_bank_names.txt"), encoding="utf-8").read().splitlines():
    i, n = line.split("\t")
    NAMES.setdefault(n, []).append(int(i))


# other FMOD banks: names come from the FSB5 name table (0-based), vgmstream subsongs are 1-based
BANKS = {}
for bank in ("Interactions", "SWAT"):
    f = os.path.join(PRIV, "scan", bank + "_bank_names.txt")
    if os.path.exists(f):
        BANKS[bank] = {}
        for line in open(f, encoding="utf-8").read().splitlines():
            i, n = line.split("\t")
            BANKS[bank].setdefault(n, []).append(int(i) + 1)


def load(key, which=0, bank=None):
    """key: subsong index (int) or bank sample name (str; which = n-th sample with that name). bank: other .bank name."""
    names = NAMES if bank is None else BANKS[bank]
    i = key if isinstance(key, int) else names[key][min(which, len(names[key]) - 1)]
    path = BANK if bank is None else os.path.join(os.path.dirname(BANK), bank + ".bank")
    os.makedirs(CACHE, exist_ok=True)
    wav = os.path.join(CACHE, f"{i:04d}.wav" if bank is None else f"{bank}_{i:04d}.wav")
    if not os.path.exists(wav):
        subprocess.run([VGM, "-s", str(i), "-o", wav, path], check=True, capture_output=True)
    a, sr = sf.read(wav, always_2d=True, dtype="float32")
    if sr != SR:  # a few banks samples are 44.1k: linear resample
        t = np.arange(int(len(a) * SR / sr)) * (sr / SR)
        a = np.stack([np.interp(t, np.arange(len(a)), a[:, c]) for c in range(a.shape[1])], axis=1).astype(np.float32)
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


class Pack:
    def __init__(self):
        self.events = {}

    def write(self, wid, name, a, mono=False, peak=0.95):
        d = os.path.join(OUT, "assets", "ronweapons", "sounds", "ron", wid)
        os.makedirs(d, exist_ok=True)
        if mono:
            a = a.mean(axis=1, keepdims=True)
        m = float(np.abs(a).max())
        if m > peak:
            a = a * (peak / m)
        f = min(len(a), int(0.01 * SR))
        a[-f:] *= np.linspace(1, 0, f)[:, None]
        sf.write(os.path.join(d, name + ".ogg"), a, SR, format="OGG", subtype="VORBIS")
        return f"ronweapons:ron/{wid}/{name}"

    def event(self, ev, sounds, subtitle=None, attenuation=None):
        e = {"replace": True, "sounds": [dict({"name": s}, **({"attenuation_distance": attenuation} if attenuation else {}))
                                         for s in sounds]}
        if subtitle:
            e["subtitle"] = subtitle
        self.events[ev] = e

    def gunshots(self, wid, layers_1p, layers_3p, suffix="", attenuation=None):
        """layers_*: list per variant of [(key, gain, offset)]; suffix "_supp" for the suppressed set"""
        fp = [self.write(wid, f"fire_1p{suffix}_{k + 1}", mix(*[(load(*key) if isinstance(key, tuple) else load(key), g, o) for key, g, o in v]))
              for k, v in enumerate(layers_1p)]
        tp = [self.write(wid, f"fire_3p{suffix}_{k + 1}", mix(*[(load(*key) if isinstance(key, tuple) else load(key), g, o) for key, g, o in v]), mono=True)
              for k, v in enumerate(layers_3p)]
        self.event(f"{wid}.fire_1p{suffix}", fp)
        sub = "subtitles.ronweapons.fire_supp" if suffix else f"subtitles.ronweapons.{wid}.fire"
        self.event(f"{wid}.fire{suffix}", tp, sub, attenuation=attenuation or (96 if wid in ("m4a1", "m590") else 64))

    def foley(self, wid, table):
        for ev, keys in table.items():
            files = [self.write(wid, f"{ev}_{n + 1}" if len(keys) > 1 else ev, load(*k) if isinstance(k, tuple) else load(k))
                     for n, k in enumerate(keys)]
            self.event(f"{wid}.{ev}", files)


def main():
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    p = Pack()
    cues = {}

    # ---------------- M4A1 (subsong indices checked by ear-free stats: core 0.24 s, body 0.73-1.0 s)
    core, body, tails = [1639, 3124, 2831, 2384], [1958, 1468, 2304, 2243], [3255, 873]
    p.gunshots("m4a1", [[(core[k], 0.65, 0), (body[k], 0.8, 0), (tails[k % 2], 1.0, 0)] for k in range(4)],
               [[(body[k], 1.0, 0), (tails[k % 2], 1.0, 0)] for k in range(4)])
    p.event("m4a1.dryfire", [p.write("m4a1", "dryfire", mix((load(2806), 1.0, 0), (load(2671), 0.8, 0.04)))], "subtitles.ronweapons.dryfire")
    p.event("fireselect", [p.write("m4a1", f"select_{n}", load(i)) for n, i in
                           (("auto1", 2896), ("auto2", 1264), ("semi1", 4016), ("semi2", 1250), ("semi3", 3973))])
    p.event("m4a1.reload", [p.write("m4a1", "reload_3p", load(1168), mono=True)], "subtitles.ronweapons.m4a1.reload", attenuation=24)
    p.foley("m4a1", {
        "draw": [255], "reload_start": [1966], "cloth_start": [948], "pouch_grab": [3156],
        "tac_magout": [3663], "tac_magin": [1168], "mag_stow": [1927], "cloth_end": [1762],
        "empty_start": [3012], "empty_magout": [2120], "mag_drop": [4101, 1717],
        "empty_magin": [1079], "bolt_slap": [832], "cloth_end2": [3147],
        "check_start": [1440], "check_magout": [3776], "check_inspect": [1354], "check_magin": [1922], "check_done": [1579],
    })
    cues["m4a1"] = {
        "draw": [[0.0, "m4a1.draw"]],
        "reload_tac": [[0.0, "m4a1.reload_start"], [0.05, "m4a1.cloth_start"], [0.42, "m4a1.pouch_grab"],
                       [0.86, "m4a1.tac_magout"], [1.60, "m4a1.tac_magin"], [2.12, "m4a1.mag_stow"], [2.70, "m4a1.cloth_end"]],
        # these two clips run at ~30 fps
        "reload_empty": [[0.0, "m4a1.empty_start"], [0.20, "m4a1.empty_magout"], [0.55, "m4a1.mag_drop", 0.7],
                         [0.98, "m4a1.empty_magin"], [1.49, "m4a1.bolt_slap"], [1.95, "m4a1.cloth_end2"]],
        "magcheck": [[0.0, "m4a1.check_start"], [0.52, "m4a1.check_magout"], [0.80, "m4a1.check_inspect"],
                     [1.90, "m4a1.check_magin"], [2.30, "m4a1.check_done"]],
    }

    # suppressed M4: RoN core + interior suppressed reflection (quiet, short tail)
    sup = [1427, 2336, 1858, 2088]
    p.gunshots("m4a1", [[(sup[k], 0.9, 0), ("M4_Reflection_int_Suppressed_Base_0" + str(k % 2 + 1), 0.6, 0)] for k in range(4)],
               [[(sup[k], 1.0, 0), ("M4_Reflection_int_Suppressed_Base_0" + str(k % 2 + 1), 0.5, 0)] for k in range(4)],
               suffix="_supp", attenuation=32)

    # ---------------- Glock 19
    p.gunshots("g19", [[(f"Glock-{k + 1}", 0.85, 0), (f"Glock_MidShot{k + 1}", 0.45, 0), (f"Glock_MidTail_0{k % 2 + 1}", 1.0, 0)] for k in range(4)],
               [[(f"Glock_MidShot{k + 1}", 1.0, 0), (f"Glock_MidTail_0{k % 2 + 1}", 1.0, 0)] for k in range(4)])
    p.gunshots("g19", [[(f"Glock-{n}-Suppressed", 0.9, 0), (f"Glock-ExteriorReflectionSuppressed0{k + 1}-1", 0.6, 0)] for k, n in enumerate((1, 3, 4))],
               [[(f"Glock-{n}-Suppressed", 1.0, 0), (f"Glock-ExteriorReflectionSuppressed0{k + 1}-1", 0.5, 0)] for k, n in enumerate((1, 3, 4))],
               suffix="_supp", attenuation=24)
    p.event("g19.dryfire", [p.write("g19", "dryfire", load("weap_g19_dry_fire_01"))], "subtitles.ronweapons.dryfire")
    p.event("g19.reload", [p.write("g19", "reload_3p", load("weap_g19_mag_in_01"), mono=True)], "subtitles.ronweapons.m4a1.reload", attenuation=20)
    p.foley("g19", {
        "cloth_start": ["Cloth-ReloadStart"], "pouch_grab": ["Cloth-MagPouchGrab"],
        "magout": ["weap_g19_mag_out_01", "weap_g19_mag_out_02"], "magin": ["weap_g19_mag_in_01", "weap_g19_mag_in_02"],
        "mag_stow": ["Cloth-MagPouchStow"], "mag_drop": ["weap_rifle_mag_drop_01"],
        "slide_release": ["weap_g19_slide_push_01", "weap_g19_slide_push_02"],
        "check_start": ["Cloth-CheckStart"], "check_magout": ["weap_g19_mag_out_03"], "check_magin": ["weap_g19_mag_in_03"],
        "check_done": ["Cloth-CheckDone"],
    })
    cues["g19"] = {
        "reload_tac": [[0.0, "g19.cloth_start"], [0.20, "g19.pouch_grab"], [0.80, "g19.magout"],
                       [1.50, "g19.magin"], [1.95, "g19.mag_stow"]],
        "reload_empty": [[0.0, "g19.cloth_start"], [0.30, "g19.magout"], [0.58, "g19.mag_drop", 0.5],
                         [0.95, "g19.magin"], [1.60, "g19.slide_release"]],
        "magcheck": [[0.0, "g19.check_start"], [0.27, "g19.check_magout"], [1.98, "g19.check_magin"], [2.30, "g19.check_done"]],
    }

    # ---------------- Colt Python .357
    p.gunshots("python", [[(f"Python_Fire_0{k + 1}", 0.9, 0), (f"Python_MidShot{k + 1}", 0.45, 0), (f"Python_MidTail_0{k % 2 + 1}", 1.0, 0)] for k in range(4)],
               [[(f"Python_MidShot{k + 1}", 1.0, 0), (f"Python_MidTail_0{k % 2 + 1}", 1.0, 0)] for k in range(4)])
    p.event("python.dryfire", [p.write("python", "dryfire", load("Python_DryFire"))], "subtitles.ronweapons.dryfire")
    p.event("python.reload", [p.write("python", "reload_3p", load("Python_ReloadBulletInsert"), mono=True)], "subtitles.ronweapons.m4a1.reload", attenuation=20)
    p.foley("python", {
        "draw": ["Python_Draw"], "reload_start": ["Python_ReloadStart"], "cyl_out": ["Python_ReloadCylOut"],
        "eject": ["Python_ReloadBulletEject"], "insert": ["Python_ReloadBulletInsert"], "cyl_in": ["Python_ReloadCylIn"],
        "check": ["Python_AmmoCheck"],
    })
    # ejector rod pushed at 0.76 s, speedloader seated at 1.68 s (tag_rod / tag_speedloader motion, diag_v6.py)
    rel = [[0.0, "python.reload_start"], [0.27, "python.cyl_out"], [0.64, "python.eject"],
           [1.50, "python.insert"], [2.40, "python.cyl_in"]]
    # Python_AmmoCheck is the whole cylinder check (swing out ~0.3 s, close ~1.9 s): one cue, nothing layered on top
    cues["python"] = {"draw": [[0.0, "python.draw"]], "reload_tac": rel, "reload_empty": rel,
                      "magcheck": [[0.0, "python.check"]]}

    # ---------------- Mossberg 590A1 (RoN's pump guns share the 870 sample set)
    p.gunshots("m590", [[(("870-" + str(k + 1), 0), 0.85, 0), (f"870_MidReflection_0{k + 1}", 0.6, 0), (f"870_MidTail_0{k % 2 + 1}", 1.0, 0)] for k in range(4)],
               [[(f"870_MidReflection_0{k + 1}", 1.0, 0), (f"870_MidTail_0{k % 2 + 1}", 1.0, 0)] for k in range(4)])
    p.event("m590.dryfire", [p.write("m590", "dryfire", load("weap_870_dryfire_01"))], "subtitles.ronweapons.dryfire")
    p.event("m590.reload", [p.write("m590", "reload_3p", load("weap_870_shell_in_01"), mono=True)], "subtitles.ronweapons.m590.reload", attenuation=20)
    p.foley("m590", {
        "draw": [f"Universal_Long_Draw_0{i}" for i in range(1, 5)],
        "pump_back": [f"weap_870_pump_out_0{i}" for i in range(1, 6)],
        "pump_fwd": [f"weap_870_pump_in_0{i}" for i in range(1, 6)],
        "reload_start": ["weap_870_reload_start_01", "weap_870_reload_start_02"],
        "shell_grab": [f"weap_shotgun_shell_pull_0{i}" for i in range(1, 7)],
        "shell_in": [f"weap_870_shell_in_0{i}" for i in range(1, 8)],
        "shell_port": ["SHell-Insert1", "SHell-Insert2", "SHell-Insert3"],
        "reload_end": ["weap_870_reload_end_01", "weap_870_reload_end_02"],
        "check": ["weap_870_mag_check_01"],
    })
    pump = [[0.30, "m590.pump_back"], [0.53, "m590.pump_fwd"]]
    cues["m590"] = {
        "draw": [[0.0, "m590.draw"]],
        "fire": pump, "fire_ads": pump,
        # times from tag_bullet / tag_charginghandle motion (diag_m590.py), clips at ~31 fps
        "reload_start": [[0.0, "m590.reload_start"], [0.45, "m590.shell_grab"]],
        "reload_loop": [[0.28, "m590.shell_in"], [0.50, "m590.shell_grab"]],
        "reload_end": [[0.30, "m590.shell_in"], [0.70, "m590.reload_end"]],
        "reload_start_empty": [[0.12, "m590.pump_back"], [0.95, "m590.shell_port"], [1.68, "m590.pump_fwd"], [2.05, "m590.shell_grab"]],
        "magcheck": [[0.25, "m590.check"]],
    }

    # ---------------- Zipcuffs (Interactions / SWAT banks)
    p.foley("zipcuffs", {
        "draw": [(f"foley_plastic_cuffs_pressure0{i}", 0, "SWAT") for i in range(1, 7)],
        "grab": [("arrest_player_01_step01", 0, "Interactions"), ("arrest_player_01_step01_02", 0, "Interactions"), ("arrest_player_01_step01_03", 0, "Interactions")],
        "cuff": [("Ziptie", 0, "Interactions")],
    })
    p.event("zipcuffs.cuff_3p", [p.write("zipcuffs", "cuff_3p", mix((load("Ziptie", 0, "Interactions"), 1.0, 0),
                                                                    (load("arrest_player_02_step04", 0, "Interactions"), 0.6, 0.0)), mono=True)],
            "subtitles.ronweapons.zipcuffs.cuff", attenuation=16)
    p.event("zipcuffs.cut", [p.write("zipcuffs", "cut", load("foley_plastic_cuffs_pressure02", 0, "SWAT"), mono=True)],
            "subtitles.ronweapons.zipcuffs.cut", attenuation=12)
    # fp_zipcuffs_add_restrain_downed (1.33 s): hands on the wrists, the right hand pulls the tie at ~0.7 s
    cues["zipcuffs"] = {"draw": [[0.0, "zipcuffs.draw", 0.6]], "restrain": [[0.0, "zipcuffs.grab", 0.8], [0.27, "zipcuffs.cuff"]]}

    # brass hitting the floor (client debris)
    for wid, kind in (("m4a1", "556"), ("g19", "9mm"), ("python", "9mm"), ("m590", "SH")):
        p.event(f"{wid}.casing_drop", [p.write(wid, f"casing_drop_{i}", load(f"{kind}Concrete_shell_drop0{i}"), mono=True) for i in range(1, 6)])

    os.makedirs(os.path.join(OUT, "assets", "ronweapons"), exist_ok=True)
    json.dump(p.events, open(os.path.join(OUT, "assets", "ronweapons", "sounds.json"), "w"), indent=1)
    json.dump(cues, open(os.path.join(OUT, "cues.json"), "w"), indent=1)
    n = sum(len(fs) for _, _, fs in os.walk(os.path.join(OUT, "assets", "ronweapons", "sounds")))
    print(f"sound pack: {len(p.events)} events, {n} ogg files -> {OUT}")


if __name__ == "__main__":
    main()
