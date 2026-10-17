"""Build the PRIVATE Minecraft resource pack from your own Ready or Not export (all weapons).

Input : private/export (tools/ronexport) and private/sound_pack (sound_pack.py, optional)
Output: private/RoN-Weapons-Private.zip (+ unpacked folder). Never commit or share it: it contains RoN assets.

usage: py build_pack.py [export_dir] [out_dir] [--install <.minecraft dir>]
"""
import glob
import json
import os
import shutil
import struct
import sys
import zipfile

import numpy as np
from PIL import Image

from actorx import conj, read_psa, read_psk

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
EXPORT = os.path.join(ROOT, "private", "export")
OUT = os.path.join(ROOT, "private")
PACK_NAME = "RoN-Weapons-Private"
NS = "ronweapons"
MAX_TEX = 2048

ARMS_MESH = "ReadyOrNot/Content/ReadyOrNot/Character/SWAT/SWAT_Officer/SK_Char_SWAT_Heavy_FP_Arms"
A = "ReadyOrNot/Content/ReadyOrNot/Animations"
M4FP, M4REV = f"{A}/FP/M4A1", f"{A}/Revised/FirstPerson/Weapons/M4_Rifle"
G19FP, PYFP, PYREV = f"{A}/FP/G19", f"{A}/FP/PYTHON", f"{A}/Revised/FirstPerson/Weapons/Python"
M590A, M590W = f"{A}/FP/M590/base_male", f"{A}/FP/M590/ron_m590"
ATT = "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/Attachments"
SHELL = "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/Shell"
# Spent brass / dropped mags (client effects). eject_on: "fire" or the cue sound that ejects; vel: camera-space blocks/s
FX = {
    "m4a1": {"casing": f"{SHELL}/762x39mm/762x39mm_Shell", "eject_bone": "EjectBrassSocket", "eject_on": "fire", "vel": [2.6, 1.6, 0.6],
             "mag_cue": "m4a1.mag_drop", "mag_socket": "mag_01_socket", "land_sound": "m4a1.casing_drop"},
    "g19": {"casing": f"{SHELL}/9mm_Casing", "eject_bone": "tag_brass", "eject_on": "fire", "vel": [2.2, 1.9, 0.4],
            "mag_cue": "g19.mag_drop", "mag_socket": "tag_mag_01_socket", "land_sound": "g19.casing_drop"},
    "python": {"casing": f"{SHELL}/357_Magnum/357_Casing", "eject_bone": "mag_01_bullets_socket", "eject_on": "python.eject",
               "count": 6, "vel": [0.0, -1.2, 0.6], "land_sound": "python.casing_drop"},
    "m590": {"casing": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/New_Weapons/Supernova/Shell_Empty", "eject_bone": "tag_brass",
             "eject_on": "m590.pump_back", "vel": [2.0, 1.3, 0.3], "land_sound": "m590.casing_drop"},
    "usp45": {"casing": f"{SHELL}/9mm_Casing", "eject_bone": "tag_brass", "eject_on": "fire", "vel": [2.2, 1.9, 0.4],
              "mag_cue": "usp45.mag_drop", "mag_socket": "tag_mag_01_Socket", "land_sound": "usp45.casing_drop"},
    "sa58": {"casing": f"{SHELL}/762x39mm/762x39mm_Shell", "eject_bone": "tag_brass", "eject_on": "fire", "vel": [2.6, 1.5, 0.5],
             "mag_cue": "sa58.mag_drop", "mag_socket": "tag_mag_01_Socket", "land_sound": "sa58.casing_drop"},
}

# Optics: mesh on a gun socket. The ADS eye goes through the centre of the RoN reticle plane ("Optic_Picture" material);
# our own reticle texture is drawn there (dot / holo ring), lens glass and invisible helpers are skipped.
# ids must match WeaponDef.optics in the mod. eye_y None = keep the hip-fire eye distance (pistols).

# Clip spec: path, or (path, "first"|"last") to take a single pose from a clip.
# Every clip is absolute (CUE4Parse bakes RoN additives onto the ref pose).
# Clips RoN stores with ACL compression cannot be exported yet; those slots use a pose from another clip or idle.
WEAPONS = {
    "m4a1": {
        "gun": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/M4_Rifle/Mesh/SK_WP_Rifle_M4",
        # iron-sight line (gun space, cm): front post tip z = 9.42 at y = -34.2, rear aperture y ~ +3; eye 8 cm behind
        "ads_eye": [0.0, 11.0, 9.42],
        "attachments": [
            {"mesh": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/M4_Rifle/Mesh/SM_Rif_M4A1_new_mag_static",
             "file": "mag", "sockets": ["mag_01_socket", "mag_02_socket"]},
        ],
        "optics": [
            {"id": "exps3", "mesh": f"{ATT}/Optic_EPXS3/Holosight_EXPS3", "socket": "EXPS3_Socket", "style": "holo", "hide": ["Weapon_1P_M4A1_CARRYHANDLE_INST"]},
            {"id": "m5b", "mesh": f"{ATT}/Optic_M5B/sk_optic_m5", "socket": "M5B_Socket", "style": "dot", "hide": ["Weapon_1P_M4A1_CARRYHANDLE_INST"]},
            {"id": "t2", "mesh": f"{ATT}/Optic_MicroT2/SK_MicroT2_TallMount", "socket": "MicroT2Raised_Socket", "style": "dot", "hide": ["Weapon_1P_M4A1_CARRYHANDLE_INST"]},
        ],
        "suppressor": {"mesh": f"{ATT}/Suppressor_SOCOM338/sk_suppressor_socom338", "socket": "socom338_socket"},
        "light": {"mesh": f"{ATT}/Flashlight_M600V/sk_illum_flashlight_M600V", "socket": "light_socket"},
        "tp": {"back": 0.30},
        "actions": {
            "draw":         (f"{M4FP}/arms/fp_m4a1_add_draw", None),
            "idle":         (f"{M4FP}/arms/fp_gen_idle_01", f"{M4FP}/weapon/fp_m4a1_idle_pose_weapon"),
            "idle_empty":   (None, f"{M4FP}/weapon/fp_m4a1_idle_pose_empty"),
            "walk":         (f"{M4FP}/arms/fp_m4a1_add_walk_loop", None),
            "run":          (f"{M4REV}/arms/fp_m4a1_add_run_variance", None),
            "fire":         (f"{M4REV}/arms/fp_m4a1_fire_single_aim_01", f"{M4REV}/weapon/fp_m4a1_add_fire_single_weapon"),
            "fire_last":    (f"{M4REV}/arms/fp_m4a1_fire_single_aim_01", f"{M4REV}/weapon/fp_m4a1_add_fire_single_last_weapon"),
            "reload_tac":   (f"{M4FP}/arms/fp_m4a1_add_tac_reload", f"{M4FP}/weapon/fp_m4a1_add_tac_reload"),
            "reload_empty": (f"{M4FP}/arms/fp_m4a1_spd_reload_empty", f"{M4FP}/weapon/fp_m4a1_spd_reload_empty"),
            "magcheck":     (f"{M4FP}/arms/fp_m4a1_add_mag_check", f"{M4FP}/weapon/fp_m4a1_add_mag_check"),
            "select_auto":  (f"{M4FP}/arms/fp_m4a1_add_fire_select_auto", None),
            "select_semi":  (f"{M4FP}/arms/fp_m4a1_add_fire_select_semi", None),
            # flip the goggles down / up (RoN's clips are built on the M4 idle pose)
            "nvg_on":       (f"{A}/Revised/FirstPerson/Weapons/Fiveseven/arms/game_feel/fp_m4a1_add_enable_swat_nvg", None),
            "nvg_off":      (f"{A}/Revised/FirstPerson/Weapons/Fiveseven/arms/game_feel/fp_m4a1_add_disable_swat_nvg", None),
        },
    },
    "g19": {
        "gun": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/G19/Meshes/SK_Pistol_G19",
        # front sight top z 3.62 (y -9), rear sight top z 3.65 (y 7); null y = keep the hip-fire distance to the eye
        "ads_eye": [0.0, None, 3.64],
        "attachments": [
            {"mesh": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/G19/Meshes/SM_G19_Mag_Static",
             "file": "mag", "sockets": ["tag_mag_01_socket", "tag_mag_02_socket"]},
            {"mesh": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/G19/Meshes/g19_fmj_bullets_static",
             "file": "rounds", "sockets": ["tag_mag_01_bullets_socket", "tag_mag_02_bullets_socket"]},
        ],
        "optics": [
            {"id": "rmr", "mesh": f"{ATT}/Optic_RMR/sk_optic_rmr_pistol", "socket": "RMRPistol_Socket", "style": "dot"},
            {"id": "sro", "mesh": f"{ATT}/Optic_SRO/sk_optic_sro_glock", "socket": "SROPistol_Socket", "style": "dot"},
        ],
        "suppressor": {"mesh": f"{ATT}/Mesh/Pistol_SuppressorSmall", "socket": "supressor_socket"},
        # the G19 light_socket is turned 90 deg against the X300 (RoN's own Glock light is a different model): the X300 lay
        # sideways across the frame. Turn it about its vertical axis so the lens faces the muzzle (like on the USP socket).
        "light": {"mesh": f"{ATT}/Light_X300/SK_Pistol_light_X300", "socket": "light_socket", "rot": [0, 0, 90]},
        "tp": {"back": 0.0},
        "actions": {
            "draw":         (f"{G19FP}/arms/fp_g19_add_draw", None),
            # fp_g19_idle_high_ready points the gun at the sky and the real idle is ACL-only: frame 0 of the tac reload is
            # the same pose every G19 clip starts and ends in (checked with diag_pistols.py)
            "idle":         ((f"{G19FP}/arms/fp_g19_add_tac_reload", "first"), f"{G19FP}/weapon/fp_g19_idle_pose"),
            "idle_empty":   (None, (f"{G19FP}/weapon/fp_g19_add_fire_empty_60fps_weapon", "last")),
            "run":          (f"{A}/FP/SHARED/pistol_sprint/fp_g19_add_loco_sprint_fwd", None),
            # no exportable arms fire clip for the G19: idle arms + procedural kick, the slide still cycles
            "fire":         (None, f"{G19FP}/weapon/fp_g19_add_fire_weapon"),
            "fire_last":    (None, f"{G19FP}/weapon/fp_g19_add_fire_empty_60fps_weapon"),
            "reload_tac":   (f"{G19FP}/arms/fp_g19_add_tac_reload", f"{G19FP}/weapon/fp_g19_add_tac_reload"),
            "reload_empty": (f"{G19FP}/arms/fp_g19_add_spd_reload_empty", f"{G19FP}/weapon/fp_g19_add_spd_reload_empty"),
            "magcheck":     (f"{G19FP}/arms/fp_g19_add_mag_check", f"{G19FP}/weapon/fp_g19_add_mag_check"),
        },
    },
    "python": {
        "gun": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/Python/Meshes/SK_Pistol_Python",
        # front ramp top z 5.66 (y -17), rear notch z 5.61 (y 0)
        "ads_eye": [0.0, None, 5.63],
        "attachments": [
            {"mesh": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/Python/Meshes/SM_Python_FMJ_Bullets_Static",
             "file": "rounds", "sockets": ["mag_01_bullets_socket"]},
        ],
        "optics": [
            {"id": "t2", "mesh": f"{ATT}/Optic_MicroT2/V2/SK_T2_shortmount", "socket": "PythonT2_socket", "style": "dot"},
        ],
        "tp": {"back": 0.0},
        "actions": {
            # weapon idle is ACL-only: frame 0 of the cylinder check has the cylinder shut and the speedloader parked
            "idle":         (f"{PYREV}/arms/fp_python_idle_pose", (f"{PYFP}/ron_python_fix/fp_python_add_mag_check_weapon", "first")),
            "fire":         (f"{PYREV}/arms/fp_m92fs_add_fire_single_python", None),
            "reload_tac":   (f"{PYFP}/base_male/fp_python_add_spd_reload", f"{PYFP}/ron_python_fix/fp_python_add_spd_reload_weapon"),
            "reload_empty": (f"{PYFP}/base_male/fp_python_add_spd_reload", f"{PYFP}/ron_python_fix/fp_python_add_spd_reload_weapon"),
            "magcheck":     (f"{PYFP}/base_male/fp_python_add_mag_check", f"{PYFP}/ron_python_fix/fp_python_add_mag_check_weapon"),
            # trigger pull on an empty cylinder: the hammer cocks and falls (the live-fire weapon clip is ACL-only)
            "dryfire":      (None, f"{PYREV}/weapon/fp_python_add_dryfire_weapon"),
            # no exportable draw: the client raises the idle pose procedurally
        },
    },
    "m590": {
        "gun": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/M590/Meshes/SK_Shotgun_M590_Assault",
        # front blade top z 5.27 (y -63.5), ghost ring at y -3.3 around z 5.3; eye 9 cm behind the ring
        "ads_eye": [0.0, 6.0, 5.3],
        "attachments": [
            # the shell the left hand carries; parked inside the stock (y > 3.5) between inserts -> hidden there
            {"mesh": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/M590/Meshes/Shells_Scaled/m590_buckshot_static_shell",
             "file": "shell", "sockets": ["tag_bullet_socket"], "hide_y_above": 3.5},
        ],
        "light": {"mesh": f"{ATT}/Mesh/Shotgun_Flashlight", "socket": "light_socket"},
        "tp": {"back": 0.35},
        "actions": {
            # weapon idle / draw clips are ACL-only: frame 0 of the fire clip is the rest pose (pump forward, shell in the tube)
            "draw":               (f"{M590A}/fp_M590_add_draw", None),
            "idle":               (f"{M590A}/fp_m590_idle_pose", (f"{M590W}/fp_m590_add_fire_weapon", "first")),
            "fire":               (f"{M590A}/fp_m590_add_fire", f"{M590W}/fp_m590_add_fire_weapon"),
            "fire_ads":           (f"{M590A}/fp_m590_add_fire_ads", f"{M590W}/fp_m590_add_fire_weapon"),
            "reload_start":       (f"{M590A}/fp_M590_add_reload_start_A", f"{M590W}/fp_M590_add_reload_start_A_weapon"),
            "reload_start_empty": (f"{M590A}/fp_M590_add_reload_start_empty_A", f"{M590W}/fp_M590_add_reload_start_empty_A_weapon"),
            "reload_loop":        (f"{M590A}/fp_M590_add_reload_loop_A", f"{M590W}/fp_M590_add_reload_loop_A_weapon"),
            "reload_end":         (f"{M590A}/fp_M590_add_reload_end_A", f"{M590W}/fp_M590_add_reload_end_A_weapon"),
            "magcheck":           (f"{M590A}/fp_M590_add_magcheck", f"{M590W}/fp_M590_add_magcheck_weapon"),
        },
    },
}

# Zipcuffs: a "weapon" with no gun logic. SK_Device_Zipcuffs is rooted at J_Gun like the guns, so it rides weapon_right.
ZIP = f"{A}/FP/Zipcuffs/base_male"
WEAPONS["zipcuffs"] = {
    "gun": "ReadyOrNot/Content/ReadyOrNot/Assets/Devices/Zipcuffs/Meshes/SK_Device_Zipcuffs",
    "ads_eye": [0.0, None, 0.0],
    "tp": {"back": 0.0},
    "actions": {
        "draw":     (f"{ZIP}/fp_zipcuffs_add_draw", None),
        "idle":     (f"{ZIP}/fp_zipcuffs_idle_pose", None),
        "restrain": (f"{ZIP}/fp_zipcuffs_add_restrain_downed", None),
    },
}

# ---------------- v7: grenades, ballistic shield, NVG
# Grenades: one arms set for all three (the flashbang's), each grenade's own pin-pull / throw weapon clips (Grenades_V2).
# RoN's grenade draw / idle weapon clips are ACL-only: the first frame of the pin pull is the rest pose.
FL, GV2 = f"{A}/FP/FLASH/base_male", f"{A}/FP/Grenades_V2"
STR = f"{A}/Revised/FirstPerson/Weapons/Stinger/arms"
for gid, folder, prefix in (("flashbang", "Flashbang", "flashbang"), ("stinger", "Stinger", "stinger"), ("csgas", "CSGas", "csgas")):
    start_w = f"{GV2}/{folder}/weapon/fp_{prefix}_add_overarm_throw_start_weapon"
    throw_w = f"{GV2}/{folder}/weapon/fp_{prefix}_add_overarm_throw_weapon"
    WEAPONS[gid] = {
        "gun": f"ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/New_Weapons/Grenades/Meshes/SK_Grenade_{folder}_V2",
        "ads_eye": [0.0, None, 0.0],
        "tp": {"back": 0.0, "yaw": 0.0},
        # the grenade leaves the hand this far into the throw clips (GrenadeClient releases at the same time)
        "hide_after": {"throw": 0.24, "throw_under": 0.34},
        "actions": {
            "draw":              (f"{A}/FP/STING/base_male/fp_stinger_add_draw" if gid == "stinger" else f"{FL}/fp_flashbang_add_draw", None),
            "idle":              (f"{FL}/fp_flashbang_idle_pose", (start_w, "first")),
            "throw_start":       (f"{FL}/fp_flashbang_add_overarm_throw_start", start_w),
            "throw":             (f"{FL}/fp_flashbang_add_overarm_throw", throw_w),
            "throw_under_start": (f"{STR}/fp_stinger_add_underarm_throw_start", start_w),
            "throw_under":       (f"{STR}/fp_stinger_add_underarm_throw", (start_w, "last")),
        },
    }

# Ballistic shield: RoN's Five-seven shield arms carry the shield on ShieldAttachSocket (left forearm); our pistols ride
# weapon_right as usual. Right click raises it (idle_up), the ADS correction comes from the raised pose.
SHB = f"{A}/FP/Shield/Fiveseven/base_male"
SHR = f"{A}/Revised/FirstPerson/Weapons/Fiveseven/arms/shield"
SHIELD_MESH = "ReadyOrNot/Content/ReadyOrNot/Assets/Devices/Deployables/Ballistic_Shield/Mesh/SK_Device_Ballistic_Shield"
for base_id, reload_w in (("g19", {"reload_tac": f"{A}/FP/Shield/G19/ron_g19/fp_g19_add_shield_reload_low_weapon",
                                   "reload_empty": f"{A}/FP/Shield/G19/ron_g19/fp_g19_add_shield_reload_empty_low_weapon",
                                   "reload_tac_up": f"{A}/FP/Shield/G19/ron_g19/fp_g19_add_shield_reload_up_weapon",
                                   "reload_empty_up": f"{A}/FP/Shield/G19/ron_g19/fp_g19_add_shield_reload_empty_UP_weapon"}),
                          ("python", {"reload_tac": f"{A}/FP/Shield/Python/ron_python/fp_python_add_shield_reload_low_weapon",
                                      "reload_empty": f"{A}/FP/Shield/Python/ron_python/fp_python_add_shield_reload_low_weapon",
                                      "reload_tac_up": f"{A}/FP/Shield/Python/ron_python/fp_python_add_shield_reload_up_weapon",
                                      "reload_empty_up": f"{A}/FP/Shield/Python/ron_python/fp_python_add_shield_reload_up_weapon"})):
    src = WEAPONS[base_id]
    wa = {k: v[1] for k, v in src["actions"].items()}
    rig = {k: v for k, v in src.items() if k not in ("actions", "tp")}
    rig.update({
        "tp": {"back": 0.0},
        "arms_attachment": {"mesh": SHIELD_MESH, "bone": "ShieldAttachSocket"},
        "ads_from": "idle_up", "ads_time": 0.45,
        "actions": {
            "draw":        (f"{SHB}/fp_fiveseven_add_shield_low_draw", None),
            "idle":        (f"{SHB}/fp_fiveseven_shield_low_idle_pose", wa.get("idle")),
            "idle_empty":  (None, wa.get("idle_empty")),
            "idle_up":     (f"{SHB}/fp_fiveseven_add_shield_up_idle", None),
            "walk_up":     (f"{SHB}/fp_fiveseven_add_shield_up_walk", None),
            "raise":       (f"{SHB}/fp_fiveseven_add_shield_low_to_up", None),
            "lower":       (f"{SHB}/fp_fiveseven_add_shield_up_to_low", None),
            "fire":        (f"{SHB}/fp_fiveseven_add_shield_up_fire", wa.get("fire")),
            "fire_last":   (f"{SHB}/fp_fiveseven_add_shield_up_fire", wa.get("fire_last")),
            "dryfire":     (f"{SHR}/fp_fiveseven_add_shield_up_dryfire", wa.get("dryfire")),
            "bash":        (f"{SHB}/fp_fiveseven_shield_low_bash", None),
            "bash_up":     (f"{SHB}/fp_fiveseven_shield_up_bash", None),
            **{k: (f"{SHB}/fp_shield_reload_generic", v) for k, v in reload_w.items()},
        },
    })
    rig["actions"] = {k: v for k, v in rig["actions"].items() if v[0] or v[1]}
    WEAPONS[base_id + "_shield"] = rig
    FX[base_id + "_shield"] = FX[base_id]

# item-only rigs (inventory / hands / ground models): the shield and the goggles as the "gun" mesh
WEAPONS["shield"] = {"gun": SHIELD_MESH, "glass": True, "ads_eye": [0.0, None, 0.0], "tp": {"back": 0.0, "yaw": 0.0, "offset": [0.0, 0.0, 0.0]},
                     "actions": {"idle": (f"{SHB}/fp_fiveseven_shield_low_idle_pose", None)}}
WEAPONS["nvg"] = {"gun": "ReadyOrNot/Content/ReadyOrNot/Assets/Devices/Nightvision/Meshes/SK_Equipment_SWAT_NVG", "ads_eye": [0.0, None, 0.0],
                  "tp": {"back": 0.0, "yaw": 0.0}, "actions": {"idle": (f"{FL}/fp_flashbang_idle_pose", None)}}

# RoN game-feel reactions (arms only) for every hand-held rig
AFF = f"{A}/FP/Gamefeel/base_male"
for wid, w in WEAPONS.items():
    if wid in ("shield", "nvg"):
        continue
    for kind, name in (("flash", "Flashbang"), ("gas", "CS_Gas"), ("sting", "Stinger")):
        for part in ("start", "loop", "end"):
            w["actions"].setdefault(f"aff_{kind}_{part}", (f"{AFF}/{name}_Affected_{part}", None))

# ---------------- v9: HK USP45, SA-58 OSW, foregrips
USPA, USPW = f"{A}/Revised/FirstPerson/Weapons/USP/Arms", f"{A}/Revised/FirstPerson/Weapons/USP/Weapon"
FALB, FALW = f"{A}/FP/FAL_OSW/body", f"{A}/FP/FAL_OSW/weapon"
GRIP_MESH = {"afg": f"{ATT}/Mesh/Foregrip_AFG", "vfg": f"{ATT}/Mesh/SK_Grip_VFG", "stubby": f"{ATT}/Mesh/Foregrip_Stubby"}
SA58_GUN = "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/SA-58/SK_Rif_FNFAL_OSW"
M4_GUN = "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/M4_Rifle/Mesh/SK_WP_Rifle_M4"
M590_GUN = "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/M590/Meshes/SK_Shotgun_M590_Assault"
# Where the left hand sits on each grip: taken from a RoN idle pose made for that grip (hand relative to the grip socket),
# so one reference serves every gun with the same socket. ref = (arms clip, weapon idle clip, gun mesh, socket)
SA58_IDLE_W = f"{FALW}/fp_fal_idle_pose_weapon"
GRIP_REF = {
    "afg": (f"{FALB}/fp_fal_idle_pose_afg", SA58_IDLE_W, SA58_GUN, "foregrip_socket"),
    "vfg": (f"{FALB}/fp_fal_idle_pose_vfg", SA58_IDLE_W, SA58_GUN, "foregrip_socket"),
    "stubby": (f"{FALB}/fp_fal_idle_pose_stubby", SA58_IDLE_W, SA58_GUN, "foregrip_socket"),
}
WEAPONS["m4a1"]["grips"] = {
    "afg": {"socket": "foregrip_socket", "ref": (f"{M4FP}/arms/fp_m4a1_idle_pose_afg", f"{M4FP}/weapon/fp_m4a1_idle_pose_weapon", M4_GUN, "foregrip_socket")},
    "vfg": {"socket": "foregrip_socket"},
    "stubby": {"socket": "foregrip_socket"},
}
WEAPONS["m590"]["grips"] = {
    "vfg": {"socket": "foregrip_socket", "ref": (f"{M590A}/fp_m590_idle_pose_vfg", (f"{M590W}/fp_m590_add_fire_weapon", "first"), M590_GUN, "foregrip_socket")},
}
WEAPONS["m4a1"]["magcheck"] = {"socket": "mag_01_bullets_socket"}
WEAPONS["g19"]["magcheck"] = {"socket": "tag_mag_01_bullets_socket"}
WEAPONS["python"]["magcheck"] = {"socket": "mag_01_bullets_socket"}
WEAPONS["usp45"] = {
    "gun": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/USP/Meshes/SK_Pistol_USP",
    # front sight top z 3.79 (y -9), rear sight top z 3.79 (y 6)
    "ads_eye": [0.0, None, 3.78],
    "attachments": [
        {"mesh": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/USP/Meshes/SM_USP_Magazine_Static",
         "file": "mag", "sockets": ["tag_mag_01_Socket", "tag_mag_02_Socket"]},
    ],
    "optics": [
        {"id": "rmr", "mesh": f"{ATT}/Optic_RMR/sk_optic_rmr_pistol", "socket": "RMRPistol_Socket", "style": "dot"},
        {"id": "sro", "mesh": f"{ATT}/Optic_SRO/sk_optic_sro_glock", "socket": "SROPistol_Socket", "style": "dot"},
    ],
    "suppressor": {"mesh": f"{ATT}/Mesh/Pistol_SuppressorSmall", "socket": "supressor_socket"},
    "light": {"mesh": f"{ATT}/Light_X300/SK_Pistol_light_X300", "socket": "light_socket"},
    "magcheck": {"socket": "tag_mag_01_bullets_Socket"},
    "tp": {"back": 0.0},
    "actions": {
        # RoN has no USP draw / arms fire clip: the Glock draw (same grip) and idle arms + procedural kick
        "draw":         (f"{G19FP}/arms/fp_g19_add_draw", None),
        "idle":         (f"{USPA}/fp_usp_idle_pose", f"{USPW}/fp_usp_idle_pose_weapon"),
        "idle_empty":   (None, f"{USPW}/fp_usp_idle_pose_empty_weapon"),
        "run":          (f"{A}/FP/SHARED/pistol_sprint/fp_g19_add_loco_sprint_fwd", None),
        "fire":         (None, f"{USPW}/fp_usp_add_fire_single_weapon"),
        "fire_last":    (None, f"{USPW}/fp_usp_add_fire_single_last_weapon"),
        "reload_tac":   (f"{USPA}/fp_usp_add_tac_reload", f"{USPW}/fp_usp_add_tac_reload_weapon"),
        "reload_empty": (f"{USPA}/fp_usp_add_spd_reload_empty", f"{USPW}/fp_usp_add_spd_reload_empty_weapon"),
        "magcheck":     (f"{USPA}/fp_usp_add_mag_check", f"{USPW}/fp_usp_add_mag_check_weapon"),
    },
}
WEAPONS["sa58"] = {
    "gun": SA58_GUN,
    # front post tip z 7.09 (y -37), rear aperture ~7.1 (y 9); eye 6 cm behind the aperture
    "ads_eye": [0.0, 15.0, 7.09],
    "attachments": [
        {"mesh": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/SA-58/SM_Rif_FNFAL_OSW_mag_static",
         "file": "mag", "sockets": ["tag_mag_01_Socket", "tag_mag_02_Socket"]},
        {"mesh": "ReadyOrNot/Content/ReadyOrNot/Assets/Weapons/SA-58/SM_Rif_FNFAL_OSW_bullet_fmj_static",
         "file": "rounds", "sockets": ["tag_mag_01_bullets_Socket", "tag_mag_02_bullets_Socket"]},
    ],
    "optics": [
        {"id": "exps3", "mesh": f"{ATT}/Optic_EPXS3/Holosight_EXPS3", "socket": "EXPS3_socket", "style": "holo"},
        {"id": "m5b", "mesh": f"{ATT}/Optic_M5B/sk_optic_m5", "socket": "M5B_Socket", "style": "dot"},
        {"id": "t2", "mesh": f"{ATT}/Optic_MicroT2/SK_MicroT2_TallMount", "socket": "MicroT2Raised_Socket", "style": "dot"},
    ],
    "suppressor": {"mesh": f"{ATT}/Suppressor_SOCOM338/sk_suppressor_socom338", "socket": "socom338_socket"},
    # the SA-58 light_socket is the M4 one turned 180 deg about the vertical: turn the M600V back so it matches the M4
    "light": {"mesh": f"{ATT}/Flashlight_M600V/sk_illum_flashlight_M600V", "socket": "light_socket", "rot": [0, 180, 0]},
    "grips": {"afg": {"socket": "foregrip_socket"}, "vfg": {"socket": "foregrip_socket"}, "stubby": {"socket": "foregrip_socket"}},
    "magcheck": {"socket": "tag_mag_01_bullets_Socket"},
    "tp": {"back": 0.32},
    "actions": {
        "draw":         (f"{FALB}/fp_fal_add_draw_first", f"{FALW}/fp_fal_add_draw_first_weapon"),
        "idle":         (f"{FALB}/fp_fal_idle_pose", SA58_IDLE_W),
        "idle_empty":   (None, f"{FALW}/fp_fal_idle_pose_empty_weapon"),
        # no exportable arms fire / walk clips: idle arms + procedural kick, the bolt still cycles
        "fire":         (None, f"{FALW}/fp_fal_add_fire_weapon"),
        "fire_last":    (None, f"{FALW}/fp_fal_add_fire_empty_weapon"),
        "reload_tac":   (f"{FALB}/fp_fal_add_tac_reload", f"{FALW}/fp_fal_add_tac_reload_weapon"),
        "reload_empty": (f"{FALB}/fp_fal_add_spd_reload_empty", f"{FALW}/fp_fal_add_spd_reload_empty_weapon"),
        "magcheck":     (f"{FALB}/fp_fal_add_mag_check", f"{FALW}/fp_fal_add_mag_check_weapon"),
        "select_auto":  (f"{FALB}/fp_fal_add_fire_select_auto", None),
        "select_semi":  (f"{FALB}/fp_fal_add_fire_select_semi", None),
    },
}
for wid in ("usp45", "sa58"):
    for kind, name in (("flash", "Flashbang"), ("gas", "CS_Gas"), ("sting", "Stinger")):
        for part in ("start", "loop", "end"):
            WEAPONS[wid]["actions"].setdefault(f"aff_{kind}_{part}", (f"{AFF}/{name}_Affected_{part}", None))

# ---------------- v10: RoN's own attachment lists per weapon (Available*Attachments of the weapon blueprints)
D4ATT = "ReadyOrNot/Plugins/GameFeatures/ReadyOrNotDLC4/Content/Weapons/Attachments"
OPTIC = {   # id: mesh, style, zoom, reticle material (substring, default "picture")
    "exps3": (f"{ATT}/Optic_EPXS3/Holosight_EXPS3", "holo", 1.0, None),
    "t2": (f"{ATT}/Optic_MicroT2/V2/SK_T2_highmount", "dot", 1.0, None),
    "m5b": (f"{ATT}/Optic_M5B/sk_optic_m5", "dot", 1.0, None),
    "srs": (f"{ATT}/Optic_SRS/SK_Optic_SRS", "dot", 1.0, None),
    "srs_r": (f"{ATT}/Riser/sk_optic_srs_raised", "dot", 1.0, None),
    "hs510c": (f"{ATT}/Optic_Holosun510C/Holosight_HS510CDot", "holo", 1.0, None),
    "bossxe": (f"{ATT}/Optic_BossXE/Holosight_BossXEDot", "holo", 1.0, None),
    "sdr": (f"{ATT}/Optic_SpecterDR/SK_Optic_SpecterDR", "scope", 4.0, "picture_specterdr"),
    "atacr": (f"{ATT}/Optic_ATACR/Atacr_RMR/sk_optic_atacr_scope_rmr", "scope", 6.0, "picture_atacr"),
    "mrohd": (f"{D4ATT}/Optic_MROHDMagnifier/SK_MRO_Sight_with_3X_Magnifier", "dot", 3.0, "mro_hd_magnifier"),
    "venom": (f"{ATT}/Mesh/Venom_TallMount", "dot", 1.0, None),
    "t1": (f"{ATT}/Mesh/MicroT1_TallMount", "dot", 1.0, None),
    "rmr": (f"{ATT}/Optic_RMR/sk_optic_rmr_pistol", "dot", 1.0, None),
    "rmr_g": (f"{ATT}/Optic_RMR/sk_optic_rmr_glock", "dot", 1.0, None),
    "sro": (f"{ATT}/Optic_SRO/sk_optic_sro_pistol", "dot", 1.0, None),
    "sro_g": (f"{ATT}/Optic_SRO/sk_optic_sro_glock", "dot", 1.0, None),
}
MUZZLE = {  # id: mesh, suppressor?
    "socom338": (f"{ATT}/Suppressor_SOCOM338/sk_suppressor_socom338", True),
    "asr": (f"{ATT}/Brake_ASR/sk_brake_ASR338", False),
    "sfmb": (f"{ATT}/Brake_SFMB/sk_brake_sfmb", False),
    "kkm": (f"{ATT}/compensators/KKMComp", False),
    "gm9": (f"{ATT}/Suppressor_GM9/SK_suppressor_gm9", True),
    "obsidian": (f"{ATT}/Obsidian45/SK_ObsSuppressor", True),
    "osprey": (f"{ATT}/Suppressor_Osprey/sk_suppressor_osprey", True),
    "choke": (f"{ATT}/Mesh/SMG_Compensator", False),
}
GRIP_MESH.update({"combat": f"{ATT}/Grip_Combat/sm_mod3", "rk1": f"{ATT}/Grip_RK1/Grip_RK1", "cqr": f"{ATT}/Grip_CQR/Grip_CQR"})
# RoN's CQR / RK1 / combat grip poses are ACL-only: the hand takes the nearest grip pose that exports, on the foregrip socket
GRIP_REF.update({"combat": GRIP_REF["vfg"], "rk1": GRIP_REF["afg"], "cqr": GRIP_REF["vfg"]})

def optics(*spec):
    return [{"id": i.split("@")[0], "socket": sock, "hide": hide} for i, sock, hide in spec]

M4HIDE = ["Weapon_1P_M4A1_CARRYHANDLE_INST"]
WEAPONS["m4a1"]["optics"] = optics(("exps3", "EXPS3_Socket", M4HIDE), ("t2", "MicroT2Raised_Socket", M4HIDE), ("m5b", "M5B_Socket", M4HIDE),
                                   ("srs_r", "SRSRaised_Socket", M4HIDE), ("sdr", "sdr_socket", M4HIDE))
WEAPONS["m4a1"]["muzzles"] = [("socom338", "socom338_socket", "tag_muzzle"), ("asr", "asr_socket", "tag_muzzle"), ("sfmb", "sfmb_socket", "tag_muzzle")]
WEAPONS["m4a1"]["grips"] = {"vfg": {"socket": "foregrip_socket"},
                            "afg": {"socket": "foregrip_socket", "ref": WEAPONS["m4a1"]["grips"]["afg"]["ref"]},
                            "combat": {"socket": "combat_grip", "hand_socket": "foregrip_socket"}}
WEAPONS["sa58"]["optics"] = optics(("srs", "Reflex_Socket", []), ("t2", "MicroT2Raised_Socket", []), ("m5b", "M5B_Socket", []),
                                   ("exps3", "EXPS3_socket", []), ("hs510c", "HS510C_socket", []), ("bossxe", "Bossxe_socket", []),
                                   ("sdr", "sdr_Socket", []), ("atacr", "atac_Socket", []), ("mrohd", "mrohd_socket", []))
WEAPONS["sa58"]["muzzles"] = [("sfmb", "sfmb_socket", "tag_barrel_2"), ("asr", "asr_socket", "tag_barrel_2"), ("socom338", "socom338_socket", "tag_muzzle")]
WEAPONS["sa58"]["grips"] = {"afg": {"socket": "foregrip_socket"}, "vfg": {"socket": "foregrip_socket"},
                            "combat": {"socket": "combat_grip", "hand_socket": "foregrip_socket"},
                            "rk1": {"socket": "rk1_socket", "hand_socket": "foregrip_socket"},
                            "cqr": {"socket": "cqr_socket", "hand_socket": "foregrip_socket"}}
WEAPONS["g19"]["optics"] = optics(("sro_g", "SROPistol_Socket", []), ("rmr_g", "RMRPistol_Socket", []))
# RoN hangs the GM9 on a socket this G19 mesh does not have: the standard suppressor socket, turned to the bore by the builder
WEAPONS["g19"]["muzzles"] = [("kkm", "comp_socket", None), ("gm9", "supressor_socket", None)]
WEAPONS["usp45"]["optics"] = optics(("rmr", "RMRPistol_Socket", []), ("sro", "SROPistol_Socket", []))
WEAPONS["usp45"]["muzzles"] = [("osprey", "supressor_socket", None), ("obsidian", "obs_suppressor_socket", None)]
WEAPONS["python"]["optics"] = []
WEAPONS["m590"]["optics"] = optics(("venom", "Reflex_Socket", []), ("t1", "Reflex_Socket", []), ("t2", "MicroT2Raised_socket", []))
WEAPONS["m590"]["muzzles"] = [("choke", "Choke_Socket", None), ("osprey", "supressor_socket", None)]
for w in WEAPONS.values():
    for op in w.get("optics", []):
        if "mesh" not in op and op["id"] in OPTIC:
            op["mesh"], op["style"], op["zoom"], op["picture"] = OPTIC[op["id"]]
    if "muzzles" in w:
        w.pop("suppressor", None)
for k in ("g19_shield", "python_shield"):
    if k in WEAPONS:
        base = WEAPONS[k.split("_")[0]]
        for key in ("optics", "muzzles"):
            WEAPONS[k][key] = base.get(key, [])
        WEAPONS[k].pop("suppressor", None)

RETICLE_RGB = (255, 36, 24)
SKIP_MATERIAL = ("glass", "invisible", "pip_scope", "clear_inst")


def quat_mat(q, pos):
    x, y, z, w = q
    m = np.eye(4)
    m[:3, :3] = [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                 [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                 [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]
    m[:3, 3] = pos
    return m


def pose_globals(mesh, spec, frame=0):
    """globals + locals (pos, quat in the runtime convention) of one frame of a clip on a mesh; spec = path or (path, first|last)"""
    rel, pick = (spec, None) if isinstance(spec, str) else spec
    p = exported(rel, ".psa")
    if not p:
        return None, None
    a = read_psa(p)
    if pick == "last":
        frame = a.frames - 1
    idx = {n.lower(): j for j, n in enumerate(a.bones)}
    locs, _ = bind_data(mesh)
    G, L = [], []
    for i, b in enumerate(mesh.bones):
        j = idx.get(b.name.lower())
        if j is None:
            pos, q = locs[i]
        else:
            pos = np.array(a.pos[frame, j], dtype=np.float64)
            q = np.array(a.quat[frame, j], dtype=np.float64)
            if j != 0:
                q[:3] *= -1
            q = q / np.linalg.norm(q)
        M = quat_mat(q, pos)
        G.append(M if b.parent < 0 else G[b.parent] @ M)
        L.append((np.array(pos, dtype=np.float64), np.array(q, dtype=np.float64)))
    return G, L


def bone_index(mesh, name):
    for i, b in enumerate(mesh.bones):
        if b.name.lower() == name.lower():
            return i
    return -1


def descendants(mesh, root):
    out, keep = [], {root}
    for i, b in enumerate(mesh.bones):
        if b.parent in keep:
            keep.add(i)
            out.append(i)
    return out


def grip_hand(arms, ref):
    """left hand relative to the grip socket (4x4, cm) and the finger locals of a RoN grip idle pose"""
    arms_clip, weapon_clip, gun_rel, socket = ref
    GA, LA = pose_globals(arms, arms_clip)
    gun_p = exported(gun_rel, ".psk")
    if GA is None or not gun_p:
        return None
    gun = read_psk(gun_p)
    GG, _ = pose_globals(gun, weapon_clip)
    if GG is None:
        _, inv = bind_data(gun)
        GG = [np.linalg.inv(m) for m in inv]
    si = bone_index(gun, socket)
    wr, hand = bone_index(arms, "weapon_right"), bone_index(arms, "hand_LE")
    if si < 0:
        return None
    off = np.linalg.inv(GA[wr] @ GG[si]) @ GA[hand]
    fingers = []
    for i in descendants(arms, hand):
        pos, q = LA[i]
        fingers.append([arms.bones[i].name] + [round(float(v), 5) for v in list(pos) + list(q)])
    return off, fingers


def rotate_mesh(m, deg):
    """turn a rigid attachment mesh in its own space (x, y, z degrees, applied x then y then z)"""
    rx, ry, rz = [np.radians(d) for d in deg]
    Rx = np.array([[1, 0, 0], [0, np.cos(rx), -np.sin(rx)], [0, np.sin(rx), np.cos(rx)]])
    Ry = np.array([[np.cos(ry), 0, np.sin(ry)], [0, 1, 0], [-np.sin(ry), 0, np.cos(ry)]])
    Rz = np.array([[np.cos(rz), -np.sin(rz), 0], [np.sin(rz), np.cos(rz), 0], [0, 0, 1]])
    R = Rz @ Ry @ Rx
    m.points = [tuple(float(v) for v in R @ np.array(p, dtype=np.float64)) for p in m.points]
    if m.normals is not None:
        m.normals = [tuple(float(v) for v in R @ np.array(n, dtype=np.float64)) for n in m.normals]
    return R


def orient_muzzle(m, S):
    """RoN muzzle devices hang off the muzzle along the bore (gun -Y). When a device sits on a socket made for another one,
    turn it (axis-aligned, in its own space) until its long axis runs along the bore and it reaches forward."""
    P = np.array(m.points, dtype=np.float64)
    def score(deg):
        R = rotate_matrix(deg)
        Q = (np.c_[P @ R.T, np.ones(len(P))] @ S.T)[:, :3]
        ext = Q.max(0) - Q.min(0)
        along = ext[1] / max(ext.max(), 1e-6)
        fwd = S[1, 3] - Q[:, 1].min()          # how far it reaches past the socket towards the muzzle
        back = Q[:, 1].max() - S[1, 3]
        return along * 10 + (fwd > back) * 5 + (deg == [0, 0, 0]) * 0.5
    cands = [[0, 0, 0], [0, 0, 90], [0, 0, -90], [0, 0, 180], [90, 0, 0], [-90, 0, 0], [180, 0, 0], [0, 180, 0]]
    best = max(cands, key=score)
    if best != [0, 0, 0]:
        rotate_mesh(m, best)
    return best


def rotate_matrix(deg):
    rx, ry, rz = [np.radians(d) for d in deg]
    Rx = np.array([[1, 0, 0], [0, np.cos(rx), -np.sin(rx)], [0, np.sin(rx), np.cos(rx)]])
    Ry = np.array([[np.cos(ry), 0, np.sin(ry)], [0, 1, 0], [-np.sin(ry), 0, np.cos(ry)]])
    Rz = np.array([[np.cos(rz), -np.sin(rz), 0], [np.sin(rz), np.cos(rz), 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def socket_matrix(gun, name):
    """bind global of a gun socket, relative to J_Gun (bone 0)"""
    _, inv = bind_data(gun)
    G = [np.linalg.inv(i) for i in inv]
    for i, b in enumerate(gun.bones):
        if b.name.lower() == name.lower():
            return np.linalg.inv(G[0]) @ G[i]
    return None


def prepare_optic(mesh, S, style, eye_dist_cm, png_path, picture=None):
    """Marks lens / helper materials #skip and the reticle plane #reticle (planar UVs, centre = (0.5, 0.5)).
    Returns the reticle centre in gun space (cm) or None. The reticle texture is our own drawing, sized in angle at the eye."""
    pts = np.array(mesh.points, dtype=np.float64)
    wp = np.array(mesh.wedge_point)
    fm = np.array(mesh.face_mat)
    F = np.array(mesh.faces)
    uv = np.array(mesh.wedge_uv, dtype=np.float64).copy()
    centre = None
    names = list(mesh.materials)
    want = None
    if picture:
        want = next((i for i, n in enumerate(names) if picture in n.lower()), None)
    for mi, mn in enumerate(names):
        low = mn.lower()
        if want is not None and "picture" in low and mi != want:
            names[mi] = "#skip " + mn
        elif any(k in low for k in SKIP_MATERIAL):
            names[mi] = "#skip " + mn
        elif "picture" in low and centre is None:
            wedges = np.unique(F[fm == mi].flatten())
            if not len(wedges):
                continue
            q = pts[wp[wedges]]
            lo, hi = q.min(0), q.max(0)
            c = (lo + hi) / 2
            ax = np.argsort(hi - lo)[::-1][:2]          # the two in-plane axes
            size = float((hi - lo)[ax].max()) or 1.0
            for w in wedges:
                p = pts[wp[w]]
                uv[w] = (0.5 + (p[ax[0]] - c[ax[0]]) / size, 0.5 - (p[ax[1]] - c[ax[1]]) / size)
            draw_reticle(png_path, style, size, eye_dist_cm)
            centre = (S @ np.append(c, 1.0))[:3]
            names[mi] = "#reticle " + mn
        elif "picture" in low:
            names[mi] = "#skip " + mn
    mesh.materials = names
    mesh.wedge_uv = uv
    return centre


def draw_reticle(path, style, size_cm, dist_cm, res=512):
    """dot: 1 MRAD-ish dot; holo: 65 MOA ring + 1 MOA-ish dot (exaggerated a bit so it reads at Minecraft resolutions)"""
    px = res / size_cm
    deg = lambda a: dist_cm * np.tan(np.radians(a)) * px     # angle -> pixels on the plane
    y, x = np.mgrid[0:res, 0:res]
    r = np.hypot(x - res / 2 + 0.5, y - res / 2 + 0.5)
    def disc(radius):
        return np.clip(radius + 0.75 - r, 0, 1.5) / 1.5
    a = disc(max(1.6, deg(0.09)))
    if style == "holo":
        rr, w = deg(0.54), max(1.2, deg(0.035))
        a = np.maximum(a, np.clip(w + 0.75 - np.abs(r - rr), 0, 1.5) / 1.5)
    if style == "scope":
        # thin black crosshair with thick outer posts and a small lit centre dot
        cx, cy = np.abs(x - res / 2 + 0.5), np.abs(y - res / 2 + 0.5)
        thin, thick = max(1.0, res / 400), max(3.0, res / 90)
        cross = ((cx < thin) | (cy < thin)) & (r > deg(0.04))
        posts = ((cx < thick) & (cy > res * 0.18)) | ((cy < thick) & (cx > res * 0.18))
        black = np.zeros((res, res, 4), np.uint8)
        black[..., 3] = ((cross | posts) * 235).astype(np.uint8)
        lit = np.clip(max(1.4, deg(0.05)) + 0.75 - r, 0, 1.5) / 1.5
        black[..., 0], black[..., 1], black[..., 2] = (np.array(RETICLE_RGB)[:, None, None] * (lit > 0)).astype(np.uint8)
        black[..., 3] = np.maximum(black[..., 3], (lit * 255).astype(np.uint8))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        Image.fromarray(black, "RGBA").save(path, optimize=True)
        return
    glow = np.exp(-(r / max(2.0, deg(0.16))) ** 2) * 0.35
    a = np.clip(np.maximum(a, glow), 0, 1)
    im = np.zeros((res, res, 4), np.uint8)
    im[..., 0], im[..., 1], im[..., 2] = RETICLE_RGB
    im[..., 3] = (a * 255).astype(np.uint8)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(im, "RGBA").save(path, optimize=True)


def utf(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def exported(rel, ext):
    p = os.path.join(EXPORT, *rel.split("/")) + ext
    if os.path.exists(p):
        return p
    if ext == ".psk" and os.path.exists(p + "x"):
        return p + "x"
    return None


def find_one(name):
    hits = glob.glob(os.path.join(EXPORT, "**", name), recursive=True)
    return hits[0] if hits else None


def material_texture(mat):
    """Base colour texture for a material, from the material JSON CUE4Parse wrote."""
    j = find_one(mat + ".json")
    if not j:
        return None
    tex = json.load(open(j, encoding="utf-8")).get("Textures", {})
    names = []
    for key, v in tex.items():
        name = v.get("ObjectName", "").split("'")[1] if "'" in v.get("ObjectName", "") else key
        names.append((key, name))
    for pref in (lambda k, n: k == "B", lambda k, n: "basecolor" in n.lower(), lambda k, n: n.lower().endswith("_b"),
                 lambda k, n: "diffuse" in k.lower() or "albedo" in k.lower()):
        for k, n in names:
            if pref(k, n) and find_one(n + ".png"):
                return find_one(n + ".png")
    return None


class Textures:
    def __init__(self, pack_dir):
        self.pack_dir, self.written, self.reticles = pack_dir, {}, {}

    def for_material(self, mat, folder):
        if mat.startswith("#skip"):
            return f"{NS}:textures/ron/skip.png"
        if mat.startswith("#reticle"):
            return self.reticles[folder]
        if mat.startswith("#glass"):
            dst = os.path.join(self.pack_dir, "assets", NS, "textures", "ron", "glass.png")
            if not os.path.exists(dst):
                Image.new("RGBA", (4, 4), (170, 190, 200, 255)).save(dst)
            return f"{NS}:textures/ron/glass.png"
        src = material_texture(mat)
        if not src:
            print(f"  ! no base colour for material {mat}, using a grey placeholder")
            src = os.path.join(OUT, "_grey.png")
            Image.new("RGBA", (4, 4), (90, 90, 90, 255)).save(src)
            ao = find_one("Zipcuffs_AO.png") if folder == "zipcuffs" else None
            if ao:  # Zipcuff_M is a flat black plastic: darken its AO map instead of plain grey
                src = os.path.join(OUT, "_zipcuffs.png")
                g = Image.open(ao).convert("L").point(lambda v: int(14 + v * 0.16))
                Image.merge("RGBA", (g, g, g.point(lambda v: min(255, v + 2)), Image.new("L", g.size, 255))).save(src)
        name = "".join(c if c.isalnum() or c in "_-." else "_" for c in os.path.splitext(os.path.basename(src))[0].lower())
        key = f"{folder}/{name}"
        if key not in self.written:
            im = Image.open(src).convert("RGBA")
            if max(im.size) > MAX_TEX:
                s = MAX_TEX / max(im.size)
                im = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.LANCZOS)
            im.putalpha(255)  # base colour alpha in RoN is not opacity
            dst = os.path.join(self.pack_dir, "assets", NS, "textures", "ron", folder, name + ".png")
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            im.save(dst, optimize=True)
            self.written[key] = True
        return f"{NS}:textures/ron/{folder}/{name}.png"


def bind_data(mesh):
    """Local bind (pos, fixed quat) and inverse bind globals."""
    locs, glob_ = [], []
    for i, b in enumerate(mesh.bones):
        q = b.quat if b.parent < 0 else conj(b.quat)
        q = q / np.linalg.norm(q)
        m = np.eye(4)
        x, y, z, w = q
        m[:3, :3] = [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]
        m[:3, 3] = b.pos
        glob_.append(m if b.parent < 0 else glob_[b.parent] @ m)
        locs.append((b.pos, q))
    return locs, [np.linalg.inv(g) for g in glob_]


def write_mesh(mesh, path, tex_for_material, rigid):
    for i, b in enumerate(mesh.bones):
        assert b.parent < i, "parents must precede children"
    per_point = {}
    for p, b, w in mesh.weights:
        per_point.setdefault(p, []).append((w, b))
    out = bytearray(b"RONM" + struct.pack(">i", 1))
    if not mesh.bones:
        # static mesh (magazine, rounds): one identity root bone so the renderer always has bone 0
        out += struct.pack(">i", 1) + utf("root") + struct.pack(">i", -1)
        out += struct.pack(">3f", 0, 0, 0) + struct.pack(">4f", 0, 0, 0, 1)
        out += struct.pack(">16f", *np.eye(4).T.flatten())
    else:
        locs, inv = bind_data(mesh)
        out += struct.pack(">i", len(mesh.bones))
        for i, b in enumerate(mesh.bones):
            pos, q = locs[i]
            out += utf(b.name) + struct.pack(">i", b.parent)
            out += struct.pack(">3f", *pos) + struct.pack(">4f", *q)
            out += struct.pack(">16f", *inv[i].T.flatten())  # column-major for JOML
    out += struct.pack(">i", len(mesh.materials))
    for m in mesh.materials:
        out += utf(m) + utf(tex_for_material(m))
    nw = len(mesh.wedge_point)
    normals = mesh.normals
    out += struct.pack(">i", nw)
    for wi in range(nw):
        p = mesh.wedge_point[wi]
        n = normals[p] if normals is not None and len(normals) != nw else (normals[wi] if normals is not None else (0, 0, 1))
        ws = sorted(per_point.get(p, [(1.0, 0)]), reverse=True)
        ws = ws[:1] if rigid else ws[:4]
        tot = sum(w for w, _ in ws) or 1.0
        out += struct.pack(">3f3f2f", *mesh.points[p], *n, *mesh.wedge_uv[wi])
        for k in range(4):
            if k < len(ws):
                out += struct.pack(">hf", ws[k][1], (1.0 if rigid else ws[k][0] / tot))
            else:
                out += struct.pack(">hf", -1, 0.0)
    by_mat = {}
    for fi, f in enumerate(mesh.faces):
        by_mat.setdefault(int(mesh.face_mat[fi]), []).append(f)
    out += struct.pack(">i", len(by_mat))
    for m, faces in sorted(by_mat.items()):
        out += struct.pack(">ii", m, len(faces))
        out += struct.pack(f">{len(faces) * 3}i", *np.array(faces).flatten())
    with open(path, "wb") as fh:
        fh.write(out)
    return nw, len(mesh.faces)


def write_anim(anim, mesh, path, pick=None):
    names = {b.name.lower() for b in mesh.bones}
    keep = [i for i, n in enumerate(anim.bones) if n.lower() in names]
    frames = range(anim.frames)
    if pick == "first":
        frames = [0, 0]
    elif pick == "last":
        frames = [anim.frames - 1, anim.frames - 1]
    frames = list(frames)
    out = bytearray(b"RONA" + struct.pack(">i", 1))
    fps = anim.fps if pick is None else 30.0
    out += struct.pack(">fii", fps, len(frames), len(keep))
    for i in keep:
        out += utf(anim.bones[i])
    data = np.zeros((len(frames), len(keep), 7), dtype=">f4")
    for j, i in enumerate(keep):
        data[:, j, 0:3] = anim.pos[frames, i]
        q = anim.quat[frames, i].copy()
        if i != 0:
            q[:, 0:3] *= -1  # conjugate non-root
        q /= np.linalg.norm(q, axis=1, keepdims=True)
        data[:, j, 3:7] = q
    out += data.tobytes()
    with open(path, "wb") as fh:
        fh.write(out)
    return len(keep), len(frames) / max(fps, 1e-6)


def main():
    global EXPORT, OUT
    args = sys.argv[1:]
    install = None
    if "--install" in args:
        k = args.index("--install"); install = args[k + 1]; del args[k:k + 2]
    if args:
        EXPORT = os.path.abspath(args[0])
    if len(args) > 1:
        OUT = os.path.abspath(args[1])
    pack_dir = os.path.join(OUT, PACK_NAME)
    if os.path.exists(pack_dir):
        shutil.rmtree(pack_dir)
    ron = os.path.join(pack_dir, "assets", NS, "ron")
    os.makedirs(ron)
    json.dump({"pack": {"pack_format": 34, "description": "RoN Weapons: PRIVATE, made from your own Ready or Not install. Do not share."}},
              open(os.path.join(pack_dir, "pack.mcmeta"), "w"), indent=2)
    tex = Textures(pack_dir)
    skip_png = os.path.join(pack_dir, "assets", NS, "textures", "ron", "skip.png")
    os.makedirs(os.path.dirname(skip_png), exist_ok=True)
    Image.new("RGBA", (1, 1), (0, 0, 0, 0)).save(skip_png)
    snd = os.path.join(OUT, "sound_pack")
    all_cues = json.load(open(os.path.join(snd, "cues.json"))) if os.path.isdir(snd) else {}
    if all_cues:
        shutil.copytree(os.path.join(snd, "assets"), os.path.join(pack_dir, "assets"), dirs_exist_ok=True)
        n = sum(len(fs) for _, _, fs in os.walk(os.path.join(snd, "assets", NS, "sounds")))
        print(f"sounds: {n} ogg files")
    else:
        print("  ! no private/sound_pack: run sound_pack.py for RoN sounds")

    arms = read_psk(exported(ARMS_MESH, ".psk"))
    print("arms (shared):", write_mesh(arms, os.path.join(ron, "arms.ronmesh"), lambda m: tex.for_material(m, "arms"), rigid=False))

    for wid, w in WEAPONS.items():
        src = exported(w["gun"], ".psk")
        if not src:
            print(f"== {wid}: gun mesh not exported, skipped")
            continue
        base = os.path.join(ron, wid)
        os.makedirs(os.path.join(base, "anims"))
        tf = lambda m, wid=wid: tex.for_material(m, wid)
        gun = read_psk(src)
        if w.get("glass"):
            gun.materials = ["#glass " + x if "glass" in x.lower() else x for x in gun.materials]
        print(f"== {wid}: gun", write_mesh(gun, os.path.join(base, "gun.ronmesh"), tf, rigid=True))
        meta = {"ads_eye": w["ads_eye"], "attachments": []}
        for att in w.get("attachments", []):
            p = exported(att["mesh"], ".psk")
            if not p:
                print(f"  ! {att['file']}: {att['mesh']} not exported")
                continue
            print(f"   {att['file']}:", write_mesh(read_psk(p), os.path.join(base, att["file"] + ".ronmesh"), tf, rigid=True))
            entry = {"mesh": att["file"], "sockets": att["sockets"]}
            if "hide_y_above" in att:
                entry["hide_y_above"] = att["hide_y_above"]
            meta["attachments"].append(entry)
        if w.get("optics"):
            meta["optics"] = []
        for op in w.get("optics", []):
            p = exported(op["mesh"], ".psk")
            S = socket_matrix(gun, op["socket"])
            if not p or S is None:
                print(f"  ! optic {op['id']}: mesh or socket {op['socket']} missing")
                continue
            m = read_psk(p)
            name = f"optic_{op['id']}"
            folder = f"{wid}_{op['id']}"
            png = os.path.join(pack_dir, "assets", NS, "textures", "ron", folder, "reticle.png")
            tex.reticles[folder] = f"{NS}:textures/ron/{folder}/reticle.png"
            eye_y = w["ads_eye"][1]
            pic_y = (S @ np.array([0, 0, 0, 1.0]))[1]
            c = prepare_optic(m, S, op["style"], (eye_y if eye_y is not None else 30.0) - pic_y, png, op.get("picture"))
            if c is None:
                print(f"  ! optic {op['id']}: no reticle plane, iron-sight eye kept")
                eye = w["ads_eye"]
            else:
                eye = [round(float(c[0]), 3), eye_y, round(float(c[2]), 3)]
            print(f"   optic {op['id']:6s}:", write_mesh(m, os.path.join(base, name + ".ronmesh"), lambda mm, f=folder: tex.for_material(mm, f), rigid=True),
                  "eye", eye)
            meta["optics"].append({"id": op["id"], "mesh": name, "socket": op["socket"], "eye": eye, "hide": op.get("hide", []),
                                   "zoom": op.get("zoom", 1.0)})
        sp = w.get("suppressor")
        if sp:
            p = exported(sp["mesh"], ".psk")
            if p:
                m = read_psk(p)
                m.materials = ["#skip " + x if any(k in x.lower() for k in SKIP_MATERIAL) else x for x in m.materials]
                print("   suppressor:", write_mesh(m, os.path.join(base, "suppressor.ronmesh"), tf, rigid=True))
                meta["suppressor"] = {"mesh": "suppressor", "socket": sp["socket"]}
            else:
                print(f"  ! suppressor {sp['mesh']} not exported")
        if w.get("muzzles"):
            meta["muzzles"] = []
        for mid, msock, hide_bone in w.get("muzzles", []):
            p = exported(MUZZLE[mid][0], ".psk")
            S = socket_matrix(gun, msock)
            if not p or S is None:
                print(f"  ! muzzle {mid}: mesh or socket {msock} missing")
                continue
            m = read_psk(p)
            m.materials = ["#skip " + x if any(k in x.lower() for k in SKIP_MATERIAL) else x for x in m.materials]
            rot = orient_muzzle(m, S)
            fname = f"muzzle_{mid}"
            print(f"   muzzle {mid:9s}:", write_mesh(m, os.path.join(base, fname + ".ronmesh"), tf, rigid=True), "turned", rot)
            meta["muzzles"].append({"id": mid, "mesh": fname, "socket": msock, "suppressor": MUZZLE[mid][1],
                                    "hide_bone": hide_bone if hide_bone and bone_index(gun, hide_bone) >= 0 else None})
        lt = w.get("light")
        if lt:
            p = exported(lt["mesh"], ".psk")
            if p and socket_matrix(gun, lt["socket"]) is not None:
                m = read_psk(p)
                m.materials = ["#skip " + x if any(k in x.lower() for k in SKIP_MATERIAL) else x for x in m.materials]
                if lt.get("rot"):
                    rotate_mesh(m, lt["rot"])
                # beam origin: centre of the light's front face (furthest towards the muzzle, gun -Y), in socket space
                S = socket_matrix(gun, lt["socket"])
                P = np.array(m.points, dtype=np.float64)
                Pg = (np.c_[P, np.ones(len(P))] @ S.T)[:, :3]
                front = Pg[Pg[:, 1] < Pg[:, 1].min() + 0.4].mean(axis=0)
                lens = [round(float(v), 3) for v in (np.linalg.inv(S) @ np.r_[front, 1.0])[:3]]
                print("   light:", write_mesh(m, os.path.join(base, "light.ronmesh"), tf, rigid=True), "lens", lens,
                      "gun-space y", round(float(Pg[:, 1].min()), 1), "..", round(float(Pg[:, 1].max()), 1),
                      "x", round(float(Pg[:, 0].min()), 1), "..", round(float(Pg[:, 0].max()), 1))
                meta["light"] = {"mesh": "light", "socket": lt["socket"], "lens": lens}
            else:
                print(f"  ! light {lt['mesh']} or socket {lt['socket']} missing")
        if w.get("grips"):
            meta["grips"] = {}
        for gid, gs in w.get("grips", {}).items():
            p = exported(GRIP_MESH[gid], ".psk")
            if not p or socket_matrix(gun, gs["socket"]) is None:
                print(f"  ! grip {gid}: mesh or socket {gs['socket']} missing")
                continue
            hand = grip_hand(arms, gs.get("ref", GRIP_REF[gid]))
            if hand is None:
                print(f"  ! grip {gid}: reference pose missing")
                continue
            off, fingers = hand
            gm = read_psk(p)
            print(f"   grip {gid:6s}:", write_mesh(gm, os.path.join(base, f"grip_{gid}.ronmesh"), tf, rigid=True),
                  "hand off", np.round(off[:3, 3], 1), len(fingers), "fingers")
            meta["grips"][gid] = {"mesh": f"grip_{gid}", "socket": gs["socket"], "hand_socket": gs.get("hand_socket", gs["socket"]),
                                  "hand": [round(float(v), 5) for v in off.T.flatten()], "fingers": fingers}
        mc = w.get("magcheck")
        if mc and bone_index(gun, mc["socket"]) >= 0:
            window = None
            spec = w["actions"].get("magcheck", (None, None))[1]
            if spec:
                rel = spec if isinstance(spec, str) else spec[0]
                pa = exported(rel, ".psa")
                if pa:
                    an = read_psa(pa)
                    names = [n.lower() for n in an.bones]
                    # the magazine bone itself (its rounds bone only twitches); else the socket's nearest animated parent
                    bi = bone_index(gun, "tag_mag_01")
                    if bi < 0 or gun.bones[bi].name.lower() not in names:
                        bi = bone_index(gun, mc["socket"])
                        while bi >= 0 and gun.bones[bi].name.lower() not in names:
                            bi = gun.bones[bi].parent
                    if bi > 0:
                        tr = an.pos[:, names.index(gun.bones[bi].name.lower())]
                        d = np.linalg.norm(tr - tr[0], axis=1)
                        moved = np.nonzero(d > 1.5)[0]
                        if len(moved):
                            window = [round(max(0.0, moved[0] / an.fps - 0.05), 2), round(moved[-1] / an.fps + 0.05, 2)]
                            print(f"   magcheck: {gun.bones[bi].name} moves {window[0]}..{window[1]} s (max {d.max():.1f} cm)")
            meta["magcheck"] = {"socket": mc["socket"], "offset": mc.get("offset", [0.0, 0.0, 1.4]), "window": window}
        aa = w.get("arms_attachment")
        if aa:
            p = exported(aa["mesh"], ".psk")
            if p:
                m = read_psk(p)
                m.materials = ["#glass " + x if "glass" in x.lower() else x for x in m.materials]
                print("   arms attachment:", write_mesh(m, os.path.join(base, "shield.ronmesh"), tf, rigid=True))
                meta["arms_attachment"] = {"mesh": "shield", "bone": aa["bone"]}
            else:
                print(f"  ! arms attachment {aa['mesh']} not exported")
        for k in ("hide_after", "ads_from", "ads_time"):
            if k in w:
                meta[k] = w[k]
        if "tp" in w:
            meta["tp"] = w["tp"]
        fx = dict(FX.get(wid, {}))
        if fx:
            p = exported(fx.pop("casing"), ".psk")
            if p:
                print("   casing:", write_mesh(read_psk(p), os.path.join(base, "casing.ronmesh"), tf, rigid=True))
                fx["casing"] = "casing"
            else:
                print(f"  ! {wid}: casing mesh not exported")
            meta["fx"] = fx
        if wid in all_cues:
            meta["cues"] = all_cues[wid]
        json.dump(meta, open(os.path.join(base, "rig.json"), "w"))
        for action, (a_rel, w_rel) in w["actions"].items():
            for kind, spec, mesh in (("arms", a_rel, arms), ("weapon", w_rel, gun)):
                if not spec:
                    continue
                rel, pick = (spec, None) if isinstance(spec, str) else spec
                psa = exported(rel, ".psa")
                if not psa:
                    print(f"  ! {action}/{kind}: {rel}.psa not exported, skipped")
                    continue
                tracks, dur = write_anim(read_psa(psa), mesh, os.path.join(base, "anims", f"{action}_{kind}.ronanim"), pick)
                print(f"   {action:13s} {kind:6s} {tracks:3d} tracks {dur:5.2f}s" + (f" ({pick} frame)" if pick else ""))

    zpath = os.path.join(OUT, PACK_NAME + ".zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for dp, _, files in os.walk(pack_dir):
            for f in files:
                full = os.path.join(dp, f)
                z.write(full, os.path.relpath(full, pack_dir).replace(os.sep, "/"))
    print("pack:", zpath, f"{os.path.getsize(zpath) / 1e6:.1f} MB")
    if install:
        dst = os.path.join(install, "resourcepacks", PACK_NAME + ".zip")
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(zpath, dst)
        print("installed:", dst)


if __name__ == "__main__":
    main()
