#!/bin/bash
# Dependensi runtime Commons Compress untuk jalur build manual (tanpa resolver Maven).
# Selaraskan versi dengan android/app/build.gradle.kts. Codec diperlukan oleh
# format/checksum lain; Lang3 dipakai TarArchiveEntry lewat SystemProperties.
set -euo pipefail

DEST="${1:?Usage: bash scripts/fetch-java-deps.sh <direktori-jar>}"
COMMONS_URL="${COMMONS_URL:-https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.26.2/commons-compress-1.26.2.jar}"
COMMONS_IO_URL="${COMMONS_IO_URL:-https://repo1.maven.org/maven2/commons-io/commons-io/2.16.1/commons-io-2.16.1.jar}"
COMMONS_LANG3_URL="${COMMONS_LANG3_URL:-https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar}"
COMMONS_CODEC_URL="${COMMONS_CODEC_URL:-https://repo1.maven.org/maven2/commons-codec/commons-codec/1.17.0/commons-codec-1.17.0.jar}"
mkdir -p "$DEST"

fetch_jar() {
    local name="$1" url="$2" required_class="$3"
    local temp="$DEST/$name.jar.part" listing="$DEST/.$name.contents"
    echo "  $name"
    # Verifikasi unduhan dan kelas sebelum mengganti berkas final. JAR versi lama
    # bisa valid sebagai ZIP tetapi belum memiliki kelas runtime yang diperlukan.
    if ! curl -fsSL --retry 2 -o "$temp" "$url" \
            || ! jar tf "$temp" > "$listing" \
            || ! grep -Fxq "$required_class" "$listing"; then
        rm -f "$temp" "$listing"
        echo "GAGAL: $name tidak terunduh/valid atau kelas $required_class tidak ada." >&2
        return 1
    fi
    mv "$temp" "$DEST/$name.jar"
    rm -f "$listing"
}

fetch_jar commons-compress "$COMMONS_URL" org/apache/commons/compress/archivers/tar/TarArchiveEntry.class
fetch_jar commons-io "$COMMONS_IO_URL" org/apache/commons/io/IOUtils.class
fetch_jar commons-lang3 "$COMMONS_LANG3_URL" org/apache/commons/lang3/SystemProperties.class
fetch_jar commons-codec "$COMMONS_CODEC_URL" org/apache/commons/codec/digest/DigestUtils.class
