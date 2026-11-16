package pitiupi.palhetada.chart;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Música escolhida: título, nome do zip e o {@code notes.mid} cru, que é o que viaja pela rede.
 */
public record Song(String title, String zipName, byte[] midi) {

    public static final int LANES = 5;

    public enum Instrument {
        GUITAR("PART GUITAR", "Guitarra"),
        BASS("PART BASS", "Baixo");

        final String track;
        private final String label;

        Instrument(String track, String label) {
            this.track = track;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Difficulty {
        EASY(60, "Easy"),
        MEDIUM(72, "Medium"),
        HARD(84, "Hard"),
        EXPERT(96, "Expert");

        final int firstNote;
        private final String label;

        Difficulty(int firstNote, String label) {
            this.firstNote = firstNote;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Lê {@code song.ini} e {@code notes.mid} de um zip de música. */
    public static Song load(Path zip) throws IOException {
        String zipName = zip.getFileName().toString();
        String name = null;
        String artist = null;
        byte[] midi = null;
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = baseName(entry.getName());
                if (entryName.equals("notes.mid")) {
                    try (InputStream in = file.getInputStream(entry)) {
                        midi = in.readAllBytes();
                    }
                } else if (entryName.equals("song.ini")) {
                    try (InputStream in = file.getInputStream(entry)) {
                        for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                            String[] kv = line.split("=", 2);
                            if (kv.length != 2) continue;
                            String key = kv[0].trim().toLowerCase();
                            if (key.equals("name")) name = kv[1].trim();
                            if (key.equals("artist")) artist = kv[1].trim();
                        }
                    }
                }
            }
        }
        if (midi == null) {
            throw new IOException("O zip não tem notes.mid (charts .chart não são suportados).");
        }
        String title = name == null ? zipName.replaceFirst("(?i)\\.zip$", "")
                : artist == null ? name : artist + " - " + name;
        return new Song(title, zipName, midi);
    }

    /** Nome do arquivo sem as pastas do zip, em minúsculas. */
    public static String baseName(String entryName) {
        return entryName.substring(entryName.lastIndexOf('/') + 1).toLowerCase();
    }

    /**
     * Instante (ms) de cada nota, por pista, em ordem.
     *
     * @throws IOException se o MIDI for inválido ou não tiver notas para o instrumento e a dificuldade
     */
    public static long[][] lanes(byte[] midi, Instrument instrument, Difficulty difficulty) throws IOException {
        Sequence sequence;
        try {
            sequence = MidiSystem.getSequence(new ByteArrayInputStream(midi));
        } catch (InvalidMidiDataException ex) {
            throw new IOException("notes.mid inválido: " + ex.getMessage());
        }
        if (sequence.getDivisionType() != Sequence.PPQ) {
            throw new IOException("notes.mid com divisão de tempo não suportada.");
        }

        List<long[]> tempos = new ArrayList<>(); // {tick, microssegundos por semínima}
        List<List<Long>> ticks = new ArrayList<>();
        for (int i = 0; i < LANES; i++) ticks.add(new ArrayList<>());
        boolean foundTrack = false;

        for (Track track : sequence.getTracks()) {
            boolean wanted = instrument.track.equals(trackName(track));
            foundTrack |= wanted;
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (message instanceof MetaMessage meta && meta.getType() == 0x51 && meta.getData().length == 3) {
                    byte[] d = meta.getData();
                    tempos.add(new long[]{event.getTick(), ((d[0] & 0xFF) << 16) | ((d[1] & 0xFF) << 8) | (d[2] & 0xFF)});
                } else if (wanted && message instanceof ShortMessage sm
                        && sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                    int lane = sm.getData1() - difficulty.firstNote;
                    if (lane >= 0 && lane < LANES) ticks.get(lane).add(event.getTick());
                }
            }
        }
        if (!foundTrack) {
            throw new IOException("O chart não tem " + instrument + ".");
        }

        tempos.sort((a, b) -> Long.compare(a[0], b[0]));
        long[][] lanes = new long[LANES][];
        boolean any = false;
        for (int lane = 0; lane < LANES; lane++) {
            lanes[lane] = ticks.get(lane).stream()
                    .mapToLong(tick -> tickToMs(tick, tempos, sequence.getResolution()))
                    .sorted().distinct().toArray();
            any |= lanes[lane].length > 0;
        }
        if (!any) {
            throw new IOException("O chart não tem notas de " + instrument + " " + difficulty + ".");
        }
        return lanes;
    }

    private static String trackName(Track track) {
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == 0x03) {
                return new String(meta.getData(), StandardCharsets.ISO_8859_1).trim();
            }
        }
        return "";
    }

    private static long tickToMs(long tick, List<long[]> tempos, int resolution) {
        double ms = 0;
        long previousTick = 0;
        long microsPerBeat = 500_000; // 120 BPM até o primeiro evento de tempo
        for (long[] tempo : tempos) {
            if (tempo[0] >= tick) break;
            ms += (tempo[0] - previousTick) * microsPerBeat / 1000.0 / resolution;
            previousTick = tempo[0];
            microsPerBeat = tempo[1];
        }
        return Math.round(ms + (tick - previousTick) * microsPerBeat / 1000.0 / resolution);
    }
}
