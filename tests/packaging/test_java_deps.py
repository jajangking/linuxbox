"""Offline packaging tests. Mock downloads/compiler/dexer, not a JVM runtime test."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
DEPS = {
    "commons-compress": ("1.26.2", "org/apache/commons/compress/archivers/tar/TarArchiveEntry.class"),
    "commons-io": ("2.16.1", "org/apache/commons/io/IOUtils.class"),
    "commons-lang3": ("3.14.0", "org/apache/commons/lang3/SystemProperties.class"),
    "commons-codec": ("1.17.0", "org/apache/commons/codec/digest/DigestUtils.class"),
}
URL_VARS = ["COMMONS_URL", "COMMONS_IO_URL", "COMMONS_LANG3_URL", "COMMONS_CODEC_URL"]


class JavaDependenciesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="linuxbox-build-test-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.bin = self.base / "bin"
        self.bin.mkdir()
        self.fixtures = self.base / "fixtures"
        self.fixtures.mkdir()
        self.dest = self.base / "libs"
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        FIXTURES=str(self.fixtures), BUILD_LOG=str(self.base / "build.log"))
        for key in URL_VARS:
            self.env.pop(key, None)
        for name, (_, entry) in DEPS.items():
            self.make_jar(name, entry)
        self.command("curl", '''
import shutil, sys
from urllib.parse import urlparse
# Select fixture using Maven artifact path, never make a network request.
artifact = urlparse(sys.argv[-1]).path.split('/')[-3]
shutil.copyfile(os.path.join(os.environ['FIXTURES'], artifact + '.jar'), sys.argv[sys.argv.index('-o') + 1])
''')
        self.command("jar", '''
import sys, zipfile
from pathlib import Path
operation, archive, *args = sys.argv[1:]
if operation == 'tf':
    with zipfile.ZipFile(archive) as z:
        print('\\n'.join(z.namelist()))
elif operation == 'cf':
    with zipfile.ZipFile(archive, 'w'):
        pass
elif operation == 'uf':
    with zipfile.ZipFile(archive, 'a') as z:
        for arg in args:
            p = Path(arg)
            for f in (p.rglob('*') if p.is_dir() else [p]):
                if f.is_file():
                    z.write(f, str(f))
else:
    raise AssertionError(operation)
''')

    def command(self, name, code):
        target = self.bin / name
        target.write_text("#!/usr/bin/env python3\nimport os\n" + code)
        target.chmod(0o755)

    def make_jar(self, name, entry):
        with zipfile.ZipFile(self.fixtures / (name + ".jar"), "w") as jar:
            jar.writestr(entry, b"fixture, not real bytecode")

    def fetch(self):
        return subprocess.run(["bash", str(ROOT / "scripts/fetch-java-deps.sh"), str(self.dest)],
                              env=self.env, text=True, capture_output=True)

    def test_all_runtime_dependencies_downloaded_and_match_gradle(self):
        result = self.fetch()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sorted(p.name for p in self.dest.iterdir()),
                         sorted(name + ".jar" for name in DEPS))
        gradle = (ROOT / "android/app/build.gradle.kts").read_text()
        fetch = (ROOT / "scripts/fetch-java-deps.sh").read_text()
        for name, (version, entry) in DEPS.items():
            self.assertIn(f':{name}:{version}"', gradle)
            self.assertIn(f'/{name}/{version}/{name}-{version}.jar', fetch)
            with zipfile.ZipFile(self.dest / (name + ".jar")) as jar:
                self.assertIn(entry, jar.namelist())

    def test_old_lang3_without_system_properties_fails_before_build(self):
        self.make_jar("commons-lang3", "org/apache/commons/lang3/StringUtils.class")
        result = self.fetch()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SystemProperties.class tidak ada", result.stderr)
        self.assertFalse((self.dest / "commons-lang3.jar").exists())
        self.assertFalse(list(self.dest.glob("*.part")))

    def test_corrupt_download_does_not_replace_existing_jar(self):
        self.dest.mkdir()
        previous = self.dest / "commons-compress.jar"
        previous.write_bytes(b"previous")
        (self.fixtures / "commons-compress.jar").write_text("not a jar")
        result = self.fetch()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(previous.read_bytes(), b"previous")
        self.assertFalse(list(self.dest.glob("*.part")))

    def test_build_passes_all_jars_to_d8_and_packages_all_dex_files(self):
        self.command("javac", '''
import json, sys
with open(os.environ['BUILD_LOG'], 'a') as log:
    log.write(json.dumps(sys.argv) + '\\n')
''')
        self.command("d8", '''
import json, sys
from pathlib import Path
with open(os.environ['BUILD_LOG'], 'a') as log:
    log.write(json.dumps(sys.argv) + '\\n')
out = Path(sys.argv[sys.argv.index('--output') + 1])
(out / 'classes.dex').write_bytes(b'first dex')
(out / 'classes2.dex').write_bytes(b'second dex')
print('test D8 diagnostic', file=sys.stderr)
''')
        self.command("aapt2", '''
import sys, zipfile
with zipfile.ZipFile(sys.argv[sys.argv.index('-o') + 1], 'w'):
    pass
''')
        work = self.base / "out"
        for child in ["gen", "assets", "dex", "jni/lib/arm64-v8a", "src/java"]:
            (work / child).mkdir(parents=True, exist_ok=True)
        (work / "src/java/Example.java").write_text("class Example {}")
        (work / "jni/lib/arm64-v8a/libexample.so").write_bytes(b"native fixture")
        self.env.update(ROOT=str(ROOT), WORK=str(work), GEN=str(work / "gen"),
                        SRC=str(work / "src"), ASSETS=str(work / "assets"),
                        ANDROID_JAR="android.jar", DEX_API="26", MANIFEST="manifest.xml",
                        AAPT_FRAMEWORK="framework-res.apk")
        # Execute the real dependency/compile/package portion of the production build.
        script = (ROOT / "scripts/build-apk.sh").read_text()
        segment = script[script.index('echo "[6] deps"'):script.index('echo "[11] sign"')]
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", segment], env=self.env,
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("test D8 diagnostic", result.stderr)
        import json
        calls = [json.loads(line) for line in (self.base / "build.log").read_text().splitlines()]
        compile_args, dex_args = calls
        self.assertIn("android.jar:" + str(work / "libs/*"), compile_args)
        for name in DEPS:
            self.assertIn(str(work / f"libs/{name}.jar"), dex_args)
        with zipfile.ZipFile(work / "unsigned.apk") as apk:
            self.assertEqual(apk.read("classes.dex"), b"first dex")
            self.assertEqual(apk.read("classes2.dex"), b"second dex")
            self.assertIn("lib/arm64-v8a/libexample.so", apk.namelist())


if __name__ == "__main__":
    unittest.main()
