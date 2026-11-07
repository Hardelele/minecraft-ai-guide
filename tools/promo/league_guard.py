"""Exact, fail-closed League process check without spawning tasklist during capture."""
import ctypes
import ctypes.wintypes as wt
import os


class ProcessEntry32W(ctypes.Structure):
    _fields_ = [
        ("dwSize", wt.DWORD), ("cntUsage", wt.DWORD),
        ("th32ProcessID", wt.DWORD), ("th32DefaultHeapID", ctypes.c_size_t),
        ("th32ModuleID", wt.DWORD), ("cntThreads", wt.DWORD),
        ("th32ParentProcessID", wt.DWORD), ("pcPriClassBase", wt.LONG),
        ("dwFlags", wt.DWORD), ("szExeFile", wt.WCHAR * 260),
    ]


def active_league(kernel=None, last_error=None):
    """Return exact image-name presence; any enumeration failure raises OSError."""
    if kernel is None:
        if os.name != "nt":
            raise OSError("League process guard requires Windows")
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.CreateToolhelp32Snapshot.argtypes = (wt.DWORD, wt.DWORD)
        kernel.CreateToolhelp32Snapshot.restype = wt.HANDLE
        kernel.Process32FirstW.argtypes = (wt.HANDLE, ctypes.POINTER(ProcessEntry32W))
        kernel.Process32FirstW.restype = wt.BOOL
        kernel.Process32NextW.argtypes = (wt.HANDLE, ctypes.POINTER(ProcessEntry32W))
        kernel.Process32NextW.restype = wt.BOOL
        kernel.CloseHandle.argtypes = (wt.HANDLE,)
        kernel.CloseHandle.restype = wt.BOOL
    if last_error is None:
        last_error = ctypes.get_last_error
    snapshot = kernel.CreateToolhelp32Snapshot(0x00000002, 0)  # TH32CS_SNAPPROCESS
    if snapshot is None or snapshot == ctypes.c_void_p(-1).value:
        raise OSError(last_error(), "CreateToolhelp32Snapshot failed")
    failure = None
    found = False
    try:
        entry = ProcessEntry32W()
        entry.dwSize = ctypes.sizeof(entry)
        if not kernel.Process32FirstW(snapshot, ctypes.byref(entry)):
            raise OSError(last_error(), "Process32FirstW failed")
        while True:
            if entry.szExeFile.casefold() == "league of legends.exe":
                found = True
                break
            if not kernel.Process32NextW(snapshot, ctypes.byref(entry)):
                code = last_error()
                if code != 18:  # ERROR_NO_MORE_FILES is the only normal end
                    raise OSError(code, "Process32NextW failed")
                break
    except Exception as exc:
        failure = exc
    if not kernel.CloseHandle(snapshot):
        raise OSError(last_error(), "CloseHandle failed")
    if failure is not None:
        raise failure
    return found


def capture_stop_reason(check=active_league):
    """No reason only on a successful absence check; uncertainty stops our capture."""
    try:
        running = check()
    except Exception as exc:
        return "League process check failed; stopping owned capture: " + str(exc)
    return "League of Legends.exe started; stopping owned capture" if running else None
