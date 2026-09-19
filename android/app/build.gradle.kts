plugins {
    id("com.android.application")
}

android {
    namespace = "com.linuxbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.linuxbox"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.26.2")
}
// --- aset web terminal (xterm.js) -------------------------------------------
// Build dari clone bersih lewat Android Studio/Gradle: assets/web/ cuma berisi
// index.html, jadi xterm.js tidak ikut ter-bundle -> halaman terminal hitam
// kosong tanpa pesan apa pun. Tugas ini mengunduhnya otomatis saat build;
// kalau sedang offline, jalankan scripts/fetch-assets.sh (butuh curl).
val xtermVersion = "6.0.0"
val fitAddonVersion = "0.11.0"
val webAssetsDir = layout.projectDirectory.dir("src/main/assets/web")
val webAssets = listOf(
    "xterm.js" to "https://cdn.jsdelivr.net/npm/@xterm/xterm@$xtermVersion/lib/xterm.js",
    "xterm.css" to "https://cdn.jsdelivr.net/npm/@xterm/xterm@$xtermVersion/css/xterm.css",
    "fit.js" to "https://cdn.jsdelivr.net/npm/@xterm/addon-fit@$fitAddonVersion/lib/addon-fit.js"
)

val downloadWebAssets = tasks.register("downloadWebAssets") {
    val targetDir = webAssetsDir
    val assets = webAssets
    inputs.property("xtermVersion", xtermVersion)
    inputs.property("fitAddonVersion", fitAddonVersion)
    outputs.files(assets.map { (name, _) -> targetDir.file(name) })
    doLast {
        assets.forEach { (name, url) ->
            val target = targetDir.file(name).asFile
            if (target.exists() && target.length() > 0L) return@forEach
            target.parentFile?.mkdirs()
            println("[web-assets] mengunduh $name <- $url")
            try {
                java.net.URI(url).toURL().openStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                throw GradleException(
                    "Gagal mengunduh $name dari $url.\n" +
                        "Jalankan scripts/fetch-assets.sh (butuh curl) atau salin manual ke ${target.path}.\n" +
                        "Penyebab: ${e.message}"
                )
            }
        }
        assets.forEach { (name, _) ->
            val f = targetDir.file(name).asFile
            if (!f.exists() || f.length() == 0L) {
                throw GradleException("Aset web $name kosong - terminal tidak akan tampil. Cek ${f.path}")
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadWebAssets) }
