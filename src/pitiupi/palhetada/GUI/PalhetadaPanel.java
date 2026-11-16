package pitiupi.palhetada.GUI;

import pitiupi.palhetada.audio.SongAudio;
import pitiupi.palhetada.chart.Song;
import pitiupi.palhetada.chart.Song.Difficulty;
import pitiupi.palhetada.chart.Song.Instrument;
import pitiupi.palhetada.control.MatchController;
import pitiupi.palhetada.control.MatchController.Phase;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Aba do plugin: escolha da música, convite recebido, pista e música opcional. */
public final class PalhetadaPanel extends JPanel {

    private final MatchController match;
    private final HighwayView highway;
    private final Timer frames;

    private final JButton chooseButton = new JButton("Escolher música…");
    private final JLabel songLabel = new JLabel("Nenhuma música escolhida");
    private final JComboBox<Instrument> instrumentBox = new JComboBox<>(Instrument.values());
    private final JComboBox<Difficulty> difficultyBox = new JComboBox<>(Difficulty.values());
    private final JLabel statusLabel = new JLabel();
    private final JLabel audioLabel = new JLabel();
    private final JButton acceptButton = new JButton("Aceitar");
    private final JButton declineButton = new JButton("Recusar");

    private Song chosen;
    /** {@code .wav} pronto por nome de zip; preenchido pelas threads de conversão. */
    private final Map<String, Path> preparedAudio = new ConcurrentHashMap<>();
    private SongAudio playing;
    private boolean audioStarted;

    public PalhetadaPanel(MatchController match) {
        super(new BorderLayout());
        this.match = match;
        this.highway = new HighwayView(match);
        this.frames = new Timer(16, e -> onFrame());

        difficultyBox.setSelectedItem(Difficulty.EXPERT);

        songLabel.setFont(songLabel.getFont().deriveFont(Font.BOLD));
        JPanel songRow = bar(chooseButton, songLabel, new JLabel("   Instrumento:"), instrumentBox,
                new JLabel("Dificuldade:"), difficultyBox);
        JPanel statusRow = bar(statusLabel, acceptButton, declineButton, audioLabel);
        audioLabel.setForeground(HighwayView.DIM);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(HighwayView.BAR);
        top.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x34344A)));
        top.add(songRow);
        top.add(statusRow);
        add(top, BorderLayout.NORTH);
        add(highway, BorderLayout.CENTER);

        chooseButton.addActionListener(e -> chooseSong());
        acceptButton.addActionListener(e -> accept());
        declineButton.addActionListener(e -> match.decline());
        refresh();
    }

    /** Linha da barra de cima: fundo escuro e textos claros. Botões e caixas ficam com o tema do app. */
    private static JPanel bar(Component... items) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        row.setBackground(HighwayView.BAR);
        for (Component item : items) {
            if (item instanceof JLabel) item.setForeground(HighwayView.TEXT);
            row.add(item);
        }
        return row;
    }

    static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    /** Música de quem vai convidar; {@code null} se nenhuma foi escolhida. */
    public Song chosenSong() {
        return chosen;
    }

    public Instrument instrument() {
        return (Instrument) instrumentBox.getSelectedItem();
    }

    public Difficulty difficulty() {
        return (Difficulty) difficultyBox.getSelectedItem();
    }

    private void chooseSong() {
        JFileChooser chooser = new JFileChooser();
        Path charts = SongAudio.chartsDirectory();
        if (Files.isDirectory(charts)) chooser.setCurrentDirectory(charts.toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Música (.zip com notes.mid)", "zip"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path zip = chooser.getSelectedFile().toPath();
        try {
            chosen = Song.load(zip);
        } catch (IOException ex) {
            match.notice("Não deu para abrir " + zip.getFileName() + ": " + ex.getMessage());
            return;
        }
        songLabel.setText(chosen.title());
        match.notice("Música pronta. Clique numa pessoa da lista e escolha Desafiar.");
        prepareAudio(zip);
    }

    /** Quem foi convidado procura o mesmo zip em plugins/palhetada/charts/ e só então aceita. */
    private void accept() {
        acceptButton.setEnabled(false);
        declineButton.setEnabled(false);
        // O nome vem da rede: só o nome do arquivo, sem pastas.
        String zipName = Path.of(match.zipName()).getFileName().toString();
        Path zip = SongAudio.chartsDirectory().resolve(zipName);
        if (!zipName.toLowerCase().endsWith(".zip") || !Files.isRegularFile(zip)) {
            audioLabel.setText("Sem som: " + zipName + " não está em plugins/palhetada/charts/.");
            match.accept();
            return;
        }
        audioLabel.setText("Preparando a música…");
        new Thread(() -> {
            prepareNow(zip);
            SwingUtilities.invokeLater(match::accept);
        }, "palhetada-prepare").start();
    }

    private void prepareAudio(Path zip) {
        if (preparedAudio.containsKey(zip.getFileName().toString())) {
            audioLabel.setText("Música pronta.");
            return;
        }
        audioLabel.setText("Preparando a música…");
        new Thread(() -> prepareNow(zip), "palhetada-prepare").start();
    }

    private void prepareNow(Path zip) {
        String text;
        try {
            preparedAudio.put(zip.getFileName().toString(), SongAudio.prepare(zip));
            text = "Música pronta.";
        } catch (IOException ex) {
            text = "Sem som: " + ex.getMessage();
        }
        String done = text;
        SwingUtilities.invokeLater(() -> audioLabel.setText(done));
    }

    /** Chamado pelo controller a cada mudança. */
    public void refresh() {
        Phase phase = match.phase();
        statusLabel.setText(match.status());
        boolean invited = phase == Phase.INVITED;
        acceptButton.setVisible(invited);
        declineButton.setVisible(invited);
        if (invited) {
            acceptButton.setEnabled(true);
            declineButton.setEnabled(true);
        }
        boolean free = !match.busy();
        chooseButton.setEnabled(free);
        instrumentBox.setEnabled(free);
        difficultyBox.setEnabled(free);

        if (phase == Phase.PLAYING && !frames.isRunning()) {
            stopAudio();
            audioStarted = false;
            frames.start();
            highway.requestFocusInWindow();
        } else if (phase != Phase.PLAYING && frames.isRunning()) {
            frames.stop();
            if (match.result() == null) stopAudio(); // saiu ou caiu: corta a música; no fim normal ela termina sozinha
        }
        highway.repaint();
    }

    private void onFrame() {
        long now = nowMs();
        match.tick(now);
        if (!audioStarted && match.phase() == Phase.PLAYING && match.gameTimeMs(now) >= 0) {
            audioStarted = true;
            Path wav = preparedAudio.get(match.zipName());
            if (wav != null) {
                playing = new SongAudio(wav);
                playing.start();
            }
        }
        highway.repaint();
    }

    public void stopAudio() {
        if (playing != null) {
            playing.stop();
            playing = null;
        }
    }
}
