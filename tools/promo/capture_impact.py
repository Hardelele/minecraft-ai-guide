"""Keep capture and inherited encoders away from the user's foreground work."""
import os


def low_impact():
    """Limit this launcher and its children to idle priority and two logical CPUs."""
    if os.name == "nt":
        import ctypes
        k = ctypes.windll.kernel32
        k.GetCurrentProcess.restype = ctypes.c_void_p
        process = k.GetCurrentProcess()
        available, system = ctypes.c_size_t(), ctypes.c_size_t()
        k.GetProcessAffinityMask.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.c_size_t)]
        k.SetProcessAffinityMask.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
        k.SetPriorityClass.argtypes = [ctypes.c_void_p, ctypes.c_uint]
        if not k.GetProcessAffinityMask(process, ctypes.byref(available), ctypes.byref(system)):
            raise ctypes.WinError()
        cores = [1 << i for i in range(available.value.bit_length()) if available.value & (1 << i)]
        if not k.SetProcessAffinityMask(process, sum(cores[-2:])) or not k.SetPriorityClass(process, 0x40):
            raise ctypes.WinError()
        print("Low impact: idle priority, two logical CPUs, software video only", flush=True)
