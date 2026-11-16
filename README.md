# Plugin Palhetada

Jogo de ritmo 1v1 na rede local. As notas de um chart descem em 5 pistas e cada jogador aperta a tecla da pista quando a nota passa pela linha. Quem fizer mais pontos até o fim da música vence.

O chart vem de uma pasta de música no formato da comunidade (`.zip` com `notes.mid`, `song.ini` e faixas de áudio). Da música, só o `notes.mid` vai pela rede; o áudio fica em cada máquina. O som é opcional: quem tem o mesmo zip e o `ffmpeg` ouve, quem não tem joga só com as notas.

## Antes de iniciar

Antes de iniciar, acrescente este objeto ao array `plugins` do `plugins.registry.json`, na raiz do pitiupi. Separe-o do item anterior com uma vírgula:

```json
{
  "id": "palhetada",
  "dir": "plugins/palhetada",
  "build": "ant",
  "jar": "dist/pitiupi.palhetada.jar",
  "menuCategory": "Jogos"
}
```

Caso você não tenha o `ffmpeg`, instale-o e, se necessário, corrija o PATH. Sem ele o jogo funciona, mas em silêncio.

**Linux**

```bash
sudo pacman -S ffmpeg     # Arch, CachyOS, Manjaro
sudo apt install ffmpeg   # Debian, Ubuntu, Mint
ffmpeg -version           # confere se está no PATH
```

Pelo gerenciador de pacotes o `ffmpeg` vai para `/usr/bin`, que já está no PATH. Se você baixou o binário à mão, acrescente a pasta dele ao PATH no `~/.bashrc` ou `~/.zshrc`:

```bash
export PATH="$PATH:$HOME/ffmpeg"   # troque pela pasta onde está o executável ffmpeg
```

**Windows**

No PowerShell ou no cmd:

```powershell
winget install Gyan.FFmpeg
```

Feche e abra o terminal e confira com `ffmpeg -version`. Se o comando não for encontrado, instale e corrija o PATH à mão:

1. Baixe o build *essentials* em <https://www.gyan.dev/ffmpeg/builds/> e extraia em `C:\ffmpeg`, de modo que exista `C:\ffmpeg\bin\ffmpeg.exe`.
2. No menu Iniciar, pesquise "variáveis de ambiente" e abra **Editar as variáveis de ambiente para sua conta**.
3. Selecione `Path`, clique em **Editar**, depois em **Novo**, e escreva `C:\ffmpeg\bin`.
4. Feche e abra o terminal e rode `ffmpeg -version` de novo.

O pitiupi lê o PATH na hora em que abre. Depois de instalar o `ffmpeg` ou mudar o PATH, feche e abra o pitiupi e o terminal de onde você o executa.
