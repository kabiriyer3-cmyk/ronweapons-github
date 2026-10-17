package ronweapons;

/**
 * Per-weapon gameplay numbers. The id is also the folder of the weapon in the private pack (assets/ronweapons/ron/&lt;id&gt;/)
 * and the prefix of its sound events (&lt;id&gt;.fire, &lt;id&gt;.fire_1p, &lt;id&gt;.reload, &lt;id&gt;.dryfire, &lt;id&gt;.fire_supp ...).
 *
 * @param clientIntervalNs  shortest time between two shots the client sends
 * @param serverIntervalNs  server-side cap, a bit below the client interval to absorb jitter
 * @param reloadTacTicks    ticks until the mag is refilled on a tactical reload (a little before the clip ends)
 * @param recoilPitch       camera kick per shot in degrees
 * @param viewKick          procedural kick of the first-person rig (0 = the RoN fire clip already has it)
 * @param optics            optic ids the weapon can cycle through; index 0 is always the iron sights ("iron")
 * @param suppressible      has a suppressor (mesh in the pack + &lt;id&gt;.fire_supp sounds)
 * @param pellets           projectiles per shot (shotguns), each doing {@code damage}
 * @param spreadDeg         pellet cone half-angle
 * @param shell             shell-by-shell reload timing (null for magazine weapons)
 * @param grips             foregrip ids the weapon can cycle through; index 0 is always none ("none")
 */
public record WeaponDef(String id, String name, int magSize, float damage, boolean auto,
                        long clientIntervalNs, long serverIntervalNs, int reloadTacTicks, int reloadEmptyTicks,
                        float recoilPitch, float viewKick, String[] optics, boolean suppressible,
                        int pellets, float spreadDeg, ShellReload shell, String[] grips) {

    /** Ticks of the RoN shotgun clips: start (grab a shell), loop (insert + grab next), end (insert last), empty start (chamber one). */
    public record ShellReload(int startTicks, int loopTicks, int endInsertTicks, int emptyStartTicks) {}

    private static final String[] NO_GRIP = {"none"};

    public static final WeaponDef M4A1 = new WeaponDef("m4a1", "M4A1", 30, 7.0f, true,
            75_000_000L, 60_000_000L, 58, 42, 0.7f, 0f,
            new String[] {"iron", "exps3", "m5b", "t2"}, true, 1, 0f, null, new String[] {"none", "afg", "vfg", "stubby"});
    public static final WeaponDef G19 = new WeaponDef("g19", "Glock 19", 15, 5.0f, false,
            90_000_000L, 75_000_000L, 46, 40, 1.4f, 1.0f,
            new String[] {"iron", "rmr", "sro"}, true, 1, 0f, null, NO_GRIP);
    public static final WeaponDef PYTHON = new WeaponDef("python", "Colt Python", 6, 11.0f, false,
            300_000_000L, 270_000_000L, 58, 58, 3.2f, 0.35f,
            new String[] {"iron", "t2"}, false, 1, 0f, null, NO_GRIP);
    /** Mossberg 590A1: 8 rounds of 00 buck, 8 pellets of 3.0 each; the RoN fire clip includes the pump stroke (0.83 s). */
    public static final WeaponDef M590 = new WeaponDef("m590", "Mossberg 590A1", 8, 3.0f, false,
            820_000_000L, 760_000_000L, 0, 0, 5.0f, 0.3f,
            new String[] {"iron"}, false, 8, 2.6f, new ShellReload(14, 14, 14, 50), new String[] {"none", "vfg"});
    /** HK USP45: 12 rounds of .45 ACP; RoN tac reload 2.37 s, empty (speed) reload 1.8 s */
    public static final WeaponDef USP45 = new WeaponDef("usp45", "HK USP45", 12, 6.5f, false,
            100_000_000L, 85_000_000L, 42, 32, 1.8f, 1.0f,
            new String[] {"iron", "rmr", "sro"}, true, 1, 0f, null, NO_GRIP);
    /** SA-58 OSW: 20 rounds of 7.62x51, ~650 rpm; RoN tac reload 3.57 s, empty (speed) reload 3.17 s */
    public static final WeaponDef SA58 = new WeaponDef("sa58", "SA-58 OSW", 20, 9.5f, true,
            92_000_000L, 78_000_000L, 64, 56, 1.15f, 0.6f,
            new String[] {"iron", "exps3", "m5b", "t2"}, true, 1, 0f, null, new String[] {"none", "afg", "vfg", "stubby"});

    public static final WeaponDef[] ALL = {M4A1, G19, USP45, PYTHON, SA58, M590};

    public boolean shellReload() { return shell != null; }

    public int gripCount() { return grips.length; }

    public int opticCount() { return optics.length; }

    /** a weapon light on the rail (RoN: M600V on the M4, X300 on the Glock, the 590's forend light); the Python has none */
    public boolean hasLight() { return !id.equals("python"); }

    /** handguns can be carried with the ballistic shield in the other hand */
    public boolean shieldable() { return id.equals("g19") || id.equals("python"); }
}
