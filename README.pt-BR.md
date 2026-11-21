# The Elder Scrolls V: Skyrim (PC, 2011) para Android

Esta é uma adaptação não oficial que recompila a versão original de 2011 de *The Elder Scrolls V: Skyrim* para Android. Ela não é a Special Edition. O projeto foi testado em um aparelho Snapdragon com GPU Adreno 610; outros aparelhos ARM64 com Vulkan 1.1 podem funcionar, mas não são todos testados.

Este repositório não contém o jogo nem seus arquivos. Para jogar, use uma cópia própria da versão para PC. O projeto não é afiliado nem endossado pela Bethesda.

> [Read this document in English](README.md).

## O que o port faz

- **Recompilação estática:** converte o código x86 do jogo para ARM64 sem reconstruir o código-fonte original. `TESV.exe`, `d3dx9_42.dll`, `binkw32.dll` e `X3DAudio1_7.dll` são recompilados.
- **Vários núcleos:** as tarefas do jogo podem usar todos os núcleos do telefone.
- **Gráficos:** uma versão modificada do [dxvk-native](https://github.com/Joshua-Ashton/dxvk-native) traduz Direct3D 9 para Vulkan 1.1.
- **Interface e áudio:** SDL2 2.30.9 cuida da janela, da entrada, do áudio e da atividade Android. Há controles opcionais na tela para jogar sem controle físico.

## Opções do aplicativo

O aplicativo abre primeiro o launcher. Ele permite escolher o idioma do próprio launcher, a pasta do jogo e as opções gráficas antes de iniciar.

- **Idioma:** inglês ou português do Brasil. A primeira execução segue o idioma do Android quando ele é português; caso contrário, inicia em inglês. A escolha pode ser alterada no launcher.
- **Pasta do Skyrim:** local dos arquivos do jogo.
- **Detalhes gráficos:** Baixo, Médio, Alto ou Ultra. Baixo é uma boa opção inicial em aparelhos modestos.
- **Resolução:** resolução da tela ou uma resolução menor, até 108p, ampliada para preencher a tela.
- **Opções gráficas:** proporção da tela, antisserrilhamento, filtragem anisotrópica, qualidade das texturas e sombras, marcas, desfoque radial, distância de exibição, FXAA e sincronização vertical.
- **Controles:** controles de toque opcionais e layout Nintendo para inverter A/B e X/Y em controles físicos.

## Arquivos do jogo necessários

Todos os arquivos vêm da sua própria instalação de *The Elder Scrolls V: Skyrim* para PC (2011).

| Para gerar o código no PC | Para jogar no Android |
| :-- | :-- |
| `TESV.exe` | `TESV.exe` |
| `d3dx9_42.dll` de 32 bits | `d3dx9_42.dll` de 32 bits |
| `binkw32.dll` | `binkw32.dll` |
| `X3DAudio1_7.dll` de 32 bits | `X3DAudio1_7.dll` de 32 bits |
| `Data/` | `Data/` |

`d3dx9_42.dll` e `X3DAudio1_7.dll` fazem parte do DirectX June 2010 Redistributable. Em algumas instalações ficam em `C:\Windows\SysWOW64` ou na pasta `_CommonRedist\DirectX` do Steam. `binkw32.dll` fica na pasta do jogo.

### Versões compatíveis

- O executável Steam `TESV.exe` 1.9.32.0.8 tem SHA-1 iniciado por `f39aeceb`.
- Outras versões de disco, versões antigas e a Special Edition não são compatíveis com o código recompilado atual. A Special Edition é 64 bits e usa uma versão diferente da engine.
- O suporte a DLC e mods não é garantido. `Update.esm` é compatível.

## Gerar uma APK

A geração é feita em duas etapas: criar os arquivos C recompilados em `gen/` a partir dos arquivos do jogo e compilar a APK com Gradle, Android NDK e CMake. O conteúdo gerado não é incluído no repositório porque deriva dos arquivos do jogo.

### 1. Preparar os arquivos recompilados

A etapa funciona melhor em Linux ou WSL. Requer Python 3.10 ou mais recente e:

```sh
pip install capstone==5.0.9 pefile==2024.8.26
```

`unicorn==2.0.1.post1` só é necessário para o autoteste `tools/difftest.py`.

Coloque os arquivos necessários em uma pasta, chamada `GAME` neste exemplo, e execute na raiz do repositório:

```sh
tools/regen_gen.sh GAME
```

O script encontra e recompila os quatro módulos do jogo. A descoberta do executável pode levar algumas horas; a recompilação leva cerca de 15 minutos. O código C gerado ocupa aproximadamente 340 MB. Se `tools/lift.py` mudar, gere `gen/` novamente.

### 2. Compilar a APK Android

Requisitos: JDK 17 ou mais recente, Android SDK Platform 34, Build Tools 34, CMake 3.22.1 e Android NDK r27 ou mais recente. O projeto foi compilado com a revisão `29.0.14206865` do NDK.

Instale os componentes pelo Android Studio ou pelo terminal:

```sh
sdkmanager "platforms;android-34" "build-tools;34.0.0" "cmake;3.22.1" "ndk;29.0.14206865"
```

Opcionalmente, gere o ícone a partir do executável:

```sh
python3 tools/make_icon.py GAME/TESV_unpacked.exe
```

Compile a APK:

```sh
cd android
# Linux ou macOS
./gradlew assembleDebug -PndkVersion=29.0.14206865
# Windows
gradlew.bat assembleDebug "-PndkVersion=29.0.14206865"
```

A APK fica em `android/app/build/outputs/apk/debug/app-debug.apk`. A primeira compilação pode levar de 15 a 60 minutos. Cada arquivo recompilado usa de 1 a 2 GB de RAM durante a compilação; o projeto limita as tarefas paralelas a três. A variante `debug` também compila o código nativo otimizado.

## Instalar e iniciar no Android

O aparelho precisa de processador ARM64, Android 9 ou mais recente, Vulkan 1.1 e pelo menos 3 GB de RAM. Reserve cerca de 7 GB de armazenamento livre.

1. Baixe uma APK na [página de versões do projeto](https://github.com/ttom795/Skyrim_Android/releases) ou gere sua própria APK seguindo as instruções acima.
2. Instale a APK. Ao abrir o arquivo no Android, permita a instalação de aplicativos para o gerenciador de arquivos usado. Pelo ADB, use `adb install -r Skyrim-<versão>.apk`.
3. Copie `TESV.exe`, as três DLLs necessárias e a pasta `Data/` para uma pasta fora de `Android/data`, por exemplo `Armazenamento interno/Skyrim/`. A pasta `Data` deve ficar dentro da pasta do jogo, ao lado do executável e das DLLs. Pelo computador, use a transferência de arquivos USB ou `adb push <pasta-do-jogo>/. /sdcard/Skyrim/`.
4. Abra o aplicativo, escolha **Escolher pasta do Skyrim**, selecione essa pasta e permita o acesso aos arquivos. O launcher mostrará **Pronto** quando os arquivos necessários forem encontrados.
5. Escolha as opções gráficas e toque em **Jogar**.

O port grava os logs, as configurações e os saves dentro da pasta `port` do jogo. Os saves ficam em `port/save/Documents/My Games/Skyrim`; eles permanecem lá mesmo que o aplicativo seja desinstalado.

## Controles

Controles Bluetooth, USB e controles integrados são compatíveis. O layout padrão é Xbox. Os controles de toque exibem analógico, A/B/X/Y, botões de ombro, Start e Back.

## Tradução do launcher e dublagem do jogo

A opção **Português (Brasil)** traduz os textos do launcher. Os arquivos de tradução do jogo e da dublagem são mods separados, descritos abaixo.

Para traduzir os textos do Skyrim e instalar vozes em português, são necessários arquivos de mods de terceiros compatíveis com a edição original de 2011. Esses arquivos não são distribuídos neste repositório. Baixe-os diretamente dos autores, siga suas instruções e confira as permissões de uso. Em geral, os arquivos de voz extraídos ficam em `Data/sound/voice/`.

O funcionamento de mods e DLC no Android não é garantido pelo projeto. A reprodução de certos formatos de áudio, incluindo xWMA, pode não funcionar em algumas versões do port. Teste o áudio antes de iniciar uma campanha longa.

## Diagnóstico

O arquivo `port/log.txt` é atualizado a cada inicialização e registra o uso dos núcleos e amostras de desempenho a cada 30 segundos. Anexe esse arquivo ao relatar um problema.

Para alterar o comportamento, crie um arquivo vazio em `port/save/`:

| Arquivo | Efeito |
| :-- | :-- |
| `singlecore.txt` | Executa as tarefas do jogo em um núcleo; fica mais lento e ajuda a investigar problemas entre threads. |
| `noprof.txt` | Desativa o perfil de amostragem no log. |

## Problemas conhecidos

- Alguns formatos de música e vozes, incluindo xWMA, podem ficar sem áudio.
- O desempenho em GPUs Adreno 610 pode ser baixo. Use o preset Baixo e reduza a resolução.
- DLCs e mods não são garantidos.

## Licença e arquivos do jogo

The Elder Scrolls, Skyrim, Bethesda e os respectivos nomes e conteúdos pertencem aos seus proprietários. Este projeto não é afiliado à Bethesda nem endossado por ela. Não redistribua conteúdo extraído dos arquivos do jogo. Componentes de terceiros mantêm suas próprias licenças, incluindo SDL2 (zlib) em `android/app/jni/SDL2` e dxvk-native (zlib) em `android/app/jni/dxvk`.
