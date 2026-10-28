"""Comanda dal PC un emulatore Android su un host raggiungibile via ssh: ssh -> script dell'emulatore -> adb.

L'host può non avere Python: tutta la logica sta sul PC e verso l'emulatore passa solo shell. Un comando
per il telefono attraversa tre livelli di virgolette (shell del PC, shell dell'host, shell di Android),
quindi viene sempre passato come UNA stringa protetta con `shlex.quote`, e gli script lunghi viaggiano
in base64 (vedi `rulescript.encode_script_command`).
"""
from __future__ import annotations

import os
import shlex
import subprocess
import time
from dataclasses import dataclass
from typing import List, Optional

# Host e percorsi dell'ambiente: solo da variabili d'ambiente (vedi e2e/README.md), nessun default privato.
EMULATOR_HOST = os.environ.get("ARGUS_E2E_HOST", "")
EMULATOR_SCRIPT = os.environ.get("ARGUS_E2E_EMULATOR", "")
AVD_DIR = os.environ.get("ARGUS_E2E_AVD_DIR", "")
EMULATOR_MEM = "10g"
SSH_OPTIONS = [
    "-o", "BatchMode=yes",
    "-o", "ConnectTimeout=15",
    "-o", "ServerAliveInterval=15",
    "-o", "ServerAliveCountMax=8",
]


@dataclass
class Completed:
    returncode: int
    stdout: str
    stderr: str


class Emulator:
    def __init__(self, host: str = EMULATOR_HOST, script: str = EMULATOR_SCRIPT, verbose: bool = False):
        if not host or not script:
            raise RuntimeError("imposta ARGUS_E2E_HOST e ARGUS_E2E_EMULATOR (vedi e2e/README.md)")
        self.host = host
        self.script = script
        self.verbose = verbose
        self.calls = 0

    # -- livello ssh ------------------------------------------------------------------------------

    def ssh(self, remote: str, timeout: float = 120.0) -> Completed:
        self.calls += 1
        command = ["ssh", *SSH_OPTIONS, self.host, remote]
        if self.verbose:
            print(f"    $ {remote[:300]}")
        try:
            proc = subprocess.run(command, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            out = (error.stdout or b"").decode("utf-8", "replace")
            return Completed(124, out, f"timeout dopo {timeout}s")
        return Completed(
            proc.returncode,
            proc.stdout.decode("utf-8", "replace"),
            proc.stderr.decode("utf-8", "replace"),
        )

    def emulator(self, *args: str, timeout: float = 120.0) -> Completed:
        remote = " ".join([shlex.quote(self.script), *map(shlex.quote, args)])
        return self.ssh(remote, timeout)

    # -- adb --------------------------------------------------------------------------------------

    def adb(self, *args: str, timeout: float = 120.0) -> Completed:
        return self.emulator("adb", *args, timeout=timeout)

    def shell(self, command: str, timeout: float = 120.0) -> Completed:
        """Un comando per la shell del telefono (una stringa: nessuna ri-interpretazione sul percorso)."""
        return self.adb("shell", command, timeout=timeout)

    def sh(self, command: str, timeout: float = 120.0) -> str:
        """Come `shell`, ma torna solo l'output pulito."""
        return self.shell(command, timeout).stdout.replace("\r", "").strip()

    # -- ciclo di vita ----------------------------------------------------------------------------

    def is_running(self) -> bool:
        out = self.emulator("status", timeout=30).stdout
        return out.strip().startswith("acceso")

    def booted(self) -> bool:
        return self.sh("getprop sys.boot_completed", timeout=30) == "1"

    def start(self, wait_s: float = 600.0) -> None:
        if self.is_running() and self.booted():
            return
        print("== avvio l'emulatore")
        if not self.is_running():
            # Un emulatore morto di colpo (OOM, kill) lascia i file di blocco dell'AVD e il successivo avvio
            # si rifiuta («Running multiple emulators with the same AVD»). Il contenitore non c'e': sono orfani.
            if AVD_DIR:
                self.ssh(f"rm -f {AVD_DIR}/*.lock", timeout=30)
        # 10 GB e non i 6 di default: dopo una mezz'ora di suite qemu supera il limite del contenitore ed e' ucciso.
        remote = f"MEM={EMULATOR_MEM} {shlex.quote(self.script)} start"
        result = self.ssh(remote, wait_s)
        if result.returncode != 0:
            raise RuntimeError(f"avvio emulatore fallito: {result.stdout[-400:]} {result.stderr[-400:]}")

    def stop(self) -> None:
        self.emulator("stop", timeout=60)

    def install(self, apk_on_unraid: str) -> str:
        result = self.emulator("install", apk_on_unraid, timeout=300)
        return (result.stdout + result.stderr).strip()

    def shizuku(self, mode: str) -> str:
        result = self.emulator("shizuku", mode, timeout=180)
        return (result.stdout + result.stderr).strip()

    def ui(self) -> List[str]:
        return self.emulator("ui", timeout=60).stdout.replace("\r", "").splitlines()

    def tap(self, text: str) -> bool:
        return self.emulator("tap", text, timeout=60).returncode == 0

    def wait_for_boot(self, timeout_s: float = 180.0) -> bool:
        end = time.time() + timeout_s
        while time.time() < end:
            if self.booted():
                return True
            time.sleep(3)
        return False

    def screenshot(self, remote_png: str) -> Optional[str]:
        result = self.emulator("shot", remote_png, timeout=60)
        return remote_png if result.returncode == 0 else None
