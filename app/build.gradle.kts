import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// BL Editor must ship with the SAME key as Bunny Loader: its entry point
// (EditorBridgeActivity) is protected by a signature permission.
// keystore.properties at the root (template in keystore.properties.example);
// without it, this machine's debug key.
val signingKeys: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

// The frame uses Terraria's font (Andy Bold, by Monotype), the same as Bunny
// Loader. It does not go into Git: it comes from the Bunny Loader checkout
// (`bl.dir` in local.properties; default ../Bunny Loader). Without it, Comic
// Neue (OFL) from src/fontFallback, under the same resource name.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val bunnyLoaderDir = rootProject.file(localProps.getProperty("bl.dir") ?: "../Bunny Loader")
val gameFont = bunnyLoaderDir.resolve("app/src/main/res/font/bunny.ttf")
val gameFontRes = layout.buildDirectory.dir("generated/gameFont/res")
val syncGameFont by tasks.registering(Sync::class) {
    from(gameFont)
    into(gameFontRes.map { it.dir("font") })
}

android {
    namespace = "dev.bunnyloader.editor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bunnyloader.editor"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (signingKeys != null) {
            create("shared") {
                storeFile = file(signingKeys.getProperty("storeFile"))
                storePassword = signingKeys.getProperty("storePassword")
                keyAlias = signingKeys.getProperty("keyAlias")
                keyPassword = signingKeys.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("shared") ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    sourceSets["main"].res.srcDir(if (gameFont.isFile) gameFontRes else file("src/fontFallback/res"))
}

if (gameFont.isFile) tasks.named("preBuild") { dependsOn(syncGameFont) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // The code editor (LGPL-2.1, unmodified) and the TextMate colors.
    implementation(platform("io.github.Rosemoe.sora-editor:bom:0.23.6"))
    implementation("io.github.Rosemoe.sora-editor:editor")
    implementation("io.github.Rosemoe.sora-editor:language-textmate")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
