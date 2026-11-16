# Plugin Palhetada

Jogo de ritmo 1v1 na rede local. As notas de um chart descem em 5 pistas e cada jogador aperta a tecla da pista quando a nota passa pela linha. Quem fizer mais pontos até o fim da música vence.

O chart vem de uma pasta de música no formato da comunidade (`.zip` com `notes.mid`, `song.ini` e faixas de áudio). Pela rede só vai o `notes.mid`. A música é opcional: quem tem o mesmo zip e o `ffmpeg` ouve, quem não tem joga só com as notas.

Todo o código fica em `plugins/palhetada/`. O núcleo não muda.

## Como jogar

1. Coloque os `.zip` das músicas em `plugins/palhetada/charts/`. A pasta está no `.gitignore` do plugin: as músicas não vão para o repositório.
2. Na aba **Palhetada**, escolha o zip, o instrumento e a dificuldade.
3. Na lista de pessoas, clique no adversário e escolha **Desafiar**.
4. O adversário aceita na aba dele. Os dois veem a contagem 3, 2, 1 e a música começa.

| Tecla | Pista |
| --- | --- |
| `A` | 1 (verde) |
| `S` | 2 (vermelha) |
| `J` | 3 (amarela) |
| `K` | 4 (azul) |
| `L` | 5 (laranja) |

Para ouvir a música é preciso ter o mesmo zip em `plugins/palhetada/charts/`, com o mesmo nome de arquivo de quem convidou, e o `ffmpeg` no PATH (`sudo pacman -S ffmpeg`, `sudo apt install ffmpeg` ou `winget install ffmpeg`). Sem um dos dois, a partida acontece em silêncio e a aba avisa o motivo.

## Compilar sem o catálogo

O plugin não está em `plugins.registry.json`, então `./scripts/pitiupi.sh run --all` não o compila. À mão, com o núcleo já compilado:

```bash
cd plugins/palhetada && ant jar
cp dist/pitiupi.palhetada.jar ../../.pitiupi/plugins/
```

Use `cp`, não link: o script apaga os links de `.pitiupi/plugins/` a cada `run`, mas mantém os arquivos copiados.

Para entrar no catálogo, acrescente ao array `plugins`:

```json
{ "id": "palhetada", "dir": "plugins/palhetada", "build": "ant", "jar": "dist/pitiupi.palhetada.jar", "menuCategory": "Jogos" }
```

## Design

### Arquivos

| Arquivo | Papel |
| --- | --- |
| `src/META-INF/services/pitiupi.models.Plugin` | SPI: `pitiupi.palhetada.plugin.PalhetadaPlugin` |
| `plugin/PalhetadaPlugin.java` | Liga ao núcleo: peer action **Desafiar**, TCP privado, aba |
| `control/MatchController.java` | Estados da partida e frames do protocolo. Sem Swing |
| `chart/Song.java` | Lê o zip: `song.ini` (nome, artista) e `notes.mid` → lista de notas |
| `audio/SongAudio.java` | Opcional: `ffmpeg` mistura as faixas num `.wav` em cache e toca |
| `game/Judge.java` | Acerto, erro, pontos, combo e multiplicador. Sem Swing |
| `GUI/PalhetadaPanel.java` | Escolha da música, convite recebido, resultado |
| `GUI/HighwayView.java` | Pista em perspectiva desenhada com Java2D a cerca de 60 quadros por segundo, e placar dos dois |

Não há biblioteca nova: `javax.sound.midi`, `javax.sound.sampled`, `java.util.zip` e o `ffmpeg` externo.

### Chart

`Song` lê o `notes.mid` com `MidiSystem.getSequence`. As notas vêm da trilha `PART GUITAR` ou `PART BASS`, conforme o instrumento:

| Dificuldade | Notas MIDI (pistas 1 a 5) |
| --- | --- |
| Expert | 96 a 100 |
| Hard | 84 a 88 |
| Medium | 72 a 76 |
| Easy | 60 a 64 |

O tick de cada nota vira milissegundos pelo mapa de tempo (eventos meta `0x51` da primeira trilha) e pela resolução do arquivo. Outros números MIDI (HOPO forçado, especial, solo) são ignorados. Cada nota é `(ms, pista)`. Duração não entra: nota longa vira toque.

A música termina 2 segundos depois da última nota.

### Partida

Tudo passa por uma conexão TCP privada entre os dois (`connectPluginTcpAsync` com `getIdentifier()` nos dois lados; chegada em `receiveBytes`). Cada frame é escrito com `DataOutputStream`: um byte de tipo e os campos. Não usa serialização Java, para não depender do classloader do plugin.

| Frame | Sentido | Campos |
| --- | --- | --- |
| `INVITE` | quem convida → adversário | nome de quem convida, título da música, nome do zip, instrumento, dificuldade, bytes do `notes.mid` |
| `ACCEPT` | adversário → quem convida | nenhum |
| `DECLINE` | qualquer lado | nenhum. Recusa o convite ou sai da partida |
| `START` | quem convida → adversário | nenhum |
| `SCORE` | os dois, a cada 250 ms | pontos, combo |
| `END` | os dois, no fim da música | pontos finais |

Fluxo:

1. Quem convida escolhe o zip. Se houver `ffmpeg`, o `.wav` é preparado nesse momento.
2. **Desafiar** abre o TCP e envia `INVITE`.
3. O adversário vê o convite com **Aceitar** e **Recusar**. `DECLINE` fecha a conexão e a aba de quem convidou mostra "recusou".
4. Ao aceitar, o adversário procura o mesmo zip em `plugins/palhetada/charts/` e prepara o `.wav` se puder. Depois envia `ACCEPT`.
5. Quem convida recebe `ACCEPT`, envia `START` e começa a contagem de 3 segundos. O adversário começa a dele ao receber `START`.
6. Durante a música, cada lado envia `SCORE` e mostra o placar do outro.
7. No fim, cada lado envia `END`. Com os dois `END`, a aba mostra **Você venceu**, **Você perdeu** ou **Empate**.

Uma partida por vez. Se chegar um convite durante outra partida, a resposta é `DECLINE`. Se a conexão cair (`onSocketClosed`) antes dos dois `END`, a aba mostra "adversário desconectou" e a partida termina. Fechar a aba (`onShellTabClosed`) para o áudio e envia `DECLINE`: o outro lado vê "saiu da partida".

O plugin não fecha o TCP privado: o núcleo não oferece esse método ao plugin. A conexão fica aberta até o tempo limite de inatividade do núcleo (5 minutos). Cada desafio abre uma conexão nova, e o controller ignora frames e quedas de qualquer conexão que não seja a da partida atual.

### Tempo

Cada jogador é julgado pelo próprio relógio: `System.nanoTime()` contado do fim da contagem. O atraso da rede só afeta quando o placar do outro aparece, nunca o acerto. Se houver áudio, ele começa a tocar no instante zero desse relógio.

### Pontuação

- Uma tecla acerta a nota mais antiga ainda não julgada daquela pista, se a diferença for de até ±100 ms.
- Acerto: 50 pontos × multiplicador. O multiplicador é 1x, sobe para 2x com 10 acertos seguidos, 3x com 20 e 4x com 30.
- Nota que passa mais de 100 ms da linha sem ser apertada: erro.
- Tecla apertada sem nota na janela: erro.
- Erro zera o combo e volta o multiplicador para 1x.

Cada pista é julgada sozinha. Um acorde é apertar as teclas das pistas juntas.

### Fora do escopo

Nota longa, HOPO, especial, nível "Perfeito", ajuste de atraso do áudio, arquivos `.chart`, bateria e vocal, mais de dois jogadores, envio do áudio pela rede. Se o som ficar visivelmente fora do tempo, o próximo passo é um campo de atraso em ms.
