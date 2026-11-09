# Retomada — 5 de novembro de 2026

**9/nov — Vega:** ambiente preparado neste Mac e alvo ampliado para Vega OS 1.0
a 2.0. Ver [VEGA_HANDOFF.md](VEGA_HANDOFF.md) e
[VEGA_OS_VERSIONS.md](VEGA_OS_VERSIONS.md).

## Tarefas futuras registradas por Henrique

- [~] Junto da ideia de gravações, avaliar a integração de DRM da Amazon
  Appstore. Não ativar DRM apenas para suprimir o aviso: definir primeiro o
  conteúdo protegido, requisitos de licença e impacto nas duas edições Fire OS.
  **9/nov:** SDK Appstore (`com.amazon.device:amazon-appstore-sdk:3.0.9`)
  integrado com verificação de licença ativa (`AppstoreBilling.kt`) e um SKU
  de doação placeholder, nenhum produto real à venda ainda. O "Yes" de DRM na
  tela de upload do console é independente disso e continua pendente, é ação
  manual de Henrique.
- [x] Traduzir e revisar a ficha da Amazon Appstore para todos os idiomas que
  a loja disponibilizar antes de ampliar a distribuição internacional.
  **9/nov:** feito para os 13 idiomas de ficha de loja que a Amazon realmente
  suporta (Dutch, English AU/IN/US, French, German, Italian, Japanese,
  Polish, Portuguese BR/PT, Spanish, Swedish) — confirmado na documentação
  oficial que esse é um conjunto separado e menor do que os 20 idiomas do
  binário. As strings do app (`values-*/strings.xml`) foram traduzidas para
  os 20 idiomas da lista de "binary supported languages" do console.
- [~] Preparar edições específicas para Alemanha e Áustria sem funções de
  gravação, cópia, download ou armazenamento de vídeo/áudio. Revisar a
  política e validar os binários antes da distribuição.
  **9/nov:** flag de build `-PbuzinNoDownload=true` criada, esconde o botão
  de download via `BuildConfig.VIDEO_DOWNLOAD_ENABLED`. APK de release gerado
  e assinado com sucesso. **Decisão de Henrique:** sem confirmação de
  restrição legal real na Alemanha/Áustria (pesquisa não achou base jurídica,
  e o console já distribui para todos os países), a versão padrão (com
  download) é publicada para todos os mercados, incluindo DE/AT. O APK
  sem-download fica pronto e guardado, não publicado, para o caso de a
  restrição se confirmar depois. **Lembrete de Henrique:** o version code da
  edição sem-download precisa ser diferente do da edição padrão e da compat
  (a Amazon não aceita APKs duplicados na mesma submissão) — corrigido para
  33 (padrão=32, compat=31, sem-download=33) em `app/build.gradle.kts`.
- [~] Publicar somente para Fire TV Stick, contemplando todas as gerações
  já lançadas e as futuras. Cobertura inicial a não esquecer: **Fire OS 1, 2,
  3, 4, 5, 6, 7, 8, 14 e 16; Vega OS 1.0, 1.1, 1.2 e 2.0**. A lista não
  limita o escopo: acrescentar outras versões que existam ao retomar o trabalho.
  Confirmar os nomes comerciais, níveis reais de API e compatibilidade antes
  de cada disponibilização. Vega continua fora desta publicação enquanto seu
  receptor e o VPKG não forem validados.
  **9/nov — decisão:** Fire OS 1, 2, 3 e 4 ficam de fora, sem plano de
  suportar — ver [FEATURES.md](FEATURES.md#limites-da-promessa-de-compatibilidade)
  para o motivo técnico (Jetpack Compose exige API 21+, abaixo disso exigiria
  reescrever toda a UI sem Compose, e nem a Amazon oferece mais emulador para
  validar). Fire OS 5 (API 22) é o piso mínimo de suporte. Fire OS 6, 7, 8, 14
  e 16 continuam cobertos pelas duas edições existentes.
  **9/nov — dispositivos-alvo no console:** conferido em Target your app >
  Supported devices que a categoria "Fire TV" (100 aparelhos) inclui Smart TVs
  com Fire TV embutido (ex.: TCL) e caixas Fire TV (Gen 1/2/3) além dos Fire TV
  Stick propriamente ditos. Decisão de Henrique: manter todos os tipos de
  dispositivo Fire TV habilitados, não restringir só a Stick. Amazon Vega TV
  (Fire TV Stick 4K Select, HD 2ª geração, 4K) segue desmarcado, conforme a
  decisão de deixar Vega fora por enquanto. Availability também já está em
  "All countries and regions where Amazon sells apps" — sem exclusão de país.
- [x] Build nativo (C++/FFmpeg) diretamente no Windows, fora do WSL, ainda não
  funciona — trava no CMake/ndk-build do jeito que o toolchain Windows está
  hoje. **9/nov:** o build via WSL (`/var/tmp/buzin-cast-build`) está validado
  e funcionando (causa raiz de falhas anteriores era CRLF nos fontes do
  FFmpeg/libplist nessa cópia, não o shell). **9/nov (resolvido):** o bloqueio
  final do build nativo Windows (`ninja: error: failed recompaction:
  Permission denied` no passo de install do OpenSSL) era um comando explícito
  de "force CMake-reload" que o próprio `BuildOpenSSL.cmake` upstream roda
  depois do `install_sw`; no Windows essa chamada aninhada de `cmake -G Ninja`
  colide com o processo ninja externo que já está com `build.ninja` aberto (no
  WSL/Linux nunca deu problema porque sobrescrever um arquivo aberto funciona
  sem erro). Corrigido pulando esse comando no build Android (patch
  `0004-skip-forced-reload-for-android-cross-build.patch`, já que os targets
  IMPORTED não dependem desse reload). Validado de ponta a ponta nos dois
  ambientes: `BUILD SUCCESSFUL` tanto no build nativo Windows quanto no WSL
  (reconfirmado após o patch). Comitado em `095ad40`.

## Candidatas corrigidas 0.6.1 — estado atual

- Fontes: `61daec22d9b7288ede4b66c74556b68387cdeb39`. Corrigido encerramento
  das threads de socket FCast antes de reutilizar a porta. Teste novo falhou
  antes e passou depois da correção: cinco execuções de 10 testes na API 36
  e três na API 22, incluindo cinco ciclos de reabertura por execução.
- Principal 0.6.1/código 24: SHA256
  `8c7c627901c1fd7dd6c15b7fc46913c8469a888ea88da176fc426c089017d1e2`.
- Compatível 0.6.1-compat/código 23: SHA256
  `78a893c882891717876391a1adb76ed5da47ad36b5ffc475c141f3d0476931aa`.
- Mesma chave de produção. Assinaturas verificadas, três ABIs, min SDK
  25/22 e target 37, sem depuração ou instrumentação de cobertura. Build e
  lint release passaram nas duas: zero erros, 90/99 avisos respectivamente.
- APKs assinados conferidos em AVDs API 36/22: launcher, configurações por
  controle, retomada e OPTIONS nativo AirPlay 200. As limitações de emissor
  Apple real, áudio/sincronismo e Fire OS 5 físico continuam explícitas.
- Artefatos e fontes correspondentes em
  `C:\Users\henri\Documents\Buzin Cast\releases\0.6.1`. Arquivo completo de
  fontes SHA256 `edf28e24dd1f3a30c1c11e8649d028307c1a416082e22540cb28d3ad7eade38c`.
- Rascunho Amazon já recebeu os dois APKs novos pelo comando Replace,
  preservando 92/5 modelos Fire TV, tablets/automotivos desmarcados e DRM
  desativado. Descrições PT-BR/EN-US, imagens, classificação e privacidade
  preparados anteriormente. Henrique enviou a versão e o console confirmou
  **Under Review**, com distribuição em todas as regiões e inglês dos EUA
  marcado como completo.
- CI final: [42088008733](https://github.com/HenriqueBuzin/buzin-cast/actions/runs/42088008733).
  Build e todas as 12 APIs passaram.
  Execuções anteriores redundantes `42083009064` e `42087765602` canceladas;
  a primeira já havia confirmado a correção de foco na API 28.
- 0.6.1 instalada nas três TVs preservando os dados e sem backups. As três
  responderam a OPTIONS com RTSP 200. Capturas da versão feitas no quarto.
- Três ícones da ficha padronizados com fundo azul, órbitas e marca maior
  centralizada. Os novos arquivos estão preparados para a próxima edição
  da ficha, que está bloqueada durante a análise.

## Loja e instalação física — atualização de 5 de novembro de 2026

- Henrique autorizou preencher a página, investigar o CI e enviar as duas
  edições Fire OS à revisão quando estiverem prontas. Vega fica fora.
- Reinstalação limpa da candidata principal assinada autorizada e concluída
  nos três Fire TV Stick AFTMM da casa (API 25/Fire OS 6). Todos iniciaram a
  versão 0.6.0/código 22; o receptor nativo respondeu `RTSP/1.0 200 OK` a
  OPTIONS nos três. Não houve nova sessão com emissor Apple, medição de áudio
  audível/sincronismo ou teste físico de Fire OS 5 nesta validação.
- A pedido de Henrique, não criar mais backups dos dispositivos. O backup
  local do quarto foi removido da pasta e enviado à Lixeira do Windows.
- Política pública bilíngue em `docs/PRIVACY.md`; URL GitHub conferida com
  HTTP 200. Textos PT-BR/EN-US, arte aprovada e três capturas reais do APK
  de produção na TV estão em `store/amazon`. Capturas em RGB 24 bits,
  1920×1080, sem edição visual. Nenhum backup ou segredo foi publicado.
- Rascunho Amazon: gratuito, todos os países disponíveis, todas as faixas
  etárias, classificação completa, declaração de privacidade salva para
  revisão. Declarados identificadores de dispositivos, fotos, vídeos,
  áudio, diagnósticos e arquivos usados localmente; sem transferência a
  terceiros. Finalidade funcional; diagnósticos também para análise de
  desempenho. Sem conta, anúncios, localização ou publicidade direcionada.
- Descrições e imagens obrigatórias aparecem completas na revisão. O
  console exigiu também a galeria geral, preenchida com as mesmas três
  capturas reais da TV; dispositivos tablet continuam desmarcados.
- Ainda **não enviado**. A tela final exige certificação de conformidade
  de importação/exportação, incluindo criptografia. Obter confirmação de
  Henrique no momento dessa declaração antes de marcá-la e submeter.
- CI anterior diagnosticado: CMake recebeu 403 da API GitHub; API 37 exige
  plataforma/imagem `37.0`; rotação/perfil de telefone alterava a navegação;
  aviso inicial de modo imersivo cobria o app nas APIs recentes. Corrigidos
  ambiente autenticado do instalador, imagem 37.0, perfil TV e preparação
  do emulador, sem remover asserções. Acrescentados diagnósticos à falha
  FCast observada na API 25; causa ainda em investigação.
- Execução de verificação atual: [42074548928](https://github.com/HenriqueBuzin/buzin-cast/actions/runs/42074548928),
  commit `6cb3d0b`. Canceladas duas execuções anteriores redundantes ainda
  na fila. Não afirmar CI verde antes de conferir a matriz completa.
- Nessa execução, build/lint das duas edições e matriz API 22, 25, 29, 30 e
  31 passaram. API 28 passou integração Android (19 testes), nativa (10)
  e cancelamento/retorno de foco, mas falhou no foco do formulário relay.
  Ajustado somente o teste para aguardar o foco inicial e as transições
  assíncronas, preservando uma tecla por movimento e todas as verificações.
  Conferência local: cinco repetições do formulário na API 36 com o teste
  anterior e três na API 28 com as esperas novas passaram. APK de debug
  recompilado do código atual, em AVDs isolados; nenhuma TV usada nesses
  testes. Demais APIs e nova execução da correção ainda pendentes.
- A execução `42074548928` também passou APIs 32, 33, 34 e 35. A API 36
  revelou pelo diagnóstico novo `EADDRINUSE` no início do FCast. Reproduzido
  localmente ao fechar um receptor com cliente conectado e imediatamente
  abrir outro na mesma porta: o descritor nativo ainda pertencia à thread
  bloqueada em accept/read. Corrigido o encerramento para fechar os sockets
  e aguardar essas threads, com limite de espera e diagnóstico de timeout.
- A integração agora usa portas atribuídas pelo sistema, anunciando a porta
  efetivamente vinculada no mDNS. A porta padrão de produção continua 46899.
  Novo teste verifica cinco ciclos de fechamento/reabertura na mesma porta
  enquanto há cliente conectado. Falhou antes da correção e passou depois:
  cinco execuções locais da classe na API 36, 10 testes por execução.
  Essa mudança requer reconstruir e reassinar os dois APKs antes de trocar
  os arquivos da Amazon e atualizar novamente as três TVs.
- A matriz de CI passa a executar até seis APIs em paralelo, em runners
  independentes, preservando todas as 12 versões e verificações existentes.

## Candidatas de produção e rascunho Amazon — 5 de novembro de 2026

Henrique autorizou preparar os pacotes de produção e usar somente as duas
edições Fire OS nesta publicação. Vega ainda está em desenvolvimento e não
foi anexado à loja. O código Android permanece nativo, sem alterações.

- Fontes dos APKs: commit `91b5a3676d7627b1ba5c713306f7bf5f49c9d5ee`.
  A cópia de build WSL foi sincronizada por `git archive`, preservando os
  caches. Submódulos e patches UxPlay conferidos contra as fontes do Git;
  diferenças na libplist eram apenas finais de linha.
- Principal: `buzin-cast-0.6.0.apk`, código 22, mínimo API 25, 24.014.277 bytes.
  SHA256 `cf64c91e5fec1d8b41ce2e17c111554df70b3f289b2dab22ac593bba9365782e`.
- Compatível: `buzin-cast-0.6.0-compat.apk`, código 21, mínimo API 22,
  11.446.897 bytes. SHA256
  `c43140ea2507ff41627b8c893fbe308fc9215efbabb38912121e745a1f60a2f2`.
- Ambos: `com.buzincast.tv`, target API 37, ARM32/ARM64/x86_64,
  mesma chave RSA-4096 de produção. Assinatura v2 validada nos dois e v1
  também na edição compatível. APKs íntegros, sem flag debuggable, componentes
  exclusivos de debug ou instrumentação JaCoCo/LLVM. Arte aprovada preservada.
- Chave de produção criada fora do repositório, em uma pasta de assinatura
  com ACL restrita ao usuário Windows e SYSTEM. O mantenedor foi orientado a
  guardar backup do PKCS12 e do JSON de credenciais. Não versionar nem anexar
  esses arquivos à Amazon. A configuração WSL está em `local.properties`
  ignorado pelo Git e com permissões 600.
- Builds `assembleRelease` e `lintRelease` passaram sequencialmente nas duas
  edições. Lint: zero erros, 90 avisos na principal e 99 na compatível. Os
  avisos continuam pendentes de revisão; não afirmar lint sem avisos.
- Reexecutados 24 testes de `receiver-core` e 145 de `dlna-domain`, sem
  falhas, erros ou testes ignorados; gate de cobertura do núcleo passou.
  Não foi reexecutada a coleta de cobertura global.
- APKs assinados instalados em AVDs novos API 36 e 22: entrada pelo alias
  do launcher, configurações por teclas de controle e retomada após Home
  passaram. O endpoint nativo AirPlay respondeu `RTSP/1.0 200 OK` a OPTIONS
  nos dois. Usadas imagens de sistema de telefone com perfil de hardware TV;
  isso não certifica o layout/launcher Amazon, recepção Apple, áudio/vídeo
  sincronizados, sessões longas ou Fire OS físico. Nenhuma TV física foi
  atualizada, reiniciada ou teve dados apagados.
- Arquivos, logs, relatórios, mappings R8, capturas e fonte correspondente
  estão em `C:\Users\henri\Documents\Buzin Cast\releases\0.6.0`.
  O arquivo de fontes inclui submódulos, patches e OpenSSL 3.5.9, sem segredos.
  Os arquivos de assinatura ficam em uma pasta separada, fora desses artefatos.
- Conta empresarial Amazon aprovada e rascunho **Buzin Cast** criado.
  Os dois APKs foram recebidos pelo console, com DRM Amazon desativado.
  Seleção salva apenas para Fire TV: 92 modelos na principal e 5 na compatível;
  tablets e automotivos desmarcados. Os três modelos do catálogo que o console
  marcou como incompatíveis com os APKs permanecem fora desta publicação.
- Compatível selecionada para Fire TV Gen 1, Fire TV Stick Gen 1,
  Fire TV Gen 2, Fire TV Stick Gen 2 e Fire TV Edition Element 4K (2017).
  Esses são filtros do console, não resultados de teste físico nesses modelos.
- A submissão permanece **Incomplete**, sem envio para revisão/publicação.
  Faltam classificação/público, declaração de dados e privacidade, descrição,
  imagens e validação física das candidatas. Antes de instalar em uma TV que
  ainda usa chave de debug, planejar a troca de assinatura sem apagar dados
  ou preferências automaticamente.
- CI mais recente consultado: [execução Android 42051919790](https://github.com/HenriqueBuzin/buzin-cast/actions/runs/42051919790).
  O job de build/lint APK passou; a matriz teve falhas em integração de
  emuladores e um job falhou na instalação de CMake. As causas não foram
  diagnosticadas nesta preparação; resolver antes de tratar a versão como
  pronta para submissão final. Os testes locais acima não substituem essa matriz.


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


## Prioridade atual: paridade das três edições

Henrique pediu alinhar Vega OS às duas edições Fire OS e continuar o port no outro computador com macOS. Não há Fire TV Vega físico disponível. O roteiro, o estado conferido e o prompt para o Codex do Mac estão em [VEGA_HANDOFF.md](VEGA_HANDOFF.md).

Fire OS principal e compatível já compartilham o código; Vega já tem a estrutura do projeto oficial integrada, mas continua com uma tela de prévia, sem receptor integrado ou VPKG validado. Esta atualização prepara a retomada, sem alterar o código do app ou declarar a paridade concluída. O simulador permite avançar no Mac; recepção e sincronismo reais precisam de aparelho Vega.

Os commits `4883a29` e `2d0b94c` já estão no histórico. A suíte documentada em [TESTING.md](TESTING.md) foi ampliada desde o registro abaixo; os números antigos de cobertura e as pendências antigas devem ser tratados como históricos até nova conferência dos relatórios. Os caminhos Windows/WSL abaixo não estarão disponíveis no clone do Mac.

## Registro histórico — 2 de novembro de 2026

As referências abaixo a Vega fora do escopo pertencem ao plano anterior; a prioridade atual está acima.

Henrique pediu salvar o estado devido ao limite do Codex, atualizar os três Fire Sticks e conferir o ícone/cache. Depois continuaremos os testes e a refatoração. Não considerar a meta global de 100% concluída.

## Código e versões

- `9acf328`: núcleo de sessões, adaptadores, logs assíncronos, testes Android/C++, atualização estável/LTS e edição Fire OS 5.
- `8019bae`: quatro diretórios de build, teclado de diálogo estável e áudio via FFmpeg no Android 5.1. A fábrica NDK antiga pode retornar um wrapper com codec interno nulo e causar SIGSEGV.
- Principal: 0.6.0, código 22, mínimo API 25. Compatibilidade: 0.6.0-compat, código 21, mínimo API 22. Mesma identidade `com.buzincast.tv`, código compartilhado.
- Saídas: `app/build`, `app/build-legacy`, `app/build-coverage`, `app/build-legacy-coverage`. Builds no mesmo checkout devem ser sequenciais.
- Versões e fontes verificadas: `docs/DEPENDENCIES.md`. Licença GPLv3 e créditos preservados. Henrique Buzin é o criador/mantenedor das contribuições Buzin Cast.

## Validação realizada

- 23 testes JVM do núcleo e 145 de domínio passaram; lint sem erros nas duas edições (há avisos).
- 17 testes Android e nove testes C++ passaram nos emuladores API 22 e 36.
- Dez cenários de UI/serviço exercitados: navegação normal/avançada, edição/cancelamento repetidos, formulário único, perfis, controles de áudio, recuperação de mídia, smoke/gravação nativa, launcher/retomada e calibração com salvar/cancelar/limites.
- O teste de calibração precisava consultar `dumpsys window` completo em Android recente; o app abria corretamente, mas o filtro antigo da automação não encontrava o foco. Corrigido neste checkpoint.
- APKs normais de ambas as edições foram verificados: ARM32/ARM64/x86_64, ZIP íntegro, sem JaCoCo nem instrumentação nativa. Principal assinado v2; compatível assinado v1 e v2. São APKs de debug para testes, não publicação na loja.
- Cobertura atual da principal, incluindo o cenário adicional aprovado em API 36: Android/JVM 66,6584% linhas e 42,0910% branches; nativo 66,6870% linhas, 44,9383% branches e 12,4224% MC/DC. Demais métricas estão no JSON. A meta de 100% de todo o código próprio permanece pendente.

## Cenário adicional ainda em validação

`--extended` ou `--case 'media remote'` executa controles remotos do player, velocidade, SRT/VTT realmente exibidas, remoção de legendas e bloqueio/desbloqueio. Passou em API 36. Em API 22, pausa/busca/velocidade e exibição SRT/VTT funcionaram, mas a navegação até CC falhou porque o menu de velocidade voltou/continuou aberto. Investigar o tratamento de Voltar/foco antes de promovê-lo à suíte padrão. O código e os resultados foram preservados; não apresentar esse cenário como aprovado em ambos os sistemas.

## Ambiente e artefatos locais

- Repositório Windows: `C:\Users\henri\Documents\GitHub\buzin-cast`.
- Build WSL Ubuntu-20.04: `/var/tmp/buzin-cast-build`.
- Ferramentas WSL: `/var/tmp/buzin-cast-tools`; JDK `jdk-25.0.5+8`, SDK `android-sdk`, Gradle user home `gradle`.
- Logs/artefatos: `C:\Users\henri\Documents\Codex\2026-10-31\va\work` e `...\outputs`.
- APKs atuais: `outputs\buzin-cast-0.6.0.apk` e `outputs\buzin-cast-0.6.0-compat.apk`. Evitar o antigo arquivo `buzin-cast-0.6.0-debug.apk`.
- Principal SHA256: `32aa025b06ddd21b2a7c19fefa335e65de67cb7eebe9dc331ccc79f119457a29`.
- Compatível SHA256: `0ca7d21746c8060a78f2c3200b94e76d9e098e9780358d123c714a308f2e59a4`.
- Resultados recentes em `outputs\buzin-cast-0.6.0-checks`: `stable-isolated-api36`, `stable-calibration-media-api36`, `compat-isolated-smoke-api22`, `compat-isolated-ui-api22`, `compat-media-api22`. A falha de calibração na primeira pasta foi resolvida pela execução direcionada na segunda; preservar os registros anteriores para rastreabilidade.
- Relatório principal mais recente no WSL: `build/coverage-report/summary.json`, gerado de `app/build-coverage/reports/jacoco/fullCoverageReport`. Consolidar também o relatório completo da edição compatível ao retomar.
- Emuladores próprios: `emulator-5580` (API 36) e `emulator-5582` (API 22). Não matar o servidor ADB global; ele compartilha as conexões com as TVs.

## Fire Sticks e próximos passos

Atualização concluída: a edição principal 0.6.0/código 22 foi instalada com sucesso nos três IPs abaixo. O cache da Appstore foi limpo pela opção **Limpar cache**, sem apagar dados; os aparelhos foram reiniciados e reconectados ao ADB. A logo aprovada foi confirmada visualmente nas três listas de apps. Capturas locais: `outputs/cozinha-apps-final-060.png`, `outputs/quarto-apps-final-060.png`, `outputs/sala-apps-final-060.png`. A cozinha exibiu um aviso de controle remoto após reiniciar; ele foi dispensado com OK.

A arte permanece idêntica. `docs/approved-brand.json` e `tools/verify_brand.py` protegem os arquivos e referências do launcher no CI. Só alterar a identidade mediante pedido explícito de Henrique. Não confundir falha de cache do sistema com autorização para redesenhar a logo.

- Cozinha/Samsung: `192.168.0.100:5555`.
- Quarto: `192.168.0.121:5555`.
- Sala: `192.168.0.153:5555`.
- Os três são AFTMM/API 25 e receberam a edição principal. Instalação e conferência do ícone/cache concluídas; retomar o desenvolvimento/testes pendentes depois, conforme solicitado.
- Preservar a logo aprovada: B à esquerda, Buzin acima e CAST abaixo à direita, sem borda externa. O launcher Fire OS pode manter cache após atualizar o APK; antes de limpar dados, verificar logs e tentar apenas cache/reinício do launcher. Não apagar dados/configurações do usuário.
- CI de `8019bae`: execução `41528642737`. A anterior `41524350857` foi cancelada por estar superada. O commit deste checkpoint inicia outra execução; verificar o resultado mais recente no GitHub antes de afirmar aprovação da matriz de 12 APIs.
- Continuar elevando cobertura real de todo o código próprio Android/C++, sem excluir arquivos por baixa cobertura. Ainda faltam caminhos de erro, USB/mDNS, gestos, controles e diversas transições. Emuladores não certificam sincronismo, Wi-Fi, desempenho, 4K ou comportamento de todos os Fire OS físicos. Vega fica fora do escopo.
