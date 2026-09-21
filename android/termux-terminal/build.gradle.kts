// Modul terminal native yang di-vendor dari termux-app (v0.118.1).
// Berisi com.termux.terminal (mesin emulasi) + com.termux.view (TerminalView).
// Lisensi: Apache-2.0 (turun dari jackpal/Android-Terminal-Emulator, lihat NOTICE.md).
// Deviations dari upstream (sesuai LinuxBox, bukan makefile externalNativeBuild):
//  * JNI.java + src/main/jni dihapus - subprocess tidak dipakai.
//  * TerminalSession.java ditambal: mode "external streams" supaya bisa
//    menempel pada PTY milik LinuxBox (lihat anotasi LINUXBOX di file tsb).
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
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
}

dependencies {
    implementation("androidx.annotation:annotation:1.7.0")
}