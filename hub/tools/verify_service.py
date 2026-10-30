"""Foundation-service checks. Lifecycle mode deliberately stops/kills only pinepi-hub.service."""
import argparse
import json
import subprocess
import time
from urllib.error import URLError
from urllib.request import urlopen

UNIT = "pinepi-hub.service"
URL = "http://127.0.0.1:8766/healthz"


def control(*args: str) -> str:
    return subprocess.check_output(
        ["sudo", "-n", "systemctl", *args, UNIT], text=True, timeout=15,
    ).strip()


def healthy() -> bool:
    try:
        with urlopen(URL, timeout=1) as response:
            return response.status == 200 and json.load(response)["status"] == "ok"
    except (URLError, TimeoutError, OSError):
        return False


def wait_healthy(*, previous_pid: str | None = None) -> str:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        pid = control("show", "--property=MainPID", "--value")
        if pid != "0" and pid != previous_pid and healthy():
            return pid
        time.sleep(0.2)
    raise RuntimeError("service failed to become healthy")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--exercise-lifecycle", action="store_true")
    parser.add_argument("--url", default=URL, help="health URL for the deployed listener")
    args = parser.parse_args()
    global_url(args.url)
    result = {"service": UNIT, "enabled": control("is-enabled")}
    assert result["enabled"] == "enabled"
    # systemctl start completes before the Python HTTP listener is necessarily ready.
    wait_healthy()
    result["healthy"] = True
    assert control("show", "--property=User", "--value") == "alan"
    result["runtime_user"] = "alan"
    if args.exercise_lifecycle:
        initial_pid = wait_healthy()
        try:
            control("kill", "--signal=SIGKILL", "--kill-whom=main")
            replacement_pid = wait_healthy(previous_pid=initial_pid)
            result["crash_auto_restart"] = replacement_pid != initial_pid
            control("stop")
            time.sleep(3)
            result["manual_stop_remains_stopped"] = (
                control("show", "--property=ActiveState", "--value") == "inactive" and not healthy()
            )
            assert result["manual_stop_remains_stopped"]
        finally:
            control("start")
            wait_healthy()
        result["manual_start_recovers"] = True
    print(json.dumps(result, ensure_ascii=False, indent=2))


def global_url(url: str) -> None:
    global URL
    URL = url


if __name__ == "__main__":
    main()
