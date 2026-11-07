package dev.bunnyloader.editor

/** Export policies are separate: a source project keeps portable MSBuild/NuGet configuration. */
object ProjectExport {
    fun includeSource(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it.startsWith('.') }) return false
        if (parts.dropLast(1).any { it.lowercase() in CACHE_FOLDERS }) return false
        val name = parts.last().lowercase()
        return USER_SUFFIXES.none { name.endsWith(it) } &&
            name !in CACHE_FILES && ARCHIVE_SUFFIXES.none { name.endsWith(it) }
    }

    fun includePackage(path: String): Boolean {
        if (!includeSource(path)) return false
        val name = path.replace('\\', '/').substringAfterLast('/').lowercase()
        return PROJECT_SUFFIXES.none { name.endsWith(it) } && name !in PROJECT_FILES
    }

    private val CACHE_FOLDERS = setOf("build", "bin", "obj", "packages", "artifacts", "testresults")
    private val CACHE_FILES = setOf("project.assets.json", "project.nuget.cache")
    private val USER_SUFFIXES = listOf(".user", ".suo", ".userosscache", ".sln.docstates", ".iml")
    private val ARCHIVE_SUFFIXES = listOf(".nupkg", ".snupkg")
    private val PROJECT_SUFFIXES = listOf(".csproj", ".sln", ".slnx", ".slnf", ".props", ".targets")
    private val PROJECT_FILES = setOf("nuget.config", "packages.config", "packages.lock.json", "global.json")
}
