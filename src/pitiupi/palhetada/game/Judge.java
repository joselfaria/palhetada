package pitiupi.palhetada.game;

/**
 * Julga as teclas de um jogador contra as notas. Tempo em ms desde o início da música.
 * Cada pista é independente: um acorde é só apertar várias pistas juntas.
 */
public final class Judge {

    public static final long WINDOW_MS = 100;
    public static final int POINTS_PER_NOTE = 50;

    private final long[][] lanes;
    private final int[] next; // primeira nota ainda não julgada de cada pista
    private int score;
    private int combo;
    private int hits;
    private int misses;

    public Judge(long[][] lanes) {
        this.lanes = lanes;
        this.next = new int[lanes.length];
    }

    /** Conta como erro toda nota que já passou da janela sem ser apertada. */
    public void advance(long nowMs) {
        for (int lane = 0; lane < lanes.length; lane++) {
            while (next[lane] < lanes[lane].length && lanes[lane][next[lane]] < nowMs - WINDOW_MS) {
                next[lane]++;
                miss();
            }
        }
    }

    public void press(int lane, long nowMs) {
        advance(nowMs);
        if (next[lane] < lanes[lane].length && Math.abs(lanes[lane][next[lane]] - nowMs) <= WINDOW_MS) {
            next[lane]++;
            score += POINTS_PER_NOTE * multiplier();
            combo++;
            hits++;
        } else {
            miss();
        }
    }

    private void miss() {
        combo = 0;
        misses++;
    }

    public int multiplier() {
        return multiplier(combo);
    }

    /** 1x, 2x com 10 acertos seguidos, 3x com 20, 4x com 30. */
    public static int multiplier(int combo) {
        return Math.min(4, 1 + combo / 10);
    }

    public long lastNoteMs() {
        long last = 0;
        for (long[] lane : lanes) {
            if (lane.length > 0) last = Math.max(last, lane[lane.length - 1]);
        }
        return last;
    }

    public long[] lane(int lane) {
        return lanes[lane];
    }

    /** Índice da primeira nota ainda não julgada; as anteriores não são mais desenhadas. */
    public int next(int lane) {
        return next[lane];
    }

    public int score() {
        return score;
    }

    public int combo() {
        return combo;
    }

    public int hits() {
        return hits;
    }

    public int misses() {
        return misses;
    }
}
