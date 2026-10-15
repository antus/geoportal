# SPDX-FileCopyrightText: 2026 Massimo Antonini
"""GP-327 F01: starts and stops the stack of the proofs.

    python stack.py up      compose up, the catalog and the proof stub built (mvn clean package), their
                            packages + settings put into the Mosaikit installation, Mosaikit started
                            in the background (log in <installation>/../mosaikit.log)
    python stack.py down    Mosaikit stopped, compose down -v (volumes of geoportal-f01 only)
    python stack.py restart-mosaikit

F01_MOSAIKIT is the Mosaikit installation (default C:\\temp\\sviluppo\\f01\\mosaikit, a stable copy of
target/dist/mosaikit). The packages installed are those of extensions/catalog, as it is on main, and of
proof-stub (the extension dev.geoportal.proofstub of these proofs only); F01_SKIP_BUILD=1 installs the
packages already in their target/ without building them again.
"""
import glob
import os
import shutil
import subprocess
import sys

from common import GW, HERE, MK, compose, wait_for

INSTALLATION = os.environ.get("F01_MOSAIKIT", r"C:\temp\sviluppo\f01\mosaikit")
# The extensions installed: (folder, package name). The catalog is the real one, untouched; the
# endpoints of the proofs are in the proof stub.
EXTENSIONS = ((os.path.join(HERE, "..", "..", "extensions", "catalog"), "catalog"),
              (os.path.join(HERE, "proof-stub"), "proofstub"))
LOG = os.path.join(os.path.dirname(INSTALLATION), "mosaikit.log")


def mosaikit_pids():
    """The JVMs of this installation (Windows: by command line)."""
    slash = INSTALLATION.replace("\\", "/").lower()
    back = INSTALLATION.replace("/", "\\").lower()
    ps = ("Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -and "
          f"($_.CommandLine.ToLower().Contains('{slash}') -or $_.CommandLine.ToLower().Contains('{back}')) }} | "
          "ForEach-Object { $_.ProcessId }")
    r = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True)
    return [p for p in r.stdout.split() if p.isdigit()]


def stop_mosaikit():
    pids = mosaikit_pids()
    for pid in pids:
        subprocess.run(["taskkill", "/PID", pid, "/T", "/F"], capture_output=True)
    print(f"Mosaikit stopped ({len(pids)} processes)")


def build():
    """mvn clean package of each extension (clean: no class of an older build left in the jar);
    -Dskip.npm=true: nothing of npm is needed for the package."""
    if os.environ.get("F01_SKIP_BUILD") == "1":
        return
    mvn = shutil.which("mvn") or "mvn"
    for folder, name in EXTENSIONS:
        subprocess.run([mvn, "-q", "clean", "package", "-DskipTests", "-Dskip.npm=true"], cwd=folder, check=True)
        print(f"built {name}")


def package(folder, name):
    found = sorted(glob.glob(os.path.join(folder, "target", f"{name}-*.zip")))
    if not found:
        raise SystemExit(f"no package of {name}: mvn -q package -DskipTests -Dskip.npm=true in {folder}")
    return found[-1]


def start_mosaikit():
    shutil.copy(os.path.join(HERE, "mosaikit", "application.properties"),
                os.path.join(INSTALLATION, "config", "application.properties"))
    for folder, name in EXTENSIONS:
        zip_path = package(folder, name)
        shutil.copy(zip_path, os.path.join(INSTALLATION, "extensions", os.path.basename(zip_path)))
    env = dict(os.environ, JAVA_OPTS="-Xmx512m -XX:+UseSerialGC")
    bash = shutil.which("bash") or r"C:\Program Files\Git\bin\bash.exe"
    with open(LOG, "ab") as log:
        subprocess.Popen([bash, "./mosaikit"], cwd=INSTALLATION, env=env, stdout=log, stderr=log,
                         creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0))
    t = wait_for(f"{MK}/api/v1/system/info", seconds=240)
    print(f"Mosaikit up in {t} s (log {LOG})")


def main(command):
    if command == "up":
        build()
        compose("up", "-d", "--build", capture=False)
        start_mosaikit()
        wait_for(f"{GW}/api/v1/system/info")
    elif command == "down":
        stop_mosaikit()
        compose("down", "-v", capture=False)
    elif command == "restart-mosaikit":
        stop_mosaikit()
        start_mosaikit()
    else:
        raise SystemExit(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else ""))
