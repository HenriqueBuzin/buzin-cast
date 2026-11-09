# Retomada no Mac — paridade entre Vega OS e Fire OS

Alvo de versões: Vega OS 1.0 a 2.0. Matriz, SDKs e linhas em paralelo em
[VEGA_OS_VERSIONS.md](VEGA_OS_VERSIONS.md).

## Ambiente preparado neste Mac — 9 de novembro de 2026

- Mac arm64/macOS 27.2 partiu sem ferramentas Vega. Instalados Homebrew, Rosetta,
  as dependências do guia oficial, `cmake`, `ninja`, nvm 0.41.0, Node 26.13.0,
  Vega CLI 1.4.9 e SDK 2.0.13986 (ativo).
- `npm ci`, lint sem avisos, TypeScript e 39 testes Jest passaram.
  `vega project doctor` passou. `npm run build:app` gerou os VPKGs release
  ARM64, ARMv7 e x86_64.
- Não executado nesta preparação: `vega exec vpt validate`, VVD e
  `tools/test_vega.py`. Paridade e receptores seguem pendentes.
- O build falhou primeiro porque o submódulo `libplist` estava vazio. Restaurado
  com `git submodule update --init --force` no commit fixado (`fe3dc34`). Neste
  checkout, `ffmpeg` e `openssl-cmake` continuam vazios: o Vega não os usa, mas
  a build Android precisa deles.
- O instalador do Homebrew trocou o `xcode-select` para as Command Line Tools.
- As ferramentas Vega coletam telemetria por padrão; ver a
  [documentação](https://developer.amazon.com/docs/vega/latest/telemetry.html)
  para desativar.

## Decisão vigente — 5 de novembro de 2026

Henrique adiou a unificação depois de avaliar a interface React Native comum.
As duas edições Fire OS devem permanecer nativas e iguais ao estado já testado;
a edição Fire OS 6+ 0.6.0/código 22 é a referência funcional e visual.
Continuar somente o port específico Vega com SDK oficial e integrações C++.
Primeiro testar a equivalência; pensar em interface/base unificada depois.
Não migrar os launchers ou interfaces Android para React Native nesta etapa.

Conferência: `git diff a5af641 -- app receiver-core dlna-domain gradle
build.gradle.kts settings.gradle.kts` não mostrou alterações. Não houve
migração Android para desfazer. Na preparação local foram instalados JDK
Temurin 25.0.5+8 LTS, Android CLI atual, plataforma 37.0, build-tools 37.0.0 e
NDK r30; a instalação já havia terminado quando a decisão chegou. Essas
ferramentas locais não alteram os apps nem as TVs e não são versionadas.


## Reprodução local W3C no Vega — 5 de novembro de 2026

- Integrado o player nativo do sistema por `@amazon-devices/react-native-w3cmedia`
  (perfil SDK `~2.3.0`, lock 2.3.5), com VideoPlayer e KeplerVideoSurfaceView.
  A página Mídia oferece Testar reprodução. A amostra própria inclui padrão
  animado H.264 Baseline 320×180/15 fps e AAC estéreo 48 kHz, oito segundos,
  em `assets/raw/media/playback-sample.mp4`. Não depende de internet.
- Reprodução com imagem visível foi conferida na tela do Mac. O smoke passou
  pelo controle real: relógio avançando, pausa mantendo posição, retomada,
  Voltar aguardando deinitialize, segunda sessão, evento ended em 7,999 s,
  Home liberando recursos no segundo plano e retorno à tela inicial.
  Relatório: `build/vega-playback-checks/result.json`; captura local em
  `playback-visible.png`. Som audível/sincronismo não foram medidos.
- O controller serializa a abertura após o encerramento da sessão anterior,
  trata Voltar durante initialize, remove listeners/timer, mantém erro terminal
  diante de evento atrasado e usa AppState para sair do teste no segundo plano.
  O módulo C++ grava estado/segundos reais por renomeação atômica no sandbox,
  sem URL ou conteúdo do usuário, em `/data/playback-state.json`.
- O primeiro manifesto de mídia deixou faltar audio.system e os canais GIPC;
  corrigido conforme a documentação completa. Adicionados os privilégios
  documentados secureplayback/file-sharing e serviços de buffers/transforms.
  Ainda houve timeout GetWriteBuff/Bad Value em novaaudiosink no VVD que vinha
  rodando desde a sessão anterior. Após reiniciar **somente o VVD**, preservando
  seus dados, todos os cenários passaram. Não afirmar que era defeito do app ou
  do hardware sem nova reprodução; guardar esse diagnóstico para recorrências.
- getVideoPlaybackQuality devolveu zero e registrou erro nativo 50001 apesar
  da imagem visível. Removida a consulta desse contador do teste/player; não
  expor zero como prova de ausência de vídeo. A consulta separada de capacidade
  H.264 também segue retornando zero, apesar de esta amostra ser reproduzida.
- Lint sem avisos, TypeScript, doctor e 12 testes Jest passaram; as três ABIs
  release passaram em build e validação ABI. Smoke inclui regressão dos sete
  testes de rede/parser/cripto, consulta de codecs e navegação Sobre/Voltar.
  O aviso dlopen do OpenSSL continua pendente da revisão já registrada.
- Fire OS permanece sem alterações. Não há receptor/descoberta anunciado no
  Vega nem integração dos callbacks AirPlay. Próximo: conectar recepção ao
  player (URL para os protocolos correspondentes; MSSE para amostras AirPlay),
  implementar descoberta/ciclo de vida dos servidores e portar configurações,
  perfis e interface da edição Fire OS 6+ testada. A meta de equivalência segue
  ativa; reprodução local não comprova paridade ou validação física.

Referências: [player W3C](https://developer.amazon.com/docs/vega-api/2.0/README.amazon-devices_react-native-w3cmedia),
[manifesto de mídia](https://developer.amazon.com/docs/vega/2.0/multiview-apps)
e [arquivos do pacote](https://developer.amazon.com/docs/vega/2.0/vpt).


## Consulta de mídia no Vega — 5 de novembro de 2026

- Integrado `@amazon-devices/keplermediadescriptor`, resolvido oficialmente por
  `vega project install` para OS 2.0/RN 0.83: linha `~1.1.0`, lock 1.1.17.
  A página Mídia consulta as classes públicas VideoDecoderConfig e
  AudioDecoderConfig com `video/h264`, `video/hevc` e `audio/aac`.
- No VVD ARM64: H.264 = 0 resultados, HEVC = 0, AAC = 1. Também foi consultado
  `video/avc` em uma build anterior, com zero resultados. A API pode retornar
  lista vazia por erro interno ou ausência de suporte; zero não prova que o
  hardware não tenha o codec. Não há fallback com valores inventados.
- O módulo C++ registra apenas as três contagens verificadas em
  `/data/media-capabilities.json`; rejeita negativos, frações e não finitos.
  Não grava mídia ou endereços. Uma consulta interrompida não grava relatório
  parcial. A tela distingue consulta de capacidade de reprodução validada.
- Cuidado para a retomada: os headers IDL do SDK oferecem
  `media_descriptor@IMediaDescriptorFactory`, mas esse módulo não está no
  contrato público OS 2.0 nem na lista `vpm list modules` do VVD. A tentativa
  compilou e foi recusada na instalação. Removidos o import IDL e a dependência
  manual; a integração final usa o módulo público versionado
  `/com.amazon.kepler.keplermediadescriptor_1@IKeplermediadescriptor_1`,
  declarado automaticamente pelo build. Não basta um header existir no SDK.
- Lint sem avisos, TypeScript, doctor e sete testes Jest passaram. Build release
  ARM64/ARMv7/x86_64 e validação ABI passaram. Persiste o aviso dlopen do OpenSSL
  já registrado. Smoke pelo controle: sete verificações nativas aprovadas,
  consulta real dos codecs, navegação Sobre/Voltar e repetição do diagnóstico.
  Resultados locais em `build/vega-media-checks/result.json`; revisão visual
  em `media-visible.png`, com texto e foco Voltar inteiros na tela.
- Android permanece idêntico à referência testada; nenhum teste foi instalado
  nas TVs físicas. A integração de reprodução, saída de áudio/vídeo e descoberta
  permanece pendente. Próximo: usar as interfaces públicas W3C Media/MSSE para
  validar amostras reais antes de conectar callbacks do UxPlay ou anunciar AirPlay.
  Capacidades do simulador não comprovam decodificação ou equivalência física.

Referência: [KeplerMediaDescriptor — API OS 2.0](https://developer.amazon.com/docs/vega-api/2.0/classes.KeplerMediaDescriptor.amazon-devices_keplermediadescriptor).


## Núcleo UxPlay compilado para Vega — 4 de novembro de 2026

- `vega/cmake/AirPlayCore.cmake` compila os mesmos submódulos UxPlay 1.73.7,
  libplist 2.8.0, llhttp e PlayFair usados no Fire OS, sem copiar suas fontes.
  Cifra com OpenSSL 3.5.9 LTS estático, compilado pela toolchain Vega.
- Download oficial OpenSSL com SHA256
  `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a`,
  conferido no asset oficial `.sha256`. Build release completa nas três ABIs.
- Diagnóstico no sandbox inclui parser UxPlay com RTSP OPTIONS fragmentado,
  rejeição de mensagem malformada e vetor AES-128-CTR NIST SP 800-38A F.5.1.
  Os sete resultados (quatro de sockets e três novos) passaram no VVD ARM64,
  via controle real, e após Voltar/Sobre/nova execução. Relatório em
  `build/vega-protocol-checks/result.json`. O primeiro teste do parser falhou
  por interpretar seu retorno como contagem de bytes; corrigido para HPE_OK.
- Licenças e créditos do código incorporado adicionados em `vega/assets/raw`.
  O VPT passou na ABI; informa uso de dlopen no OpenSSL. Rever configuração
  de providers/DSO antes de publicação e manter teste de cifra no dispositivo.
- Limite: parser/cripto e arquivo estático de protocolo não são recepção
  AirPlay. DNS-SD é uma fronteira ainda sem adaptador Vega; não foi criada
  implementação fictícia nem anunciado serviço. Áudio/vídeo seguem pendentes.


## Revalidação visual e correção de layout — 4 de novembro de 2026

- Henrique confirmou launcher e Buzin Cast visíveis. A captura da tela inteira
  do macOS mostrou o app funcionando, com logo aprovada e foco correto.
  **A hipótese anterior de falha de renderização não se confirmou**: PNG vazio
  do screenshooter e framebuffer QMP preto são limitações desses métodos de
  captura neste ambiente, não evidência de app sem imagem.
- A revisão visual encontrou um defeito real: na página Estado do port o
  cabeçalho grande empurrava Voltar para fora da tela. Corrigido com cabeçalho
  compacto, espaçamentos menores nas páginas internas e resultado de rede
  legível em duas linhas. Tela inicial mantém o cabeçalho original.
- Verificadas visualmente tela inicial, diagnóstico completo, foco de Voltar
  e página Sobre, usando controle do VVD. Capturas locais em
  `build/vega-checks/{home-visible,status-fixed,status-back-focus,about-visible}.png`.
  Captura da tela inteira não é versionada (inclui outras janelas do usuário).
- Lint sem avisos, TypeScript e quatro testes Jest passaram; VPKGs release
  recompilados nas três arquiteturas. Aplicativo aberto no VVD ARM64.
- O controle de PC não reconhece o executável Qt do VVD como app selecionável;
  a captura do macOS, explicitamente solicitada por Henrique, permitiu a revisão.
  Não bloquear o port por essa limitação da ferramenta.
- A meta reafirmada por Henrique é paridade funcional com as duas edições
  Fire OS. Projeto, prévia e diagnóstico não encerram o trabalho: seguir com
  rede/LAN/mDNS, UxPlay, decodificação/renderização/áudio e demais recursos.


## Módulo nativo e execução no VVD — 4 de novembro de 2026

- SDK e VVD 2.0.13986 instalados. Build release com C++23 concluída nas
  três arquiteturas, com `libBuzinNetwork.so` e validação ABI pelo VPT.
- Turbo Module próprio, derivado de `basic-turbo-module`, usa headers reais
  do SDK. Interface gerada por Kepler Codegen 1.2.5. Autolink fica em
  `react-native.config.js`: a CLI rejeita o campo antigo em `package.json`,
  embora o guia ainda o mostre para módulos in-app.
- Diagnóstico acionado pelo controle testa TCP/UDP em loopback, bind TCP
  em todas as interfaces e entrada/saída do grupo multicast 224.0.0.251.
  Nenhum anúncio DNS-SD, receptor AirPlay ou pacote multicast é enviado.
  Resultado booleano salvo em `/data/network-probe.json` no sandbox do app.
- `python3 tools/test_vega.py --serial emulator-5554 --vpkg
  vega/build/aarch64-release/buzincast_aarch64.vpkg` passou no VVD ARM64.
  A sequência de controle acionou JS → C++ → sockets no sandbox, voltou,
  percorreu Sobre e repetiu o diagnóstico. Todos os quatro resultados foram
  `true` nas duas execuções. O script recusa seriais físicos/IP.
- Executável de diagnóstico também rodou no shell de desenvolvimento, com
  quatro resultados `true`; esse resultado separado não substitui o teste no
  sandbox. Executar de `/tmp` dentro do sandbox foi negado (noexec), como
  esperado; nenhuma proteção foi desligada.
- Artefatos locais: `build/vega-checks/result.json`, `commands.json`,
  `packages.json` (tamanhos/SHA256) e `native-build.log`. SDK, imagens, logs,
  executáveis e pacotes continuam fora do Git.
- **Validação visual pendente:** o app foi instalado/iniciado e o ciclo de
  controle/diagnóstico passou, mas `gwsi-tool-screenshooter` ficou aguardando
  e criou PNG vazio. Captura QMP do framebuffer ficou preta; logs em macOS
  27.2 têm falhas de validação OpenGL. Desbloquear o Mac não resolveu.
  Tentativa reversível de SwiftShader no config do VVD foi ignorada pelo
  wrapper (modo host); a biblioteca SwiftShader não está no SDK instalado.
  Config original restaurado e VVD encerrado para liberar recursos.
- Lint sem avisos, TypeScript, quatro testes Jest e doctor aprovados nesta
  etapa. O CI cobre fontes; ainda não executa o SDK/VVD. Meta global de
  cobertura 100% permanece pendente. Android não foi alterado nesta etapa.
- Próximo: resolver renderização do VVD neste Mac, testar layout/launcher
  visualmente e persistência de configurações; testar TCP/UDP recebidos da
  LAN e anúncio mDNS com privilégios reais antes de portar UxPlay e mídia.
  O diagnóstico de sockets não é receptor nem prova de descoberta local.
  Áudio/vídeo, sincronismo, protocolos e paridade Fire OS continuam pendentes.


## Build Vega concluída no Mac — 4 de novembro de 2026

- Após limpeza de espaço autorizada, SDK estável 2.0.13986 reinstalado e
  selecionado com `vega sdk use 2.0.13986`; CLI 1.4.7, Node nvm 26.13.0 LTS.
- `vega project update-manifest --os-min 2.0 --os-version 2.0` removeu a
  dependência OS gerada manualmente pelo template. Removida também a
  dependência opcional NetworkManager que a prévia não usa.
- Prévia ganhou foco visível, páginas Estado do port/Sobre e retorno pelo
  controle. Três testes Jest passaram; TypeScript e lint sem avisos.
- Ícone 512x512 copiado sem alterações de `mipmap-xxxhdpi/buzin_launcher.png`,
  referenciado pelo manifesto e confirmado dentro do pacote.
- `vega project doctor`: todas as verificações críticas passaram.
  `npm run build:app`: release concluída em ARM64, ARMv7 e x86_64.
  VPKGs em `vega/build/{aarch64,armv7,x86_64}-release/` (não versionados).
  `vega exec vpt validate` aceitou o pacote ARM64.
- CI de fontes adicionado em `.github/workflows/vega-check.yml`: npm ci,
  lint sem avisos, TypeScript, Jest e comparação binária das duas artes.
  Esse CI não equivale a build SDK nem execução no VVD.
- Instalação da imagem VVD ainda em andamento nesta entrega de build;
  execução no simulador e testes nativos serão registrados separadamente.
  Não há receptor implementado nem paridade concluída.


## Retomada no Mac — 4 de novembro de 2026

- Integrado em `vega/` o template oficial `helloWorld` do SDK 2.0.13986,
  gerado primeiro em `/tmp/buzin-cast-vega-official`. Manifesto com ID
  `com.buzincast.tv`, mínimo/target 2.0 e categoria principal do launcher.
- Fontes em `vega/src`, arte aprovada copiada sem alterações, entrypoint,
  configurações, teste de renderização e lock npm adicionados.
- Mac arm64/macOS 27.2. CLI 1.4.7 instalada em `~/vega/bin`.
  Node 26.13.0 LTS instalado pelo nvm; `.nvmrc` fixa essa versão.
  Dependências Homebrew exigidas pela documentação instaladas.
- `npm ci`, `npm test -- --runInBand`, `npx tsc --noEmit` e verificação da
  marca executados. Build release gerou JS/sourcemap/Hermes, mas falhou na
  etapa nativa por ausência de SDK após a reversão descrita abaixo.
- Bloqueio: disco chegou a aproximadamente 1 GiB livre durante extração VVD.
  Interrompida a extração; o gerenciador reverteu SDK e VVD. Somente a CLI
  permanece instalada. O SDK ocupa cerca de 2,7 GiB e o arquivo VVD contém
  cerca de 3,51 GiB descompactados, além de downloads e temporários.
  Após espaço liberado por Henrique, havia cerca de 5,1 GiB livres; reservar
  pelo menos 10 GiB antes de repetir `vega sdk install 2.0.13986`.
- `npm audit fix` aplicou atualizações compatíveis: ainda há 30 ocorrências
  (10 baixas, 15 moderadas, 5 altas), incluindo ferramentas Amazon sem correção
  indicada. Não executado `--force`, que sugeria rebaixamentos incompatíveis.
- Nenhum VPKG validado, nenhum teste no simulador e nenhum receptor portado.
  Código Android e TVs da rede preservados; sem nova regressão Android nesta
  entrega restrita a Vega/documentação. A paridade permanece pendente.
- Próximo: reinstalar SDK/VVD com espaço suficiente, repetir doctor e build,
  abrir a prévia no VVD, implementar/verificar foco e controle remoto; só então
  avançar para rede e o módulo nativo com headers reais do SDK.


Atualizado em 4 de novembro de 2026, após alinhamento com Henrique.

## Objetivo autorizado

Levar Vega OS, Fire OS 6+ e Fire OS 5 ao mesmo nível de recursos e experiência do Buzin Cast. As duas edições Fire OS já compartilham o código do aplicativo. Vega precisa de um port próprio; a tela de prévia não representa um receptor funcional.

Henrique tem outro computador com macOS, onde o Codex continuará o trabalho. Ainda não há Fire TV com Vega OS disponível. Usar o simulador para avançar e registrar separadamente o que exige aparelho físico. Fazer commit ao terminar cada entrega verificável.

## Estado anterior à integração no Mac

- Fire OS 6+: `app`, versão `0.6.0`, código `22`, mínimo API 25.
- Fire OS 5: mesmo módulo e fontes, com `-PbuzinLegacy=true`, versão `0.6.0-compat`, código `21`, mínimo API 22. Há dependências compatíveis e caminhos específicos para o sistema antigo, inclusive áudio. Não duplicar o aplicativo em uma terceira implementação Android.
- Vega OS: A antiga `vega/App.tsx` (agora `vega/src/App.tsx`) apenas exibe a marca e a mensagem de prévia. Não há projeto completo gerado pelo SDK, manifesto Vega, módulo nativo, descoberta, receptor ou pipeline de áudio/vídeo nessa pasta. Não há VPKG compilado ou validado registrado.
- Identidade do aplicativo: `com.buzincast.tv`. Preservar a logo aprovada, verificada por `tools/verify_brand.py` e `docs/approved-brand.json`.
- Código Android de referência: `app/src/main/kotlin`, `app/src/main/cpp`, `receiver-core` e `dlna-domain`. Os dois últimos são Kotlin/JVM; a ausência de APIs Android nesses módulos não os torna diretamente executáveis no Vega.
- Os commits `4883a29` e `2d0b94c` contêm a rodada de regressões, a prévia Vega e a apresentação pública do projeto. Conferir o estado atual do Git antes de trabalhar; não depender da informação antiga de que esses commits estavam sem push.

Nesta entrega de preparação, não foram alterados o aplicativo, as dependências ou os motores de recepção. A paridade continua pendente.

## Preparar o ambiente no Mac

1. Sincronizar este repositório e seus submódulos. Em um clone novo:

   ```sh
   git clone --recurse-submodules https://github.com/HenriqueBuzin/buzin-cast.git
   cd buzin-cast
   git submodule update --init --recursive
   ```

2. Ler as instruções vigentes de [instalação do SDK Vega](https://developer.amazon.com/docs/vega/2.0/install-vega-sdk), instalar as ferramentas no Mac e confirmar `vega --version`. Registrar a versão efetivamente instalada e a arquitetura do Mac. O ambiente Windows anterior só tem WSL; não foi usado para validar uma build Vega.
3. Usar os templates oficiais descritos em [Build Your App](https://developer.amazon.com/docs/vega/2.0/build-an-app). A documentação consultada oferece `helloWorld` com React Native 0.83 e `basic-turbo-module`/`idl-turbo-module` para integração nativa. Conferir os templates presentes no SDK instalado antes de gerar arquivos.
4. Gerar inicialmente em uma pasta nova, sem sobrescrever este repositório. Integrar ao diretório `vega/` os arquivos necessários para que o port possa ser reconstruído a partir do Git, incluindo manifesto, configuração e arquivo de lock. Não versionar SDK, credenciais, caches, `node_modules` ou pacotes de build.
5. Compilar e abrir a prévia no Vega Virtual Device antes de acrescentar o receptor. Preservar a arte e o ID do aplicativo. Verificar a versão mínima do Vega e a categoria do launcher no manifesto oficial gerado.

O [guia nativo Vega](https://developer.amazon.com/docs/vega-native/2.0/vega-overview) descreve a integração C++ com Turbo Modules e interfaces de áudio, mídia, gráficos e conectividade. Implementar com os headers e exemplos do SDK instalado, sem inventar APIs ou presumir que JNI, MediaCodec, Oboe ou Android NSD estejam disponíveis no Vega.

## Ordem de implementação

1. **Projeto reproduzível:** prévia compilada, navegação pelo controle, launcher e testes de interface no simulador. Guardar os comandos, versões e resultados.
2. **Rede e descoberta:** verificar as APIs e permissões reais para TCP/UDP e mDNS, incluindo conexões recebidas, multicast, ciclo de vida e pacote de distribuição. Criar um teste reproduzível antes de anunciar o receptor na rede.
3. **AirPlay com imagem e áudio:** reaproveitar o núcleo UxPlay e as correções locais quando possível; separar protocolo de JNI, DNS-SD e renderização Android. Integrar decodificação, saída de áudio, vídeo e relógio do Vega. Não publicar serviços que anunciem codecs ou funções ainda indisponíveis.
4. **Comportamento do aplicativo:** nome persistido, perfis Padrão/Cinema/Apresentação/Jogos, aprovação de conexões, estados, foco, configurações, diagnóstico e recuperação. Usar as regras e cenários Android como referência e verificar os limites específicos do Vega.
5. **Demais recursos:** portar e testar mídia por URL/HLS, DLNA, FCast v2, experimento Cast V2, legendas, fotos DLNA, gravação/biblioteca e retransmissão Buzin. Preservar o caráter experimental dos protocolos existentes. Para cada recurso, registrar implementado, teste executado e validação física pendente.
6. **Regressão das três edições:** executar build, lint e testes apropriados nas duas variantes Fire OS depois de mudanças compartilhadas. Adicionar as verificações Vega que forem reproduzíveis ao CI. Nunca compilar as duas variantes Android simultaneamente no mesmo checkout.

O inventário funcional detalhado está em [FEATURES.md](FEATURES.md). O atalho Miracast depende do sistema: investigar a existência de uma API equivalente no Vega antes de oferecer o botão. Não prometer compatibilidade Google certificada, DRM, 4K ou desempenho com base apenas em testes sintéticos.

## Critérios para dizer que chegaram ao mesmo ponto

- Cada recurso da edição Fire OS tem uma implementação Vega verificada ou uma limitação explícita, aceita e documentada. Uma tela semelhante, stubs ou número de versão igual não comprovam paridade.
- As duas variantes Fire OS continuam compilando e preservam os comportamentos testados. Usar [TESTING.md](TESTING.md) para os comandos e as diferenças entre testes de núcleo, instrumentação, interface e cobertura.
- O VPKG é gerado de forma reproduzível e o app abre no simulador, com controle remoto e configurações persistidas. Registrar a arquitetura testada; uma build do simulador não comprova a build ARM de distribuição.
- A recepção real com iPhone/Mac, sincronismo, sessões longas, reconexão, descoberta na rede e comportamento em segundo plano precisam ser validados em Fire TV Vega físico antes de afirmar equivalência validada ou prontidão para a loja. Atualmente esse aparelho não está disponível.
- A meta de 100% de cobertura do código próprio permanece. Não reduzir a meta, excluir arquivos por baixa cobertura ou confundir o gate parcial do núcleo com cobertura global.

## Registros e cuidados na retomada

- Atualizar este arquivo e [WORK_CHECKPOINT.md](WORK_CHECKPOINT.md) com cada avanço, comandos executados, resultados e bloqueios concretos.
- Manter o README público focado em explicar o produto e o estado real de compatibilidade.
- Os relatórios antigos em `C:\Users\henri\Documents\Codex\2026-10-31\va\outputs` e o build WSL em `/var/tmp/buzin-cast-build` são locais do Windows; não estarão no clone do Mac. Não transcrever métricas sem conferir os relatórios. Reexecutar ou obter os arquivos necessários.
- Não instalar builds, reiniciar ou limpar dados das TVs da rede durante a preparação no Mac. Os três Fire Sticks anteriormente usados rodam Fire OS, não Vega.
- Preservar licenças, créditos, patches UxPlay e avisos de terceiros. Registrar novas dependências e suas obrigações.

## Prompt para o Codex do Mac

> Continue o Buzin Cast neste repositório. Leia `docs/VEGA_HANDOFF.md`, `docs/WORK_CHECKPOINT.md`, `docs/TESTING.md`, `docs/FEATURES.md` e `vega/README.md`. Quero levar Vega OS ao mesmo nível das duas edições Fire OS, preservando os recursos, a logo aprovada e o código Android compartilhado. Este é o Mac de desenvolvimento; ainda não tenho Fire TV com Vega OS. Prepare o SDK oficial, gere e integre um projeto Vega reproduzível, compile e teste no simulador, e avance no port dos receptores com as APIs reais do SDK. Registre o que foi implementado, testado e o que ainda depende de hardware. Não declare paridade completa com base só no simulador. Atualize o ponto de retomada e faça commit ao terminar cada entrega verificável.
