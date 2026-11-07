"""Run a process on a separate, hidden Windows desktop: its windows can never take the focus on the desktop someone
is using (whatever the program calls: SetForegroundWindow only works within its own desktop).

    proc = desk.spawn(cmd, cwd, log_path)      # stdout and stderr go to log_path
    proc.poll() / proc.kill() / proc.pid
    desk.windows()                             # (hwnd, title) of the windows on the hidden desktop
"""
import ctypes, ctypes.wintypes as wt, os, subprocess

NAME = "aiguide-hidden"
k32 = ctypes.WinDLL("kernel32", use_last_error=True)
u32 = ctypes.WinDLL("user32", use_last_error=True)
GENERIC_ALL = 0x10000000


class STARTUPINFOW(ctypes.Structure):
    _fields_ = [("cb", wt.DWORD), ("lpReserved", wt.LPWSTR), ("lpDesktop", wt.LPWSTR), ("lpTitle", wt.LPWSTR),
                ("dwX", wt.DWORD), ("dwY", wt.DWORD), ("dwXSize", wt.DWORD), ("dwYSize", wt.DWORD),
                ("dwXCountChars", wt.DWORD), ("dwYCountChars", wt.DWORD), ("dwFillAttribute", wt.DWORD),
                ("dwFlags", wt.DWORD), ("wShowWindow", wt.WORD), ("cbReserved2", wt.WORD), ("lpReserved2", ctypes.c_void_p),
                ("hStdInput", wt.HANDLE), ("hStdOutput", wt.HANDLE), ("hStdError", wt.HANDLE)]


class PROCESS_INFORMATION(ctypes.Structure):
    _fields_ = [("hProcess", wt.HANDLE), ("hThread", wt.HANDLE), ("dwProcessId", wt.DWORD), ("dwThreadId", wt.DWORD)]


class SECURITY_ATTRIBUTES(ctypes.Structure):
    _fields_ = [("nLength", wt.DWORD), ("lpSecurityDescriptor", ctypes.c_void_p), ("bInheritHandle", wt.BOOL)]


u32.CreateDesktopW.restype = wt.HANDLE
u32.CreateDesktopW.argtypes = [wt.LPCWSTR, wt.LPCWSTR, ctypes.c_void_p, wt.DWORD, wt.DWORD, ctypes.c_void_p]
k32.CreateFileW.restype = wt.HANDLE
_desk = None


def desktop():
    """the hidden desktop (created once; it lives while this process holds it)"""
    global _desk
    if _desk is None:
        _desk = u32.CreateDesktopW(NAME, None, None, 0, GENERIC_ALL, None)
        if not _desk: raise OSError("CreateDesktop failed: %d" % ctypes.get_last_error())
    return _desk


class Proc:
    def __init__(self, pi):
        self.h, self.pid = pi.hProcess, pi.dwProcessId
        k32.CloseHandle(pi.hThread)

    def poll(self):
        code = wt.DWORD()
        k32.GetExitCodeProcess(self.h, ctypes.byref(code))
        return None if code.value == 259 else code.value   # STILL_ACTIVE

    def kill(self):
        k32.TerminateProcess(self.h, 1)

    def wait(self, ms=0xFFFFFFFF):
        k32.WaitForSingleObject(self.h, ms)
        return self.poll()


def spawn(cmd, cwd, log_path, below_normal=True):
    desktop()
    sa = SECURITY_ATTRIBUTES(ctypes.sizeof(SECURITY_ATTRIBUTES), None, True)
    log = k32.CreateFileW(log_path, 0x40000000, 0x1 | 0x2, ctypes.byref(sa), 2, 0x80, None)   # GENERIC_WRITE, share read/write, CREATE_ALWAYS
    if log == wt.HANDLE(-1).value: raise OSError("log not opened: %d" % ctypes.get_last_error())
    si = STARTUPINFOW()
    si.cb = ctypes.sizeof(si)
    si.lpDesktop = "WinSta0\\" + NAME
    si.dwFlags = 0x100   # STARTF_USESTDHANDLES
    si.hStdInput, si.hStdOutput, si.hStdError = None, log, log
    pi = PROCESS_INFORMATION()
    flags = 0x08000000 | 0x00000400 | (0x4000 if below_normal else 0)   # CREATE_NO_WINDOW, CREATE_UNICODE_ENVIRONMENT, BELOW_NORMAL
    line = subprocess.list2cmdline(cmd)
    ok = k32.CreateProcessW(None, ctypes.create_unicode_buffer(line), None, None, True, flags, None, cwd, ctypes.byref(si), ctypes.byref(pi))
    k32.CloseHandle(log)
    if not ok: raise OSError("CreateProcess failed: %d" % ctypes.get_last_error())
    return Proc(pi)


def windows():
    """the visible top-level windows on the hidden desktop"""
    out = []

    @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    def cb(h, _):
        n = u32.GetWindowTextLengthW(h)
        b = ctypes.create_unicode_buffer(n + 1)
        u32.GetWindowTextW(h, b, n + 1)
        if u32.IsWindowVisible(h): out.append((h, b.value))
        return True
    u32.EnumDesktopWindows(desktop(), cb, 0)
    return out


def on_user_desktop(pid):
    """any window of the process on the desktop the user sees (should be none)"""
    found = []

    @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    def cb(h, _):
        o = wt.DWORD()
        u32.GetWindowThreadProcessId(h, ctypes.byref(o))
        if o.value == pid and u32.IsWindowVisible(h): found.append(h)
        return True
    u32.EnumWindows(cb, 0)
    return found
