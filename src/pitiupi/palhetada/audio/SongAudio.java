package pitiupi.palhetada.audio;

import pitiupi.palhetada.chart.Song;
import pitiupi.platform.PitiupiHome;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Música opcional. O {@code ffmpeg} mistura as faixas do zip num {@code .wav} em
 * {@code .pitiupi/cache/palhetada/}; o Java toca o {@code .wav}. Sem {@code ffmpeg}, a partida segue sem som.
 */
public final class SongAudio {

    private static final List<String> AUDIO = List.of(".opus", ".ogg", ".mp3", ".wav", ".flac");

    private final Path wav;
    private volatile boolean stopped;

    public SongAudio(Path wav) {
        this.wav = wav;
    }

    /**
     * {@code plugins/palhetada/charts/} do clone: o seletor abre aqui e o adversário procura aqui o mesmo zip.
     * O {@code .pitiupi/} fica na raiz do clone, então a raiz é a pasta pai dele.
     */
    public static Path chartsDirectory() {
        return PitiupiHome.get().rootDirectory().getParent().resolve("plugins").resolve("palhetada").resolve("charts");
    }

    /**
     * Devolve o {@code .wav} da música, convertendo na primeira vez. Demora alguns segundos: fora da thread do Swing.
     *
     * @throws IOException com o motivo em português (sem ffmpeg, zip sem áudio, conversão falhou)
     */
    public static Path prepare(Path zip) throws IOException {
        Path cache = PitiupiHome.get().cacheDirectory().resolve("palhetada");
        // ponytail: cache pelo nome do zip; dois zips diferentes com o mesmo nome reaproveitam o .wav errado.
        Path wav = cache.resolve(zip.getFileName().toString().replaceFirst("(?i)\\.zip$", "") + ".wav");
        if (Files.isRegularFile(wav)) return wav;

        Files.createDirectories(cache);
        Path work = Files.createTempDirectory(cache, "faixas");
        try {
            List<String> command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-loglevel", "error"));
            int inputs = 0;
            try (ZipFile file = new ZipFile(zip.toFile())) {
                Enumeration<? extends ZipEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = Song.baseName(entry.getName()); // sem pastas: nada sai de work/
                    if (entry.isDirectory() || name.startsWith("preview") || AUDIO.stream().noneMatch(name::endsWith)) {
                        continue;
                    }
                    Path track = work.resolve(name);
                    try (InputStream in = file.getInputStream(entry)) {
                        Files.copy(in, track, StandardCopyOption.REPLACE_EXISTING);
                    }
                    command.addAll(List.of("-i", track.toString()));
                    inputs++;
                }
            }
            if (inputs == 0) throw new IOException("O zip não tem faixas de áudio.");

            Path partial = work.resolve("mix.wav");
            command.addAll(List.of("-filter_complex", "amix=inputs=" + inputs + ":normalize=0",
                    "-ac", "2", "-ar", "44100", "-c:a", "pcm_s16le", partial.toString()));
            run(command);
            Files.move(partial, wav, StandardCopyOption.REPLACE_EXISTING);
            return wav;
        } finally {
            try (Stream<Path> files = Files.walk(work)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void run(List<String> command) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException ex) {
            throw new IOException("ffmpeg não encontrado no PATH; a partida segue sem som.");
        }
        String output = new String(process.getInputStream().readAllBytes());
        try {
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg demorou demais.");
            }
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Conversão interrompida.");
        }
        if (process.exitValue() != 0) {
            throw new IOException("ffmpeg falhou: " + output.strip());
        }
    }

    /** Começa a tocar numa thread própria. */
    public void start() {
        Thread thread = new Thread(() -> {
            try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
                SourceDataLine line = AudioSystem.getSourceDataLine(in.getFormat());
                line.open(in.getFormat());
                line.start();
                byte[] buffer = new byte[8192];
                int read;
                while (!stopped && (read = in.read(buffer)) > 0) {
                    line.write(buffer, 0, read);
                }
                if (stopped) line.flush();
                else line.drain();
                line.close();
            } catch (Exception ex) {
                System.out.println("Palhetada: áudio falhou: " + ex);
            }
        }, "palhetada-audio");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        stopped = true;
    }
}
