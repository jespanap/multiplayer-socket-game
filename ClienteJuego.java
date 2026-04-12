import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cliente Java - Juego Multijugador de Ciberseguridad
 * Uso: java ClienteJuego <host> <puerto>
 *
 * Mismo protocolo TCP que el cliente Python:
 *   COMANDO|PARAM1|PARAM2\n
 */
public class ClienteJuego {

    // ─── Paleta visual ───────────────────────────────────────────────────────
    static final Color BG_DARK    = new Color(0x0a0e1a);
    static final Color BG_PANEL   = new Color(0x111827);
    static final Color BG_CARD    = new Color(0x1a2235);
    static final Color CYAN       = new Color(0x00e5ff);
    static final Color RED        = new Color(0xff3b6b);
    static final Color GREEN      = new Color(0x00ff9f);
    static final Color AMBER      = new Color(0xffb700);
    static final Color TEXT_MAIN  = new Color(0xe2e8f0);
    static final Color TEXT_DIM   = new Color(0x64748b);
    static final Color BORDER     = new Color(0x1e3a5f);

    static final int PLANO_PX  = 500;
    static final int PLANO_MAX = 100;

    // ─── Punto de entrada ────────────────────────────────────────────────────
    public static void main(String[] args) {
        String host   = args.length > 0 ? args[0] : "localhost";
        int    puerto = args.length > 1 ? Integer.parseInt(args[1]) : 8080;

        SwingUtilities.invokeLater(() -> new VentanaPrincipal(host, puerto).setVisible(true));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CAPA DE RED
    // ════════════════════════════════════════════════════════════════════════
    static class Conexion {
        private Socket       sock;
        private PrintWriter  out;
        private BufferedReader in;
        private final String host;
        private final int    puerto;

        Conexion(String host, int puerto) {
            this.host   = host;
            this.puerto = puerto;
        }

        /** Resuelve DNS y conecta. Sin IPs hardcodeadas. */
        boolean conectar() {
            try {
                InetAddress addr = InetAddress.getByName(host); // DNS lookup
                sock = new Socket(addr, puerto);
                out  = new PrintWriter(new OutputStreamWriter(sock.getOutputStream()), true);
                in   = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                return true;
            } catch (IOException e) {
                System.err.println("[ERROR] Conexión fallida: " + e.getMessage());
                return false;
            }
        }

        /** Envía un comando; agrega \\n automáticamente. */
        void enviar(String msg) {
            if (out != null) out.println(msg);
        }

        /** Devuelve la siguiente línea del servidor, o null si se cerró. */
        String recibirLinea() {
            try {
                return in != null ? in.readLine() : null;
            } catch (IOException e) {
                return null;
            }
        }

        void cerrar() {
            try { if (sock != null) sock.close(); } catch (IOException ignored) {}
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  VENTANA PRINCIPAL (controlador de pantallas)
    // ════════════════════════════════════════════════════════════════════════
    static class VentanaPrincipal extends JFrame {
        private final String hostDefault;
        private final int    puertoDefault;
        private Conexion     conexion;
        private String       nombreUsuario;

        VentanaPrincipal(String host, int puerto) {
            this.hostDefault   = host;
            this.puertoDefault = puerto;
            setTitle("CyberSim · Cliente Java");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setBackground(BG_DARK);
            mostrarLogin();
        }

        void limpiar() { getContentPane().removeAll(); }

        // ── Pantallas ────────────────────────────────────────────────────────
        void mostrarLogin() {
            limpiar();
            setResizable(true);
            PantallaLogin p = new PantallaLogin(hostDefault, String.valueOf(puertoDefault),
                (host, puerto, nombre) -> {
                    nombreUsuario = nombre;
                    conexion = new Conexion(host, puerto);
                    if (!conexion.conectar()) {
                        JOptionPane.showMessageDialog(this,
                            "No se pudo conectar a " + host + ":" + puerto,
                            "Error de conexión", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    mostrarLobby();
                });
            setContentPane(p);
            setSize(520, 480);
            setLocationRelativeTo(null);
            revalidate(); repaint();
        }

        void mostrarLobby() {
            limpiar();
            PantallaLobby p = new PantallaLobby(nombreUsuario, conexion,
                (nombre, rol, sala) -> mostrarEspera(nombre, rol, sala));
            setContentPane(p);
            setSize(560, 420);
            setLocationRelativeTo(null);
            revalidate(); repaint();
        }

        void mostrarEspera(String nombre, String rol, String sala) {
            limpiar();
            setContentPane(new PantallaEspera(nombre, rol, sala));
            setSize(520, 360);
            setLocationRelativeTo(null);
            revalidate(); repaint();

            // Hilo que espera EVENT|START
            new Thread(() -> {
                while (true) {
                    String linea = conexion.recibirLinea();
                    if (linea == null) break;
                    if (linea.equals("EVENT|START")) {
                        SwingUtilities.invokeLater(() -> mostrarJuego(nombre, rol, sala));
                        break;
                    }
                }
            }, "hilo-espera").start();
        }

        void mostrarJuego(String nombre, String rol, String sala) {
            limpiar();
            PantallaJuego juego = new PantallaJuego(nombre, rol, sala, conexion,
                this::mostrarLogin);
            setContentPane(juego);
            setSize(PLANO_PX + 260, PLANO_PX + 60);
            setResizable(false);
            setLocationRelativeTo(null);
            revalidate(); repaint();

            // Hilo de recepción asíncrona
            new Thread(() -> {
                String linea;
                while ((linea = conexion.recibirLinea()) != null) {
                    final String msg = linea;
                    SwingUtilities.invokeLater(() -> juego.procesarMensaje(msg));
                }
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Conexión perdida con el servidor."));
                SwingUtilities.invokeLater(this::mostrarLogin);
            }, "hilo-recv").start();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PANTALLA LOGIN
    // ════════════════════════════════════════════════════════════════════════
    interface CallbackConectar { void conectar(String host, int puerto, String nombre); }

    static class PantallaLogin extends JPanel {
        PantallaLogin(String hostDef, String puertoDef, CallbackConectar cb) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(BG_DARK);
            setBorder(BorderFactory.createEmptyBorder(40, 60, 40, 60));

            // Título
            JLabel titulo = new JLabel("<html><center>⬡ CYBER<br>SIM</center></html>", SwingConstants.CENTER);
            titulo.setFont(new Font("Monospaced", Font.BOLD, 28));
            titulo.setForeground(CYAN);
            titulo.setAlignmentX(CENTER_ALIGNMENT);
            add(titulo);

            JLabel sub = new JLabel("Simulador de Ciberseguridad · Multiplayer");
            sub.setFont(new Font("Monospaced", Font.PLAIN, 10));
            sub.setForeground(TEXT_DIM);
            sub.setAlignmentX(CENTER_ALIGNMENT);
            add(sub);
            add(Box.createVerticalStrut(30));

            // Campos
            JTextField fHost   = campo(this, "HOST / DOMINIO", hostDef);
            JTextField fPuerto = campo(this, "PUERTO",          puertoDef);
            JTextField fNombre = campo(this, "NOMBRE DE USUARIO", "");
            add(Box.createVerticalStrut(20));

            // Botón
            JButton btn = boton("CONECTAR  →", CYAN, BG_DARK);
            btn.setAlignmentX(CENTER_ALIGNMENT);
            btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            add(btn);

            JLabel lblErr = new JLabel(" ");
            lblErr.setFont(new Font("Monospaced", Font.PLAIN, 9));
            lblErr.setForeground(RED);
            lblErr.setAlignmentX(CENTER_ALIGNMENT);
            add(Box.createVerticalStrut(8));
            add(lblErr);

            btn.addActionListener(e -> {
                String host   = fHost.getText().trim();
                String pStr   = fPuerto.getText().trim();
                String nombre = fNombre.getText().trim();
                if (host.isEmpty() || pStr.isEmpty() || nombre.isEmpty()) {
                    lblErr.setText("⚠  Completa todos los campos."); return;
                }
                try {
                    cb.conectar(host, Integer.parseInt(pStr), nombre);
                } catch (NumberFormatException ex) {
                    lblErr.setText("⚠  Puerto debe ser un número.");
                }
            });
        }

        private JTextField campo(JPanel parent, String label, String def) {
            JLabel lbl = new JLabel(label);
            lbl.setFont(new Font("Monospaced", Font.PLAIN, 9));
            lbl.setForeground(TEXT_DIM);
            lbl.setAlignmentX(LEFT_ALIGNMENT);
            parent.add(lbl);
            parent.add(Box.createVerticalStrut(2));

            JTextField f = new JTextField(def);
            f.setFont(new Font("Monospaced", Font.PLAIN, 13));
            f.setBackground(BG_PANEL);
            f.setForeground(TEXT_MAIN);
            f.setCaretColor(CYAN);
            f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            f.setAlignmentX(LEFT_ALIGNMENT);
            parent.add(f);
            parent.add(Box.createVerticalStrut(10));
            return f;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PANTALLA LOBBY
    // ════════════════════════════════════════════════════════════════════════
    interface CallbackUnirse { void unirse(String nombre, String rol, String sala); }

    static class PantallaLobby extends JPanel {
        private final DefaultListModel<String> modeloLista = new DefaultListModel<>();
        private final JList<String>            lista       = new JList<>(modeloLista);
        private final JLabel                   lblErr;
        private final Conexion                 conexion;
        private final String                   nombre;
        private final CallbackUnirse           cb;

        PantallaLobby(String nombre, Conexion conexion, CallbackUnirse cb) {
            this.nombre   = nombre;
            this.conexion = conexion;
            this.cb       = cb;

            setLayout(new BorderLayout(12, 12));
            setBackground(BG_DARK);
            setBorder(BorderFactory.createEmptyBorder(16, 24, 16, 24));

            // Top
            JPanel top = new JPanel(new BorderLayout());
            top.setBackground(BG_DARK);
            JLabel t = new JLabel("⬡ LOBBY");
            t.setFont(new Font("Monospaced", Font.BOLD, 18));
            t.setForeground(CYAN);
            JLabel u = new JLabel("Usuario: " + nombre);
            u.setFont(new Font("Monospaced", Font.PLAIN, 10));
            u.setForeground(TEXT_DIM);
            top.add(t, BorderLayout.WEST);
            top.add(u, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);

            // Lista
            lista.setBackground(BG_CARD);
            lista.setForeground(TEXT_MAIN);
            lista.setFont(new Font("Monospaced", Font.PLAIN, 13));
            lista.setSelectionBackground(CYAN);
            lista.setSelectionForeground(BG_DARK);
            lista.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            JScrollPane scroll = new JScrollPane(lista);
            scroll.setBorder(BorderFactory.createLineBorder(BORDER));
            scroll.setBackground(BG_CARD);
            add(scroll, BorderLayout.CENTER);

            // Botones
            JPanel bots = new JPanel(new BorderLayout(8, 0));
            bots.setBackground(BG_DARK);
            JButton btnAct   = boton("↺  ACTUALIZAR", BG_CARD, CYAN);
            JButton btnUnir  = boton("UNIRSE  →", CYAN, BG_DARK);
            lblErr = new JLabel(" ");
            lblErr.setFont(new Font("Monospaced", Font.PLAIN, 9));
            lblErr.setForeground(RED);
            bots.add(btnAct,  BorderLayout.WEST);
            bots.add(lblErr,  BorderLayout.CENTER);
            bots.add(btnUnir, BorderLayout.EAST);

            JPanel sur = new JPanel(new BorderLayout());
            sur.setBackground(BG_DARK);
            sur.add(bots, BorderLayout.CENTER);
            add(sur, BorderLayout.SOUTH);

            btnAct.addActionListener(e -> actualizarSalas());
            btnUnir.addActionListener(e -> unirse());
            actualizarSalas();
        }

        void actualizarSalas() {
            conexion.enviar("LIST");
            String resp = conexion.recibirLinea();
            modeloLista.clear();
            if (resp != null && resp.startsWith("ROOMS|")) {
                for (String id : resp.substring(6).split(",")) {
                    id = id.trim();
                    if (!id.isEmpty()) modeloLista.addElement("  Sala " + id);
                }
            } else {
                modeloLista.addElement("  (No hay salas disponibles)");
            }
        }

        void unirse() {
            String sel = lista.getSelectedValue();
            if (sel == null) { lblErr.setText("⚠  Selecciona una sala."); return; }
            String salaId = sel.trim().split("\\s+")[1];
            conexion.enviar("JOIN|" + nombre + "|" + salaId);
            String resp = conexion.recibirLinea();
            if (resp != null && resp.startsWith("OK|JOIN|")) {
                String[] p = resp.split("\\|");
                // OK|JOIN|nombre|rol|sala
                cb.unirse(p[2], p[3], p[4]);
            } else {
                lblErr.setText("Error: " + resp);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PANTALLA ESPERA
    // ════════════════════════════════════════════════════════════════════════
    static class PantallaEspera extends JPanel {
        PantallaEspera(String nombre, String rol, String sala) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(BG_DARK);
            setBorder(BorderFactory.createEmptyBorder(60, 40, 40, 40));

            JLabel ico = new JLabel("⬡", SwingConstants.CENTER);
            ico.setFont(new Font("Monospaced", Font.BOLD, 48));
            ico.setForeground(CYAN);
            ico.setAlignmentX(CENTER_ALIGNMENT);
            add(ico);

            JLabel t = new JLabel("ESPERANDO JUGADORES");
            t.setFont(new Font("Monospaced", Font.BOLD, 16));
            t.setForeground(TEXT_MAIN);
            t.setAlignmentX(CENTER_ALIGNMENT);
            add(t);
            add(Box.createVerticalStrut(12));

            JLabel info = new JLabel("Sala " + sala + "  ·  " + nombre);
            info.setFont(new Font("Monospaced", Font.PLAIN, 10));
            info.setForeground(TEXT_DIM);
            info.setAlignmentX(CENTER_ALIGNMENT);
            add(info);
            add(Box.createVerticalStrut(8));

            Color colRol = rol.equals("A") ? RED : CYAN;
            String txtRol = rol.equals("A") ? "ATACANTE" : "DEFENSOR";
            JLabel lRol = new JLabel(txtRol);
            lRol.setFont(new Font("Monospaced", Font.BOLD, 22));
            lRol.setForeground(colRol);
            lRol.setAlignmentX(CENTER_ALIGNMENT);
            add(lRol);
            add(Box.createVerticalStrut(20));

            JLabel msg = new JLabel("<html><center>La partida iniciará cuando haya<br>al menos 1 atacante y 1 defensor.</center></html>");
            msg.setFont(new Font("Monospaced", Font.PLAIN, 10));
            msg.setForeground(TEXT_DIM);
            msg.setAlignmentX(CENTER_ALIGNMENT);
            add(msg);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PANTALLA JUEGO
    // ════════════════════════════════════════════════════════════════════════
    static class PantallaJuego extends JPanel {
        private final String   nombre, rol, sala;
        private final Conexion conexion;
        private final Runnable onSalir;

        // Estado del juego
        private int posX = 0, posY = 0;
        private final ConcurrentHashMap<String, int[]> jugadores = new ConcurrentHashMap<>();
        // recursos: estado 0=ok, 1=atacado, 2=destruido
        private final int[][] recursos = {{0, 20, 20}, {1, 80, 80}};
        private final int[]   estadoRecursos = {0, 0};
        private boolean juegoTerminado = false;

        // UI
        private final PlanoCanvas canvas;
        private final JLabel      lblPos;
        private final JTextArea   logArea;
        private       JLabel      lblAlerta; // solo defensor
        private       JTextField  entryRec;

        PantallaJuego(String nombre, String rol, String sala, Conexion conexion, Runnable onSalir) {
            this.nombre   = nombre;
            this.rol      = rol;
            this.sala     = sala;
            this.conexion = conexion;
            this.onSalir  = onSalir;

            setLayout(new BorderLayout());
            setBackground(BG_DARK);

            // ─ Barra superior
            JPanel topbar = new JPanel(new BorderLayout());
            topbar.setBackground(BG_PANEL);
            topbar.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));

            JLabel lTitulo = new JLabel("⬡ CYBER SIM");
            lTitulo.setFont(new Font("Monospaced", Font.BOLD, 13));
            lTitulo.setForeground(CYAN);

            Color colRol = rol.equals("A") ? RED : CYAN;
            String txtRol = rol.equals("A") ? "⚔  ATACANTE" : "🛡  DEFENSOR";
            JLabel lRol = new JLabel("  " + txtRol);
            lRol.setFont(new Font("Monospaced", Font.BOLD, 11));
            lRol.setForeground(colRol);

            JLabel lInfo = new JLabel("Sala " + sala + "  ·  " + nombre);
            lInfo.setFont(new Font("Monospaced", Font.PLAIN, 9));
            lInfo.setForeground(TEXT_DIM);

            JPanel izq = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            izq.setBackground(BG_PANEL);
            izq.add(lTitulo); izq.add(lRol); izq.add(Box.createHorizontalStrut(12)); izq.add(lInfo);

            JButton btnSalir = boton("SALIR", BG_DARK, RED);
            btnSalir.addActionListener(e -> salir());

            topbar.add(izq, BorderLayout.WEST);
            topbar.add(btnSalir, BorderLayout.EAST);
            add(topbar, BorderLayout.NORTH);

            // ─ Cuerpo
            JPanel body = new JPanel(new BorderLayout(8, 0));
            body.setBackground(BG_DARK);
            body.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 0));

            // Canvas
            canvas = new PlanoCanvas();
            canvas.setPreferredSize(new Dimension(PLANO_PX, PLANO_PX));
            canvas.setBorder(BorderFactory.createLineBorder(BORDER));
            canvas.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { clickMover(e.getX(), e.getY()); }
            });
            canvas.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            body.add(canvas, BorderLayout.CENTER);

            // Panel lateral
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBackground(BG_PANEL);
            panel.setPreferredSize(new Dimension(220, PLANO_PX));
            panel.setBorder(BorderFactory.createEmptyBorder(12, 8, 12, 8));

            // Posición
            etiqueta(panel, "POSICIÓN", TEXT_DIM);
            lblPos = new JLabel("X: 0  Y: 0");
            lblPos.setFont(new Font("Monospaced", Font.BOLD, 14));
            lblPos.setForeground(CYAN);
            lblPos.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(lblPos);

            JLabel hint = new JLabel("Clic en el mapa para moverte");
            hint.setFont(new Font("Monospaced", Font.PLAIN, 8));
            hint.setForeground(TEXT_DIM);
            hint.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(hint);
            panel.add(separador());

            // Acciones según rol
            if (rol.equals("A")) buildPanelAtacante(panel);
            else                  buildPanelDefensor(panel);

            panel.add(separador());
            etiqueta(panel, "LOG", TEXT_DIM);

            logArea = new JTextArea();
            logArea.setFont(new Font("Monospaced", Font.PLAIN, 8));
            logArea.setBackground(BG_DARK);
            logArea.setForeground(TEXT_MAIN);
            logArea.setEditable(false);
            logArea.setLineWrap(true);
            logArea.setWrapStyleWord(true);
            JScrollPane scrollLog = new JScrollPane(logArea);
            scrollLog.setBorder(null);
            scrollLog.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(scrollLog);

            body.add(panel, BorderLayout.EAST);
            add(body, BorderLayout.CENTER);
        }

        // ── Construcción del panel según rol ─────────────────────────────────
        void buildPanelAtacante(JPanel p) {
            etiqueta(p, "ACCIONES · ATACANTE", RED);

            JButton btnScan = boton("◉  SCAN (radio 20)", BG_DARK, AMBER);
            btnScan.setAlignmentX(LEFT_ALIGNMENT);
            btnScan.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            btnScan.addActionListener(e -> conexion.enviar("SCAN"));
            p.add(btnScan);
            p.add(Box.createVerticalStrut(10));

            etiqueta(p, "ID recurso a atacar:", TEXT_DIM);
            entryRec = campoTexto();
            p.add(entryRec);
            p.add(Box.createVerticalStrut(4));

            JButton btnAtk = boton("⚡  ATTACK", RED, BG_DARK);
            btnAtk.setAlignmentX(LEFT_ALIGNMENT);
            btnAtk.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            btnAtk.addActionListener(e -> atacar());
            p.add(btnAtk);
        }

        void buildPanelDefensor(JPanel p) {
            etiqueta(p, "ACCIONES · DEFENSOR", CYAN);

            JLabel info = new JLabel("<html>Recursos en<br>(20,20) y (80,80)</html>");
            info.setFont(new Font("Monospaced", Font.PLAIN, 9));
            info.setForeground(TEXT_DIM);
            info.setAlignmentX(LEFT_ALIGNMENT);
            p.add(info);
            p.add(Box.createVerticalStrut(8));

            lblAlerta = new JLabel(" ");
            lblAlerta.setFont(new Font("Monospaced", Font.BOLD, 9));
            lblAlerta.setForeground(RED);
            lblAlerta.setAlignmentX(LEFT_ALIGNMENT);
            p.add(lblAlerta);
            p.add(Box.createVerticalStrut(4));

            etiqueta(p, "ID recurso a defender:", TEXT_DIM);
            entryRec = campoTexto();
            p.add(entryRec);
            p.add(Box.createVerticalStrut(4));

            JButton btnDef = boton("🛡  DEFEND", CYAN, BG_DARK);
            btnDef.setAlignmentX(LEFT_ALIGNMENT);
            btnDef.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            btnDef.addActionListener(e -> defender());
            p.add(btnDef);
        }

        // ── Acciones ─────────────────────────────────────────────────────────
        void clickMover(int px, int py) {
            if (juegoTerminado) return;
            int lx = Math.max(0, Math.min(100, px * PLANO_MAX / PLANO_PX));
            int ly = Math.max(0, Math.min(100, (PLANO_PX - py) * PLANO_MAX / PLANO_PX));
            conexion.enviar("MOVE|" + lx + "|" + ly);
        }

        void atacar() {
            if (juegoTerminado) return;
            String rid = entryRec.getText().trim();
            if (!rid.matches("\\d+")) { log("⚠ ID numérico requerido.", RED); return; }
            conexion.enviar("ATTACK|" + rid);
            entryRec.setText("");
        }

        void defender() {
            if (juegoTerminado) return;
            String rid = entryRec.getText().trim();
            if (!rid.matches("\\d+")) { log("⚠ ID numérico requerido.", RED); return; }
            conexion.enviar("DEFEND|" + rid);
            entryRec.setText("");
        }

        void salir() {
            conexion.enviar("EXIT");
            conexion.cerrar();
            onSalir.run();
        }

        // ── Procesamiento de mensajes ─────────────────────────────────────────
        void procesarMensaje(String msg) {
            String[] p = msg.split("\\|");
            if (p.length == 0) return;

            switch (p[0]) {
                case "OK" -> {
                    if (p.length < 2) break;
                    switch (p[1]) {
                        case "SCAN" -> {
                            if (p.length >= 5 && !p[2].equals("NONE"))
                                log("◉ SCAN: Recurso " + p[2] + " en (" + p[3] + "," + p[4] + ")", AMBER);
                            else
                                log("◉ SCAN: Sin recursos cercanos.", GREEN);
                        }
                        case "ATTACK" -> log("⚡ ATTACK enviado.", AMBER);
                        case "DEFEND" -> log("🛡 DEFEND exitoso.", GREEN);
                        case "EXIT"   -> salir();
                    }
                }
                case "ERR" -> log("✗ " + msg, RED);
                case "EVENT" -> {
                    if (p.length < 2) break;
                    switch (p[1]) {
                        case "MOVE" -> {
                            if (p.length >= 5) {
                                String jNombre = p[2];
                                int jx = Integer.parseInt(p[3]);
                                int jy = Integer.parseInt(p[4]);
                                if (jNombre.equals(nombre)) {
                                    posX = jx; posY = jy;
                                    lblPos.setText("X: " + jx + "  Y: " + jy);
                                } else {
                                    int[] prev = jugadores.getOrDefault(jNombre, new int[]{0, 0, 0});
                                    jugadores.put(jNombre, new int[]{jx, jy, prev[2]});
                                }
                            }
                        }
                        case "ALERT" -> {
                            if (p.length >= 6) {
                                String rid = p[2], rx = p[3], ry = p[4], t = p[5];
                                estadoRecursos[Integer.parseInt(rid)] = 1;
                                log("🚨 ALERTA: Recurso " + rid + " en (" + rx + "," + ry + ") — " + t + "s", RED);
                                if (lblAlerta != null)
                                    lblAlerta.setText("<html>🚨 Ataque R" + rid + " (" + rx + "," + ry + ")<br>" + t + "s para reparar</html>");
                            }
                        }
                        case "RESOLVED" -> {
                            if (p.length >= 3) {
                                int rid = Integer.parseInt(p[2]);
                                estadoRecursos[rid] = 0;
                                log("✔ Recurso " + rid + " reparado.", GREEN);
                                if (lblAlerta != null) lblAlerta.setText(" ");
                            }
                        }
                        case "DESTROYED" -> {
                            if (p.length >= 3) {
                                int rid = Integer.parseInt(p[2]);
                                estadoRecursos[rid] = 2;
                                log("💀 Recurso " + rid + " destruido.", RED);
                            }
                        }
                        case "GAMEOVER" -> {
                            if (p.length >= 3) {
                                juegoTerminado = true;
                                String txt = p[2].equals("A") ? "¡ATACANTE GANÓ!" : "¡DEFENSOR GANÓ!";
                                log("🏁 " + txt, AMBER);
                                JOptionPane.showMessageDialog(this, txt, "FIN DE PARTIDA",
                                    JOptionPane.INFORMATION_MESSAGE);
                            }
                        }
                    }
                }
            }
            canvas.repaint();
        }

        void log(String msg, Color color) {
            // Appende texto con color simple (prefijo)
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }

        // ── Canvas del plano ─────────────────────────────────────────────────
        class PlanoCanvas extends JPanel {
            PlanoCanvas() { setBackground(new Color(0x050a12)); }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth(), h = getHeight();

                // Grid
                g2.setColor(new Color(0x0d1f33));
                for (int i = 0; i <= 10; i++) {
                    int x = i * w / 10, y = i * h / 10;
                    g2.drawLine(x, 0, x, h);
                    g2.drawLine(0, y, w, y);
                }

                // Recursos
                for (int[] r : recursos) {
                    int rid = r[0], rx = r[1], ry = r[2];
                    int px = rx * w / PLANO_MAX;
                    int py = h - ry * h / PLANO_MAX;
                    Color c = estadoRecursos[rid] == 0 ? GREEN :
                              estadoRecursos[rid] == 1 ? RED : TEXT_DIM;
                    g2.setColor(c);
                    g2.setStroke(new BasicStroke(2));
                    g2.drawRect(px - 10, py - 10, 20, 20);
                    g2.setFont(new Font("Monospaced", Font.BOLD, 9));
                    g2.drawString("R" + rid, px - 5, py + 4);
                }

                // Radio de scan (atacante)
                if (rol.equals("A")) {
                    int px = posX * w / PLANO_MAX;
                    int py = h - posY * h / PLANO_MAX;
                    int radio = 20 * w / PLANO_MAX;
                    g2.setColor(new Color(AMBER.getRed(), AMBER.getGreen(), AMBER.getBlue(), 60));
                    g2.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                        0, new float[]{4}, 0));
                    g2.drawOval(px - radio, py - radio, radio * 2, radio * 2);
                }

                // Otros jugadores
                for (var entry : jugadores.entrySet()) {
                    String jn = entry.getKey();
                    if (jn.equals(nombre)) continue;
                    int[] jd = entry.getValue();
                    int px = jd[0] * w / PLANO_MAX;
                    int py = h - jd[1] * h / PLANO_MAX;
                    Color c = jd[2] == 0 ? RED : CYAN; // 0=A, 1=D (aproximado)
                    g2.setColor(c);
                    g2.fillOval(px - 6, py - 6, 12, 12);
                    g2.setFont(new Font("Monospaced", Font.PLAIN, 8));
                    g2.drawString(jn, px + 8, py - 4);
                }

                // Mi jugador
                int px = posX * w / PLANO_MAX;
                int py = h - posY * h / PLANO_MAX;
                Color colJug = rol.equals("A") ? RED : CYAN;
                g2.setColor(colJug);
                g2.setStroke(new BasicStroke(2));
                g2.fillOval(px - 9, py - 9, 18, 18);
                g2.setColor(Color.WHITE);
                g2.drawOval(px - 9, py - 9, 18, 18);
                g2.setFont(new Font("Monospaced", Font.BOLD, 8));
                g2.drawString("▲ " + nombre, px - 20, py - 14);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HELPERS DE UI
    // ════════════════════════════════════════════════════════════════════════
    static JButton boton(String texto, Color bg, Color fg) {
        JButton b = new JButton(texto);
        b.setFont(new Font("Monospaced", Font.BOLD, 10));
        b.setBackground(bg);
        b.setForeground(fg);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        return b;
    }

    static void etiqueta(JPanel p, String txt, Color color) {
        JLabel l = new JLabel(txt);
        l.setFont(new Font("Monospaced", Font.PLAIN, 8));
        l.setForeground(color);
        l.setAlignmentX(0.0f);
        p.add(l);
        p.add(Box.createVerticalStrut(2));
    }

    static JSeparator separador() {
        JSeparator s = new JSeparator();
        s.setForeground(BORDER);
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        s.setAlignmentX(0.0f);
        return s;
    }

    static JTextField campoTexto() {
        JTextField f = new JTextField();
        f.setFont(new Font("Monospaced", Font.PLAIN, 12));
        f.setBackground(BG_DARK);
        f.setForeground(TEXT_MAIN);
        f.setCaretColor(CYAN);
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        f.setAlignmentX(0.0f);
        return f;
    }
}
