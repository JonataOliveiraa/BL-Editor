using System.Collections.Immutable;
using System.Reflection;
using System.Reflection.Metadata;

namespace BunnyLoader.Build;

// AssemblyRef also includes private implementation code (e.g. the on-device compiler).
// Only exposed type signatures require references in a consumer's compile graph.
internal sealed class ApiDependencies(MetadataReader reader) : ISignatureTypeProvider<int, object?>
{
    readonly HashSet<string> dependencies = new(StringComparer.OrdinalIgnoreCase);

    public static HashSet<string> Read(MetadataReader reader)
    {
        var visitor = new ApiDependencies(reader);
        foreach (var handle in reader.TypeDefinitions)
        {
            if (!visitor.IsVisible(handle)) continue;
            var type = reader.GetTypeDefinition(handle);
            visitor.Visit(type.BaseType);
            foreach (var implementation in type.GetInterfaceImplementations())
                visitor.Visit(reader.GetInterfaceImplementation(implementation).Interface);
            visitor.Constraints(type.GetGenericParameters());
            foreach (var fieldHandle in type.GetFields())
            {
                var field = reader.GetFieldDefinition(fieldHandle);
                if ((field.Attributes & FieldAttributes.FieldAccessMask) is FieldAttributes.Public or FieldAttributes.Family or FieldAttributes.FamORAssem)
                    field.DecodeSignature(visitor, null);
            }
            foreach (var methodHandle in type.GetMethods())
            {
                var method = reader.GetMethodDefinition(methodHandle);
                if ((method.Attributes & MethodAttributes.MemberAccessMask) is not (MethodAttributes.Public or MethodAttributes.Family or MethodAttributes.FamORAssem)) continue;
                method.DecodeSignature(visitor, null);
                visitor.Constraints(method.GetGenericParameters());
            }
            // Public property/event types are also present in their public accessor signatures.
        }
        return visitor.dependencies;
    }

    bool IsVisible(TypeDefinitionHandle handle)
    {
        var type = reader.GetTypeDefinition(handle);
        return (type.Attributes & TypeAttributes.VisibilityMask) switch
        {
            TypeAttributes.Public => true,
            TypeAttributes.NestedPublic or TypeAttributes.NestedFamily or TypeAttributes.NestedFamORAssem => IsVisible(type.GetDeclaringType()),
            _ => false
        };
    }

    void Constraints(GenericParameterHandleCollection parameters)
    {
        foreach (var parameter in parameters)
            foreach (var constraint in reader.GetGenericParameter(parameter).GetConstraints())
                Visit(reader.GetGenericParameterConstraint(constraint).Type);
    }

    void Visit(EntityHandle handle)
    {
        if (handle.IsNil) return;
        switch (handle.Kind)
        {
            case HandleKind.TypeReference: GetTypeFromReference(reader, (TypeReferenceHandle)handle, 0); break;
            case HandleKind.TypeSpecification: GetTypeFromSpecification(reader, null, (TypeSpecificationHandle)handle, 0); break;
        }
    }

    public int GetTypeFromReference(MetadataReader metadata, TypeReferenceHandle handle, byte rawTypeKind)
    {
        var scope = metadata.GetTypeReference(handle).ResolutionScope;
        if (scope.Kind == HandleKind.AssemblyReference)
            dependencies.Add(metadata.GetString(metadata.GetAssemblyReference((AssemblyReferenceHandle)scope).Name));
        else if (scope.Kind == HandleKind.TypeReference)
            GetTypeFromReference(metadata, (TypeReferenceHandle)scope, rawTypeKind);
        return 0;
    }
    public int GetTypeFromSpecification(MetadataReader metadata, object? genericContext, TypeSpecificationHandle handle, byte rawTypeKind)
        => metadata.GetTypeSpecification(handle).DecodeSignature(this, genericContext);
    public int GetTypeFromDefinition(MetadataReader metadata, TypeDefinitionHandle handle, byte rawTypeKind) => 0;
    public int GetArrayType(int elementType, ArrayShape shape) => 0;
    public int GetByReferenceType(int elementType) => 0;
    public int GetFunctionPointerType(MethodSignature<int> signature) => 0;
    public int GetGenericInstantiation(int genericType, ImmutableArray<int> typeArguments) => 0;
    public int GetGenericMethodParameter(object? genericContext, int index) => 0;
    public int GetGenericTypeParameter(object? genericContext, int index) => 0;
    public int GetModifiedType(int modifier, int unmodifiedType, bool isRequired) => 0;
    public int GetPinnedType(int elementType) => 0;
    public int GetPointerType(int elementType) => 0;
    public int GetPrimitiveType(PrimitiveTypeCode typeCode) => 0;
    public int GetSZArrayType(int elementType) => 0;
}
