using System.IO.Compression;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Build.Framework;

namespace BunnyLoader.Build;

// Produces the same source package as BL Editor: format 3, manifest and sources at root.
public sealed class PackMod : Microsoft.Build.Utilities.Task
{
    [Required] public string ProjectDirectory { get; set; } = "";
    [Required] public string OutputDirectory { get; set; } = "";
    [Output] public string PackagePath { get; set; } = "";

    static readonly HashSet<string> ExcludedDirectories = new(StringComparer.OrdinalIgnoreCase)
    { "bin", "obj", "build", "out", "artifacts", "refs", "packages", "node_modules", "BunnyLoaderSDK" };
    static readonly HashSet<string> ExcludedExtensions = new(StringComparer.OrdinalIgnoreCase)
    { ".csproj", ".sln", ".slnx", ".slnf", ".user", ".props", ".targets", ".nupkg", ".snupkg", ".bl", ".pdb", ".lock" };

    public override bool Execute()
    {
        string? temporary = null;
        try
        {
            var root = Path.TrimEndingDirectorySeparator(Path.GetFullPath(ProjectDirectory));
            var output = Path.TrimEndingDirectorySeparator(Path.GetFullPath(OutputDirectory, root));
            if (output.Equals(root, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("BunnyLoaderPackageDir must be a separate directory, not the project root.");
            using var manifest = JsonDocument.Parse(File.ReadAllText(Path.Combine(root, "manifest.json")));
            var json = manifest.RootElement;
            string RequiredString(string key) => json.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(value.GetString())
                ? value.GetString()! : throw new InvalidDataException($"manifest.json: missing or invalid '{key}'.");
            var id = RequiredString("id");
            if (!Regex.IsMatch(id, "^[a-z][a-z0-9_]{0,63}$"))
                throw new InvalidDataException("manifest.json: id must match ^[a-z][a-z0-9_]{0,63}$.");
            var version = RequiredString("version");
            if (!Regex.IsMatch(version, "^[0-9A-Za-z][0-9A-Za-z.+_-]{0,79}$"))
                throw new InvalidDataException("manifest.json: version contains unsafe filename characters.");
            RequiredString("name");
            if (!Guid.TryParseExact(RequiredString("uid"), "D", out _))
                throw new InvalidDataException("manifest.json: uid must be a UUID in D format.");
            if (RequiredString("type") != "mod" || json.GetProperty("blVersion").GetInt32() != 3)
                throw new InvalidDataException("This SDK packages BL Editor C# format 3 mods (type=mod, blVersion=3).");
            if (!File.Exists(Path.Combine(root, id + ".cs")))
                throw new InvalidDataException($"Missing root Mod entry file: {id}.cs.");
            Directory.CreateDirectory(output);
            var destination = Path.Combine(output, $"{id}-{version}.bl");
            temporary = Path.Combine(output, $".{Guid.NewGuid():N}.tmp");
            using (var zip = ZipFile.Open(temporary, ZipArchiveMode.Create))
            {
                foreach (var file in Walk(root).Order(StringComparer.Ordinal))
                {
                    var absolute = Path.GetFullPath(file);
                    if (absolute.StartsWith(output + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) continue;
                    var relative = Path.GetRelativePath(root, file).Replace('\\', '/');
                    var entry = zip.CreateEntry(relative, CompressionLevel.Optimal);
                    entry.LastWriteTime = new DateTimeOffset(1980, 1, 1, 0, 0, 0, TimeSpan.Zero);
                    using var input = File.OpenRead(file);
                    using var target = entry.Open();
                    input.CopyTo(target);
                }
            }
            File.Move(temporary, destination, true);
            temporary = null;
            PackagePath = destination;
            Log.LogMessage(MessageImportance.High, "BunnyLoader source package: {0}", destination);
            return true;
        }
        catch (Exception ex)
        {
            Log.LogError("BLSDK001: {0}", ex.Message);
            return false;
        }
        finally { if (temporary != null && File.Exists(temporary)) File.Delete(temporary); }
    }

    static IEnumerable<string> Walk(string directory)
    {
        foreach (var entry in Directory.EnumerateFileSystemEntries(directory))
        {
            var name = Path.GetFileName(entry);
            if (name.StartsWith('.')) continue;
            var attributes = File.GetAttributes(entry);
            if ((attributes & FileAttributes.ReparsePoint) != 0)
                throw new InvalidDataException($"Package cannot include symbolic links: {name}.");
            if ((attributes & FileAttributes.Directory) != 0)
            {
                if (!ExcludedDirectories.Contains(name))
                    foreach (var file in Walk(entry)) yield return file;
            }
            else if (!ExcludedExtensions.Contains(Path.GetExtension(entry)) &&
                     !name.Equals("NuGet.Config", StringComparison.OrdinalIgnoreCase) &&
                     !name.Equals("global.json", StringComparison.OrdinalIgnoreCase) &&
                     !name.Equals("api-version.json", StringComparison.OrdinalIgnoreCase))
                yield return entry;
        }
    }
}
