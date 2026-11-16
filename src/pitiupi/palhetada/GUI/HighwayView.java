package pitiupi.palhetada.GUI;

import pitiupi.palhetada.chart.Song;
import pitiupi.palhetada.control.MatchController;
import pitiupi.palhetada.control.MatchController.Phase;
import pitiupi.palhetada.game.Judge;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint.CycleMethod;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * A pista em perspectiva: notas vindo do fundo até a linha, teclas A S J K L e o placar dos dois.
 *
 * <p>Um ponto da pista tem a posição {@code u} na largura (-1 na borda esquerda, 1 na direita) e a
 * profundidade {@code d} (0 na linha de acerto, 1 no fundo). Na tela ele encolhe para
 * {@code 1 / (1 + DEPTH * d)} do tamanho e sobe em direção ao horizonte, como numa câmera.</p>
 */
final class HighwayView extends JComponent {

    static final int[] KEYS = {KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_J, KeyEvent.VK_K, KeyEvent.VK_L};
    private static final String[] KEY_LABELS = {"A", "S", "J", "K", "L"};
    private static final Color[] COLORS = {
            new Color(0x3DBE5A), new Color(0xE0473C), new Color(0xF2C230), new Color(0x3C7FE0), new Color(0xF08A2C)
    };
    static final Color BACKGROUND = new Color(0x101016);
    /** Fundo da barra de cima do painel. */
    static final Color BAR = new Color(0x1E1E28);
    private static final Color LANE = new Color(0x2C2C3A);
    static final Color TEXT = new Color(0xECECF1);
    static final Color DIM = new Color(0x8C8C9E);
    private static final Color MISS = new Color(0xFF3B3B);
    /** Cor do multiplicador no placar: 1x, 2x, 3x, 4x. */
    private static final Color[] MULTIPLIER_COLORS = {DIM, COLORS[0], COLORS[2], COLORS[4]};
    /** Quanto tempo à frente aparece na pista. */
    private static final long LOOKAHEAD_MS = 1800;
    /** Duração do brilho de acerto e do vermelho de erro. */
    private static final long FLASH_MS = 150;
    /** Uma linha atravessa a pista a cada tanto de música, para dar a sensação de movimento. */
    private static final long STRIPE_MS = 500;
    /** Força da perspectiva: o fundo da pista fica {@code 1 / (1 + DEPTH)} da largura da frente. */
    private static final double DEPTH = 3.2;
    /** Altura das notas e alvos em relação à largura, como se estivessem deitados na pista. */
    private static final double FLAT = 0.5;
    /** Espaço abaixo da linha de acerto, para as teclas. */
    private static final int BELOW_HIT = 95;
    /** Onde o fundo da pista encosta, a partir do topo. */
    private static final int FAR_Y = 40;

    private final MatchController match;
    private final boolean[] held = new boolean[Song.LANES];
    /** Instante do último acerto de cada pista, para o brilho no alvo. */
    private final long[] hitAt = new long[Song.LANES];
    /** Instante do último erro (combo quebrado), para o vermelho na linha. */
    private long missAt = Long.MIN_VALUE / 2;
    private int lastMisses;

    // Projeção do quadro atual, calculada no começo de paintComponent.
    private double centerX;
    private double halfWidth;
    private double hitY;
    private double horizonY;
    /** Quadro desenhado em memória e copiado para a tela de uma vez. */
    private BufferedImage frame;

    HighwayView(MatchController match) {
        this.match = match;
        Arrays.fill(hitAt, Long.MIN_VALUE / 2);
        setFocusable(true);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int lane = laneOf(e.getKeyCode());
                if (lane < 0 || held[lane]) return; // segurar a tecla não conta de novo
                held[lane] = true;
                long now = PalhetadaPanel.nowMs();
                Judge judge = match.judge();
                int hits = judge == null ? 0 : judge.hits();
                match.press(lane, now);
                if (judge != null && judge.hits() > hits) hitAt[lane] = now;
                repaint();
            }

            @Override
            public void keyReleased(KeyEvent e) {
                int lane = laneOf(e.getKeyCode());
                if (lane >= 0) held[lane] = false;
                repaint();
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
            }
        });
    }

    private static int laneOf(int keyCode) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i] == keyCode) return i;
        }
        return -1;
    }

    private static double scale(double d) {
        return 1 / (1 + DEPTH * d);
    }

    private double y(double d) {
        return horizonY + (hitY - horizonY) * scale(d);
    }

    private double x(double u, double d) {
        return centerX + u * halfWidth * scale(d);
    }

    /** Profundidade do ponto da pista que aparece na altura {@code screenY}. */
    private double depthAt(double screenY) {
        return ((hitY - horizonY) / (screenY - horizonY) - 1) / DEPTH;
    }

    /** Centro da pista {@code lane} em {@code u}. */
    private static double laneU(int lane) {
        return -1 + (2 * lane + 1) / (double) Song.LANES;
    }

    /** Trecho da pista entre {@code u0..u1} na largura e {@code d0..d1} na profundidade. */
    private Path2D quad(double u0, double u1, double d0, double d1) {
        Path2D path = new Path2D.Double();
        path.moveTo(x(u0, d0), y(d0));
        path.lineTo(x(u1, d0), y(d0));
        path.lineTo(x(u1, d1), y(d1));
        path.lineTo(x(u0, d1), y(d1));
        path.closePath();
        return path;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        // Desenhar degradê transparente direto na tela é lento no Linux (XRender): ~136 ms por quadro.
        // Numa imagem em memória o mesmo quadro leva ~8 ms, e a cópia para a tela é uma só.
        if (frame == null || frame.getWidth() != getWidth() || frame.getHeight() != getHeight()) {
            frame = new BufferedImage(Math.max(1, getWidth()), Math.max(1, getHeight()), BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        int w = getWidth();
        int h = getHeight();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, w, h);

        double laneW = Math.max(40, Math.min(100, (w - 340) / (double) Song.LANES));
        double r = laneW * 0.36;
        centerX = w / 2.0;
        halfWidth = laneW * Song.LANES / 2;
        hitY = h - BELOW_HIT;
        double far = scale(1);
        horizonY = (FAR_Y - hitY * far) / (1 - far); // assim o fundo da pista (d = 1) fica em FAR_Y
        double near = depthAt(hitY + r * FLAT + 12); // a pista passa um pouco da linha, até a borda dos alvos

        long now = PalhetadaPanel.nowMs();
        Judge judge = match.judge();
        long t = match.gameTimeMs(now);
        boolean inMatch = judge != null && (match.phase() == Phase.PLAYING || match.phase() == Phase.FINISHED);
        if (judge != null) {
            if (judge.misses() > lastMisses) missAt = now;
            lastMisses = judge.misses(); // partida nova começa em 0
        }
        if (!inMatch) t = 0;

        // Chão da pista: escurece para o fundo; cada pista com um toque da sua cor perto da linha.
        g.setPaint(new GradientPaint(0, (float) y(1), BACKGROUND, 0, (float) hitY, LANE));
        g.fill(quad(-1, 1, near, 1));
        for (int lane = 0; lane < Song.LANES; lane++) {
            double u0 = -1 + 2.0 * lane / Song.LANES;
            g.setPaint(new GradientPaint(0, (float) y(0.6), alpha(COLORS[lane], 0), 0, (float) hitY, alpha(COLORS[lane], 40)));
            g.fill(quad(u0, u0 + 2.0 / Song.LANES, near, 0.6));
        }

        // Linhas que atravessam a pista e andam junto com as notas.
        for (long k = Math.floorDiv(t, STRIPE_MS) + 1; (k * STRIPE_MS - t) <= LOOKAHEAD_MS; k++) {
            double d = (k * STRIPE_MS - t) / (double) LOOKAHEAD_MS;
            g.setColor(alpha(Color.WHITE, 16));
            g.setStroke(new BasicStroke((float) (3 * scale(d))));
            g.draw(new Line2D.Double(x(-1, d), y(d), x(1, d), y(d)));
        }
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(alpha(Color.WHITE, 26));
        for (int lane = 1; lane < Song.LANES; lane++) {
            double u = -1 + 2.0 * lane / Song.LANES;
            g.draw(new Line2D.Double(x(u, near), y(near), x(u, 1), y(1)));
        }
        g.setStroke(new BasicStroke(3));
        g.setColor(alpha(Color.WHITE, 90));
        g.draw(new Line2D.Double(x(-1, near), y(near), x(-1, 1), y(1)));
        g.draw(new Line2D.Double(x(1, near), y(near), x(1, 1), y(1)));

        // Linha de acerto com brilho; fica vermelha por um instante quando o combo quebra.
        double miss = fade(missAt, now);
        if (miss > 0) {
            g.setPaint(new GradientPaint(0, (float) y(0.25), alpha(MISS, 0), 0, (float) hitY, alpha(MISS, (int) (120 * miss))));
            g.fill(quad(-1, 1, 0, 0.25));
        }
        Color line = miss > 0 ? MISS : TEXT;
        Line2D hitLine = new Line2D.Double(x(-1, 0), hitY, x(1, 0), hitY);
        glow(g, hitLine, line, 4 + 4 * miss);

        for (int lane = 0; lane < Song.LANES; lane++) {
            double cx = x(laneU(lane), 0);
            Color color = COLORS[lane];
            double hit = fade(hitAt[lane], now);
            if (hit > 0) {
                for (int ring = 3; ring >= 1; ring--) { // brilho em camadas, maior e mais fraco por fora
                    double grow = r * (0.35 * ring + 0.4 * (1 - hit));
                    g.setColor(alpha(color, (int) (70 * hit)));
                    g.fill(ellipse(cx, hitY, r + grow));
                }
            }
            if (held[lane]) {
                gem(g, color, cx, hitY, r);
            } else {
                g.setColor(alpha(color, 45));
                g.fill(ellipse(cx, hitY, r));
            }
            glow(g, ellipse(cx, hitY, r), color, 4);
        }

        if (inMatch) {
            for (int lane = 0; lane < Song.LANES; lane++) {
                long[] times = judge.lane(lane);
                int first = judge.next(lane);
                int last = first;
                while (last < times.length && times[last] - t <= LOOKAHEAD_MS) last++;
                for (int i = last - 1; i >= first; i--) { // de trás para frente: a nota mais perto fica por cima
                    double d = (times[i] - t) / (double) LOOKAHEAD_MS;
                    if (d < near) continue; // já saiu da pista: perto da tela ela cresceria por cima das teclas
                    gem(g, COLORS[lane], x(laneU(lane), d), y(d), r * scale(d));
                }
            }
        }

        // Névoa no fundo: as notas surgem do escuro em vez de aparecer de repente.
        g.setPaint(new GradientPaint(0, (float) y(0.85), BACKGROUND, 0, (float) y(0.35), alpha(BACKGROUND, 0)));
        g.fill(quad(-1.2, 1.2, 0.35, 1.1));

        g.setFont(getFont().deriveFont(Font.BOLD, 16f));
        for (int lane = 0; lane < Song.LANES; lane++) {
            keyCap(g, KEY_LABELS[lane], x(laneU(lane), 0), hitY + r * FLAT + 20, held[lane] ? COLORS[lane] : alpha(Color.WHITE, 50));
        }

        if (inMatch) {
            int cardW = (int) Math.max(130, Math.min(230, x(-1, 0) - 40));
            hud(g, 20, cardW, "Você", judge.score(), judge.combo());
            hud(g, w - 20 - cardW, cardW, match.opponentName(), match.opponentScore(), match.opponentCombo());
            if (match.phase() == Phase.PLAYING && t < 0) {
                g.setFont(getFont().deriveFont(Font.BOLD, 110f));
                g.setColor(TEXT);
                centered(g, String.valueOf((-t + 999) / 1000), w / 2.0, h * 0.42);
            }
            if (match.phase() == Phase.FINISHED) {
                g.setColor(alpha(Color.BLACK, 150));
                g.fillRect(0, 0, w, h);
                g.setFont(getFont().deriveFont(Font.BOLD, 52f));
                g.setColor(TEXT);
                centered(g, match.result() != null ? match.result() : "Partida encerrada", w / 2.0, h / 2.0);
            }
        } else {
            g.setFont(getFont().deriveFont(Font.BOLD, 52f));
            g.setColor(TEXT);
            centered(g, "Palhetada", w / 2.0, h * 0.32);
            g.setFont(getFont().deriveFont(Font.PLAIN, 16f));
            g.setColor(DIM);
            centered(g, "Acerte as notas com A S J K L quando passarem pela linha.", w / 2.0, h * 0.32 + 36);
        }
        g.dispose();
        graphics.drawImage(frame, 0, 0, null);
    }

    private static Ellipse2D ellipse(double cx, double cy, double r) {
        return new Ellipse2D.Double(cx - r, cy - r * FLAT, r * 2, r * 2 * FLAT);
    }

    /** Contorno com brilho: o mesmo traço mais largo e transparente por baixo. */
    private static void glow(Graphics2D g, java.awt.Shape shape, Color color, double width) {
        g.setColor(alpha(color, 35));
        g.setStroke(new BasicStroke((float) (width * 3.5)));
        g.draw(shape);
        g.setColor(alpha(color, 70));
        g.setStroke(new BasicStroke((float) (width * 2)));
        g.draw(shape);
        g.setColor(color);
        g.setStroke(new BasicStroke((float) width));
        g.draw(shape);
    }

    /** Nota: halo da cor, esfera com luz em cima, reflexo branco e contorno escuro. */
    private static void gem(Graphics2D g, Color color, double cx, double cy, double r) {
        g.setColor(alpha(color, 50));
        g.fill(ellipse(cx, cy, r * 1.35));
        Point2D focus = new Point2D.Double(cx - r * 0.25, cy - r * FLAT * 0.5);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) r, focus,
                new float[]{0f, 0.6f, 1f}, new Color[]{mix(color, Color.WHITE, 0.55), color, color.darker()},
                CycleMethod.NO_CYCLE));
        Ellipse2D body = ellipse(cx, cy, r);
        g.fill(body);
        g.setColor(alpha(Color.WHITE, 140));
        g.fill(new Ellipse2D.Double(cx - r * 0.5, cy - r * FLAT * 0.78, r * 0.9, r * FLAT * 0.55));
        g.setColor(alpha(Color.BLACK, 110));
        g.setStroke(new BasicStroke((float) Math.max(1, r / 9)));
        g.draw(body);
    }

    private static void keyCap(Graphics2D g, String key, double cx, double top, Color border) {
        int x = (int) Math.round(cx) - 16;
        int y = (int) Math.round(top);
        g.setColor(alpha(Color.WHITE, 16));
        g.fillRoundRect(x, y, 32, 30, 9, 9);
        g.setColor(border);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, y, 32, 30, 9, 9);
        g.setColor(TEXT);
        centered(g, key, cx, y + 21);
    }

    /** Cartão do placar: nome, pontos, combo e o multiplicador na cor do nível. */
    private void hud(Graphics2D g, int x, int width, String who, int score, int combo) {
        int y = 18;
        int height = 112;
        g.setColor(alpha(Color.WHITE, 16));
        g.fillRoundRect(x, y, width, height, 20, 20);
        g.setColor(alpha(Color.WHITE, 40));
        g.setStroke(new BasicStroke(1));
        g.drawRoundRect(x, y, width, height, 20, 20);

        g.setColor(DIM);
        g.setFont(getFont().deriveFont(Font.BOLD, 15f));
        g.drawString(who, x + 16, y + 28);
        g.setColor(TEXT);
        g.setFont(getFont().deriveFont(Font.BOLD, 34f));
        g.drawString(String.valueOf(score), x + 16, y + 68);
        g.setColor(DIM);
        g.setFont(getFont().deriveFont(Font.PLAIN, 14f));
        g.drawString("combo " + combo, x + 16, y + 96);

        int multiplier = Judge.multiplier(combo);
        String text = "x" + multiplier;
        g.setColor(MULTIPLIER_COLORS[multiplier - 1]);
        g.setFont(getFont().deriveFont(Font.BOLD, 28f));
        g.drawString(text, x + width - 16 - g.getFontMetrics().stringWidth(text), y + 96);
    }

    /** 1 logo depois do instante {@code at}, caindo até 0 em {@link #FLASH_MS}. */
    private static double fade(long at, long now) {
        long since = now - at;
        return since < 0 || since >= FLASH_MS ? 0 : 1 - (double) since / FLASH_MS;
    }

    private static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static Color mix(Color a, Color b, double amount) {
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * amount),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * amount),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * amount));
    }

    private static void centered(Graphics2D g, String text, double cx, double baseline) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (float) (cx - fm.stringWidth(text) / 2.0), (float) baseline);
    }
}
