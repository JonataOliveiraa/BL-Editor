package dev.bunnyloader.editor

/** A portable desktop project: the SDK package supplies API references and build targets. */
object ModProjectFile {
    /** [namespace] is the same escaped C# namespace used by the generated source. */
    fun generate(namespace: String): String = """
        |<Project Sdk="Microsoft.NET.Sdk">
        |  <PropertyGroup>
        |    <TargetFramework>net10.0</TargetFramework>
        |    <ImplicitUsings>disable</ImplicitUsings>
        |    <Nullable>enable</Nullable>
        |    <RootNamespace>${xml(namespace)}</RootNamespace>
        |  </PropertyGroup>
        |  <ItemGroup>
        |    <PackageReference Include="BunnyLoader.ModSdk" Version="0.3.1" PrivateAssets="all" />
        |  </ItemGroup>
        |</Project>
        |
    """.trimMargin()

    private fun xml(value: String): String = buildString {
        for (char in value) append(when (char) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&apos;"
            else -> char.toString()
        })
    }
}
