package ronweapons;

/**
 * Per-weapon gameplay numbers. The id is also the folder of the weapon in the private pack (assets/ronweapons/ron/&lt;id&gt;/)
 * and the prefix of its sound events (&lt;id&gt;.fire, &lt;id&gt;.fire_1p, &lt;id&gt;.reload, &lt;id&gt;.dryfire).
 *
 * @param clientIntervalNs  shortest time between two shots the client sends
 * @param serverIntervalNs  server-side cap, a bit below the client interval to absorb jitter
 * @param reloadTacTicks    ticks until the mag is refilled on a tactical reload (a little before the clip ends)
 * @param recoilPitch       camera kick per shot in degrees
 * @param viewKick          procedural kick of the first-person rig (0 = the RoN fire clip already has it)
 */
public record WeaponDef(String id, String name, int magSize, float damage, boolean auto,
                        long clientIntervalNs, long serverIntervalNs, int reloadTacTicks, int reloadEmptyTicks,
                        float recoilPitch, float viewKick) {
    public static final WeaponDef M4A1 = new WeaponDef("m4a1", "M4A1", 30, 7.0f, true,
            75_000_000L, 60_000_000L, 58, 42, 0.7f, 0f);
    public static final WeaponDef G19 = new WeaponDef("g19", "Glock 19", 15, 5.0f, false,
            90_000_000L, 75_000_000L, 46, 40, 1.4f, 1.0f);
    public static final WeaponDef PYTHON = new WeaponDef("python", "Colt Python", 6, 11.0f, false,
            300_000_000L, 270_000_000L, 58, 58, 3.2f, 0.35f);

    public static final WeaponDef[] ALL = {M4A1, G19, PYTHON};
}
