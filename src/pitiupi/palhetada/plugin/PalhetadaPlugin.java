package pitiupi.palhetada.plugin;

import pitiupi.api.plugin.PeerActionsRegistrar;
import pitiupi.api.plugin.PluginWorkspace;
import pitiupi.application.peeractions.PeerAction;
import pitiupi.application.peeractions.PeerActionTarget;
import pitiupi.application.peeractions.PeerTarget;
import pitiupi.control.ConnectionManager;
import pitiupi.models.Messages.Message;
import pitiupi.models.Plugin;
import pitiupi.net.tcp.TcpAttemptListener;
import pitiupi.net.tcp.TcpConnection;
import pitiupi.palhetada.GUI.PalhetadaPanel;
import pitiupi.palhetada.chart.Song;
import pitiupi.palhetada.chart.Song.Difficulty;
import pitiupi.palhetada.chart.Song.Instrument;
import pitiupi.palhetada.control.MatchController;
import pitiupi.presentation.shell.PeerIpv4;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

/**
 * Liga o jogo ao núcleo: peer action "Desafiar", TCP privado e a aba.
 * Todo o estado da partida muda na thread do Swing.
 */
public class PalhetadaPlugin extends Plugin {

    private final MatchController match = new MatchController();
    private PalhetadaPanel panel;
    private PluginWorkspace workspace;
    private ConnectionManager connectionManager;

    @Override
    public void createPlugin(ConnectionManager connectionManager, PluginWorkspace workspace) {
        this.connectionManager = connectionManager;
        this.workspace = workspace;
        this.panel = new PalhetadaPanel(match);
        match.setOnChange(() -> {
            panel.refresh();
            if (match.phase() == MatchController.Phase.INVITED) {
                workspace.openOrFocusTab(getIdentifier(), getDisplayName());
            }
        });
        bindShellWorkspaceContent(panel);
        installPeerActions(workspace.peerActions());
    }

    @Override
    public void installPeerActions(PeerActionsRegistrar registrar) {
        registrar.register(PeerAction.of(
                "palhetada.challenge",
                "Desafiar",
                PeerActionTarget.SINGLE_PEER,
                this::challenge
        ));
    }

    private void challenge(PeerTarget target) {
        workspace.openOrFocusTab(getIdentifier(), getDisplayName());
        if (!(target instanceof PeerTarget.Single single)) return;
        if (match.busy()) {
            match.notice("Você já está numa partida.");
            return;
        }
        Song song = panel.chosenSong();
        if (song == null) {
            match.notice("Escolha uma música antes de desafiar.");
            return;
        }
        String opponent = single.peer().getDisplayName();
        Optional<InetAddress> address = single.peer().getInetAddress();
        if (address.isEmpty()) {
            match.notice("Sem IP para " + opponent + ".");
            return;
        }
        Instrument instrument = panel.instrument();
        Difficulty difficulty = panel.difficulty();
        String ip = PeerIpv4.fromInetAddress(address.get());
        connectionManager.connectPluginTcpAsync(ip, getIdentifier(), getIdentifier(), new TcpAttemptListener() {
            @Override
            public void onConnecting(String endpoint) {
                match.notice("Conectando em " + opponent + " (" + endpoint + ")…");
            }

            @Override
            public void onSuccess(TcpConnection connection) {
                match.invite(connection, bytes -> send(connection, bytes), connectionManager.getUserName(),
                        opponent, song, instrument, difficulty);
            }

            @Override
            public void onFailure(String message) {
                match.notice(message);
            }
        });
    }

    // ponytail: o núcleo não deixa o plugin fechar o TCP privado; ele fica aberto até o tempo limite de 5 min.
    private void send(TcpConnection connection, byte[] frame) {
        try {
            connection.send(frame);
        } catch (IOException ex) {
            SwingUtilities.invokeLater(() -> match.onClosed(connection));
        }
    }

    @Override
    public boolean receivePrivateConnection(TcpConnection connection) {
        return true; // o convite chega como frame em receiveBytes
    }

    @Override
    public boolean receiveBytes(TcpConnection connection, byte[] bytes) {
        long now = System.nanoTime() / 1_000_000; // instante da chegada, não o da fila do Swing
        SwingUtilities.invokeLater(() -> match.onFrame(connection, frame -> send(connection, frame), bytes, now));
        return true;
    }

    @Override
    public boolean onSocketClosed(TcpConnection connection) {
        SwingUtilities.invokeLater(() -> match.onClosed(connection));
        return true;
    }

    @Override
    public void onShellTabClosed() {
        match.quit();
        panel.stopAudio();
    }

    @Override
    public List<Class<? extends Message>> getMessages() {
        return List.of();
    }

    @Override
    public void receiveMessage(Message message) {
    }

    @Override
    public String getName() {
        return "Palhetada";
    }

    @Override
    public String getDescription() {
        return "Jogo de ritmo 1v1: acerte as notas no tempo e faça mais pontos que o adversário.";
    }

    @Override
    public String getAuthor() {
        return "José Faria";
    }

    @Override
    public String getVersion() {
        return "1";
    }

    @Override
    public String getIdentifier() {
        return "pitiupi.palhetada";
    }
}
