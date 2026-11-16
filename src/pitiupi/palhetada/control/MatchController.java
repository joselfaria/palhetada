package pitiupi.palhetada.control;

import pitiupi.palhetada.chart.Song;
import pitiupi.palhetada.chart.Song.Difficulty;
import pitiupi.palhetada.chart.Song.Instrument;
import pitiupi.palhetada.game.Judge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

/**
 * Uma partida 1v1. Sem Swing e sem socket: o plugin entrega os frames recebidos e o relógio,
 * e recebe os frames a enviar pelo {@code Consumer<byte[]>} da conexão. Chame tudo numa thread só.
 *
 * <p>Frame: um byte de tipo e os campos, escritos com {@link DataOutputStream}.</p>
 */
public final class MatchController {

    public enum Phase {
        /** Sem partida. */
        IDLE,
        /** Convite enviado, esperando resposta. */
        INVITING,
        /** Convite recebido, esperando Aceitar ou Recusar. */
        INVITED,
        /** Aceitou; esperando o START de quem convidou. */
        WAITING_START,
        /** Contagem (tempo negativo) e música. */
        PLAYING,
        /** Resultado na tela, ou a partida acabou antes da hora. */
        FINISHED
    }

    static final byte INVITE = 1;
    static final byte ACCEPT = 2;
    /** Recusa o convite ou sai da partida. */
    static final byte DECLINE = 3;
    static final byte START = 4;
    static final byte SCORE = 5;
    static final byte END = 6;

    public static final long COUNTDOWN_MS = 3000;
    public static final long TAIL_MS = 2000;
    static final long SCORE_EVERY_MS = 250;

    private Runnable onChange = () -> { };

    private Phase phase = Phase.IDLE;
    private String status = "Escolha uma música e desafie alguém pela lista de pessoas.";
    private Object link;
    private Consumer<byte[]> out;

    private String opponentName = "";
    private String title = "";
    private String zipName = "";
    private Instrument instrument;
    private Difficulty difficulty;
    private long[][] lanes;

    private Judge judge;
    private long startMs;
    private long endMs;
    private long lastScoreSentMs;
    private boolean sentEnd;
    private int opponentScore;
    private int opponentCombo;
    private Integer opponentFinal;
    private String result;

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    public boolean busy() {
        return phase != Phase.IDLE && phase != Phase.FINISHED;
    }

    /** Quem convida: a conexão TCP já abriu. */
    public void invite(Object link, Consumer<byte[]> out, String myName, String opponentName,
                       Song song, Instrument instrument, Difficulty difficulty) {
        if (busy()) {
            notice("Você já está numa partida.");
            return;
        }
        long[][] parsed;
        try {
            parsed = Song.lanes(song.midi(), instrument, difficulty);
        } catch (IOException ex) {
            notice(ex.getMessage());
            return;
        }
        reset(link, out, opponentName, song.title(), song.zipName(), instrument, difficulty, parsed);
        phase = Phase.INVITING;
        status = "Desafio enviado para " + opponentName + ". Esperando resposta…";
        out.accept(frame(INVITE, myName, song.title(), song.zipName(),
                instrument.name(), difficulty.name(), song.midi()));
        onChange.run();
    }

    /** Quem foi convidado aceita. Prepare o áudio antes: depois do ACCEPT a partida pode começar a qualquer momento. */
    public void accept() {
        if (phase != Phase.INVITED) return;
        phase = Phase.WAITING_START;
        status = "Aceito. Esperando " + opponentName + " começar…";
        out.accept(frame(ACCEPT));
        onChange.run();
    }

    public void decline() {
        if (phase != Phase.INVITED) return;
        out.accept(frame(DECLINE));
        phase = Phase.IDLE;
        status = "Desafio recusado.";
        onChange.run();
    }

    /** Sai da partida em andamento (aba fechada). */
    public void quit() {
        if (!busy()) return;
        out.accept(frame(DECLINE));
        phase = Phase.IDLE;
        status = "Você saiu da partida.";
        onChange.run();
    }

    public void onFrame(Object from, Consumer<byte[]> reply, byte[] bytes, long nowMs) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte type = in.readByte();
            if (type == INVITE) {
                receiveInvite(from, reply, in);
                return;
            }
            if (from != link || !busy()) return;
            switch (type) {
                case ACCEPT -> {
                    if (phase != Phase.INVITING) return;
                    out.accept(frame(START));
                    begin(nowMs);
                }
                case START -> {
                    if (phase == Phase.WAITING_START) begin(nowMs);
                }
                case SCORE -> {
                    opponentScore = in.readInt();
                    opponentCombo = in.readInt();
                }
                case END -> {
                    opponentFinal = in.readInt();
                    opponentScore = opponentFinal;
                    opponentCombo = 0;
                    finishIfBothEnded();
                }
                case DECLINE -> end(phase == Phase.INVITING
                        ? opponentName + " recusou o desafio."
                        : opponentName + " saiu da partida.");
                default -> {
                }
            }
            onChange.run();
        } catch (IOException | IllegalArgumentException ex) {
            // Frame malformado: ignora.
        }
    }

    private void receiveInvite(Object from, Consumer<byte[]> reply, DataInputStream in) throws IOException {
        if (busy()) {
            reply.accept(frame(DECLINE));
            return;
        }
        String name = in.readUTF();
        String songTitle = in.readUTF();
        String songZip = in.readUTF();
        Instrument inst = Instrument.valueOf(in.readUTF());
        Difficulty diff = Difficulty.valueOf(in.readUTF());
        int midiLength = in.readInt();
        if (midiLength < 0 || midiLength > in.available()) {
            throw new IOException("Tamanho do chart inválido.");
        }
        byte[] midi = in.readNBytes(midiLength);
        long[][] parsed;
        try {
            parsed = Song.lanes(midi, inst, diff);
        } catch (IOException ex) {
            reply.accept(frame(DECLINE));
            notice(name + " mandou um chart que não abre: " + ex.getMessage());
            return;
        }
        reset(from, reply, name, songTitle, songZip, inst, diff, parsed);
        phase = Phase.INVITED;
        status = name + " te desafiou: " + songTitle + " (" + inst + ", " + diff + ").";
        onChange.run();
    }

    /** A conexão caiu ou o envio falhou. */
    public void onClosed(Object closed) {
        if (closed != link || !busy()) return;
        end(opponentName + " desconectou.");
        onChange.run();
    }

    /** Chamado a cada quadro durante a partida. */
    public void tick(long nowMs) {
        if (phase != Phase.PLAYING) return;
        long t = gameTimeMs(nowMs);
        if (t < 0) return;
        judge.advance(t);
        if (!sentEnd && t >= endMs) {
            sentEnd = true;
            out.accept(frame(END, judge.score()));
            finishIfBothEnded();
            onChange.run();
        } else if (!sentEnd && nowMs - lastScoreSentMs >= SCORE_EVERY_MS) {
            lastScoreSentMs = nowMs;
            out.accept(frame(SCORE, judge.score(), judge.combo()));
        }
    }

    public void press(int lane, long nowMs) {
        if (phase != Phase.PLAYING || sentEnd) return;
        long t = gameTimeMs(nowMs);
        if (t >= 0) judge.press(lane, t);
    }

    private void begin(long nowMs) {
        judge = new Judge(lanes);
        startMs = nowMs + COUNTDOWN_MS;
        endMs = judge.lastNoteMs() + TAIL_MS;
        lastScoreSentMs = nowMs;
        phase = Phase.PLAYING;
        status = title + " (" + instrument + ", " + difficulty + ") contra " + opponentName + ".";
    }

    private void finishIfBothEnded() {
        if (!sentEnd || opponentFinal == null) return;
        int mine = judge.score();
        result = mine > opponentFinal ? "Você venceu!" : mine < opponentFinal ? "Você perdeu." : "Empate.";
        phase = Phase.FINISHED;
        status = result + " " + mine + " x " + opponentFinal + " contra " + opponentName + ".";
    }

    private void end(String why) {
        phase = Phase.FINISHED;
        result = null;
        status = why;
    }

    private void reset(Object link, Consumer<byte[]> out, String opponentName, String title, String zipName,
                       Instrument instrument, Difficulty difficulty, long[][] lanes) {
        this.link = link;
        this.out = out;
        this.opponentName = opponentName;
        this.title = title;
        this.zipName = zipName;
        this.instrument = instrument;
        this.difficulty = difficulty;
        this.lanes = lanes;
        this.judge = null;
        this.sentEnd = false;
        this.opponentScore = 0;
        this.opponentCombo = 0;
        this.opponentFinal = null;
        this.result = null;
    }

    /** Mostra um aviso sem mudar a fase. */
    public void notice(String status) {
        this.status = status;
        onChange.run();
    }

    static byte[] frame(byte type, Object... fields) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeByte(type);
            for (Object field : fields) {
                if (field instanceof String s) {
                    data.writeUTF(s);
                } else if (field instanceof Integer i) {
                    data.writeInt(i);
                } else if (field instanceof byte[] b) {
                    data.writeInt(b.length);
                    data.write(b);
                } else {
                    throw new IllegalArgumentException("Campo sem formato: " + field);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return bytes.toByteArray();
    }

    /** Tempo da música em ms; negativo durante a contagem. */
    public long gameTimeMs(long nowMs) {
        return nowMs - startMs;
    }

    public Phase phase() {
        return phase;
    }

    public String status() {
        return status;
    }

    public String opponentName() {
        return opponentName;
    }

    public String zipName() {
        return zipName;
    }

    /** Placar desta partida; {@code null} antes de começar. */
    public Judge judge() {
        return judge;
    }

    public int opponentScore() {
        return opponentScore;
    }

    public int opponentCombo() {
        return opponentCombo;
    }

    /** "Você venceu!", "Você perdeu." ou "Empate."; {@code null} se a partida não terminou com os dois placares. */
    public String result() {
        return result;
    }
}
