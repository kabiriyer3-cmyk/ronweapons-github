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


def tinnitus(seconds=5.0):
    """our own ringing-ears tone (RoN does it with FMOD DSP): two close high sines, quick swell, slow fade"""
    t = np.arange(int(seconds * SR)) / SR
    env = np.minimum(1, t / 0.08) * np.exp(-t / 1.8)
    a = (np.sin(2 * np.pi * 3720 * t) * 0.6 + np.sin(2 * np.pi * 3790 * t + 1.0) * 0.4) * env * 0.35
    return np.stack([a, a * 0.97], axis=1).astype(np.float32)


def motion(rel, bone, thr=1.5):
    """(first time the bone leaves its start pose, first time after that it reaches its final pose) in seconds"""
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from actorx import read_psa
    path = os.path.join(PRIV, "export", *rel.split("/")) + ".psa"
    if not os.path.exists(path):
        return None
    a = read_psa(path)
    j = [n.lower() for n in a.bones].index(bone.lower())
    pos = a.pos[:, j]
    d0 = np.linalg.norm(pos - pos[0], axis=1)
    dl = np.linalg.norm(pos - pos[-1], axis=1)
    out = np.nonzero(d0 > thr)[0]
    if not len(out):
        return None
    k0 = out[0]
    back = np.nonzero(dl[k0:] < thr)[0]
    k1 = k0 + back[0] if len(back) else a.frames - 1
    return k0 / a.fps, k1 / a.fps


def shield_reloads(base_id):
    """reload cues for the shield rigs, from the RoN shield reload weapon clips (mag / speedloader motion)"""
    R = "ReadyOrNot/Content/ReadyOrNot/Animations/FP/Shield"
    out = {}
    if base_id == "g19":
        for act, clip in (("reload_tac", "low"), ("reload_empty", "empty_low"), ("reload_tac_up", "up"), ("reload_empty_up", "empty_UP")):
            m = motion(f"{R}/G19/ron_g19/fp_g19_add_shield_reload_{clip}_weapon", "tag_mag_01")
            if not m:
                continue
            t_out, t_in = m
            c = [[0.0, "g19.cloth_start"], [round(t_out, 2), "g19.magout"]]
            if "empty" in act:
                c.append([round(t_out + 0.3, 2), "g19.mag_drop", 0.5])
                c.append([round(t_in + 0.45, 2), "g19.slide_release"])
            else:
                c.append([round(t_in + 0.4, 2), "g19.mag_stow"])
            c.append([round(t_in, 2), "g19.magin"])
            out[act] = sorted(c)
            print(f"  shield {act}: mag out {t_out:.2f} in {t_in:.2f}")
    else:
        for act, clip in (("reload_tac", "low"), ("reload_empty", "low"), ("reload_tac_up", "up"), ("reload_empty_up", "up")):
            m = motion(f"{R}/Python/ron_python/fp_python_add_shield_reload_{clip}_weapon", "tag_bullets_root")
            if not m:
                continue
            t0, t_in = m
            out[act] = sorted([[0.0, "python.reload_start"], [round(max(0.2, t0 - 0.9), 2), "python.cyl_out"],
                               [round(max(0.4, t0 - 0.4), 2), "python.eject"], [round(t_in, 2), "python.insert"],
                               [round(t_in + 0.8, 2), "python.cyl_in"]])
            print(f"  shield {act}: loader moves {t0:.2f} seated {t_in:.2f}")
    return out


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

    # ---------------- v7: grenades (weapons / Interactions / SWAT banks)
    p.foley("grenade", {
        "pouch": [(f"char_reload_pouch_grenades_0{i}", 0, "SWAT") for i in range(1, 9)],
    })
    p.foley("flashbang", {"pin": [f"weap_grenade_ring_0{i}" for i in range(1, 5)]})
    p.foley("stinger", {"pin": [f"weap_stinger_pin_0{i}" for i in range(1, 4)] + ["Stinger_PinPull"]})
    p.foley("csgas", {"pin": ["CS_PinPull"]})
    # 3P world sounds (mono, positional): detonations carry far (the server plays them at volume 5-6 = 80-96 blocks)
    p.event("flashbang.bang", [p.write("flashbang", f"bang_{i}", mix((load(f"weap_flashbang_out_0{i}"), 1.0, 0),
                                                                   (load(f"weap_flashbang_debris_0{(i - 1) % 3 + 1}", 0, "Interactions"), 0.5, 0.03)), mono=True)
                                for i in range(1, 7)], "subtitles.ronweapons.flashbang")
    p.event("stinger.bang", [p.write("stinger", f"bang_{i}", mix((load(f"weap_stinger_out_0{i}"), 1.0, 0),
                                                                 (load("weap_grenade_fx_stinger_v2_01"), 0.6, 0.02)), mono=True)
                              for i in range(1, 5)], "subtitles.ronweapons.stinger")
    p.event("csgas.pop", [p.write("csgas", f"pop_{i}", mix((load(f"weap_grenade_csgas_expl_0{i}", 0, "Interactions"), 1.0, 0),
                                                           (load(f"weap_grenade_csgas_click_0{i}", 0, "Interactions"), 0.7, 0)), mono=True)
                           for i in range(1, 4)], "subtitles.ronweapons.csgas")
    p.event("csgas.hiss", [p.write("csgas", f"hiss_{i}", load(f"weap_grenade_csgas_gas_0{i}", 0, "Interactions"), mono=True) for i in range(1, 4)])
    p.event("grenade.bounce", [p.write("grenade", f"bounce_{n}", load(k), mono=True) for n, k in enumerate(
        [f"weap_grenade_bnce_thud_hi_0{i}" for i in range(1, 7)] + [f"weap_grenade_bnce_hi_short_0{i}" for i in range(1, 6)])])
    p.event("stinger.bounce", [p.write("stinger", f"bounce_{i}", load(f"weap_stinger_bnce_hard_0{i}"), mono=True) for i in range(1, 9)])
    p.event("csgas.bounce", [p.write("csgas", f"bounce_{i}", load(f"impact_can_bounce_0{i}", 0, "Interactions"), mono=True) for i in range(1, 6)])
    p.event("grenade.throw", [p.write("grenade", "throw", load("Stinger_Throw"), mono=True)])
    p.event("grenade.lever", [p.write("grenade", f"lever_{i}", load(f"weap_grenade_lever_0{i}"), mono=True) for i in range(1, 5)])
    pin = {"flashbang": "flashbang.pin", "stinger": "stinger.pin", "csgas": "csgas.pin"}
    for gid, ev in pin.items():
        # the pin leaves the grenade 0.52 s into every RoN pin-pull clip (tag_pin motion, diag_v7c.py)
        cues[gid] = {"draw": [[0.0, "grenade.pouch", 0.7]], "throw_start": [[0.50, ev]], "throw_under_start": [[0.50, ev]]}

    # ---------------- ballistic shield
    p.foley("shield", {
        "draw": [("tools_shield_pickup_01", 0, "SWAT"), ("tools_shield_pickup_02", 0, "SWAT")],
        "up": [("shield_up_01", 0, "SWAT"), ("shield_up_02", 0, "SWAT")],
        "down": [("tools_shield_down_01", 0, "SWAT"), ("tools_shield_down_02", 0, "SWAT")],
    })
    p.event("shield.hit", [p.write("shield", f"hit_{i}", load(f"weap_shield_bullet_hit_0{i}"), mono=True) for i in range(2, 7)],
            "subtitles.ronweapons.shield.hit")
    p.event("shield.bash", [p.write("shield", f"bash_{i}", load(f"imp_bash_hit_0{i}", 0, "Interactions"), mono=True) for i in range(1, 5)])
    for base_id in ("g19", "python"):
        c = {"draw": [[0.0, "shield.draw"]], "raise": [[0.0, "shield.up", 0.8]], "lower": [[0.0, "shield.down", 0.7]]}
        for act, rel in shield_reloads(base_id).items():
            c[act] = rel
        cues[base_id + "_shield"] = c

    # ---------------- night vision, weapon light, ringing ears
    p.event("nvg.on", [p.write("nvg", "on", mix((load("NVG_On_V2", 0, "Interactions"), 1.0, 0),
                                                (load("NVG_On_Whine_Short", 0, "Interactions"), 0.6, 0.05)), mono=True)])
    p.event("nvg.off", [p.write("nvg", "off", load("NVG_Off", 0, "Interactions"), mono=True)])
    p.event("light.toggle", [p.write("light", f"click_{i}", load(f"weap_ump45_switch_0{i}", 0, "SWAT"), mono=True) for i in (1, 2)])
    p.event("fx.tinnitus", [p.write("fx", "tinnitus", tinnitus())])

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
