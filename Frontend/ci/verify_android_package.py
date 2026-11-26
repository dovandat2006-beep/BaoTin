"""Static checks for compiled APK/AAB archives; does not execute/install the app."""
import argparse, hashlib, json, pathlib, struct, zipfile

HERMES = bytes.fromhex("c61fbc03c103191f")
ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}

def elf_load_alignment(data):
    if data[:4] != b"\x7fELF":
        raise ValueError("Expected ELF shared library")
    order = "<" if data[5] == 1 else ">"
    if data[4] == 2:
        phoff = struct.unpack_from(order + "Q", data, 32)[0]
        size, count = struct.unpack_from(order + "HH", data, 54)
        align_offset, fmt = 48, "Q"
    elif data[4] == 1:
        phoff = struct.unpack_from(order + "I", data, 28)[0]
        size, count = struct.unpack_from(order + "HH", data, 42)
        align_offset, fmt = 28, "I"
    else:
        raise ValueError("Unsupported ELF class")
    values = []
    for i in range(count):
        offset = phoff + i * size
        if struct.unpack_from(order + "I", data, offset)[0] == 1:
            values.append(struct.unpack_from(order + fmt, data, offset + align_offset)[0])
    if not values:
        raise ValueError("ELF has no loadable segments")
    return values

def verify(archive, origin):
    archive = pathlib.Path(archive)
    aab = archive.suffix == ".aab"
    prefix = "base/" if aab else ""
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None, "Archive CRC failed"
        names = set(z.namelist())
        bundle = z.read(prefix + "assets/index.android.bundle")
        assert bundle.startswith(HERMES), "Release does not contain compiled Hermes bytecode"
        assert origin.encode() in bundle, "Gateway origin missing from compiled app"
        assert b"fixture-refresh" not in bundle, "Unit test fixture leaked into app bundle"
        # AAPT may rename fonts into compiled res/ entries; compare bytes, not filenames.
        fonts = [n for n in names if n.startswith((prefix + "assets/", prefix + "res/")) and n.endswith(".ttf")]
        assert len(fonts) >= 4, "Expected packaged Roboto font assets"
        source_fonts = pathlib.Path(__file__).resolve().parents[1] / "node_modules/@expo-google-fonts/roboto"
        expected_fonts = {hashlib.sha256((source_fonts / f"{weight}/{name}.ttf").read_bytes()).digest()
                          for weight, name in [("400Regular", "Roboto_400Regular"), ("500Medium", "Roboto_500Medium"),
                                               ("600SemiBold", "Roboto_600SemiBold"), ("700Bold", "Roboto_700Bold")]}
        assert expected_fonts <= {hashlib.sha256(z.read(n)).digest() for n in fonts}, "Packaged Roboto fonts differ from locked dependencies"
        libraries = [n for n in names if n.startswith(prefix + "lib/") and n.endswith(".so")]
        found = {n[len(prefix):].split("/")[1] for n in libraries}
        assert found == ABIS, f"Missing or extra ABI: {found}"
        for abi in ABIS:
            assert prefix + f"lib/{abi}/libreactnative.so" in names, f"Missing React Native library for {abi}"
            assert any(prefix + f"lib/{abi}/{lib}" in names for lib in ("libhermesvm.so", "libhermes.so")), f"Missing Hermes runtime for {abi}"
        checked = 0
        for name in libraries:
            # Android's 16KB page-size requirement applies to 64-bit device ABIs.
            if "/arm64-v8a/" in name or "/x86_64/" in name:
                assert min(elf_load_alignment(z.read(name))) >= 16384, f"ELF load alignment below16KB: {name}"
                checked += 1
        assert checked > 0
        assert not any(n.lower().endswith((".jks", ".keystore", "signing.properties", ".pem", ".env")) for n in names), "Private build file packaged"
        if aab:
            assert "BundleConfig.pb" in names and "base/manifest/AndroidManifest.xml" in names
        else:
            assert "AndroidManifest.xml" in names and "classes.dex" in names
    return {"status": "PASS", "path": str(archive.resolve()), "bytes": archive.stat().st_size,
            "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(), "type": "AAB" if aab else "APK",
            "hermes": True, "gatewayOrigin": origin, "ABIs": sorted(found), "fonts": len(fonts),
            "nativeLibraries": len(libraries), "ELF64Libraries16KBAligned": checked,
            "deviceExecuted": False, "privateBuildFilesPackaged": False}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive")
    parser.add_argument("--origin", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = verify(args.archive, args.origin)
    pathlib.Path(args.output).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"ANDROID_PACKAGE PASS type={result['type']} hermes=true origin=matched ABIs=4 fonts={result['fonts']} ELF64_16KB=PASS privateFiles=absent deviceExecuted=false")
