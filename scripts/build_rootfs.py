#!/usr/bin/env python3
"""
Builds a standalone, reproducible aarch64 Termux + Google Antigravity CLI (agy)
rootfs archive for LytsoCoder directly from official upstream sources:
  - Termux bootstrap-aarch64.zip (termux/termux-packages releases)
  - Termux official APT repository (packages-cf.termux.dev/apt/termux-main)
  - Google official Antigravity CLI manifest & binary (antigravity.google/cli)

Rewrites "com.termux" byte-for-byte to the 10-byte target package ID
(default: lytso.code) using the exact rules of TermuxRelocation.kt.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

TERMUX_PACKAGE = b"com.termux"
TERMUX_RELEASES_API = "https://api.github.com/repos/termux/termux-packages/releases"
TERMUX_APT_BASE = "https://packages-cf.termux.dev/apt/termux-main"
TERMUX_PACKAGES_GZ = f"{TERMUX_APT_BASE}/dists/stable/main/binary-aarch64/Packages.gz"
AGY_MANIFEST_URL = "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/android_arm64.json"

SKIPPED_EXTENSIONS = {"apk", "jar", "zip", "aar", "whl", "dex", "jmod"}
KEPT_SUFFIXES = (b".termuxam", b".x11.")
LOOKAHEAD = max(len(s) for s in KEPT_SUFFIXES)
CHUNK_SIZE = 256 * 1024

SEED_PACKAGES = [
    "clang",
    "make",
    "pkg-config",
    "python",
    "python-pip",
    "nodejs",
    "npm",
    "pnpm",
    "git",
    "gh",
    "openssh",
    "curl",
    "wget",
    "zip",
    "unzip",
    "tar",
    "xz-utils",
    "zstd",
    "jq",
    "ripgrep",
    "fd",
    "neovim",
    "nano",
    "zsh",
    "zsh-completions",
    "less",
    "procps",
    "psmisc",
    "lsof",
    "net-tools",
    "inetutils",
    "pv",
    "dos2unix",
    "patch",
    "termux-am",
    "termux-api",
    "termux-auth",
    "tree-sitter",
]


def http_get_bytes(url: str, *, github_api: bool = False, retries: int = 3, timeout: int = 120) -> bytes:
    headers = {"User-Agent": "LytsoCoder-RootfsBuilder/1.0"}
    if github_api:
        headers["Accept"] = "application/vnd.github.v3+json"
        token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
        if token:
            headers["Authorization"] = f"Bearer {token}"
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.read()
        except Exception as exc:
            last_err = exc
            if attempt + 1 < retries:
                time.sleep(2 ** (attempt + 1))
    raise RuntimeError(f"GET {url} failed after {retries} attempts: {last_err}") from last_err


def http_download_file(url: str, dest: Path, *, retries: int = 3, timeout: int = 300) -> None:
    headers = {"User-Agent": "LytsoCoder-RootfsBuilder/1.0"}
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as resp, open(dest, "wb") as out:
                shutil.copyfileobj(resp, out, 256 * 1024)
            return
        except Exception as exc:
            last_err = exc
            if dest.exists():
                dest.unlink()
            if attempt + 1 < retries:
                time.sleep(2 ** (attempt + 1))
    raise RuntimeError(f"Download {url} failed after {retries} attempts: {last_err}") from last_err


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def sha512_file(path: Path) -> str:
    h = hashlib.sha512()
    with open(path, "rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def md5_file(path: Path) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        while chunk := f.read(256 * 1024):
            h.update(chunk)
    return h.hexdigest()


def dpkg_compare_versions(v1: str, op: str, v2: str) -> bool:
    res = subprocess.run(["dpkg", "--compare-versions", v1, op, v2], check=False)
    return res.returncode == 0


def safe_rel_path(raw: str) -> Path | None:
    clean = raw.lstrip("/")
    while clean.startswith("./"):
        clean = clean[2:]
    clean = clean.rstrip("/")
    if not clean or clean == ".":
        return None
    parts = [p for p in clean.split("/") if p and p != "."]
    if any(p == ".." for p in parts):
        raise ValueError(f"Unsafe archive path: {raw}")
    return Path(*parts) if parts else None


def remove_existing_path(target: Path) -> None:
    if not os.path.lexists(target):
        return
    if os.path.islink(target) or not os.path.isdir(target):
        try:
            os.chmod(target, 0o700)
        except OSError:
            pass
        target.unlink()
    else:
        shutil.rmtree(target)


def find_bootstrap_asset() -> tuple[str, str]:
    latest = json.loads(http_get_bytes(f"{TERMUX_RELEASES_API}/latest", github_api=True))
    releases = [latest] + json.loads(http_get_bytes(f"{TERMUX_RELEASES_API}?per_page=5", github_api=True))
    for rel in releases:
        tag = rel.get("tag_name", "unknown")
        for asset in rel.get("assets", []):
            if asset.get("name") == "bootstrap-aarch64.zip" and asset.get("browser_download_url"):
                return tag, asset["browser_download_url"]
    raise RuntimeError("Could not find bootstrap-aarch64.zip in recent termux/termux-packages releases")


def extract_bootstrap(zip_path: Path, usr_dir: Path) -> None:
    usr_dir.mkdir(parents=True, exist_ok=True)
    symlinks: list[tuple[str, str]] = []
    with zipfile.ZipFile(zip_path, "r") as zf:
        for info in zf.infolist():
            rel = safe_rel_path(info.filename)
            if rel is None:
                continue
            if rel.as_posix() == "SYMLINKS.txt":
                text = zf.read(info).decode("utf-8", errors="replace")
                for line in text.splitlines():
                    stripped = line.strip()
                    if not stripped:
                        continue
                    parts = stripped.split("←", 1)
                    if len(parts) == 2:
                        symlinks.append((parts[0].strip(), parts[1].strip()))
                continue

            target = usr_dir / rel
            unix_mode = (info.external_attr >> 16) & 0o177777
            perm = unix_mode & 0o7777

            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                os.chmod(target, (perm | 0o700) if perm else 0o755)
            elif stat.S_ISLNK(unix_mode):
                target.parent.mkdir(parents=True, exist_ok=True)
                remove_existing_path(target)
                link_target = zf.read(info).decode("utf-8")
                os.symlink(link_target, target)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                remove_existing_path(target)
                with zf.open(info, "r") as src, open(target, "wb") as dst:
                    shutil.copyfileobj(src, dst, 256 * 1024)
                if perm == 0:
                    in_exec_dir = any(p in ("bin", "libexec", "sbin", "applets") for p in rel.parts[:-1])
                    perm = 0o755 if in_exec_dir else 0o644
                os.chmod(target, perm)

    for link_target, link_rel in symlinks:
        rel = safe_rel_path(link_rel)
        if rel is None:
            continue
        dst = usr_dir / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        remove_existing_path(dst)
        os.symlink(link_target, dst)


def parse_stanzas(text: str) -> list[tuple[dict[str, str], str]]:
    text = text.replace("\r\n", "\n")
    result: list[tuple[dict[str, str], str]] = []
    for raw in text.split("\n\n"):
        raw = raw.strip("\n")
        if not raw.strip():
            continue
        fields: dict[str, str] = {}
        cur_key: str | None = None
        for line in raw.split("\n"):
            if line and line[0] in (" ", "\t"):
                if cur_key is not None:
                    fields[cur_key] = fields[cur_key] + "\n" + line
            elif ":" in line:
                k, v = line.split(":", 1)
                cur_key = k.strip()
                fields[cur_key] = v.strip()
        if "Package" in fields:
            result.append((fields, raw))
    return result


def index_provides(fields: dict[str, str], provides: dict[str, list[str]]) -> None:
    pkg = fields["Package"]
    raw_prov = fields.get("Provides", "")
    if not raw_prov:
        return
    for item in raw_prov.split(","):
        virt = re.sub(r"\s*\([^)]*\)", "", item).strip().split(":")[0].strip()
        if virt:
            provides.setdefault(virt, [])
            if pkg not in provides[virt]:
                provides[virt].append(pkg)


def parse_dep_groups(raw_deps: str) -> list[list[str]]:
    groups: list[list[str]] = []
    if not raw_deps:
        return groups
    for clause in raw_deps.split(","):
        alts: list[str] = []
        for alt in clause.split("|"):
            name = re.sub(r"\s*\([^)]*\)", "", alt).strip().split(":")[0].strip()
            if name:
                alts.append(name)
        if alts:
            groups.append(alts)
    return groups


def resolve_packages(
    repo_pkgs: dict[str, dict[str, str]],
    repo_provides: dict[str, list[str]],
    installed_fields: dict[str, dict[str, str]],
    installed_provides: dict[str, list[str]],
    seeds: list[str],
) -> list[str]:
    to_install: dict[str, dict[str, str]] = {}
    queue: list[str] = []

    def schedule(pkg_name: str) -> None:
        if pkg_name in to_install:
            return
        if pkg_name not in repo_pkgs:
            raise RuntimeError(f"Package '{pkg_name}' not found in Termux repository")
        to_install[pkg_name] = repo_pkgs[pkg_name]
        queue.append(pkg_name)

    def choose_candidate(alts: list[str]) -> str | None:
        # 1. Already scheduled or installed (real or virtual provider)
        for name in alts:
            if name in to_install or name in installed_fields:
                return None  # already satisfied (installed upgrades handled below)
            for prov in repo_provides.get(name, []) + installed_provides.get(name, []):
                if prov in to_install or prov in installed_fields:
                    return None
        # 2. First real package available in repo
        for name in alts:
            if name in repo_pkgs:
                return name
        # 3. First real package providing a virtual package
        for name in alts:
            providers = repo_provides.get(name, [])
            for p in providers:
                if p in repo_pkgs:
                    return p
        return None

    # Seed packages
    for seed in seeds:
        schedule(seed)

    # Upgrade any already-installed bootstrap package that has a newer repo version so shared libs match
    for name, inst in installed_fields.items():
        if name in repo_pkgs:
            inst_ver = inst.get("Version", "0")
            repo_ver = repo_pkgs[name].get("Version", "0")
            if dpkg_compare_versions(repo_ver, "gt", inst_ver):
                schedule(name)

    idx = 0
    while idx < len(queue):
        pkg = queue[idx]
        idx += 1
        meta = to_install[pkg]
        deps_str = ", ".join(filter(None, [meta.get("Pre-Depends", ""), meta.get("Depends", "")]))
        for alts in parse_dep_groups(deps_str):
            chosen = choose_candidate(alts)
            if chosen:
                schedule(chosen)
            else:
                # Ensure any virtual/real dependency isn't completely missing
                satisfied = any(
                    a in to_install
                    or a in installed_fields
                    or any(p in to_install or p in installed_fields for p in repo_provides.get(a, []) + installed_provides.get(a, []))
                    for a in alts
                )
                if not satisfied:
                    raise RuntimeError(f"Unresolvable dependency {alts} required by {pkg}")

    return list(to_install.keys())


def download_debs(pkg_names: list[str], repo_pkgs: dict[str, dict[str, str]], cache_dir: Path) -> dict[str, Path]:
    cache_dir.mkdir(parents=True, exist_ok=True)
    paths: dict[str, Path] = {}

    def worker(name: str) -> tuple[str, Path]:
        meta = repo_pkgs[name]
        filename = meta["Filename"]
        expected_sha = meta["SHA256"].strip().lower()
        url = f"{TERMUX_APT_BASE}/{urllib.parse.quote(filename, safe='/')}"
        dest = cache_dir / f"{name}.deb"
        http_download_file(url, dest)
        actual_sha = sha256_file(dest).lower()
        if actual_sha != expected_sha:
            raise RuntimeError(f"SHA256 mismatch for {name}: expected {expected_sha}, got {actual_sha}")
        return name, dest

    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(worker, name) for name in pkg_names]
        for fut in as_completed(futures):
            name, dest = fut.result()
            paths[name] = dest

    return paths


def extract_deb(
    pkg: str,
    deb_path: Path,
    usr_dir: Path,
    installed_fields: dict[str, dict[str, str]],
    installed_raw: dict[str, str],
) -> None:
    info_dir = usr_dir / "var/lib/dpkg/info"
    info_dir.mkdir(parents=True, exist_ok=True)
    old_list_file = info_dir / f"{pkg}.list"
    old_non_dir_rels: set[str] = set()
    if old_list_file.is_file():
        for line in old_list_file.read_text(encoding="utf-8", errors="replace").splitlines():
            p = line.strip()
            if p.startswith("/data/data/com.termux/files/usr/"):
                rel = p[len("/data/data/com.termux/files/usr/") :]
                target = usr_dir / rel
                if os.path.lexists(target) and (os.path.islink(target) or not os.path.isdir(target)):
                    old_non_dir_rels.add(rel)

    with tempfile.TemporaryDirectory() as tmp_ctrl_str:
        tmp_ctrl = Path(tmp_ctrl_str)
        subprocess.run(["dpkg-deb", "-e", str(deb_path), str(tmp_ctrl)], check=True)

        prefix_list = [
            "/.",
            "/data",
            "/data/data",
            "/data/data/com.termux",
            "/data/data/com.termux/files",
            "/data/data/com.termux/files/usr",
        ]
        seen_paths: set[str] = set(prefix_list)
        ordered_paths: list[str] = list(prefix_list)
        new_rels: set[str] = set()

        def record_rel(rel_posix: str) -> None:
            parts = [p for p in rel_posix.split("/") if p]
            acc = ""
            for part in parts:
                acc = f"{acc}/{part}" if acc else part
                full = f"/data/data/com.termux/files/usr/{acc}"
                if full not in seen_paths:
                    seen_paths.add(full)
                    ordered_paths.append(full)

        proc = subprocess.Popen(["dpkg-deb", "--fsys-tarfile", str(deb_path)], stdout=subprocess.PIPE)
        assert proc.stdout is not None
        try:
            with tarfile.open(fileobj=proc.stdout, mode="r|") as tf:
                for member in tf:
                    raw = member.name.rstrip("/")
                    while raw.startswith("./"):
                        raw = raw[2:]
                    if not raw or raw == ".":
                        continue
                    termux_root = "data/data/com.termux/files/usr"
                    if raw in ("data", "data/data", "data/data/com.termux", "data/data/com.termux/files", termux_root):
                        continue
                    if not raw.startswith(termux_root + "/"):
                        raise RuntimeError(f"Unexpected path in {pkg}.deb outside Termux prefix: {member.name}")
                    rel_posix = raw[len(termux_root) + 1 :]
                    rel_path = safe_rel_path(rel_posix)
                    if rel_path is None:
                        continue

                    record_rel(rel_path.as_posix())
                    new_rels.add(rel_path.as_posix())
                    dst = usr_dir / rel_path
                    perm = member.mode & 0o7777

                    if member.isdir():
                        if os.path.lexists(dst) and not os.path.isdir(dst):
                            remove_existing_path(dst)
                        dst.mkdir(parents=True, exist_ok=True)
                        os.chmod(dst, (perm | 0o700) if perm else 0o755)
                    elif member.issym():
                        dst.parent.mkdir(parents=True, exist_ok=True)
                        remove_existing_path(dst)
                        os.symlink(member.linkname, dst)
                    elif member.islnk():
                        dst.parent.mkdir(parents=True, exist_ok=True)
                        remove_existing_path(dst)
                        link_raw = member.linkname.rstrip("/")
                        while link_raw.startswith("./"):
                            link_raw = link_raw[2:]
                        if not link_raw.startswith(termux_root + "/"):
                            raise RuntimeError(f"Unexpected hardlink target in {pkg}.deb: {member.linkname}")
                        link_src = usr_dir / safe_rel_path(link_raw[len(termux_root) + 1 :])
                        os.link(link_src, dst)
                    elif member.isreg():
                        dst.parent.mkdir(parents=True, exist_ok=True)
                        remove_existing_path(dst)
                        fobj = tf.extractfile(member)
                        if fobj is None:
                            raise RuntimeError(f"Could not read file payload {member.name} in {pkg}.deb")
                        with fobj, open(dst, "wb") as out:
                            shutil.copyfileobj(fobj, out, 256 * 1024)
                        os.chmod(dst, perm if perm else 0o644)
        finally:
            rc = proc.wait()
            if rc != 0:
                raise RuntimeError(f"dpkg-deb --fsys-tarfile failed for {pkg} with code {rc}")

        # Remove stale files belonging solely to the replaced version
        for obsolete_rel in old_non_dir_rels - new_rels:
            obs_path = usr_dir / obsolete_rel
            if os.path.lexists(obs_path) and (os.path.islink(obs_path) or not os.path.isdir(obs_path)):
                remove_existing_path(obs_path)

        # Remove old info/<pkg>.* metadata files before installing new ones
        for existing in info_dir.glob(f"{pkg}.*"):
            if existing.is_file() or existing.is_symlink():
                existing.unlink()

        for ctrl_item in tmp_ctrl.iterdir():
            if ctrl_item.name == "control" or not ctrl_item.is_file():
                continue
            dest_meta = info_dir / f"{pkg}.{ctrl_item.name}"
            shutil.copy2(ctrl_item, dest_meta)

        (info_dir / f"{pkg}.list").write_text("\n".join(ordered_paths) + "\n", encoding="utf-8")

        ctrl_text = (tmp_ctrl / "control").read_text(encoding="utf-8", errors="replace").replace("\r\n", "\n")
        stanza_lines: list[str] = []
        status_written = False
        for line in ctrl_text.rstrip("\n").split("\n"):
            if line.startswith("Status:") or line.startswith("Conffiles:"):
                continue
            stanza_lines.append(line)
            if line.startswith("Package:") and not status_written:
                stanza_lines.append("Status: install ok installed")
                status_written = True
        if not status_written:
            stanza_lines.insert(1, "Status: install ok installed")

        conffiles_file = tmp_ctrl / "conffiles"
        if conffiles_file.is_file():
            conf_entries: list[str] = []
            for raw_cf in conffiles_file.read_text(encoding="utf-8", errors="replace").splitlines():
                cf = raw_cf.strip()
                if not cf:
                    continue
                if cf.startswith("/data/data/com.termux/files/usr/"):
                    cf_rel = cf[len("/data/data/com.termux/files/usr/") :]
                    cf_path = usr_dir / cf_rel
                    if cf_path.is_file():
                        conf_entries.append(f" {cf} {md5_file(cf_path)}")
            if conf_entries:
                stanza_lines.append("Conffiles:")
                stanza_lines.extend(conf_entries)

        new_raw = "\n".join(stanza_lines)
        parsed = parse_stanzas(new_raw)
        if not parsed:
            raise RuntimeError(f"Could not parse control file for {pkg}")
        installed_fields[pkg] = parsed[0][0]
        installed_raw[pkg] = new_raw


def apply_offline_postinst(usr_dir: Path) -> None:
    (usr_dir / "var/empty").mkdir(parents=True, exist_ok=True)
    os.chmod(usr_dir / "var/empty", 0o755)
    (usr_dir / "tmp").mkdir(parents=True, exist_ok=True)
    os.chmod(usr_dir / "tmp", 0o755)

    lib_dir = usr_dir / "lib"
    direct_exec = lib_dir / "libtermux-exec-direct-ld-preload.so"
    primary_exec = lib_dir / "libtermux-exec-ld-preload.so"
    exec_symlink = lib_dir / "libtermux-exec.so"
    if not os.path.lexists(primary_exec) and direct_exec.is_file():
        shutil.copy2(direct_exec, primary_exec)
        os.chmod(primary_exec, 0o755)
    if primary_exec.is_file() and not os.path.lexists(exec_symlink):
        os.symlink("libtermux-exec-ld-preload.so", exec_symlink)

    alternatives = [
        ("bin/pager", "less"),
        ("bin/editor", "nano"),
        ("bin/vim", "nvim"),
        ("bin/ex", "../libexec/nvim/ex"),
        ("bin/view", "../libexec/nvim/view"),
        ("bin/vimdiff", "../libexec/nvim/vimdiff"),
    ]
    for link_rel, target_name in alternatives:
        link_path = usr_dir / link_rel
        target_check = (link_path.parent / target_name).resolve()
        if target_check.exists() and not os.path.lexists(link_path):
            link_path.parent.mkdir(parents=True, exist_ok=True)
            os.symlink(target_name, link_path)


def install_agy_cli(home_dir: Path) -> str:
    manifest = json.loads(http_get_bytes(AGY_MANIFEST_URL))
    version = manifest["version"]
    url = manifest["url"]
    expected_sha512 = manifest["sha512"].strip().lower()

    local_bin = home_dir / ".local/bin"
    local_bin.mkdir(parents=True, exist_ok=True)
    os.chmod(local_bin, 0o755)
    (home_dir / ".local").chmod(0o755)
    (home_dir / "projects").mkdir(parents=True, exist_ok=True)
    os.chmod(home_dir / "projects", 0o755)

    with tempfile.TemporaryDirectory() as tmpdir_str:
        tmpdir = Path(tmpdir_str)
        payload = tmpdir / ("cli_android_arm64.tar.gz" if ".tar.gz" in url else "agy")
        http_download_file(url, payload)
        actual_sha512 = sha512_file(payload).lower()
        if actual_sha512 != expected_sha512:
            raise RuntimeError(f"agy SHA512 mismatch: expected {expected_sha512}, got {actual_sha512}")

        agy_dest = local_bin / "agy"
        if ".tar.gz" in url:
            extracted = False
            with tarfile.open(payload, "r:gz") as tf:
                for m in tf.getmembers():
                    if m.isreg() and m.name.lstrip("./") in ("antigravity", "agy"):
                        fobj = tf.extractfile(m)
                        assert fobj is not None
                        with fobj, open(agy_dest, "wb") as out:
                            shutil.copyfileobj(fobj, out, 256 * 1024)
                        extracted = True
                        break
            if not extracted:
                raise RuntimeError("Could not find antigravity binary inside cli_android_arm64.tar.gz")
        else:
            shutil.copyfile(payload, agy_dest)

        os.chmod(agy_dest, 0o755)

    with open(local_bin / "agy", "rb") as f:
        hdr = f.read(20)
    if len(hdr) < 20 or hdr[:4] != b"\x7fELF" or hdr[4] != 2 or hdr[5] != 1:
        raise RuntimeError("Downloaded agy binary is not a valid 64-bit little-endian ELF")
    e_machine = int.from_bytes(hdr[18:20], "little")
    if e_machine != 183:
        raise RuntimeError(f"Downloaded agy ELF e_machine={e_machine}, expected 183 (AArch64)")

    return str(version)


def kept_at(buf: bytes | bytearray, at: int, length: int) -> bool:
    for suf in KEPT_SUFFIXES:
        if at + len(suf) <= length and buf[at : at + len(suf)] == suf:
            return True
    return False


def rewrite_bytes(data: bytes, new_pkg: bytes, *, paths_only: bool = False) -> bytes:
    old = TERMUX_PACKAGE
    n = len(old)
    out = bytearray(data)
    i = 0
    total = len(out)
    while i <= total - n:
        if out[i : i + n] == old:
            in_path = (not paths_only) or (i > 0 and out[i - 1] == ord("/"))
            if in_path and not kept_at(out, i + n, total):
                out[i : i + n] = new_pkg
            i += n
        else:
            i += 1
    return bytes(out)


def find_offsets(path: Path, old: bytes, *, paths_only: bool = False) -> list[int]:
    offsets: list[int] = []
    n = len(old)
    buf = bytearray()
    base = 0
    before: int | None = None
    with open(path, "rb") as f:
        while True:
            chunk = f.read(CHUNK_SIZE)
            eof = len(chunk) == 0
            if not eof:
                buf.extend(chunk)
            length = len(buf)
            last = (length - n) if eof else (length - n - LOOKAHEAD)
            i = 0
            while i <= last:
                pos = buf.find(old, i, last + n)
                if pos == -1:
                    i = max(i, last + 1)
                    break
                prev_byte = buf[pos - 1] if pos > 0 else before
                in_path = (not paths_only) or (prev_byte == ord("/"))
                if in_path and not kept_at(buf, pos + n, length):
                    offsets.append(base + pos)
                i = pos + n
            if eof:
                break
            keep_from = min(i, length)
            if keep_from > 0:
                before = buf[keep_from - 1]
            del buf[:keep_from]
            base += keep_from
    return offsets


def relocate_tree(root: Path, new_pkg_str: str, *, paths_only: bool = False) -> tuple[int, int, int]:
    new_pkg = new_pkg_str.encode("ascii")
    if len(new_pkg) != len(TERMUX_PACKAGE):
        raise ValueError(f"Package '{new_pkg_str}' must be exactly {len(TERMUX_PACKAGE)} bytes")

    files_checked = 0
    files_patched = 0
    links_patched = 0
    stack = [root]
    while stack:
        cur = stack.pop()
        children = [cur / "config"] if cur.name == ".git" else list(cur.iterdir())
        for item in children:
            try:
                st = os.lstat(item)
            except FileNotFoundError:
                continue
            mode = st.st_mode
            if stat.S_ISLNK(mode):
                target = os.readlink(item)
                if "com.termux" in target:
                    new_target = rewrite_bytes(target.encode("utf-8"), new_pkg, paths_only=paths_only).decode("utf-8")
                    if new_target != target:
                        item.unlink()
                        os.symlink(new_target, item)
                        links_patched += 1
            elif stat.S_ISDIR(mode):
                stack.append(item)
            elif stat.S_ISREG(mode):
                files_checked += 1
                ext = item.suffix.lstrip(".").lower()
                if ext in SKIPPED_EXTENSIONS:
                    continue
                offsets = find_offsets(item, TERMUX_PACKAGE, paths_only=paths_only)
                if not offsets:
                    continue
                perms = stat.S_IMODE(mode)
                owner_writable = bool(perms & stat.S_IWUSR)
                if not owner_writable:
                    os.chmod(item, perms | stat.S_IWUSR)
                try:
                    with open(item, "r+b") as f:
                        for off in offsets:
                            f.seek(off)
                            f.write(new_pkg)
                    files_patched += 1
                finally:
                    if not owner_writable:
                        os.chmod(item, perms)
    return files_checked, files_patched, links_patched


def verify_stage(stage_dir: Path, pkg: str) -> None:
    usr_dir = stage_dir / "usr"
    home_dir = stage_dir / "home"
    required = [
        usr_dir / "bin/bash",
        usr_dir / "bin/sh",
        usr_dir / "lib/libandroid-support.so",
        usr_dir / "bin/python3",
        usr_dir / "bin/node",
        usr_dir / "bin/npm",
        usr_dir / "bin/pnpm",
        usr_dir / "bin/git",
        usr_dir / "bin/gh",
        usr_dir / "bin/clang",
        usr_dir / "bin/apt-get",
        usr_dir / "bin/dpkg",
        usr_dir / "var/lib/dpkg/info/termux-api.list",
        home_dir / ".local/bin/agy",
    ]
    missing_req = [str(p) for p in required if not os.path.lexists(p)]
    if missing_req:
        raise RuntimeError(f"Missing required files in staged rootfs: {missing_req}")

    # Verify bash RUNPATH targets our package lib directory
    res = subprocess.run(["readelf", "-d", str(usr_dir / "bin/bash")], check=True, capture_output=True, text=True)
    expected_lib = f"/data/data/{pkg}/files/usr/lib"
    if expected_lib not in res.stdout:
        raise RuntimeError(f"bash RUNPATH does not point to {expected_lib}:\n{res.stdout}")
    if "com.termux" in res.stdout or "ai.antigem" in res.stdout:
        raise RuntimeError(f"bash dynamic section still leaks old package:\n{res.stdout}")

    # Verify every dpkg .list entry under /data/data/<pkg>/files/usr/ actually exists on disk
    prefix = f"/data/data/{pkg}/files/usr/"
    info_dir = usr_dir / "var/lib/dpkg/info"
    broken: list[str] = []
    for list_file in info_dir.glob("*.list"):
        cf_file = info_dir / (list_file.stem + ".conffiles")
        conffiles = set(cf_file.read_text(encoding="utf-8", errors="replace").splitlines()) if cf_file.is_file() else set()
        for entry in list_file.read_text(encoding="utf-8", errors="replace").splitlines():
            entry = entry.strip()
            if not entry or entry in conffiles or not entry.startswith(prefix):
                continue
            rel = entry[len(prefix) :].rstrip("/")
            if rel and not os.path.lexists(usr_dir / rel):
                broken.append(f"{list_file.name}: {entry}")
    if broken:
        sample = "\n  ".join(broken[:30])
        raise RuntimeError(f"Found {len(broken)} missing files referenced in dpkg .list metadata:\n  {sample}")

    # Verify zero leftover AntiGem references anywhere in file names or symlinks
    for dirpath, dirnames, filenames in os.walk(stage_dir, followlinks=False):
        for name in dirnames + filenames:
            p = Path(dirpath) / name
            if "ai.antigem" in str(p):
                raise RuntimeError(f"Forbidden path found in staged rootfs: {p}")
            if os.path.islink(p) and "ai.antigem" in os.readlink(p):
                raise RuntimeError(f"Forbidden symlink target in staged rootfs: {p} -> {os.readlink(p)}")

    # Verify dpkg dependency closure
    status_text = (usr_dir / "var/lib/dpkg/status").read_text(encoding="utf-8")
    installed: dict[str, dict[str, str]] = {}
    provides: dict[str, list[str]] = {}
    for fields, _ in parse_stanzas(status_text):
        installed[fields["Package"]] = fields
        index_provides(fields, provides)
    for name, fields in installed.items():
        deps_str = ", ".join(filter(None, [fields.get("Pre-Depends", ""), fields.get("Depends", "")]))
        for alts in parse_dep_groups(deps_str):
            if not any(a in installed or any(p in installed for p in provides.get(a, [])) for a in alts):
                raise RuntimeError(f"Unsatisfied dependency {alts} for installed package {name}")


def build_rootfs(target_pkg: str, output_zip: Path, meta_out: Path | None) -> None:
    if len(target_pkg.encode("ascii")) != len(TERMUX_PACKAGE):
        raise ValueError(f"Target package '{target_pkg}' must be 10 ASCII bytes")

    output_zip = output_zip.resolve()
    output_zip.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as work_str:
        work = Path(work_str)
        stage = work / "stage"
        usr_dir = stage / "usr"
        home_dir = stage / "home"
        dl_dir = work / "downloads"
        dl_dir.mkdir(parents=True, exist_ok=True)

        print("==> [1/6] Fetching official Termux bootstrap-aarch64.zip...")
        bootstrap_tag, bootstrap_url = find_bootstrap_asset()
        bootstrap_zip = dl_dir / "bootstrap-aarch64.zip"
        http_download_file(bootstrap_url, bootstrap_zip)
        print(f"    Bootstrap release: {bootstrap_tag}")
        extract_bootstrap(bootstrap_zip, usr_dir)

        print("==> [2/6] Parsing Termux APT repository & resolving dependencies...")
        packages_gz_bytes = http_get_bytes(TERMUX_PACKAGES_GZ)
        import gzip

        packages_txt = gzip.decompress(packages_gz_bytes).decode("utf-8", errors="replace")
        repo_pkgs: dict[str, dict[str, str]] = {}
        repo_provides: dict[str, list[str]] = {}
        for fields, _ in parse_stanzas(packages_txt):
            name = fields["Package"]
            if name not in repo_pkgs or dpkg_compare_versions(fields["Version"], "gt", repo_pkgs[name]["Version"]):
                repo_pkgs[name] = fields
            index_provides(fields, repo_provides)

        status_path = usr_dir / "var/lib/dpkg/status"
        installed_fields: dict[str, dict[str, str]] = {}
        installed_raw: dict[str, str] = {}
        installed_provides: dict[str, list[str]] = {}
        for fields, raw in parse_stanzas(status_path.read_text(encoding="utf-8")):
            installed_fields[fields["Package"]] = fields
            installed_raw[fields["Package"]] = raw
            index_provides(fields, installed_provides)

        pkg_queue = resolve_packages(repo_pkgs, repo_provides, installed_fields, installed_provides, SEED_PACKAGES)
        print(f"    Queued {len(pkg_queue)} packages (new installs + ABI-aligned upgrades)")

        debs = download_debs(pkg_queue, repo_pkgs, dl_dir / "debs")
        for pkg in pkg_queue:
            extract_deb(pkg, debs[pkg], usr_dir, installed_fields, installed_raw)

        ordered_status = "\n\n".join(installed_raw[name] for name in sorted(installed_raw)) + "\n"
        status_path.write_text(ordered_status, encoding="utf-8")
        apply_offline_postinst(usr_dir)

        print("==> [3/6] Installing official Google Antigravity CLI (android_arm64)...")
        agy_version = install_agy_cli(home_dir)
        print(f"    Installed agy v{agy_version}")

        print(f"==> [4/6] Relocating com.termux -> {target_pkg}...")
        u_checked, u_patched, u_links = relocate_tree(usr_dir, target_pkg, paths_only=False)
        h_checked, h_patched, h_links = relocate_tree(home_dir, target_pkg, paths_only=True)
        print(f"    usr: patched {u_patched}/{u_checked} files, {u_links} symlinks")
        print(f"    home: patched {h_patched}/{h_checked} files, {h_links} symlinks")

        print("==> [5/6] Verifying binaries, ELF RUNPATH, dpkg database, and cleanliness...")
        verify_stage(stage, target_pkg)

        # Clean staging scratch before archiving
        tmp_dir = usr_dir / "tmp"
        if tmp_dir.is_dir():
            for child in tmp_dir.iterdir():
                remove_existing_path(child)

        print(f"==> [6/6] Packing {output_zip.name}...")
        partial_zip = output_zip.with_suffix(".zip.partial")
        if partial_zip.exists():
            partial_zip.unlink()
        if output_zip.exists():
            output_zip.unlink()

        subprocess.run(["zip", "-q", "-r", "-y", "-6", str(partial_zip), "usr", "home"], cwd=str(stage), check=True)
        subprocess.run(["unzip", "-tqq", str(partial_zip)], check=True)
        partial_zip.replace(output_zip)

    size_bytes = output_zip.stat().st_size
    digest = sha256_file(output_zip)
    total_pkgs = len(installed_raw)
    print(f"DONE: {output_zip} | {size_bytes} bytes ({size_bytes / (1024 * 1024):.1f} MiB) | sha256={digest}")

    if meta_out is not None:
        meta_out.parent.mkdir(parents=True, exist_ok=True)
        meta = {
            "asset_name": output_zip.name,
            "package": target_pkg,
            "arch": "aarch64",
            "bootstrap_tag": bootstrap_tag,
            "package_count": total_pkgs,
            "agy_version": agy_version,
            "size_bytes": size_bytes,
            "sha256": digest,
        }
        meta_out.write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build LytsoCoder standalone aarch64 rootfs ZIP")
    parser.add_argument("--package", default="lytso.code", help="10-byte Android package ID (default: lytso.code)")
    parser.add_argument("--output", required=True, help="Output ZIP path")
    parser.add_argument("--meta-out", default=None, help="Optional JSON metadata output path")
    args = parser.parse_args()

    build_rootfs(args.package, Path(args.output), Path(args.meta_out) if args.meta_out else None)
    return 0


if __name__ == "__main__":
    sys.exit(main())
