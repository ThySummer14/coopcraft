"""Minecraft 客户端自动化：PrintWindow 截屏 + PostMessage 发按键。

这台机器抢不到游戏窗口的前台焦点，所以：
  - 截图用 PrintWindow(hwnd, dc, 2)
  - 按键用 PostMessage(WM_KEYDOWN / WM_CHAR)，不能依赖 SetForegroundWindow

用法：
    python mc_ctl.py find
    python mc_ctl.py shot  out.png
    python mc_ctl.py chat  "/give @s infinitystones:infinity_gauntlet"
    python mc_ctl.py key   VK_F2
"""
import ctypes
import ctypes.wintypes as wt
import struct
import sys
import time
import zlib

user32 = ctypes.windll.user32
gdi32 = ctypes.windll.gdi32

WM_KEYDOWN = 0x0100
WM_KEYUP = 0x0101
WM_CHAR = 0x0102
WM_MOUSEMOVE = 0x0200
WM_LBUTTONDOWN = 0x0201
WM_LBUTTONUP = 0x0202
WM_RBUTTONDOWN = 0x0204
WM_RBUTTONUP = 0x0205

VK = {
    "VK_RETURN": 0x0D, "VK_ESCAPE": 0x1B, "VK_T": 0x54, "VK_E": 0x45,
    "VK_F1": 0x70, "VK_F2": 0x71, "VK_F3": 0x72, "VK_F5": 0x74,
    "VK_E": 0x45, "VK_Q": 0x51, "VK_SLASH": 0xBF, "VK_TAB": 0x09,
    "VK_SPACE": 0x20, "VK_LSHIFT": 0xA0, "VK_1": 0x31, "VK_2": 0x32,
    "VK_3": 0x33, "VK_4": 0x34, "VK_5": 0x35, "VK_6": 0x36,
    "VK_7": 0x37, "VK_8": 0x38, "VK_9": 0x39, "VK_F": 0x46,
}


def dpi_aware():
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:  # noqa: BLE001
        try:
            user32.SetProcessDPIAware()
        except Exception:  # noqa: BLE001
            pass


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [
        ("biSize", wt.DWORD), ("biWidth", wt.LONG), ("biHeight", wt.LONG),
        ("biPlanes", wt.WORD), ("biBitCount", wt.WORD), ("biCompression", wt.DWORD),
        ("biSizeImage", wt.DWORD), ("biXPelsPerMeter", wt.LONG),
        ("biYPelsPerMeter", wt.LONG), ("biClrUsed", wt.DWORD),
        ("biClrImportant", wt.DWORD),
    ]


class BITMAPINFO(ctypes.Structure):
    _fields_ = [("bmiHeader", BITMAPINFOHEADER), ("bmiColors", wt.DWORD * 3)]


def find_window():
    found = []

    @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    def cb(h, lparam):
        n = user32.GetWindowTextLengthW(h)
        if n:
            buf = ctypes.create_unicode_buffer(n + 1)
            user32.GetWindowTextW(h, buf, n + 1)
            if "Minecraft" in buf.value:
                found.append((h, buf.value))
        return True

    user32.EnumWindows(cb, 0)
    return found[0] if found else (0, "")


def chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


def save_png(path, w, h, rgba):
    raw = b""
    stride = w * 4
    for y in range(h):
        raw += b"\x00" + rgba[y * stride:(y + 1) * stride]
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 6))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def shot(hwnd, out):
    rect = wt.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(rect))
    w = rect.right - rect.left
    h = rect.bottom - rect.top
    if w <= 0 or h <= 0:
        raise SystemExit("窗口尺寸异常: %dx%d" % (w, h))

    hwnd_dc = user32.GetWindowDC(hwnd)
    mem_dc = gdi32.CreateCompatibleDC(hwnd_dc)
    bitmap = gdi32.CreateCompatibleBitmap(hwnd_dc, w, h)
    gdi32.SelectObject(mem_dc, bitmap)
    user32.PrintWindow(hwnd, mem_dc, 2)

    bmi = BITMAPINFO()
    bmi.bmiHeader.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bmi.bmiHeader.biWidth = w
    bmi.bmiHeader.biHeight = -h
    bmi.bmiHeader.biPlanes = 1
    bmi.bmiHeader.biBitCount = 32
    bmi.bmiHeader.biCompression = 0

    buf = ctypes.create_string_buffer(w * h * 4)
    gdi32.GetDIBits(mem_dc, bitmap, 0, h, buf, ctypes.byref(bmi), 0)
    data = bytearray(buf.raw)
    for i in range(0, len(data), 4):
        data[i], data[i + 2] = data[i + 2], data[i]
    save_png(out, w, h, bytes(data))

    gdi32.DeleteObject(bitmap)
    gdi32.DeleteDC(mem_dc)
    user32.ReleaseDC(hwnd, hwnd_dc)
    print("shot %s (%dx%d)" % (out, w, h))


def key(hwnd, vk, hold=0.05):
    scan = user32.MapVirtualKeyW(vk, 0)
    user32.PostMessageW(hwnd, WM_KEYDOWN, vk, (scan << 16) | 1)
    time.sleep(hold)
    user32.PostMessageW(hwnd, WM_KEYUP, vk, (scan << 16) | 1 | (1 << 30) | (1 << 31))
    time.sleep(0.08)


def chars(hwnd, text):
    for ch in text:
        user32.PostMessageW(hwnd, WM_CHAR, ord(ch), 1)
        time.sleep(0.02)


def chat(hwnd, command):
    # 直接往聊天框塞 '/' 字符会被吞，所以改按 '/' 键——原版会打开命令聊天框并预填斜杠
    if command.startswith("/"):
        command = command[1:]
        user32.PostMessageW(hwnd, WM_KEYDOWN, VK["VK_SLASH"],
                            (user32.MapVirtualKeyW(VK["VK_SLASH"], 0) << 16) | 1)
        time.sleep(0.05)
        user32.PostMessageW(hwnd, WM_KEYUP, VK["VK_SLASH"],
                            (user32.MapVirtualKeyW(VK["VK_SLASH"], 0) << 16) | 1 | (1 << 30) | (1 << 31))
    else:
        key(hwnd, VK["VK_T"])
    time.sleep(1.2)
    chars(hwnd, command)
    time.sleep(0.3)
    key(hwnd, VK["VK_RETURN"])
    time.sleep(0.4)
    print("chat: /%s" % command)


def click(hwnd, button="right", hold=0.3):
    """把光标移到窗口中心再按下/松开，触发游戏内的右键使用 / 左键攻击。"""
    rect = wt.RECT()
    user32.GetClientRect(hwnd, ctypes.byref(rect))
    cx, cy = rect.right // 2, rect.bottom // 2
    lp = (cx & 0xFFFF) | ((cy & 0xFFFF) << 16)
    user32.PostMessageW(hwnd, WM_MOUSEMOVE, 0, lp)
    time.sleep(0.1)
    if button == "right":
        user32.PostMessageW(hwnd, WM_RBUTTONDOWN, 0x0002, lp)
        time.sleep(hold)
        user32.PostMessageW(hwnd, WM_RBUTTONUP, 0x0000, lp)
    else:
        user32.PostMessageW(hwnd, WM_LBUTTONDOWN, 0x0001, lp)
        time.sleep(hold)
        user32.PostMessageW(hwnd, WM_LBUTTONUP, 0x0000, lp)
    time.sleep(0.3)
    print("%s click" % button)


def sneak_click(hwnd):
    """按住潜行再右键，用于测试响指。"""
    vk = VK["VK_LSHIFT"]
    scan = user32.MapVirtualKeyW(vk, 0)
    user32.PostMessageW(hwnd, WM_KEYDOWN, vk, (scan << 16) | 1)
    time.sleep(0.25)
    click(hwnd, "right")
    time.sleep(0.1)
    user32.PostMessageW(hwnd, WM_KEYUP, vk, (scan << 16) | 1 | (1 << 30) | (1 << 31))
    print("sneak + right click")


def main():
    dpi_aware()
    hwnd, title = find_window()
    if not hwnd:
        raise SystemExit("找不到 Minecraft 窗口")

    cmd = sys.argv[1] if len(sys.argv) > 1 else "find"
    if cmd == "find":
        rect = wt.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(rect))
        print("hwnd=%s title=%r rect=(%d,%d)-(%d,%d) %dx%d" % (
            hwnd, title, rect.left, rect.top, rect.right, rect.bottom,
            rect.right - rect.left, rect.bottom - rect.top))
    elif cmd == "shot":
        shot(hwnd, sys.argv[2])
    elif cmd == "key":
        for name in sys.argv[2:]:
            key(hwnd, VK[name] if name in VK else int(name, 0))
        print("keys sent")
    elif cmd == "chat":
        chat(hwnd, sys.argv[2])
    elif cmd in ("rc", "lc"):
        click(hwnd, "right" if cmd == "rc" else "left")
    elif cmd == "src":
        sneak_click(hwnd)
    else:
        raise SystemExit("未知命令: %s" % cmd)


if __name__ == "__main__":
    main()
