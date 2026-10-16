// ronexport: read-only tool for Ready or Not paks.
//   ronexport list   [paksDir] [outDir]                -> encryption check + asset listing
//   ronexport export <targets.txt> [paksDir] [outDir]  -> meshes (psk), anims (psa), materials + textures (png)
// targets.txt: one package path per line (without extension) or a folder prefix ending with '/'. '#' = comment.
// RoN uses unversioned properties, so exports need a .usmap made by UE4SS (Ctrl+Numpad6 in game).
// Lookup order: env RON_USMAP, private\Mappings.usmap, <game>\ReadyOrNot\Binaries\Win64\Mappings.usmap.
using CUE4Parse.Compression;
using CUE4Parse.Encryption.Aes;
using CUE4Parse.FileProvider;
using CUE4Parse.MappingsProvider.Usmap;
using CUE4Parse.UE4.Assets.Exports;
using CUE4Parse.UE4.Assets.Exports.Animation;
using CUE4Parse.UE4.Assets.Exports.SkeletalMesh;
using CUE4Parse.UE4.Assets.Exports.StaticMesh;
using CUE4Parse.UE4.Assets.Exports.Texture;
using CUE4Parse.UE4.Objects.Core.Misc;
using CUE4Parse.UE4.Versions;
using CUE4Parse_Conversion;
using CUE4Parse_Conversion.Options;

const string DefaultPaks = @"D:\SteamLibrary\steamapps\common\Ready Or Not\ReadyOrNot\Content\Paks";
var mode = args.Length > 0 ? args[0] : "list";

string? FindUsmap(string paksDir)
{
    var candidates = new[]
    {
        Environment.GetEnvironmentVariable("RON_USMAP"),
        @"C:\Modding\ron-to-minecraft\private\Mappings.usmap",
        Path.GetFullPath(Path.Combine(paksDir, @"..\..\Binaries\Win64\Mappings.usmap")),
    };
    return candidates.FirstOrDefault(c => !string.IsNullOrEmpty(c) && File.Exists(c));
}

DefaultFileProvider Open(string paksDir)
{
    ZlibHelper.Initialize();
    OodleHelper.Initialize();
    var p = new DefaultFileProvider(paksDir, SearchOption.TopDirectoryOnly, new VersionContainer(EGame.GAME_UE5_3));
    var usmap = FindUsmap(paksDir);
    if (usmap != null) { p.MappingsContainer = new FileUsmapTypeMappingsProvider(usmap, StringComparer.OrdinalIgnoreCase); Console.WriteLine($"Mappings: {usmap}"); }
    else Console.WriteLine("Mappings: NOT FOUND (exports of RoN assets will fail)");
    p.Initialize();
    // Unencrypted archives mount with an all-zero key (verified: all 25 RoN paks).
    p.SubmitKey(new FGuid(), new FAesKey("0x" + new string('0', 64)));
    return p;
}

if (mode == "list")
{
    var paksDir = args.Length > 1 ? args[1] : DefaultPaks;
    var outDir = args.Length > 2 ? args[2] : @"C:\Modding\ron-to-minecraft\private\scan";
    Directory.CreateDirectory(outDir);
    var provider = Open(paksDir);
    Console.WriteLine($"Mounted archives: {provider.MountedVfs.Count}, still unloaded: {provider.UnloadedVfs.Count}");
    var paths = provider.Files.Keys.OrderBy(p => p, StringComparer.OrdinalIgnoreCase).ToList();
    File.WriteAllLines(Path.Combine(outDir, "all_files.txt"), paths);
    Console.WriteLine($"Total files: {paths.Count}");
    return;
}

if (mode == "export")
{
    var targetsFile = args[1];
    var paksDir = args.Length > 2 ? args[2] : DefaultPaks;
    var outDir = args.Length > 3 ? args[3] : @"C:\Modding\ron-to-minecraft\private\export";
    Directory.CreateDirectory(outDir);
    var provider = Open(paksDir);

    var targets = File.ReadAllLines(targetsFile).Select(l => l.Trim()).Where(l => l.Length > 0 && !l.StartsWith('#')).ToList();
    var packages = new List<string>();
    foreach (var t in targets)
    {
        if (t.EndsWith('/'))
            packages.AddRange(provider.Files.Keys.Where(k => k.StartsWith(t, StringComparison.OrdinalIgnoreCase)
                && k.EndsWith(".uasset", StringComparison.OrdinalIgnoreCase)
                && !k[t.Length..].Contains('/')).Select(k => k[..^".uasset".Length]));
        else packages.Add(t);
    }

    var report = new List<string>();
    var session = new ExportSession(null);
    int queued = 0, skipped = 0;
    foreach (var pkgPath in packages.Distinct(StringComparer.OrdinalIgnoreCase))
    {
        IEnumerable<UObject> exports;
        try { exports = provider.LoadPackage(pkgPath).GetExports(); }
        catch (Exception e) { report.Add($"FAIL load {pkgPath}: {e.Message}"); continue; }
        foreach (var obj in exports)
        {
            if (obj is USkeletalMesh or UStaticMesh or UAnimSequenceBase or UTexture2D) { session.Add(obj); queued++; report.Add($"QUEUE {obj.ExportType} {obj.Name}"); }
            else skipped++;
        }
    }
    Console.WriteLine($"Packages: {packages.Count}, queued: {queued}, skipped exports: {skipped}");

    var options = new ExportOptions(
        meshFormat: EMeshFormat.ActorX,
        meshQuality: EMeshQuality.Highest,
        textureFormat: ETextureFormat.Png,
        exportMaterials: true);
    var results = await session.RunAsync(outDir, options, null, CancellationToken.None);
    int ok = 0, fail = 0;
    foreach (var r in results)
    {
        if (r.Success) { ok++; report.Add($"OK {r.ObjectPath} -> {string.Join(" | ", r.DiskFilePaths ?? [])}"); }
        else { fail++; report.Add($"FAIL {r.ObjectPath}: {r.Error}"); }
    }
    File.WriteAllLines(Path.Combine(outDir, "export_report.txt"), report);
    Console.WriteLine($"Exported ok: {ok}, failed: {fail}");
    foreach (var line in report.Where(r => r.StartsWith("FAIL")).Take(30)) Console.WriteLine(line);
    return;
}

Console.WriteLine("usage: ronexport list|export ...");
