# -*- coding: utf-8 -*-
"""USB 스피커 - PC 프로그램 v2 (Windows)
PC 소리를 캡처해 USB 테더링(또는 Wi-Fi)으로 폰에 보냅니다.
v2: PIN 인증, GUI 창, 음소거, 기본 출력 장치 변경 자동 감지"""
import hashlib
import hmac
import os
import queue
import random
import socket
import struct
import threading
import time
import tkinter as tk

import numpy as np
import pyaudiowpatch as pyaudio

DISCOVERY_PORT = 50505
TCP_PORT = 50506


def recvn(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise OSError("closed")
        buf += chunk
    return buf


class Engine:
    """오디오 캡처 + 네트워크 전송"""

    def __init__(self, ui):
        self.ui = ui
        self.pin = f"{random.SystemRandom().randint(0, 999999):06d}"
        self.mute = False
        self.client = None
        self.client_addr = ""
        self.lock = threading.Lock()
        self.q = queue.Queue(maxsize=20)

        self.p = None
        self.stream = None
        self.rate = 48000
        self.in_ch = 2
        self.out_ch = 2
        self.dev_name = ""

        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp.bind(("0.0.0.0", DISCOVERY_PORT))
        self.tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.tcp.bind(("0.0.0.0", TCP_PORT))
        self.tcp.listen(2)

    # ----- 캡처 -----
    def _find_loopback(self, p):
        wasapi = p.get_host_api_info_by_type(pyaudio.paWASAPI)
        speakers = p.get_device_info_by_index(wasapi["defaultOutputDevice"])
        if speakers.get("isLoopbackDevice"):
            return speakers
        for lb in p.get_loopback_device_info_generator():
            if speakers["name"] in lb["name"]:
                return lb
        raise RuntimeError("기본 스피커의 루프백 장치를 찾지 못했습니다.")

    def open_capture(self):
        self.close_capture()
        self.p = pyaudio.PyAudio()
        dev = self._find_loopback(self.p)
        self.dev_name = dev["name"]
        self.rate = int(dev["defaultSampleRate"])
        self.in_ch = int(dev["maxInputChannels"])
        self.out_ch = 1 if self.in_ch == 1 else 2
        self.stream = self.p.open(
            format=pyaudio.paInt16, channels=self.in_ch, rate=self.rate, input=True,
            input_device_index=dev["index"], frames_per_buffer=self.rate // 100,
            stream_callback=self._callback)
        self.stream.start_stream()
        self.ui.set_device(f"{self.dev_name} ({self.rate}Hz)")
        self.ui.log(f"소리를 가져올 장치: {self.dev_name}")

    def close_capture(self):
        try:
            if self.stream:
                self.stream.stop_stream()
                self.stream.close()
            if self.p:
                self.p.terminate()
        except Exception:
            pass
        self.stream = None
        self.p = None

    def restart_capture(self):
        self.ui.log("오디오 장치를 다시 불러옵니다…")
        with self.lock:
            c, self.client = self.client, None
        if c:
            try:
                c.close()
            except OSError:
                pass
        try:
            self.open_capture()
        except Exception as e:
            self.ui.log(f"장치 열기 실패: {e}")
            self.ui.set_device("장치 없음 - [장치 다시 불러오기]를 눌러보세요")

    def _callback(self, in_data, frame_count, time_info, status):
        if self.client is not None:
            if self.mute:
                in_data = bytes(frame_count * self.out_ch * 2)
            elif self.in_ch > 2:
                a = np.frombuffer(in_data, dtype=np.int16).reshape(-1, self.in_ch)[:, :2]
                in_data = a.tobytes()
            try:
                self.q.put_nowait(in_data)
            except queue.Full:
                try:
                    self.q.get_nowait()
                    self.q.put_nowait(in_data)
                except (queue.Empty, queue.Full):
                    pass
        return (None, pyaudio.paContinue)

    # ----- 네트워크 -----
    def start_threads(self):
        for fn in (self._discovery, self._accept, self._send, self._watch_device):
            threading.Thread(target=fn, daemon=True).start()

    def _discovery(self):
        while True:
            try:
                data, addr = self.udp.recvfrom(64)
                if data.startswith(b"SPKR_DISCOVER2"):
                    self.udp.sendto(f"SPKR_HERE {TCP_PORT}".encode(), addr)
            except OSError:
                time.sleep(0.2)

    def _accept(self):
        while True:
            conn, addr = self.tcp.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            conn.settimeout(5)
            try:
                nonce = os.urandom(16)
                conn.sendall(b"SPK2" + nonce)
                mac = recvn(conn, 32)
                expected = hmac.new(self.pin.encode(), nonce, hashlib.sha256).digest()
                if not hmac.compare_digest(mac, expected):
                    conn.sendall(b"\x00")
                    conn.close()
                    self.ui.log(f"PIN 불일치 - 연결 거부: {addr[0]}")
                    continue
                conn.sendall(b"\x01" + struct.pack(">II", self.rate, self.out_ch))
            except OSError:
                try:
                    conn.close()
                except OSError:
                    pass
                continue
            while not self.q.empty():
                try:
                    self.q.get_nowait()
                except queue.Empty:
                    break
            with self.lock:
                old, self.client = self.client, conn
                self.client_addr = addr[0]
            if old:
                try:
                    old.close()
                except OSError:
                    pass
            self.ui.set_client(addr[0])
            self.ui.log(f"폰 연결됨: {addr[0]}")

    def _send(self):
        while True:
            try:
                data = self.q.get(timeout=0.5)
            except queue.Empty:
                data = bytes((self.rate // 100) * self.out_ch * 2)
            with self.lock:
                c = self.client
            if c is None:
                continue
            try:
                c.sendall(data)
            except OSError:
                with self.lock:
                    if self.client is c:
                        self.client = None
                        self.client_addr = ""
                try:
                    c.close()
                except OSError:
                    pass
                self.ui.set_client("")
                self.ui.log("폰 연결이 끊어졌습니다. 다시 연결을 기다립니다.")

    def _watch_device(self):
        """기본 출력 장치가 바뀌면 자동으로 다시 캡처"""
        while True:
            time.sleep(3)
            try:
                p2 = pyaudio.PyAudio()
                try:
                    wasapi = p2.get_host_api_info_by_type(pyaudio.paWASAPI)
                    name = p2.get_device_info_by_index(wasapi["defaultOutputDevice"])["name"]
                finally:
                    p2.terminate()
                if self.dev_name and name and name not in self.dev_name and self.dev_name not in name:
                    self.ui.log(f"기본 출력 장치 변경 감지: {name}")
                    self.restart_capture()
            except Exception:
                pass


class UI:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("USB 스피커 PC")
        self.root.geometry("420x430")
        self.root.resizable(False, False)
        self.msgs = queue.Queue()

        bg = "#1e1f26"
        card = "#2a2c36"
        fg = "#e8e8ee"
        sub = "#9aa0b0"
        accent = "#4f9cff"
        self.ok = "#39d98a"
        self.idle = "#f0a830"
        self.root.configure(bg=bg)

        tk.Label(self.root, text="USB 스피커", bg=bg, fg=fg,
                 font=("Malgun Gothic", 16, "bold")).pack(pady=(14, 2))
        tk.Label(self.root, text="폰 앱에 아래 PIN을 입력하세요", bg=bg, fg=sub,
                 font=("Malgun Gothic", 9)).pack()

        pinf = tk.Frame(self.root, bg=card)
        pinf.pack(pady=8, padx=16, fill="x")
        self.pin_label = tk.Label(pinf, text="------", bg=card, fg=accent,
                                  font=("Consolas", 30, "bold"))
        self.pin_label.pack(pady=8)

        self.status = tk.Label(self.root, text="● 폰 연결 대기 중", bg=bg, fg=self.idle,
                               font=("Malgun Gothic", 11, "bold"))
        self.status.pack(pady=(4, 0))
        self.device = tk.Label(self.root, text="", bg=bg, fg=sub,
                               font=("Malgun Gothic", 9), wraplength=380)
        self.device.pack()

        self.mute_var = tk.BooleanVar(value=False)
        tk.Checkbutton(self.root, text="음소거 (폰으로 소리 안 보내기)", variable=self.mute_var,
                       command=self._on_mute, bg=bg, fg=fg, selectcolor=card,
                       activebackground=bg, activeforeground=fg,
                       font=("Malgun Gothic", 10)).pack(pady=6)
        tk.Button(self.root, text="장치 다시 불러오기", command=self._on_reload,
                  bg=card, fg=fg, relief="flat", font=("Malgun Gothic", 10),
                  activebackground="#3a3d4a", activeforeground=fg).pack()

        self.logbox = tk.Text(self.root, height=8, bg=card, fg=sub, relief="flat",
                              font=("Malgun Gothic", 9), state="disabled")
        self.logbox.pack(padx=16, pady=10, fill="both", expand=True)

        self.engine = None
        self.root.after(200, self._poll)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    def bind_engine(self, engine):
        self.engine = engine
        self.pin_label.config(text=engine.pin)

    def _on_mute(self):
        if self.engine:
            self.engine.mute = self.mute_var.get()

    def _on_reload(self):
        if self.engine:
            threading.Thread(target=self.engine.restart_capture, daemon=True).start()

    def _on_close(self):
        try:
            if self.engine:
                self.engine.close_capture()
        finally:
            self.root.destroy()
            os._exit(0)

    # 스레드에서 안전하게 호출
    def log(self, msg):
        self.msgs.put(("log", msg))

    def set_client(self, ip):
        self.msgs.put(("client", ip))

    def set_device(self, name):
        self.msgs.put(("device", name))

    def _poll(self):
        try:
            while True:
                kind, val = self.msgs.get_nowait()
                if kind == "log":
                    self.logbox.config(state="normal")
                    self.logbox.insert("end", time.strftime("[%H:%M:%S] ") + val + "\n")
                    self.logbox.see("end")
                    self.logbox.config(state="disabled")
                elif kind == "client":
                    if val:
                        self.status.config(text=f"● 재생 중 - 폰: {val}", fg=self.ok)
                    else:
                        self.status.config(text="● 폰 연결 대기 중", fg=self.idle)
                elif kind == "device":
                    self.device.config(text=val)
        except queue.Empty:
            pass
        self.root.after(200, self._poll)


def main():
    ui = UI()
    try:
        engine = Engine(ui)
    except OSError:
        ui.log("오류: 포트를 열 수 없습니다. 프로그램이 이미 실행 중인지 확인하세요.")
        ui.root.mainloop()
        return
    ui.bind_engine(engine)
    try:
        engine.open_capture()
    except Exception as e:
        ui.log(f"장치 열기 실패: {e}")
    engine.start_threads()
    ui.log("준비 완료. 폰 앱에 PIN을 입력하고 [시작]을 누르세요.")
    ui.root.mainloop()


if __name__ == "__main__":
    main()
