"""Stable inspection and exact artifact gates for this private Windows Android fork."""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
FORK = "Darkaxt/mamu"
UPSTREAM = "MorpheApp/morphe-manager"
SIGNER = "70ea66803df6909e2c011ce672d244aa25c886bd46bbf89f67a5e5eedc8a3493"


def run(*args: str, cwd: pathlib.Path = ROOT) -> str:
    result = subprocess.run(args, cwd=cwd, text=True, capture_output=True, check=True,
                            encoding="utf-8", errors="replace")
    return result.stdout.strip()


def stable_tag(release: dict) -> str:
    tag = release.get("tag_name", "")
    if (release.get("draft") is not False or release.get("prerelease") is not False or
            not release.get("published_at") or not re.fullmatch(r"v\d+\.\d+\.\d+", tag)):
        raise ValueError("Upstream candidate is not a published stable release")
    return tag


def require_origins() -> dict:
    config = json.loads((ROOT / "maintenance.json").read_text(encoding="utf-8"))
    if config["repository"] != FORK or config["upstream"] != UPSTREAM or config["signerSha256"] != SIGNER:
        raise ValueError("Unexpected fork, upstream or release signer")
    for remote, repository in (("origin", FORK), ("upstream", UPSTREAM)):
        actual = run("git", "remote", "get-url", remote).rstrip("/").removesuffix(".git")
        if actual != f"https://github.com/{repository}":
            raise ValueError(f"Unexpected {remote} URL")
    return config


def delivery_state(version: str, release: dict, proof: dict | None, tag_commit: str | None) -> str:
    """An unchanged upstream baseline is not evidence that its APK was delivered."""
    if release.get("tag_name") != f"v{version}":
        return "PUBLISH"
    if (release.get("draft") is not False or release.get("prerelease") is not False or
            not release.get("published_at")):
        return "VERIFY"
    assets = {asset["name"]: asset for asset in release.get("assets", [])}
    apk_name = f"Mamu-{version}.apk"
    if set(assets) != {apk_name, "release-verification.json"} or proof is None:
        return "VERIFY"
    if (not tag_commit or proof.get("version") != version or
            proof.get("releaseId") != release.get("id") or
            any(proof.get(field) != tag_commit for field in ("tagCommit", "sourceHead", "packagedRevision")) or
            any(proof.get(field) is not True for field in
                ("independentDownloadVerified", "allAssetBytesMatched", "committedSourceVerified")) or
            proof.get("signerSha256") != SIGNER or
            assets[apk_name].get("digest") != "sha256:" + proof.get("sha256", "") or
            assets[apk_name].get("size") != proof.get("bytes")):
        return "VERIFY"
    return "COMPLETE"


def inspect_stable() -> dict:
    config = require_origins()
    release = json.loads(run("gh", "api", f"repos/{UPSTREAM}/releases/latest"))
    tag = stable_tag(release)
    # Fetch the release tag, never main/dev: branches may include unreleased changes.
    run("git", "fetch", "--no-tags", "upstream",
        f"refs/tags/{tag}:refs/remotes/upstream/extended-stable-candidate")
    commit = run("git", "rev-parse", "refs/remotes/upstream/extended-stable-candidate^{commit}")
    baseline = config["upstreamCommit"]
    merge_path = pathlib.Path(run("git", "rev-parse", "--git-path", "MERGE_HEAD"))
    if not merge_path.is_absolute():
        merge_path = ROOT / merge_path
    merge_head = merge_path.read_text().strip() if merge_path.exists() else None
    # A recorded in-progress merge is resumable, not a completed baseline or a fresh merge.
    run("git", "merge-base", "--is-ancestor", baseline, merge_head or "HEAD")
    run("git", "merge-base", "--is-ancestor", baseline, commit)
    if tag == config["upstreamTag"] and commit != baseline:
        raise ValueError("Published baseline tag changed identity")
    properties = dict(re.findall(r"(?m)^\s*(\w+)\s*=\s*(\S+)\s*$",
                                 (ROOT / "app/gradle.properties").read_text()))
    version = properties["version"]
    validate_extended_version(version, config["upstreamTag"])
    fork_release = json.loads(run("gh", "api", f"repos/{FORK}/releases/latest"))
    proof_path = ROOT / f"docs/verification/release-{version}.json"
    proof = json.loads(proof_path.read_text()) if proof_path.exists() else None
    tag_commit = None
    if fork_release.get("tag_name") == f"v{version}":
        ref = json.loads(run("gh", "api", f"repos/{FORK}/git/ref/tags/v{version}"))["object"]
        if ref["type"] == "tag":
            ref = json.loads(run("gh", "api", f"repos/{FORK}/git/tags/{ref['sha']}"))["object"]
        if ref["type"] != "commit":
            raise ValueError("Release tag does not identify a commit")
        tag_commit = ref["sha"]
    delivery = delivery_state(version, fork_release, proof, tag_commit)
    return {"tag": tag, "commit": commit, "changed": commit != baseline,
            "publishedAt": release["published_at"], "mergeInProgress": merge_head,
            "extendedVersion": version, "deliveryState": delivery,
            "deliveryNeeded": merge_head is not None or delivery != "COMPLETE",
            "publishedExtendedTag": fork_release.get("tag_name")}


def prepare_merge(repo: pathlib.Path, commit: str) -> None:
    if run("git", "branch", "--show-current", cwd=repo) != "main":
        raise ValueError("Stable integration requires main")
    if run("git", "status", "--porcelain", cwd=repo):
        raise ValueError("Preserve the dirty working tree; cannot start upstream integration")
    # A real three-way merge preserves fork history. Conflicts stop here for inspected resolution.
    run("git", "merge", "--no-ff", "--no-commit", commit, cwd=repo)


def input_paths() -> list[str]:
    names = set(run("git", "ls-files", "-co", "--exclude-standard").splitlines())
    build_files = {"app/build.gradle.kts", "app/gradle.properties", "build.gradle.kts",
                   "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
                   "app/proguard-rules.pro", "app/google-services.json", "NOTICE"}
    return sorted(p for p in names if (ROOT / p).is_file() and
                  (p.startswith(("app/src/", "app/schemas/", "gradle/", "buildSrc/", "patcher-core/", "app/aboutlibraries/", "app/licenses/")) or p in build_files))


def snapshot(output: pathlib.Path) -> dict:
    inputs = {p: {"sha256": hashlib.sha256((ROOT / p).read_bytes()).hexdigest(),
                  "gitBlob": run("git", "hash-object", "--path=" + p, p)} for p in input_paths()}
    data = {"inputs": inputs}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    return {"manifest": str(output), "inputCount": len(inputs)}


def verify_inputs(manifest: dict, committed: dict[str, str] | None = None) -> None:
    inputs = manifest["inputs"]
    if set(inputs) != set(input_paths()):
        raise ValueError("Build input set changed")
    for path, expected in inputs.items():
        if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != expected["sha256"]:
            raise ValueError(f"Build input changed: {path}")
        if committed is not None and committed.get(path) != expected["gitBlob"]:
            raise ValueError(f"Built source differs from HEAD: {path}")


def validate_extended_version(version: str, upstream_tag: str) -> None:
    match = re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)\.([1-9]\d*)", version)
    if not match or ".".join(match.groups()[:3]) != upstream_tag.removeprefix("v"):
        raise ValueError("Expected upstream MAJOR.MINOR.PATCH plus positive numeric Extended revision")


def verify_apk(apk: pathlib.Path, inputs: pathlib.Path, committed: bool = False) -> dict:
    config = require_origins()
    manifest = json.loads(inputs.read_text(encoding="utf-8"))
    blobs = None
    if committed:
        blobs = {}
        for line in run("git", "ls-tree", "-r", "HEAD").splitlines():
            fields, path = line.split("\t", 1)
            blobs[path] = fields.split()[2]
    verify_inputs(manifest, blobs)
    properties = dict(re.findall(r"(?m)^\s*(\w+)\s*=\s*(\S+)\s*$",
                                 (ROOT / "app/gradle.properties").read_text()))
    version, code = properties["version"], int(properties["extendedVersionCode"])
    validate_extended_version(version, config["upstreamTag"])
    sdk = re.search(r"(?m)^sdk.dir=(.+)$", (ROOT / "local.properties").read_text()).group(1).strip()
    tools = pathlib.Path(sdk) / "build-tools/37.0.0"
    certs = run(str(tools / "apksigner.bat"), "verify", "--verbose", "--print-certs", str(apk))
    if (f"certificate SHA-256 digest: {SIGNER}" not in certs or
            "Number of signers: 1" not in certs or
            "Verified using v2 scheme (APK Signature Scheme v2): true" not in certs):
        raise ValueError("APK signature identity/scheme mismatch")
    run(str(tools / "zipalign.exe"), "-c", "-P", "16", "4", str(apk))
    badging = run(str(tools / "aapt.exe"), "dump", "badging", str(apk))
    expected = f"package: name='{config['applicationId']}' versionCode='{code}' versionName='{version}'"
    if not badging.startswith(expected) or "application-label:'Mamu'" not in badging:
        raise ValueError("APK name/package/version mismatch")
    with zipfile.ZipFile(apk) as archive:
        if archive.read("uptodown.js") != (ROOT / "app/src/main/resources/uptodown.js").read_bytes():
            raise ValueError("Packaged Uptodown recipe differs from source")
        if archive.read("universal-categories.json") != (ROOT / "app/src/main/resources/universal-categories.json").read_bytes():
            raise ValueError("Packaged universal categories differ from source")
    results = []
    for directory in ("app/build/test-results/testDebugUnitTest", "patcher-core/build/test-results/test"):
        suites = list((ROOT / directory).glob("TEST-*.xml"))
        if not suites:
            raise ValueError(f"No inspected unit results in {directory}")
        results.extend(suites)
    for path in results:
        result = ET.parse(path).getroot()
        if int(result.get("failures", "0")) or int(result.get("errors", "0")):
            raise ValueError(f"Unit suite failed: {result.get('name')}")
    runtime_assets = verify_embedded_runtime(apk)
    framework_assets = verify_host_frameworks(apk)
    license_assets = verify_license_assets(apk)
    return {"version": version, "versionCode": code, "package": config["applicationId"],
            "sourceHead": run("git", "rev-parse", "HEAD"), "committedSourceVerified": committed,
            "upstreamTag": config["upstreamTag"], "upstreamCommit": config["upstreamCommit"],
            "signerSha256": SIGNER, "bytes": apk.stat().st_size,
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "assetName": f"Mamu-{version}.apk", "embeddedRuntimeAssets": runtime_assets,
            "hostFrameworkAssets": framework_assets, "licenseAssets": license_assets}


def verify_license_assets(apk: pathlib.Path) -> dict:
    """Bind the new license assets to their generator, configured notices and extras."""
    extras = json.loads((ROOT / "app/licenses/licenses.json").read_text(encoding="utf-8"))
    hashes = {}
    with zipfile.ZipFile(apk) as archive:
        index_bytes = archive.read("assets/licenses/licenses.json")
        index = json.loads(index_bytes)
        generated = [p for p in (ROOT / "app/build/generated").rglob("licenses.json")
                     if "release" in str(p.relative_to(ROOT / "app/build/generated")).lower()
                     and p.read_bytes() == index_bytes]
        if not generated:
            raise ValueError("Packaged license index differs from release generation")
        if any(library not in index["libraries"] for library in extras["libraries"]):
            raise ValueError("Packaged license index omitted configured library notices")
        for library in index["libraries"]:
            if not library["licenses"] or any(name not in index["licenses"] for name in library["licenses"]):
                raise ValueError(f"Library has no resolved license: {library['name']}")
        sources = list((ROOT / "app/licenses/texts").glob("*.txt")) + [ROOT / "NOTICE"]
        for source in sources:
            name = source.name if source.name != "NOTICE" else "NOTICE.txt"
            content = archive.read("assets/licenses/" + name)
            if content != source.read_bytes():
                raise ValueError(f"Packaged license text differs: {name}")
            hashes[name] = hashlib.sha256(content).hexdigest()
        hashes["licenses.json"] = hashlib.sha256(index_bytes).hexdigest()
    return hashes


def verify_host_frameworks(apk: pathlib.Path, dependency: pathlib.Path | None = None) -> dict:
    """The unpacked host receives these exact resources from the APK, not from its shrunk JAR."""
    if dependency is None:
        import os
        import tomllib
        versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
        gradle_home = pathlib.Path(os.environ.get("GRADLE_USER_HOME", pathlib.Path.home() / ".gradle"))
        candidates = list((gradle_home / "caches/modules-2/files-2.1/com.github.MorpheApp/ARSCLib" /
                           versions["arsclib"]).glob("*/ARSCLib-*.jar"))
        if len(candidates) != 1:
            raise ValueError("Expected the resolved pinned ARSCLib dependency for host framework verification")
        dependency = candidates[0]
    with zipfile.ZipFile(dependency) as source, zipfile.ZipFile(apk) as package:
        expected = {p.filename for p in source.infolist() if not p.is_dir() and p.filename.startswith("frameworks/")}
        actual = {p.filename for p in package.infolist() if not p.is_dir() and p.filename.startswith("frameworks/")}
        if not expected or actual != expected:
            raise ValueError("Packaged host framework resource set differs from pinned ARSCLib")
        hashes = {}
        for name in sorted(expected):
            content = package.read(name)
            if content != source.read(name):
                raise ValueError(f"Packaged host framework bytes differ: {name}")
            hashes[name] = hashlib.sha256(content).hexdigest()
    return {"dependencySha256": hashlib.sha256(dependency.read_bytes()).hexdigest(), "assets": hashes}


def verify_embedded_runtime(apk: pathlib.Path) -> dict:
    """Bind every compressed runtime/host byte to the generated inputs and require the v2 bridge."""
    import io
    import lzma
    root = ROOT / "app/build/generated/embeddedJvm/jvm"
    # The configured output location is inspected, rather than accepting arbitrary JVM-looking assets.
    if not root.is_dir():
        raise ValueError(f"Missing generated embedded JVM assets: {root}")
    expected = {p.name: p for p in root.iterdir() if p.is_file()}
    required = {"arm64-v8a.zip.xz", "armeabi-v7a.zip.xz", "x86_64.zip.xz", "modules.xz", "patcher-host.jar.xz"}
    if not required <= expected.keys():
        raise ValueError("Embedded runtime assets are incomplete")
    decoder = shutil.which("7z.exe") or shutil.which("7z")
    if decoder is None:
        raise ValueError("Runtime verification requires installed 7-Zip with ARM64 BCJ support")
    hashes = {}
    with zipfile.ZipFile(apk) as archive:
        for name, source in expected.items():
            content = archive.read("assets/jvm/" + name)
            if content != source.read_bytes():
                raise ValueError(f"Packaged embedded JVM asset differs: {name}")
            hashes[name] = hashlib.sha256(content).hexdigest()
        for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
            # The JVM runs as a process of its own through the bundled glibc loader (upstream #1474)
            for library in ("libjvm_launcher.so", "libglibc_loader.so"):
                launcher = f"lib/{abi}/{library}"
                native = ROOT / "app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out" / launcher
                launcher_bytes = archive.read(launcher)
                if launcher_bytes != native.read_bytes():
                    raise ValueError(f"Packaged native launcher differs from the native build: {launcher}")
                hashes[launcher] = hashlib.sha256(launcher_bytes).hexdigest()
            # Python's bundled liblzma does not support the ARM64 BCJ filter used by the runtime.
            decoded = subprocess.run([decoder, "e", "-so", "-si", "-txz"],
                input=archive.read(f"assets/jvm/{abi}.zip.xz"), stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, check=True).stdout
            with zipfile.ZipFile(io.BytesIO(decoded)) as runtime:
                release = runtime.read("release").decode("utf-8")
                if not re.search(r'JAVA_VERSION="25(?:[.\-+"]|$)', release):
                    raise ValueError(f"Unexpected embedded Java feature version: {abi}")
                for path in ("bin/java", "lib/libjli.so", "lib/server/libjvm.so", "lib/jvm.cfg"):
                    runtime.getinfo(path)
                for library in ("libjava", "libjimage", "libzip", "libnet", "libnio",
                                "libverify", "libsyslookup", "libmanagement"):
                    runtime.getinfo(f"lib/{library}.so")
                if not any(p.startswith("legal/java.base/") for p in runtime.namelist()):
                    raise ValueError(f"Embedded runtime legal notices missing: {abi}")
        with zipfile.ZipFile(io.BytesIO(lzma.decompress(archive.read("assets/jvm/patcher-host.jar.xz")))) as host:
            host.getinfo("app/morphe/manager/patcher/host/HostEvent$PatchOutcome.class")
            host.getinfo("app/morphe/manager/patcher/host/HostEvent$PatchExecuting.class")
            host.getinfo("app/morphe/manager/patcher/host/HostMainKt.class")
            main_class = host.read("app/morphe/manager/patcher/host/HostMainKt.class")
            if main_class[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(main_class[6:8], "big") > 61:
                raise ValueError("Patcher host bytecode exceeds the Java17 target required by the ART path")
    return hashes


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("inspect")
    commands.add_parser("prepare")
    capture = commands.add_parser("snapshot")
    capture.add_argument("--output", type=pathlib.Path, required=True)
    verify = commands.add_parser("verify")
    verify.add_argument("--apk", type=pathlib.Path, required=True)
    verify.add_argument("--inputs", type=pathlib.Path, required=True)
    verify.add_argument("--committed", action="store_true")
    args = parser.parse_args()
    if args.command in ("inspect", "prepare"):
        result = inspect_stable()
        if args.command == "prepare" and result["mergeInProgress"]:
            raise ValueError("Resume the existing merge and tracked release stages; do not start another merge")
        if args.command == "prepare" and result["deliveryNeeded"]:
            raise ValueError("Complete the existing release delivery before starting another upstream merge")
        if args.command == "prepare" and result["changed"]:
            prepare_merge(ROOT, result["commit"])
    elif args.command == "snapshot":
        result = snapshot(args.output)
    else:
        result = verify_apk(args.apk.resolve(), args.inputs, args.committed)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
