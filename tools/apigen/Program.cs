// Builds the C# API tree that powers the editor's IntelliSense.
//
//   dotnet run --project tools/apigen -c Release -- <folder with BunnyLoader.dll> <assets out> [full tree out]
//
// It reads the assemblies mods compile against (BunnyLoader.dll holds the Bunny
// Loader API and the Terraria, ReLogic and XNA types; the .NET base library comes
// from the same folder) through their metadata, so nothing gets loaded or run.
//
// The result is one tree: namespaces > types > members. On the phone the branches
// load on demand, so it is written in two levels:
//   index.blapi        namespaces and types, with where each type's members are
//   members/NNN.blapi  the members of many types, in blocks of about 256 KB
// Both are gzipped JSON. Not named .gz: the Android build would unpack those by itself.
// The optional third argument also writes the whole tree as a single JSON file.

using System.Collections.Immutable;
using System.IO.Compression;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Text;
using System.Text.Json;

if (args.Length < 2)
{
    Console.Error.WriteLine("usage: apigen <assemblies folder> <assets out> [full tree out]");
    return 1;
}

var source = args[0];
var output = args[1];
var tree = new ApiBuilder();
tree.AddAssembly(Path.Combine(source, "BunnyLoader.dll"), _ => true);
string[] baseNamespaces =
[
    "System", "System.Collections", "System.Collections.Generic", "System.Linq", "System.IO", "System.Text",
    "System.Text.RegularExpressions", "System.Threading", "System.Threading.Tasks", "System.Numerics",
    "System.Reflection", "System.Diagnostics", "System.Globalization",
];
foreach (var name in new[] { "System.Private.CoreLib.dll", "System.Collections.dll", "System.Linq.dll",
             "System.Runtime.Numerics.dll", "System.Text.RegularExpressions.dll", "System.Collections.Concurrent.dll" })
{
    var file = Path.Combine(source, name);
    if (File.Exists(file)) tree.AddAssembly(file, baseNamespaces.Contains);
}
tree.Write(output, args.Length > 2 ? args[2] : null);
return 0;

sealed class ApiType
{
    public required string FullName, Name, Namespace, Kind;
    public string Flags = "";
    public string? Base;
    public List<string> Interfaces = new();
    public List<string> Generics = new();
    public List<object?[]> Members = new();
    public List<ApiType> Nested = new();
    public HashSet<string> ExtensionTargets = new();
}

sealed class ApiBuilder
{
    readonly Dictionary<string, ApiType> topLevel = new();

    public void AddAssembly(string path, Func<string, bool> wantNamespace)
    {
        using var stream = File.OpenRead(path);
        using var pe = new PEReader(stream);
        var md = pe.GetMetadataReader();
        foreach (var handle in md.TypeDefinitions)
        {
            var def = md.GetTypeDefinition(handle);
            if (!def.GetDeclaringType().IsNil || !IsVisible(def.Attributes)) continue;
            var ns = md.GetString(def.Namespace);
            if (string.IsNullOrEmpty(ns) || !wantNamespace(ns)) continue;
            var type = ReadType(md, def, ns, ImmutableArray<string>.Empty, null);
            if (type != null) topLevel.TryAdd(type.FullName, type);
        }
    }

    static bool IsVisible(TypeAttributes a) => (a & TypeAttributes.VisibilityMask) switch
    {
        TypeAttributes.Public or TypeAttributes.NestedPublic or TypeAttributes.NestedFamily
            or TypeAttributes.NestedFamORAssem => true,
        _ => false,
    };

    static string Clean(string name)
    {
        var tick = name.IndexOf('`');
        return tick < 0 ? name : name[..tick];
    }

    ApiType? ReadType(MetadataReader md, TypeDefinition def, string ns, ImmutableArray<string> outerGenerics, ApiType? outer)
    {
        var raw = md.GetString(def.Name);
        if (raw.Contains('<') || raw.Contains('$') || HasAttribute(md, def.GetCustomAttributes(), "CompilerGeneratedAttribute")) return null;
        var name = Clean(raw);
        var attributes = def.Attributes;
        var ownGenerics = def.GetGenericParameters().Select(h => md.GetString(md.GetGenericParameter(h).Name)).ToImmutableArray();
        var allGenerics = ownGenerics;
        var baseName = BaseOf(md, def);
        var kind = (attributes & TypeAttributes.Interface) != 0 ? "interface"
            : baseName == "System.Enum" ? "enum"
            : baseName == "System.ValueType" ? "struct"
            : baseName == "System.MulticastDelegate" ? "delegate"
            : "class";
        var type = new ApiType
        {
            FullName = outer != null ? $"{outer.FullName}.{name}" : $"{ns}.{name}",
            Name = name,
            Namespace = ns,
            Kind = kind,
        };
        var isStatic = (attributes & TypeAttributes.Abstract) != 0 && (attributes & TypeAttributes.Sealed) != 0;
        if (isStatic) type.Flags += "S";
        else if (kind == "class" && (attributes & TypeAttributes.Abstract) != 0) type.Flags += "A";
        if (kind == "class" && !isStatic && (attributes & TypeAttributes.Sealed) != 0) type.Flags += "F";
        // Nested types carry the generics of their outer types first; only the new ones show.
        var skip = outerGenerics.Length;
        type.Generics.AddRange(ownGenerics.Skip(skip));

        var provider = new SignatureNames();
        var context = new GenericContext(ownGenerics, ImmutableArray<string>.Empty);
        if (kind is "class" or "struct" && !def.BaseType.IsNil && baseName is not ("System.Object" or "System.ValueType"))
            type.Base = DecodeHandle(md, def.BaseType, provider, context);
        foreach (var impl in def.GetInterfaceImplementations())
        {
            var text = DecodeHandle(md, md.GetInterfaceImplementation(impl).Interface, provider, context);
            if (text != null) type.Interfaces.Add(text);
        }
        ReadFields(md, def, type, provider, context);
        ReadMethods(md, def, type, provider, context, out var accessors);
        ReadProperties(md, def, type, provider, context, accessors);
        ReadEvents(md, def, type, provider, context);
        foreach (var nestedHandle in def.GetNestedTypes())
        {
            var nested = md.GetTypeDefinition(nestedHandle);
            if (!IsVisible(nested.Attributes)) continue;
            var child = ReadType(md, nested, ns, ownGenerics, type);
            if (child != null) type.Nested.Add(child);
        }
        return type;
    }

    static string? BaseOf(MetadataReader md, TypeDefinition def)
    {
        if (def.BaseType.IsNil) return null;
        if (def.BaseType.Kind == HandleKind.TypeReference)
        {
            var r = md.GetTypeReference((TypeReferenceHandle)def.BaseType);
            return $"{md.GetString(r.Namespace)}.{md.GetString(r.Name)}";
        }
        if (def.BaseType.Kind == HandleKind.TypeDefinition)
        {
            var d = md.GetTypeDefinition((TypeDefinitionHandle)def.BaseType);
            return $"{md.GetString(d.Namespace)}.{md.GetString(d.Name)}";
        }
        return null;
    }

    static string? DecodeHandle(MetadataReader md, EntityHandle handle, SignatureNames provider, GenericContext context)
    {
        switch (handle.Kind)
        {
            case HandleKind.TypeDefinition: return provider.GetTypeFromDefinition(md, (TypeDefinitionHandle)handle, 0);
            case HandleKind.TypeReference: return provider.GetTypeFromReference(md, (TypeReferenceHandle)handle, 0);
            case HandleKind.TypeSpecification:
                return md.GetTypeSpecification((TypeSpecificationHandle)handle).DecodeSignature(provider, context);
            default: return null;
        }
    }

    static bool HasAttribute(MetadataReader md, CustomAttributeHandleCollection attributes, string name)
    {
        foreach (var handle in attributes)
        {
            var attribute = md.GetCustomAttribute(handle);
            StringHandle typeName = default;
            if (attribute.Constructor.Kind == HandleKind.MemberReference)
            {
                var parent = md.GetMemberReference((MemberReferenceHandle)attribute.Constructor).Parent;
                if (parent.Kind == HandleKind.TypeReference) typeName = md.GetTypeReference((TypeReferenceHandle)parent).Name;
            }
            else if (attribute.Constructor.Kind == HandleKind.MethodDefinition)
            {
                var parent = md.GetMethodDefinition((MethodDefinitionHandle)attribute.Constructor).GetDeclaringType();
                typeName = md.GetTypeDefinition(parent).Name;
            }
            if (!typeName.IsNil && md.StringComparer.Equals(typeName, name)) return true;
        }
        return false;
    }

    static bool FieldVisible(FieldAttributes a) => (a & FieldAttributes.FieldAccessMask) is FieldAttributes.Public
        or FieldAttributes.Family or FieldAttributes.FamORAssem;

    static bool MethodVisible(MethodAttributes a) => (a & MethodAttributes.MemberAccessMask) is MethodAttributes.Public
        or MethodAttributes.Family or MethodAttributes.FamORAssem;

    static string Visibility(bool isProtected) => isProtected ? "P" : "";

    void ReadFields(MetadataReader md, TypeDefinition def, ApiType type, SignatureNames provider, GenericContext context)
    {
        foreach (var handle in def.GetFields())
        {
            var field = md.GetFieldDefinition(handle);
            if (!FieldVisible(field.Attributes) || (field.Attributes & FieldAttributes.SpecialName) != 0) continue;
            var name = md.GetString(field.Name);
            if (name.Contains('<')) continue;
            var fieldType = field.DecodeSignature(provider, context);
            // The proxies' plumbing, not part of what a modder writes.
            if ((name == "Class" && fieldType == "BunnyLoader.Interop.GameClass") || (name == "Pointer" && fieldType == "nint")) continue;
            var flags = Visibility((field.Attributes & FieldAttributes.FieldAccessMask) != FieldAttributes.Public);
            var literal = (field.Attributes & FieldAttributes.Literal) != 0;
            if ((field.Attributes & FieldAttributes.Static) != 0 && !literal) flags += "S";
            if ((field.Attributes & FieldAttributes.InitOnly) != 0) flags += "R";
            if (literal)
                type.Members.Add(["k", name, flags, type.Kind == "enum" ? type.FullName : fieldType, ConstantText(md, field.GetDefaultValue())]);
            else
                type.Members.Add(["f", name, flags, fieldType]);
        }
    }

    static string? ConstantText(MetadataReader md, ConstantHandle handle)
    {
        if (handle.IsNil) return null;
        var constant = md.GetConstant(handle);
        var blob = md.GetBlobReader(constant.Value);
        return constant.TypeCode switch
        {
            ConstantTypeCode.Boolean => blob.ReadBoolean() ? "true" : "false",
            ConstantTypeCode.Char => $"'{blob.ReadChar()}'",
            ConstantTypeCode.SByte => blob.ReadSByte().ToString(),
            ConstantTypeCode.Byte => blob.ReadByte().ToString(),
            ConstantTypeCode.Int16 => blob.ReadInt16().ToString(),
            ConstantTypeCode.UInt16 => blob.ReadUInt16().ToString(),
            ConstantTypeCode.Int32 => blob.ReadInt32().ToString(),
            ConstantTypeCode.UInt32 => blob.ReadUInt32().ToString(),
            ConstantTypeCode.Int64 => blob.ReadInt64().ToString(),
            ConstantTypeCode.UInt64 => blob.ReadUInt64().ToString(),
            ConstantTypeCode.Single => blob.ReadSingle().ToString(System.Globalization.CultureInfo.InvariantCulture) + "f",
            ConstantTypeCode.Double => blob.ReadDouble().ToString(System.Globalization.CultureInfo.InvariantCulture),
            ConstantTypeCode.String => blob.Length == 0 ? "\"\"" : blob.ReadUTF16(blob.Length) is var s && s.Length <= 40 ? $"\"{s}\"" : null,
            ConstantTypeCode.NullReference => "null",
            _ => null,
        };
    }

    static string MethodFlags(MethodAttributes a)
    {
        var flags = Visibility((a & MethodAttributes.MemberAccessMask) != MethodAttributes.Public);
        if ((a & MethodAttributes.Static) != 0) flags += "S";
        if ((a & MethodAttributes.Abstract) != 0) flags += "A";
        else if ((a & MethodAttributes.Virtual) != 0 && (a & MethodAttributes.Final) == 0)
            flags += (a & MethodAttributes.NewSlot) != 0 ? "V" : "O";
        return flags;
    }

    void ReadMethods(MetadataReader md, TypeDefinition def, ApiType type, SignatureNames provider, GenericContext context,
        out HashSet<MethodDefinitionHandle> accessors)
    {
        accessors = new HashSet<MethodDefinitionHandle>();
        foreach (var handle in def.GetProperties())
        {
            var a = md.GetPropertyDefinition(handle).GetAccessors();
            if (!a.Getter.IsNil) accessors.Add(a.Getter);
            if (!a.Setter.IsNil) accessors.Add(a.Setter);
        }
        foreach (var handle in def.GetEvents())
        {
            var a = md.GetEventDefinition(handle).GetAccessors();
            if (!a.Adder.IsNil) accessors.Add(a.Adder);
            if (!a.Remover.IsNil) accessors.Add(a.Remover);
            if (!a.Raiser.IsNil) accessors.Add(a.Raiser);
        }
        foreach (var handle in def.GetMethods())
        {
            var method = md.GetMethodDefinition(handle);
            if (accessors.Contains(handle) || !MethodVisible(method.Attributes)) continue;
            var name = md.GetString(method.Name);
            if (name == ".cctor" || name.Contains('<') || name.Contains('.') && name != ".ctor") continue;
            var isCtor = name == ".ctor";
            if ((method.Attributes & MethodAttributes.SpecialName) != 0 && !isCtor) continue;
            var generics = method.GetGenericParameters().Select(h => md.GetString(md.GetGenericParameter(h).Name)).ToImmutableArray();
            var signature = method.DecodeSignature(provider, new GenericContext(context.Type, generics));
            var parameters = ParameterTexts(md, method, signature);
            var flags = MethodFlags(method.Attributes);
            var extension = HasAttribute(md, method.GetCustomAttributes(), "ExtensionAttribute");
            if (extension) flags += "E";
            if (isCtor)
            {
                type.Members.Add(["x", type.Name, flags, null, parameters]);
                continue;
            }
            if (extension && signature.ParameterTypes.Length > 0) type.ExtensionTargets.Add(StripType(signature.ParameterTypes[0]));
            type.Members.Add(generics.Length == 0
                ? ["m", name, flags, signature.ReturnType, parameters]
                : ["m", name, flags, signature.ReturnType, parameters, generics.ToArray()]);
        }
    }

    /// <summary>`ref Foo.Bar&lt;T&gt;[]` becomes `Foo.Bar`: the name extension methods are indexed under.</summary>
    static string StripType(string text)
    {
        foreach (var prefix in new[] { "ref ", "in ", "out " })
            if (text.StartsWith(prefix)) text = text[prefix.Length..];
        var cut = text.IndexOfAny(['<', '[']);
        return cut < 0 ? text.TrimEnd('?') : text[..cut];
    }

    static string[] ParameterTexts(MetadataReader md, MethodDefinition method, MethodSignature<string> signature)
    {
        var result = new string[signature.ParameterTypes.Length];
        var names = new string?[result.Length];
        var defaults = new string?[result.Length];
        var outs = new bool[result.Length];
        var ins = new bool[result.Length];
        var paramsArray = new bool[result.Length];
        foreach (var handle in method.GetParameters())
        {
            var p = md.GetParameter(handle);
            if (p.SequenceNumber == 0 || p.SequenceNumber > result.Length) continue;
            var i = p.SequenceNumber - 1;
            names[i] = md.GetString(p.Name);
            outs[i] = (p.Attributes & ParameterAttributes.Out) != 0;
            ins[i] = (p.Attributes & ParameterAttributes.In) != 0;
            if ((p.Attributes & ParameterAttributes.HasDefault) != 0) defaults[i] = ConstantText(md, p.GetDefaultValue()) ?? "default";
            else if ((p.Attributes & ParameterAttributes.Optional) != 0) defaults[i] = "default";
            paramsArray[i] = HasAttribute(md, p.GetCustomAttributes(), "ParamArrayAttribute");
        }
        for (var i = 0; i < result.Length; i++)
        {
            var type = signature.ParameterTypes[i];
            if (type.StartsWith("ref "))
            {
                if (outs[i]) type = "out " + type[4..];
                else if (ins[i]) type = "in " + type[4..];
            }
            if (paramsArray[i]) type = "params " + type;
            var text = $"{type} {names[i] ?? $"arg{i}"}";
            result[i] = defaults[i] != null ? $"{text}={defaults[i]}" : text;
        }
        return result;
    }

    void ReadProperties(MetadataReader md, TypeDefinition def, ApiType type, SignatureNames provider, GenericContext context,
        HashSet<MethodDefinitionHandle> accessors)
    {
        foreach (var handle in def.GetProperties())
        {
            var property = md.GetPropertyDefinition(handle);
            var a = property.GetAccessors();
            var getterVisible = !a.Getter.IsNil && MethodVisible(md.GetMethodDefinition(a.Getter).Attributes);
            var setterVisible = !a.Setter.IsNil && MethodVisible(md.GetMethodDefinition(a.Setter).Attributes);
            if (!getterVisible && !setterVisible) continue;
            var name = md.GetString(property.Name);
            if (name.Contains('.')) continue;
            var access = md.GetMethodDefinition(getterVisible ? a.Getter : a.Setter);
            var flags = MethodFlags(access.Attributes);
            if (!setterVisible) flags += "R";
            var signature = property.DecodeSignature(provider, context);
            if (signature.ParameterTypes.Length > 0)
            {
                var parameters = getterVisible
                    ? ParameterTexts(md, access, signature)
                    : signature.ParameterTypes.Select((t, i) => $"{t} arg{i}").ToArray();
                type.Members.Add(["i", "this", flags, signature.ReturnType, parameters]);
            }
            else type.Members.Add(["p", name, flags, signature.ReturnType]);
        }
    }

    void ReadEvents(MetadataReader md, TypeDefinition def, ApiType type, SignatureNames provider, GenericContext context)
    {
        foreach (var handle in def.GetEvents())
        {
            var ev = md.GetEventDefinition(handle);
            var adder = ev.GetAccessors().Adder;
            if (adder.IsNil) continue;
            var method = md.GetMethodDefinition(adder);
            if (!MethodVisible(method.Attributes)) continue;
            var eventType = DecodeHandle(md, ev.Type, provider, context);
            type.Members.Add(["e", md.GetString(ev.Name), MethodFlags(method.Attributes), eventType]);
        }
    }

    // ------------------------------- output --------------------------------

    public void Write(string assets, string? full)
    {
        Directory.CreateDirectory(assets);
        var members = Path.Combine(assets, "members");
        if (Directory.Exists(members)) Directory.Delete(members, true);
        Directory.CreateDirectory(members);

        var all = new List<ApiType>();
        void Walk(ApiType t)
        {
            all.Add(t);
            t.Nested.ForEach(Walk);
        }
        foreach (var t in topLevel.Values.OrderBy(t => t.FullName, StringComparer.Ordinal)) Walk(t);

        // Members go to blocks in order, so a type's relatives tend to share a block.
        var locations = new Dictionary<ApiType, (int Block, int Offset, int Length)>();
        var block = new MemoryStream();
        var blockIndex = 0;
        long rawSize = 0, packedSize = 0;
        void Flush()
        {
            if (block.Length == 0) return;
            var bytes = block.ToArray();
            var file = Path.Combine(members, $"{blockIndex:000}.blapi");
            using (var outStream = File.Create(file))
            using (var gzip = new GZipStream(outStream, CompressionLevel.SmallestSize))
                gzip.Write(bytes);
            rawSize += bytes.Length;
            packedSize += new FileInfo(file).Length;
            blockIndex++;
            block = new MemoryStream();
        }
        foreach (var t in all)
        {
            if (t.Members.Count == 0) continue;
            var bytes = JsonSerializer.SerializeToUtf8Bytes(t.Members.Select(TrimNulls));
            if (block.Length > 0 && block.Length + bytes.Length > BlockSize) Flush();
            locations[t] = (blockIndex, (int)block.Length, bytes.Length);
            block.Write(bytes);
        }
        Flush();

        var index = new Dictionary<string, object?>
        {
            ["format"] = "blapi/1",
            ["blocks"] = blockIndex,
            ["ns"] = BuildNamespaces(t => IndexNode(t, locations)),
            ["ext"] = all.Where(t => t.ExtensionTargets.Count > 0)
                .SelectMany(t => t.ExtensionTargets.Select(target => (target, t.FullName)))
                .GroupBy(p => p.target, StringComparer.Ordinal)
                .ToDictionary(g => g.Key, g => g.Select(p => p.FullName).Distinct().ToArray()),
        };
        var indexFile = Path.Combine(assets, "index.blapi");
        using (var outStream = File.Create(indexFile))
        using (var gzip = new GZipStream(outStream, CompressionLevel.SmallestSize))
            JsonSerializer.Serialize(gzip, index);

        if (full != null)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(full))!);
            using var outStream = File.Create(full);
            JsonSerializer.Serialize(outStream, new Dictionary<string, object?>
            {
                ["format"] = "blapi/1",
                ["ns"] = BuildNamespaces(FullNode),
            });
        }
        Console.WriteLine($"{all.Count} types, {all.Sum(t => t.Members.Count)} members, {blockIndex} blocks, " +
            $"{rawSize / 1024} KB raw, {packedSize / 1024} KB packed, index {new FileInfo(indexFile).Length / 1024} KB");
    }

    const int BlockSize = 256 * 1024;

    static object?[] TrimNulls(object?[] member)
    {
        var end = member.Length;
        while (end > 0 && member[end - 1] == null) end--;
        return end == member.Length ? member : member[..end];
    }

    /// <summary>The namespace tree: { "Terraria": { "n": { "ID": {...} }, "t": { "Item": {...} } } }.</summary>
    Dictionary<string, object?> BuildNamespaces(Func<ApiType, Dictionary<string, object?>> node)
    {
        var roots = new Dictionary<string, object?>();
        foreach (var type in topLevel.Values.OrderBy(t => t.FullName, StringComparer.Ordinal))
        {
            var level = roots;
            Dictionary<string, object?>? current = null;
            foreach (var part in type.Namespace.Split('.'))
            {
                if (!level.TryGetValue(part, out var found))
                {
                    found = new Dictionary<string, object?>
                    {
                        ["n"] = new Dictionary<string, object?>(),
                        ["t"] = new Dictionary<string, object?>(),
                    };
                    level[part] = found;
                }
                current = (Dictionary<string, object?>)found!;
                level = (Dictionary<string, object?>)current["n"]!;
            }
            ((Dictionary<string, object?>)current!["t"]!)[type.Name] = node(type);
        }
        return roots;
    }

    static Dictionary<string, object?> Common(ApiType t)
    {
        var node = new Dictionary<string, object?> { ["k"] = t.Kind };
        if (t.Flags.Length > 0) node["f"] = t.Flags;
        if (t.Base != null) node["b"] = t.Base;
        if (t.Interfaces.Count > 0) node["i"] = t.Interfaces;
        if (t.Generics.Count > 0) node["g"] = t.Generics;
        return node;
    }

    static Dictionary<string, object?> IndexNode(ApiType t, Dictionary<ApiType, (int Block, int Offset, int Length)> locations)
    {
        var node = Common(t);
        if (locations.TryGetValue(t, out var at)) node["at"] = new[] { at.Block, at.Offset, at.Length };
        if (t.Nested.Count > 0) node["t"] = t.Nested.ToDictionary(n => n.Name, n => (object?)IndexNode(n, locations));
        return node;
    }

    static Dictionary<string, object?> FullNode(ApiType t)
    {
        var node = Common(t);
        if (t.Members.Count > 0) node["m"] = t.Members.Select(TrimNulls);
        if (t.Nested.Count > 0) node["t"] = t.Nested.ToDictionary(n => n.Name, n => (object?)FullNode(n));
        return node;
    }
}

readonly record struct GenericContext(ImmutableArray<string> Type, ImmutableArray<string> Method);

/// <summary>Turns signatures into C# type texts, with full names so the phone can resolve them.</summary>
sealed class SignatureNames : ISignatureTypeProvider<string, GenericContext>
{
    public string GetArrayType(string element, ArrayShape shape) => $"{element}[{new string(',', shape.Rank - 1)}]";
    public string GetByReferenceType(string element) => element.StartsWith("ref ") ? element : "ref " + element;
    public string GetFunctionPointerType(MethodSignature<string> signature) => "nint";
    public string GetGenericInstantiation(string generic, ImmutableArray<string> arguments)
    {
        if (generic == "System.Nullable" && arguments.Length == 1) return arguments[0] + "?";
        return $"{generic}<{string.Join(",", arguments)}>";
    }
    public string GetGenericMethodParameter(GenericContext context, int index) =>
        index < context.Method.Length ? context.Method[index] : $"T{index}";
    public string GetGenericTypeParameter(GenericContext context, int index) =>
        index < context.Type.Length ? context.Type[index] : $"T{index}";
    public string GetModifiedType(string modifier, string unmodified, bool isRequired) => unmodified;
    public string GetPinnedType(string element) => element;
    public string GetPointerType(string element) => element + "*";
    public string GetPrimitiveType(PrimitiveTypeCode code) => code switch
    {
        PrimitiveTypeCode.Boolean => "bool",
        PrimitiveTypeCode.Byte => "byte",
        PrimitiveTypeCode.Char => "char",
        PrimitiveTypeCode.Double => "double",
        PrimitiveTypeCode.Int16 => "short",
        PrimitiveTypeCode.Int32 => "int",
        PrimitiveTypeCode.Int64 => "long",
        PrimitiveTypeCode.IntPtr => "nint",
        PrimitiveTypeCode.Object => "object",
        PrimitiveTypeCode.SByte => "sbyte",
        PrimitiveTypeCode.Single => "float",
        PrimitiveTypeCode.String => "string",
        PrimitiveTypeCode.TypedReference => "object",
        PrimitiveTypeCode.UInt16 => "ushort",
        PrimitiveTypeCode.UInt32 => "uint",
        PrimitiveTypeCode.UInt64 => "ulong",
        PrimitiveTypeCode.UIntPtr => "nuint",
        PrimitiveTypeCode.Void => "void",
        _ => "object",
    };
    public string GetSZArrayType(string element) => element + "[]";
    public string GetTypeFromDefinition(MetadataReader md, TypeDefinitionHandle handle, byte rawTypeKind)
    {
        var def = md.GetTypeDefinition(handle);
        var name = Clean(md.GetString(def.Name));
        var declaring = def.GetDeclaringType();
        if (!declaring.IsNil) return $"{GetTypeFromDefinition(md, declaring, rawTypeKind)}.{name}";
        var ns = md.GetString(def.Namespace);
        return ns.Length == 0 ? name : $"{ns}.{name}";
    }
    public string GetTypeFromReference(MetadataReader md, TypeReferenceHandle handle, byte rawTypeKind)
    {
        var r = md.GetTypeReference(handle);
        var name = Clean(md.GetString(r.Name));
        if (r.ResolutionScope.Kind == HandleKind.TypeReference)
            return $"{GetTypeFromReference(md, (TypeReferenceHandle)r.ResolutionScope, rawTypeKind)}.{name}";
        var ns = md.GetString(r.Namespace);
        return Keyword(ns.Length == 0 ? name : $"{ns}.{name}");
    }
    public string GetTypeFromSpecification(MetadataReader md, GenericContext context, TypeSpecificationHandle handle, byte rawTypeKind) =>
        md.GetTypeSpecification(handle).DecodeSignature(this, context);

    public string GetSystemType() => "System.Type";
    public bool IsSystemType(string type) => type == "System.Type";
    public string GetTypeFromSerializedName(string name) => name;
    public PrimitiveTypeCode GetUnderlyingEnumType(string type) => PrimitiveTypeCode.Int32;

    static string Clean(string name)
    {
        var tick = name.IndexOf('`');
        return tick < 0 ? name : name[..tick];
    }

    static string Keyword(string name) => name switch
    {
        "System.Object" => "object",
        "System.String" => "string",
        "System.Boolean" => "bool",
        "System.Int32" => "int",
        "System.Single" => "float",
        "System.Void" => "void",
        _ => name,
    };
}
