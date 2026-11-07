using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using Microsoft.Build.Framework;

namespace BunnyLoader.Build;

// Inspect metadata only: the Android API must never be loaded into the MSBuild runtime.
public sealed class ValidateApi : Microsoft.Build.Utilities.Task
{
    [Required] public string ApiPath { get; set; } = "";
    public ITaskItem[] AdditionalReferences { get; set; } = [];
    [Required] public ITaskItem[] FrameworkReferences { get; set; } = [];

    public override bool Execute()
    {
        try
        {
            if (string.IsNullOrWhiteSpace(ApiPath) || !File.Exists(ApiPath))
                throw new InvalidDataException("Supply -p:BunnyLoaderApiPath=<absolute path to BunnyLoader.dll> when packing. The API is embedded as a compile-only NuGet reference; no user profile or environment fallback is used.");
            if (!Path.GetFileName(ApiPath).Equals("BunnyLoader.dll", StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("BunnyLoaderApiPath must point to BunnyLoader.dll.");
            var standard = FrameworkReferences.Select(x => Path.GetFileNameWithoutExtension(x.ItemSpec)).ToHashSet(StringComparer.OrdinalIgnoreCase);
            standard.Add("netstandard");
            standard.Add("mscorlib");
            standard.Add("System.Private.CoreLib");
            var paths = new[] { ApiPath }.Concat(AdditionalReferences.Select(x => x.ItemSpec)).ToArray();
            var supplied = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var path in paths)
            {
                if (!File.Exists(path)) throw new InvalidDataException($"API reference does not exist: {path}");
                using var stream = File.OpenRead(path);
                using var pe = new PEReader(stream);
                var metadata = pe.GetMetadataReader();
                var name = metadata.GetString(metadata.GetAssemblyDefinition().Name);
                if (path != ApiPath && standard.Contains(name))
                    throw new InvalidDataException($"Do not bundle BCL/runtime assembly '{name}'. .NET 10 supplies framework references.");
                if (!Path.GetFileNameWithoutExtension(path).Equals(name, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidDataException($"Reference filename must match assembly name '{name}': {path}");
                if (!supplied.TryAdd(name, path)) throw new InvalidDataException($"Duplicate API assembly '{name}'.");
                if (path == ApiPath)
                {
                    if (name != "BunnyLoader") throw new InvalidDataException("Expected assembly identity BunnyLoader.");
                    foreach (var required in new[] { "Mod", "ModItem" })
                        if (!metadata.TypeDefinitions.Select(metadata.GetTypeDefinition).Any(t =>
                            metadata.GetString(t.Namespace) == "Terraria.ModLoader" && metadata.GetString(t.Name) == required &&
                            (t.Attributes & System.Reflection.TypeAttributes.Public) != 0))
                            throw new InvalidDataException($"API metadata is missing public Terraria.ModLoader.{required}; use the complete C# mod API.");
                }
            }
            foreach (var path in paths)
            {
                using var stream = File.OpenRead(path);
                using var pe = new PEReader(stream);
                var metadata = pe.GetMetadataReader();
                foreach (var name in ApiDependencies.Read(metadata))
                {
                    if (!standard.Contains(name) && !supplied.ContainsKey(name))
                        throw new InvalidDataException($"'{Path.GetFileName(path)}' requires extra API assembly '{name}'. Supply it with -p:BunnyLoaderAdditionalApiPaths=<path(s)> (semicolon separated). Framework assemblies must not be copied from a device.");
                }
            }
            Log.LogMessage(MessageImportance.High, "Validated BunnyLoader API metadata and {0} additional compile-only assemblies.", paths.Length - 1);
            return true;
        }
        catch (Exception ex)
        {
            Log.LogError("BLSDK003: {0}", ex.Message);
            return false;
        }
    }
}
