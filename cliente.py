#!/usr/bin/env python3
"""
Cliente Python - Juego Multijugador de Ciberseguridad
Conecta al servidor de juego usando el protocolo TCP definido.
Uso: python3 cliente.py <host> <puerto>
"""

import tkinter as tk
from tkinter import ttk, messagebox, simpledialog
import socket
import threading
import sys
import math

# ─────────────────────────────────────────────
#  PALETA Y CONSTANTES VISUALES
# ─────────────────────────────────────────────
BG_DARK     = "#0a0e1a"
BG_PANEL    = "#111827"
BG_CARD     = "#1a2235"
ACCENT_CYAN = "#00e5ff"
ACCENT_RED  = "#ff3b6b"
ACCENT_GREEN= "#00ff9f"
ACCENT_AMBER= "#ffb700"
TEXT_MAIN   = "#e2e8f0"
TEXT_DIM    = "#64748b"
BORDER      = "#1e3a5f"

PLANO_W = 500   # píxeles del canvas
PLANO_H = 500
PLANO_MAX = 100 # coordenadas lógicas del servidor


def logico_a_px(val, max_px=PLANO_W):
    """Convierte coordenada lógica (0-100) a píxeles."""
    return int(val / PLANO_MAX * max_px)


def px_a_logico(px, max_px=PLANO_W):
    return int(px / max_px * PLANO_MAX)


# ─────────────────────────────────────────────
#  CAPA DE RED
# ─────────────────────────────────────────────
class ConexionJuego:
    def __init__(self, host, puerto):
        self.host = host
        self.puerto = puerto
        self.sock = None
        self._buf = ""
        self._lock = threading.Lock()

    def conectar(self):
        """Resuelve el nombre (sin IPs hardcodeadas) y conecta."""
        try:
            info = socket.getaddrinfo(self.host, self.puerto,
                                      socket.AF_INET, socket.SOCK_STREAM)
            if not info:
                raise OSError("DNS sin resultados")
            ip, puerto = info[0][4]
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.connect((ip, puerto))
            return True
        except OSError as e:
            print(f"[ERROR] Conexión fallida: {e}")
            return False

    def enviar(self, mensaje: str):
        """Envía un mensaje asegurando el delimitador \\n."""
        if not mensaje.endswith("\n"):
            mensaje += "\n"
        try:
            with self._lock:
                self.sock.sendall(mensaje.encode())
        except OSError as e:
            print(f"[ERROR] enviar: {e}")

    def recibir_linea(self) -> str | None:
        """Devuelve la siguiente línea completa del buffer, o None."""
        while "\n" not in self._buf:
            try:
                datos = self.sock.recv(4096)
                if not datos:
                    return None          # servidor cerró
                self._buf += datos.decode(errors="replace")
            except OSError:
                return None
        linea, self._buf = self._buf.split("\n", 1)
        return linea.strip()

    def cerrar(self):
        try:
            if self.sock:
                self.sock.close()
        except OSError:
            pass


# ─────────────────────────────────────────────
#  PANTALLA DE LOGIN
# ─────────────────────────────────────────────
class PantallaLogin(tk.Frame):
    def __init__(self, master, on_conectar):
        super().__init__(master, bg=BG_DARK)
        self.on_conectar = on_conectar
        self._build()

    def _build(self):
        # Encabezado
        tk.Label(self, text="⬡ CYBER\nSIM", font=("Courier", 28, "bold"),
                 fg=ACCENT_CYAN, bg=BG_DARK, justify="center").pack(pady=(60, 4))
        tk.Label(self, text="Simulador de Ciberseguridad · Multiplayer",
                 font=("Courier", 10), fg=TEXT_DIM, bg=BG_DARK).pack()

        # Card central
        card = tk.Frame(self, bg=BG_CARD, bd=0, highlightthickness=1,
                        highlightbackground=BORDER)
        card.pack(pady=40, padx=80, fill="x")

        def campo(parent, label, default="", show=""):
            tk.Label(parent, text=label, font=("Courier", 9),
                     fg=TEXT_DIM, bg=BG_CARD, anchor="w").pack(fill="x", padx=24, pady=(16,2))
            e = tk.Entry(parent, font=("Courier", 12), bg=BG_PANEL,
                         fg=TEXT_MAIN, insertbackground=ACCENT_CYAN,
                         bd=0, highlightthickness=1,
                         highlightbackground=BORDER,
                         highlightcolor=ACCENT_CYAN,
                         show=show)
            e.insert(0, default)
            e.pack(fill="x", padx=24, ipady=6)
            return e

        self.e_host   = campo(card, "HOST / DOMINIO", "localhost")
        self.e_puerto = campo(card, "PUERTO",          "8080")
        self.e_nombre = campo(card, "NOMBRE DE USUARIO")

        # Botón conectar
        btn = tk.Button(card, text="CONECTAR  →",
                        font=("Courier", 11, "bold"),
                        bg=ACCENT_CYAN, fg=BG_DARK,
                        activebackground="#00b8cc", activeforeground=BG_DARK,
                        bd=0, cursor="hand2", pady=10,
                        command=self._conectar)
        btn.pack(fill="x", padx=24, pady=(24, 28))

        self.lbl_estado = tk.Label(self, text="", font=("Courier", 9),
                                   fg=ACCENT_RED, bg=BG_DARK)
        self.lbl_estado.pack()

    def _conectar(self):
        host   = self.e_host.get().strip()
        puerto = self.e_puerto.get().strip()
        nombre = self.e_nombre.get().strip()

        if not host or not puerto or not nombre:
            self.lbl_estado.config(text="⚠  Completa todos los campos.")
            return
        try:
            puerto = int(puerto)
        except ValueError:
            self.lbl_estado.config(text="⚠  Puerto debe ser un número.")
            return

        self.lbl_estado.config(text="Conectando…", fg=ACCENT_AMBER)
        self.update()
        self.on_conectar(host, puerto, nombre)

    def mostrar_error(self, msg):
        self.lbl_estado.config(text=f"⚠  {msg}", fg=ACCENT_RED)


# ─────────────────────────────────────────────
#  PANTALLA DE LOBBY (LIST + JOIN)
# ─────────────────────────────────────────────
class PantallaLobby(tk.Frame):
    def __init__(self, master, nombre, conexion, on_unirse):
        super().__init__(master, bg=BG_DARK)
        self.nombre   = nombre
        self.conexion = conexion
        self.on_unirse = on_unirse
        self._build()
        self._actualizar_salas()

    def _build(self):
        top = tk.Frame(self, bg=BG_DARK)
        top.pack(fill="x", padx=24, pady=(20, 0))
        tk.Label(top, text="⬡ LOBBY", font=("Courier", 18, "bold"),
                 fg=ACCENT_CYAN, bg=BG_DARK).pack(side="left")
        tk.Label(top, text=f"Usuario: {self.nombre}", font=("Courier", 10),
                 fg=TEXT_DIM, bg=BG_DARK).pack(side="right", pady=8)

        tk.Label(self, text="Selecciona una sala para unirte",
                 font=("Courier", 9), fg=TEXT_DIM, bg=BG_DARK).pack(anchor="w", padx=24)

        # Lista de salas
        frame_lista = tk.Frame(self, bg=BG_CARD, highlightthickness=1,
                               highlightbackground=BORDER)
        frame_lista.pack(fill="both", expand=True, padx=24, pady=12)

        self.listbox = tk.Listbox(frame_lista,
                                  font=("Courier", 12),
                                  bg=BG_CARD, fg=TEXT_MAIN,
                                  selectbackground=ACCENT_CYAN,
                                  selectforeground=BG_DARK,
                                  bd=0, highlightthickness=0,
                                  activestyle="none")
        self.listbox.pack(fill="both", expand=True, padx=8, pady=8)

        # Botones
        bframe = tk.Frame(self, bg=BG_DARK)
        bframe.pack(fill="x", padx=24, pady=(0, 20))

        tk.Button(bframe, text="↺  ACTUALIZAR",
                  font=("Courier", 10), bg=BG_CARD, fg=ACCENT_CYAN,
                  bd=0, cursor="hand2", pady=8, padx=16,
                  activebackground=BG_PANEL, activeforeground=ACCENT_CYAN,
                  command=self._actualizar_salas).pack(side="left")

        tk.Button(bframe, text="UNIRSE  →",
                  font=("Courier", 10, "bold"), bg=ACCENT_CYAN, fg=BG_DARK,
                  bd=0, cursor="hand2", pady=8, padx=20,
                  activebackground="#00b8cc", activeforeground=BG_DARK,
                  command=self._unirse).pack(side="right")

        self.lbl_estado = tk.Label(self, text="", font=("Courier", 9),
                                   fg=ACCENT_RED, bg=BG_DARK)
        self.lbl_estado.pack()

    def _actualizar_salas(self):
        self.conexion.enviar("LIST")
        resp = self.conexion.recibir_linea()
        self.listbox.delete(0, tk.END)
        if resp and resp.startswith("ROOMS|"):
            ids = resp.split("|")[1].split(",")
            for sid in ids:
                sid = sid.strip()
                if sid:
                    self.listbox.insert(tk.END, f"  Sala {sid}")
        elif resp == "ERR|NO_ROOMS":
            self.listbox.insert(tk.END, "  (No hay salas disponibles)")
        else:
            self.lbl_estado.config(text=f"Respuesta inesperada: {resp}")

    def _unirse(self):
        sel = self.listbox.curselection()
        if not sel:
            self.lbl_estado.config(text="⚠  Selecciona una sala primero.")
            return
        texto = self.listbox.get(sel[0])
        try:
            sala_id = texto.strip().split()[-1]
            int(sala_id)  # valida que sea número
        except (ValueError, IndexError):
            self.lbl_estado.config(text="⚠  Sala inválida.")
            return

        self.conexion.enviar(f"JOIN|{self.nombre}|{sala_id}")
        resp = self.conexion.recibir_linea()
        if resp and resp.startswith("OK|JOIN|"):
            partes = resp.split("|")
            # OK|JOIN|nombre|rol|sala
            nombre = partes[2]
            rol    = partes[3]   # 'A' o 'D'
            sala   = partes[4]
            self.on_unirse(nombre, rol, sala)
        else:
            self.lbl_estado.config(text=f"Error: {resp}")


# ─────────────────────────────────────────────
#  PANTALLA DE ESPERA (antes de EVENT|START)
# ─────────────────────────────────────────────
class PantallaEspera(tk.Frame):
    def __init__(self, master, nombre, rol, sala):
        super().__init__(master, bg=BG_DARK)
        self._build(nombre, rol, sala)

    def _build(self, nombre, rol, sala):
        self.pack_propagate(False)
        tk.Label(self, text="⬡", font=("Courier", 48),
                 fg=ACCENT_CYAN, bg=BG_DARK).pack(pady=(80, 8))
        tk.Label(self, text="ESPERANDO JUGADORES",
                 font=("Courier", 16, "bold"),
                 fg=TEXT_MAIN, bg=BG_DARK).pack()

        info = f"Sala {sala}  ·  Usuario: {nombre}  ·  Rol: {'ATACANTE' if rol=='A' else 'DEFENSOR'}"
        tk.Label(self, text=info, font=("Courier", 9),
                 fg=TEXT_DIM, bg=BG_DARK).pack(pady=8)

        color_rol = ACCENT_RED if rol == "A" else ACCENT_CYAN
        tk.Label(self, text="ATACANTE" if rol == "A" else "DEFENSOR",
                 font=("Courier", 22, "bold"),
                 fg=color_rol, bg=BG_DARK).pack(pady=4)

        tk.Label(self, text="La partida iniciará cuando haya\nal menos 1 atacante y 1 defensor.",
                 font=("Courier", 9), fg=TEXT_DIM, bg=BG_DARK,
                 justify="center").pack(pady=20)


# ─────────────────────────────────────────────
#  PANTALLA DE JUEGO
# ─────────────────────────────────────────────
class PantallaJuego(tk.Frame):
    RECURSOS = [
        {"id": 0, "x": 20, "y": 20},
        {"id": 1, "x": 80, "y": 80},
    ]

    def __init__(self, master, nombre, rol, sala, conexion, on_salir):
        super().__init__(master, bg=BG_DARK)
        self.nombre   = nombre
        self.rol      = rol       # 'A' o 'D'
        self.sala     = sala
        self.conexion = conexion
        self.on_salir = on_salir

        # Estado local
        self.pos_x = 0
        self.pos_y = 0
        self.jugadores = {}         # nombre -> (x, y, rol)
        self.recursos  = {r["id"]: {"x": r["x"], "y": r["y"], "estado": 0}
                          for r in self.RECURSOS}
        self.alerta_activa = {}     # id_recurso -> tiempo_restante
        self.juego_terminado = False

        self._build()

    # ── UI ──────────────────────────────────────
    def _build(self):
        # ─ Barra superior
        topbar = tk.Frame(self, bg=BG_PANEL, pady=6)
        topbar.pack(fill="x")

        color_rol = ACCENT_RED if self.rol == "A" else ACCENT_CYAN
        rol_txt   = "⚔  ATACANTE" if self.rol == "A" else "🛡  DEFENSOR"
        tk.Label(topbar, text="⬡ CYBER SIM", font=("Courier", 13, "bold"),
                 fg=ACCENT_CYAN, bg=BG_PANEL).pack(side="left", padx=16)
        tk.Label(topbar, text=rol_txt, font=("Courier", 11, "bold"),
                 fg=color_rol, bg=BG_PANEL).pack(side="left", padx=8)
        tk.Label(topbar, text=f"Sala {self.sala}  ·  {self.nombre}",
                 font=("Courier", 9), fg=TEXT_DIM, bg=BG_PANEL).pack(side="left", padx=8)

        tk.Button(topbar, text="SALIR", font=("Courier", 9),
                  bg=BG_DARK, fg=ACCENT_RED, bd=0, cursor="hand2",
                  activebackground=BG_PANEL, activeforeground=ACCENT_RED,
                  command=self._salir).pack(side="right", padx=16)

        # ─ Cuerpo principal
        body = tk.Frame(self, bg=BG_DARK)
        body.pack(fill="both", expand=True)

        # Canvas del plano
        canvas_frame = tk.Frame(body, bg=BORDER, bd=1)
        canvas_frame.pack(side="left", padx=(16, 8), pady=16)

        self.canvas = tk.Canvas(canvas_frame, width=PLANO_W, height=PLANO_H,
                                bg="#050a12", highlightthickness=0, cursor="crosshair")
        self.canvas.pack()
        self._dibujar_grid()

        # Click para moverse
        self.canvas.bind("<Button-1>", self._click_mover)

        # ─ Panel lateral
        panel = tk.Frame(body, bg=BG_PANEL, width=220)
        panel.pack(side="right", fill="y", padx=(0, 16), pady=16)
        panel.pack_propagate(False)

        tk.Label(panel, text="POSICIÓN", font=("Courier", 8),
                 fg=TEXT_DIM, bg=BG_PANEL).pack(anchor="w", padx=12, pady=(16, 2))
        self.lbl_pos = tk.Label(panel, text="X: 0  Y: 0",
                                font=("Courier", 14, "bold"),
                                fg=ACCENT_CYAN, bg=BG_PANEL)
        self.lbl_pos.pack(anchor="w", padx=12)

        # Instrucciones de movimiento
        tk.Label(panel, text="Haz clic en el mapa para moverte",
                 font=("Courier", 7), fg=TEXT_DIM, bg=BG_PANEL,
                 wraplength=190, justify="left").pack(anchor="w", padx=12, pady=(2, 12))

        sep = tk.Frame(panel, bg=BORDER, height=1)
        sep.pack(fill="x", padx=12)

        # Botones según rol
        if self.rol == "A":
            self._build_panel_atacante(panel)
        else:
            self._build_panel_defensor(panel)

        sep2 = tk.Frame(panel, bg=BORDER, height=1)
        sep2.pack(fill="x", padx=12, pady=8)

        tk.Label(panel, text="LOG", font=("Courier", 8),
                 fg=TEXT_DIM, bg=BG_PANEL).pack(anchor="w", padx=12)
        self.log_text = tk.Text(panel, font=("Courier", 8),
                                bg=BG_DARK, fg=TEXT_MAIN,
                                bd=0, highlightthickness=0,
                                state="disabled", height=10, wrap="word")
        self.log_text.pack(fill="both", expand=True, padx=8, pady=(4, 12))
        self.log_text.tag_config("ok",    foreground=ACCENT_GREEN)
        self.log_text.tag_config("err",   foreground=ACCENT_RED)
        self.log_text.tag_config("event", foreground=ACCENT_AMBER)

    def _build_panel_atacante(self, panel):
        tk.Label(panel, text="ACCIONES · ATACANTE", font=("Courier", 8),
                 fg=ACCENT_RED, bg=BG_PANEL).pack(anchor="w", padx=12, pady=(12, 4))

        tk.Button(panel, text="◉  SCAN  (radio 20)",
                  font=("Courier", 10, "bold"), bg=BG_DARK, fg=ACCENT_AMBER,
                  bd=0, cursor="hand2", pady=8, anchor="w", padx=12,
                  activebackground=BG_CARD, activeforeground=ACCENT_AMBER,
                  command=self._accion_scan).pack(fill="x", padx=8, pady=2)

        tk.Label(panel, text="ID recurso a atacar:", font=("Courier", 8),
                 fg=TEXT_DIM, bg=BG_PANEL).pack(anchor="w", padx=12, pady=(8, 2))
        self.entry_rec = tk.Entry(panel, font=("Courier", 11),
                                  bg=BG_DARK, fg=TEXT_MAIN,
                                  insertbackground=ACCENT_CYAN,
                                  bd=0, highlightthickness=1,
                                  highlightbackground=BORDER,
                                  highlightcolor=ACCENT_RED)
        self.entry_rec.pack(fill="x", padx=12, ipady=4)

        tk.Button(panel, text="⚡  ATTACK",
                  font=("Courier", 10, "bold"), bg=ACCENT_RED, fg=BG_DARK,
                  bd=0, cursor="hand2", pady=8,
                  activebackground="#cc2255", activeforeground=BG_DARK,
                  command=self._accion_attack).pack(fill="x", padx=8, pady=(6, 2))

    def _build_panel_defensor(self, panel):
        tk.Label(panel, text="ACCIONES · DEFENSOR", font=("Courier", 8),
                 fg=ACCENT_CYAN, bg=BG_PANEL).pack(anchor="w", padx=12, pady=(12, 4))

        tk.Label(panel, text="Recursos críticos en posiciones\nconocidas: (20,20) y (80,80)",
                 font=("Courier", 8), fg=TEXT_DIM, bg=BG_PANEL,
                 justify="left").pack(anchor="w", padx=12, pady=(4, 8))

        tk.Label(panel, text="ID recurso a defender:", font=("Courier", 8),
                 fg=TEXT_DIM, bg=BG_PANEL).pack(anchor="w", padx=12, pady=(4, 2))
        self.entry_rec = tk.Entry(panel, font=("Courier", 11),
                                  bg=BG_DARK, fg=TEXT_MAIN,
                                  insertbackground=ACCENT_CYAN,
                                  bd=0, highlightthickness=1,
                                  highlightbackground=BORDER,
                                  highlightcolor=ACCENT_CYAN)
        self.entry_rec.pack(fill="x", padx=12, ipady=4)

        tk.Button(panel, text="🛡  DEFEND",
                  font=("Courier", 10, "bold"), bg=ACCENT_CYAN, fg=BG_DARK,
                  bd=0, cursor="hand2", pady=8,
                  activebackground="#00b8cc", activeforeground=BG_DARK,
                  command=self._accion_defend).pack(fill="x", padx=8, pady=(6, 2))

        # Alerta visual
        self.frame_alerta = tk.Frame(panel, bg=BG_PANEL)
        self.frame_alerta.pack(fill="x", padx=8, pady=4)
        self.lbl_alerta = tk.Label(self.frame_alerta, text="",
                                   font=("Courier", 9, "bold"),
                                   fg=ACCENT_RED, bg=BG_PANEL,
                                   wraplength=190, justify="left")
        self.lbl_alerta.pack(fill="x")

    # ── GRID DEL CANVAS ─────────────────────────
    def _dibujar_grid(self):
        paso = PLANO_W // 10
        for i in range(0, PLANO_W + 1, paso):
            self.canvas.create_line(i, 0, i, PLANO_H, fill="#0d1f33", width=1)
            self.canvas.create_line(0, i, PLANO_W, i, fill="#0d1f33", width=1)
        # Etiquetas de coordenadas
        for v in range(0, 101, 20):
            px = logico_a_px(v)
            self.canvas.create_text(px, PLANO_H - 4, text=str(v),
                                    fill=TEXT_DIM, font=("Courier", 7), anchor="s")
            if v > 0:
                self.canvas.create_text(4, PLANO_H - logico_a_px(v),
                                        text=str(v), fill=TEXT_DIM,
                                        font=("Courier", 7), anchor="w")

    # ── DIBUJO ───────────────────────────────────
    def _redibujar(self):
        self.canvas.delete("dynamic")

        # Recursos
        for rid, r in self.recursos.items():
            px = logico_a_px(r["x"])
            py = PLANO_H - logico_a_px(r["y"])
            color = ACCENT_GREEN if r["estado"] == 0 else \
                    ACCENT_RED   if r["estado"] == 1 else TEXT_DIM
            self.canvas.create_rectangle(px-10, py-10, px+10, py+10,
                                         outline=color, fill="#050a12",
                                         width=2, tags="dynamic")
            self.canvas.create_text(px, py, text=f"R{rid}",
                                    fill=color, font=("Courier", 8, "bold"),
                                    tags="dynamic")

        # Otros jugadores
        for nombre, (jx, jy, jrol) in self.jugadores.items():
            if nombre == self.nombre:
                continue
            px = logico_a_px(jx)
            py = PLANO_H - logico_a_px(jy)
            col = ACCENT_RED if jrol == "A" else ACCENT_CYAN
            self.canvas.create_oval(px-6, py-6, px+6, py+6,
                                    fill=col, outline="", tags="dynamic")
            self.canvas.create_text(px, py - 12, text=nombre,
                                    fill=col, font=("Courier", 7),
                                    tags="dynamic")

        # Mi jugador
        px = logico_a_px(self.pos_x)
        py = PLANO_H - logico_a_px(self.pos_y)
        col = ACCENT_RED if self.rol == "A" else ACCENT_CYAN

        # Radio de scan (atacante)
        if self.rol == "A":
            radio_px = logico_a_px(20)
            self.canvas.create_oval(px - radio_px, py - radio_px,
                                    px + radio_px, py + radio_px,
                                    outline=ACCENT_AMBER, fill="",
                                    dash=(4, 4), tags="dynamic")

        self.canvas.create_oval(px-9, py-9, px+9, py+9,
                                fill=col, outline="white", width=2,
                                tags="dynamic")
        self.canvas.create_text(px, py - 16, text=f"▲ {self.nombre}",
                                fill="white", font=("Courier", 8, "bold"),
                                tags="dynamic")

    # ── EVENTOS DE USUARIO ───────────────────────
    def _click_mover(self, event):
        if self.juego_terminado:
            return
        lx = px_a_logico(event.x)
        ly = px_a_logico(PLANO_H - event.y)
        lx = max(0, min(100, lx))
        ly = max(0, min(100, ly))
        self.conexion.enviar(f"MOVE|{lx}|{ly}")

    def _accion_scan(self):
        if self.juego_terminado:
            return
        self.conexion.enviar("SCAN")

    def _accion_attack(self):
        if self.juego_terminado:
            return
        rid = self.entry_rec.get().strip()
        if not rid.isdigit():
            self._log("⚠ Escribe un ID numérico de recurso.", "err")
            return
        self.conexion.enviar(f"ATTACK|{rid}")
        self.entry_rec.delete(0, tk.END)

    def _accion_defend(self):
        if self.juego_terminado:
            return
        rid = self.entry_rec.get().strip()
        if not rid.isdigit():
            self._log("⚠ Escribe un ID numérico de recurso.", "err")
            return
        self.conexion.enviar(f"DEFEND|{rid}")
        self.entry_rec.delete(0, tk.END)

    def _salir(self):
        self.conexion.enviar("EXIT")
        self.on_salir()

    # ── LOG ─────────────────────────────────────
    def _log(self, msg, tag="ok"):
        self.log_text.config(state="normal")
        self.log_text.insert(tk.END, msg + "\n", tag)
        self.log_text.see(tk.END)
        self.log_text.config(state="disabled")

    # ── PROCESAMIENTO DE RESPUESTAS/EVENTOS ─────
    def procesar_mensaje(self, msg: str):
        """Llamado desde el hilo de red; usa after() para tocar la UI."""
        self.after(0, self._manejar_msg, msg)

    def _manejar_msg(self, msg: str):
        partes = msg.split("|")
        cmd = partes[0]

        # ── Respuestas a comandos ──
        if cmd == "OK":
            sub = partes[1] if len(partes) > 1 else ""
            if sub == "MOVE":
                # La posición real la actualizamos cuando recibimos EVENT|MOVE propio
                pass
            elif sub == "SCAN":
                if len(partes) >= 5 and partes[2] != "NONE":
                    rid, rx, ry = partes[2], partes[3], partes[4]
                    self._log(f"◉ SCAN: Recurso {rid} en ({rx},{ry})", "event")
                else:
                    self._log("◉ SCAN: Sin recursos cercanos.", "ok")
            elif sub == "ATTACK":
                self._log("⚡ ATTACK enviado. Espera alertas.", "event")
            elif sub == "DEFEND":
                self._log("🛡 DEFEND exitoso.", "ok")
            elif sub == "EXIT":
                self.on_salir()
                return

        elif cmd == "ERR":
            self._log(f"✗ {msg}", "err")

        # ── Eventos asíncronos ──
        elif cmd == "EVENT":
            sub = partes[1] if len(partes) > 1 else ""

            if sub == "MOVE" and len(partes) >= 5:
                nombre, jx, jy = partes[2], int(partes[3]), int(partes[4])
                if nombre == self.nombre:
                    self.pos_x = jx
                    self.pos_y = jy
                    self.lbl_pos.config(text=f"X: {jx}  Y: {jy}")
                else:
                    rol_j = self.jugadores.get(nombre, (0, 0, "?"))[2]
                    self.jugadores[nombre] = (jx, jy, rol_j)

            elif sub == "ALERT" and len(partes) >= 6:
                rid, rx, ry, tiempo = partes[2], partes[3], partes[4], partes[5]
                self._log(f"🚨 ALERTA: Recurso {rid} en ({rx},{ry}) — {tiempo}s", "err")
                self.recursos[int(rid)]["estado"] = 1
                if hasattr(self, "lbl_alerta"):
                    self.lbl_alerta.config(
                        text=f"🚨 Ataque R{rid} ({rx},{ry})\n{tiempo}s para reparar")

            elif sub == "RESOLVED" and len(partes) >= 3:
                rid = int(partes[2])
                self.recursos[rid]["estado"] = 0
                self._log(f"✔ Recurso {rid} reparado.", "ok")
                if hasattr(self, "lbl_alerta"):
                    self.lbl_alerta.config(text="")

            elif sub == "DESTROYED" and len(partes) >= 3:
                rid = int(partes[2])
                self.recursos[rid]["estado"] = 2
                self._log(f"💀 Recurso {rid} destruido.", "err")

            elif sub == "GAMEOVER" and len(partes) >= 3:
                ganador = partes[2]
                self.juego_terminado = True
                txt = "¡ATACANTE GANÓ!" if ganador == "A" else "¡DEFENSOR GANÓ!"
                self._log(f"🏁 {txt}", "event")
                messagebox.showinfo("FIN DE PARTIDA", txt)

        self._redibujar()


# ─────────────────────────────────────────────
#  APLICACIÓN PRINCIPAL
# ─────────────────────────────────────────────
class App(tk.Tk):
    def __init__(self, host_default="localhost", puerto_default=8080):
        super().__init__()
        self.title("CyberSim · Cliente de Juego")
        self.configure(bg=BG_DARK)
        self.resizable(True, True)

        self.host_default   = host_default
        self.puerto_default = puerto_default

        self.conexion   = None
        self.pantalla   = None
        self._hilo_recv = None
        self._escuchando = False

        self._mostrar_login()

    # ── NAVEGACIÓN ───────────────────────────────
    def _limpiar(self):
        if self.pantalla:
            self.pantalla.destroy()
            self.pantalla = None

    def _mostrar_login(self):
        self._limpiar()
        self.geometry("520x600")
        self.pantalla = PantallaLogin(self, self._on_conectar)
        self.pantalla.pack(fill="both", expand=True)
        # Pre-rellenar host/puerto si vienen de argv
        self.pantalla.e_host.delete(0, tk.END)
        self.pantalla.e_host.insert(0, self.host_default)
        self.pantalla.e_puerto.delete(0, tk.END)
        self.pantalla.e_puerto.insert(0, str(self.puerto_default))

    def _mostrar_lobby(self, nombre):
        self._limpiar()
        self.geometry("560x440")
        self.pantalla = PantallaLobby(self, nombre, self.conexion,
                                      self._on_unirse)
        self.pantalla.pack(fill="both", expand=True)

    def _mostrar_espera(self, nombre, rol, sala):
        self._limpiar()
        self.geometry("520x380")
        self.pantalla = PantallaEspera(self, nombre, rol, sala)
        self.pantalla.pack(fill="both", expand=True)

    def _mostrar_juego(self, nombre, rol, sala):
        self._limpiar()
        self.geometry(f"{PLANO_W + 260}x{PLANO_H + 60}")
        self.pantalla = PantallaJuego(self, nombre, rol, sala,
                                      self.conexion, self._on_salir_juego)
        self.pantalla.pack(fill="both", expand=True)

    # ── CALLBACKS ────────────────────────────────
    def _on_conectar(self, host, puerto, nombre):
        self.nombre_usuario = nombre
        self.conexion = ConexionJuego(host, puerto)
        if not self.conexion.conectar():
            self.pantalla.mostrar_error("No se pudo conectar al servidor.")
            return
        self._mostrar_lobby(nombre)

    def _on_unirse(self, nombre, rol, sala):
        self.rol_actual  = rol
        self.sala_actual = sala
        self._mostrar_espera(nombre, rol, sala)
        # Iniciar hilo de recepción asíncrona
        self._escuchando = True
        self._hilo_recv = threading.Thread(
            target=self._bucle_recepcion,
            args=(nombre, rol, sala),
            daemon=True
        )
        self._hilo_recv.start()

    def _on_salir_juego(self):
        self._escuchando = False
        if self.conexion:
            self.conexion.cerrar()
        self._mostrar_login()

    # ── HILO DE RECEPCIÓN ────────────────────────
    def _bucle_recepcion(self, nombre, rol, sala):
        """Corre en hilo separado. Reenvía mensajes a la UI via after()."""
        juego_iniciado = False
        while self._escuchando:
            linea = self.conexion.recibir_linea()
            if linea is None:
                self.after(0, lambda: messagebox.showerror(
                    "Desconectado", "Se perdió la conexión con el servidor."))
                self.after(0, self._on_salir_juego)
                break

            if linea == "EVENT|START" and not juego_iniciado:
                juego_iniciado = True
                self.after(0, self._mostrar_juego, nombre, rol, sala)
            elif juego_iniciado and isinstance(self.pantalla, PantallaJuego):
                self.pantalla.procesar_mensaje(linea)


# ─────────────────────────────────────────────
#  ENTRADA
# ─────────────────────────────────────────────
if __name__ == "__main__":
    host   = sys.argv[1] if len(sys.argv) > 1 else "localhost"
    puerto = int(sys.argv[2]) if len(sys.argv) > 2 else 8080
    app = App(host, puerto)
    app.mainloop()
