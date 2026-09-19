cmake_minimum_required(VERSION 3.22.1)
project(ptylauncher LANGUAGES C)

# Dibuild sebagai shared lib agar ter-packaging ke lib/<abi>/;
# file .so-nya dijalankan langsung sebagai ELF binary (libc NDK cocok untuk device).
# Alternatif produksi: add_executable + buka di APK via assets.
add_library(ptylauncher SHARED ptylauncher.c)
target_compile_options(ptylauncher PRIVATE -O2 -Wall)