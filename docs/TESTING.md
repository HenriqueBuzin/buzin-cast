# Testes automatizados

## Verificação da atualização 0.6.6 — 15/nov/2026

- Principal e compatível de produção compiladas para ARM32/ARM64 com lint
  vital; códigos 42 e 40. ZIPs íntegros e certificado de produção iguais.
- 304 strings suecas e parâmetros de formatação conferidos contra o inglês;
  os 22 idiomas próprios têm todas as chaves traduzíveis do app.
- Interface sueca e 11 conjuntos de localizações da loja capturados no
  Fire TV Stick do quarto (AFTMM/API 25). Capturas regionais 0.6.5 em
  alemão/inglês. Não representam teste físico em todas as gerações.
- `tools/verify_brand.py` passou: 12 recursos do launcher preservados.
- Classes do SDK Amazon preservadas nos DEX e ausência de `capture_locale`
  nos APKs finais conferidas. O mecanismo temporário usado nas capturas foi
  removido antes dos builds finais e não integra o código de produção.
- Licença por instalação oficial/LAT e aprovação da Amazon continuam
  pendentes; não inferir aprovação a partir do sideload local.

A versão 0.6.0 acrescenta testes de comportamento e medição de cobertura. Automação completa dos comandos da suíte não equivale a cobertura de 100% do aplicativo nem a compatibilidade garantida com todos os emissores e aparelhos físicos.

## Plano de compatibilidade dos Fire TV Sticks

### Próxima versão 0.6.7 — trabalho iniciado

- **16/nov — protótipo 0.3 de áudio para API 17:** o mesmo `FfmpegDecoder`
  e `TimelineBuffer` da produção agora podem ser compilados sem os headers e
  as fábricas de `libmediandk`. O app moderno continua usando seu backend
  habitual. A ponte JNI isolada limita o tamanho dos pacotes, acrescenta o
  padding exigido pelo FFmpeg e serializa decodificação, leitura e destruição.
  O autoteste injeta tons próprios ALAC/352, AAC-LC/1024 e AAC-ELD/480 e /512,
  exige PCM não nulo no buffer temporal e o entrega a um AudioTrack real com
  volume zero, conferindo avanço do relógio. Não é ainda áudio de um emissor
  na rede nem sincronização A/V; o receptor persistente segue anunciando
  somente vídeo. AAC-LC/960 ainda não tem fixture. Geração reproduzível em
  `tools/LEGACY_AUDIO_FIXTURES.md`; FDK AAC é ferramenta de host, fora do APK.
  ARMv7/x86, lint e os 16 testes JVM passaram. Dois ciclos completos do
  emulador x86 passaram, incluindo o áudio duas vezes por ciclo; pacote
  removido no fim. Relatório em `outputs/fireos3-audio-checks/report.json`,
  [captura real do emulador](images/fireos3-audio-probe.png). Os ELF das duas
  ABIs dependem somente de `libm`, `liblog`, `libandroid`, `libdl` e `libc`.
  A regressão do app moderno x86_64 também compilou, com lint e a verificação
  do núcleo aprovados após a mudança no header/CMake compartilhado.
- O indicador do menu lateral agora depende também do destino selecionado,
  além do foco. Deve continuar visível ao entrar no conteúdo e atrás do Sobre.
  O estado selecionado também é exposto à acessibilidade.
- Os modificadores de foco compartilhados recebem foco após um toque
  concluído, sem consumir o clique. Gestos cancelados por rolagem não devem
  mover o foco. Não ampliar os dispositivos da ficha antes da validação.
  `canFocus` é mantido no modo touch, conforme as
  [propriedades de foco do Compose](https://developer.android.com/develop/ui/compose/touch-input/focus/change-focus-behavior).
  As seis verificações do script passaram na API 28, incluindo o contorno
  medido em pixels atrás do Sobre. A TV física não foi usada nesta etapa.
- Principal 0.6.7 debug (46) compilada; compatível (44) compilação Kotlin
  conferida. Lint de ambas passou. Os 25 testes do núcleo e seu limite de
  cobertura passaram. A substituição atômica do snapshot de logs no Windows
  agora usa um backend JVM; o Android injeta seu rename compatível com APIs
  antigas, sem usar `java.nio.file` no caminho da aplicação.
- A prova API 17 agora encaminha o callback `video_process` real do UxPlay
  para Java, usando uma referência global de JNI e anexando/desanexando as
  threads nativas. Uma fila limitada a oito quadros/4 MiB separa a recepção
  da decodificação. Codec incompatível e estouro do orçamento são erros
  explícitos; o encerramento para o receptor antes de liberar a fila.
- Teste sintético: receptor RTSP ativo, callback invocado em pthread nativa,
  fila Java, MediaCodec e Surface. Dois ciclos completos e duas reproduções
  por atividade passaram na API 17 x86, com quadro colorido conferido.
  Isso **não** representa uma sessão real de espelhamento, áudio ou paridade
  com o app principal. A prova mantém um pacote de teste isolado, sem APK
  de produção para Fire OS 3.
- **16/nov — protótipo 0.2:** acrescentados descoberta mDNS/DNS-SD com
  JmDNS 3.6.5, identidade local aleatória persistente (não usa o MAC real),
  TXT e chave pública extraídos do mesmo receptor nativo que possui a porta.
  A biblioteca recebe somente a adaptação UTF-8 necessária para API 17; APIs
  Java adicionais usam desugaring/MultiDex. O `NsdServiceInfo.setAttribute`
  da aplicação atual só existe a partir da
  [API 21](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo#setAttribute(java.lang.String,java.lang.String)).
- `Start experimental video receiver` inicia um receptor persistente de vídeo
  H.264 em uma interface IPv4 privada com multicast. Admissão nativa limitada
  à sub-rede selecionada e loopback; IPv6 de LAN ainda não implementado. Áudio,
  fotos, HLS e H.265 não são anunciados. O player Java configura MediaCodec
  a partir do SPS/PPS Annex-B recebido, acompanha mudanças de configuração e
  descarta a sessão anterior após reset. Sem `libmediandk`.
- `Run streaming stack checks` consulta de fato o grupo multicast usando um
  socket UDP separado, confere PTR/SRV/TXT, porta, chave pública e capacidades;
  faz `OPTIONS` e `/info` no receptor RTSP; injeta vídeo sintético pela thread
  nativa na fila/decodificador persistente e exige atualização da Surface.
  Também confere que a porta TCP fecha ao parar. Dois ciclos completos na
  API 17 passaram, incluindo iniciar/parar o modo persistente entre autotestes.
  Os 16 testes unitários e o lint do protótipo passaram; ARMv7 e x86 compilados.
  **Não houve teste com emissor Apple real nem Stick físico com Fire OS 3.**

```powershell
python tools/test_focus_navigation.py --adb <adb> --serial emulator-5558 --apk app/build/outputs/apk/debug/app-debug.apk
python tools/test_fireos3_probe.py --adb <adb> --serial emulator-5560 --apk fireos3-probe/build/outputs/apk/debug/fireos3-probe-debug.apk
```

Os dois scripts recusam aparelhos físicos. O teste API 17 captura pelo console
do emulador quando o `screencap` antigo retorna um framebuffer preto, e rejeita
uma imagem sem o quadro sintético colorido. Use um emulador com área suficiente
para a prévia (por exemplo, 1280×720). Relatórios e prints ficam em `outputs`.
O teste API 17 faz dois ciclos por padrão (`--cycles 1` permite um ciclo curto),
para e desinstala somente o pacote de teste no fim. `--keep-installed` mantém
o APK parado para investigação manual. Nenhum deles publica na Amazon.
O teste do menu também confere a borda em pixels; sem ela não declara sucesso.
Ele suspende a animação decorativa no emulador durante o teste e restaura a
configuração no fim. Se uma instalação anterior tiver assinatura diferente,
`--reset` remove explicitamente apenas o app e seus dados naquele emulador.
As capturas desta fixture são evidência de teste, não imagens novas da ficha.

Evidências selecionadas sem dados de rede doméstica:
[menu atrás do Sobre](images/0.6.7-about-active-menu.png),
[foco após toque e fechamento](images/0.6.7-touch-focus.png) e
[quadro sintético API 17](images/fireos3-callback-probe.png).

O alvo são todas as famílias de Fire OS que rodaram em Sticks físicos.
Manter as mesmas funções por geração dentro de cada variante regional.
Vega OS é desenvolvido em outro ambiente.

1. **Fire OS 3 (API 17, AFTM no firmware original).** A primeira prova
   passou no emulador x86: Android Views com setas e OK, parser nativo
   e um quadro H.264 decodificado pelo MediaCodec Java. A segunda prova
   carregou a amostra H.264 por HTTP e confirmou um quadro visível na
   Surface do emulador API 17. A amostra agora passa por JNI usando
   `video_decode_struct` e a assinatura `onVideoData(byte[], long, boolean)`
   do receptor. O APK de teste inclui ARMv7.
   O protótipo compila o núcleo UxPlay com OpenSSL, sem vincular
   `libmediandk`. O botão de compatibilidade inclui uma prova RTSP
   local: iniciar, enviar `OPTIONS`, conferir os métodos anunciados
   pelo receptor e parar. Dois ciclos passaram no emulador x86 API 17
   com a resposta RTSP 200, incluindo `SETUP` e `RECORD` no cabeçalho
   `Public`. A ponte do callback de vídeo agora também passa pelo receptor
   ativo e uma thread nativa. O protótipo 0.2 agora tem descoberta multicast e
   player persistente Annex-B/MediaCodec, conforme os autotestes acima.
   Próximo marco: áudio compatível e sessão real com emissor; depois ampliar
   os controles e verificar temporização, reconexão e mudança de rede.
   Depois portar as telas, configurações e funções restantes e validar
   com emissores reais em um AFTM com Fire OS 3.
2. **Fire OS 5 (API 22, AFTM atualizado e AFTT).** Validar os dois
   APKs compat já assinados. Testar primeiro no AFTM de 2014, que é o
   modelo mais antigo, e depois no Stick de 2ª geração e no Basic Edition.
   Conferir instalação, abertura a frio, foco, espelhamento, áudio,
   reprodução e recuperação após interrupção da rede.
3. **Fire OS 6, 7 e 8 (APIs 25, 28 e 30 nos Sticks).** Validar os dois
   APKs principais já assinados. Começar com um Stick 4K de 1ª geração
   no 6, um Stick ou Lite no 7 e um Stick 4K de 2ª geração no 8. Repetir
   os testes principais nos demais modelos do catálogo antes de marcar
   a cobertura de cada modelo. Conferir codecs, controle remoto e
   reprodução prolongada.
4. **Paridade e publicação.** Usar [FEATURES.md](FEATURES.md#disponível-no-código)
   como inventário. Verificar funções de rede, mídia, fotos, legendas,
   gravação, download e retransmissão conforme a variante. Em Alemanha e
   Áustria, validar que download e gravação não podem ser acionados.
   No console, conferir elegibilidade dos Sticks por modelo e país,
   distribuição das variantes e entrega ao firmware Fire OS 3. Validar
   instalação e atualização na loja antes de anunciar cobertura.

Projeção de seis APKs, dois por faixa: API 17, API 22 e API 25 ou superior.
As quatro edições existentes da 0.6.4 já têm assinatura de produção. O
protótipo API 17 é um APK de depuração com ID próprio.

### Reproduzir a prova H.264 na API 17

```powershell
.\gradlew.bat -PbuzinFireOs3Probe=true :fireos3-probe:assembleDebug :fireos3-probe:lintDebug
$serial = 'emulator-5560' # Substitua pelo serial do emulador API 17.
adb -s $serial install -r fireos3-probe/build/outputs/apk/debug/fireos3-probe-debug.apk
adb -s $serial shell am start -n io.buzincast.fireos3probe/.ProbeActivity
```

Navegue com as setas, acione **Run compatibility checks** com OK e
confirme **Native UxPlay HTTP parser: passed**, **UxPlay receiver: RTSP OPTIONS passed**
e **H.264 sample: decoded frame**. O teste RTSP usa um cliente no próprio
emulador e encerra o servidor ao terminar.
A amostra sintética foi criada com:

```bash
ffmpeg -f lavfi -i testsrc2=size=64x48:rate=5:duration=1 -c:v libx264 -profile:v baseline -pix_fmt yuv420p -movflags +faststart fireos3-probe/src/main/res/raw/h264_sample.mp4
```

Para a prova de rede, sirva a mesma amostra no host do emulador:

```powershell
python -m http.server 18765 --bind 127.0.0.1 --directory fireos3-probe/src/main/res/raw
```

No protótipo, acione **Render network sample**. O emulador acessa o host
em `10.0.2.2:18765`. Confirme o GET no servidor, o quadro colorido e a
mensagem **Network H.264 sample: frame displayed**. A mensagem depende de
uma atualização da textura da Surface, com tempo limite de dois segundos
após o envio do quadro. A fonte HTTP é uma amostra de teste. O protótipo
limita o tamanho do arquivo temporário e o remove ao terminar. Com o
servidor desligado, o teste exibiu erro de conexão e a prova local
voltou a decodificar na mesma atividade. A sessão de espelhamento do
UxPlay segue pendente.

## Núcleo e protocolos

```bash
./gradlew :receiver-core:check :receiver-core:jacocoTestReport :dlna-domain:test assembleDebug lintDebug
```

`receiver-core` não depende de Android. Os testes verificam exclusão entre sessões, substituição e eventos atrasados, disputa entre threads, encerramento dos adaptadores mesmo após falhas, escrita de logs fora do chamador, rotação, pressão na fila, falhas de disco, negociação dos perfis e orçamento de recuperação por sessão.

O gate do núcleo exige pelo menos 90% de cobertura de linhas e 80% de decisões. A cobertura efetiva é publicada em `receiver-core/build/reports/jacoco/test/html/index.html`; nenhum teste é dispensado para atingir o número. Os 145 testes de domínio (incluindo a regressão SOAP) DLNA, quadros FCast/Cast, gravação e ajuste de imagem continuam em `dlna-domain`.

## Interface e integração no emulador

Em um emulador isolado, com ADB autorizado:

```bash
python3 tools/test_emulator.py --serial emulator-5554 --apk app/build-coverage/outputs/apk/debug/app-debug.apk
```

O script recusa serial físico ou IP. Usa apenas a biblioteca padrão Python. Instala o debug APK, usa a navegação lateral e os controles reais em uma atividade exclusiva de debug, ajusta preferências do emulador, gera áudio silencioso sintético e simula uma falha HTTP. Não conecta nas TVs da rede. No fim, encerra o app no emulador e restaura os overrides de tamanho/densidade.

Os cenários são:

- Percorrer configurações normais e avançadas, inclusive valores personalizados, sem abertura do teclado.
- Abrir edição com OK, cancelar sem salvar e recuperar o foco anterior.
- Preencher o formulário único de outra TV por controle remoto, com navegação sem teclado automático.
- Aplicar os perfis usando capacidades reais informadas pelos codecs do emulador.
- Gravar uma transmissão H.264/AAC sintética, percorrer o caminho HEVC, finalizar o arquivo e verificar sua presença e tamanho.
- Verificar a ausência dos ícones PiP/tela cheia no espelhamento de TV e a saída com Voltar.
- Iniciar o motor nativo, reproduzir após falha de conexão, substituir sessão, ignorar parada/desconexão AirPlay ao reproduzir mídia local e encerrar uma sessão AirPlay por seu próprio comando.

Resultados JSON, capturas e logcat ficam em `build/emulator-checks`. Para repetir um cenário após uma correção, `--case native` seleciona a integração do player. Uma execução completa continua sendo o padrão do CI.

O suporte de teste existe apenas no source set debug e os componentes exportados exigem `android.permission.DUMP`. A build release não contém essas entradas.

## GitHub Actions

O workflow primeiro compila, executa os testes JVM, verifica cobertura e lint. Depois exercita a edição de compatibilidade em API 22 e a edição principal em API 25, 28–37, cobrindo as bases Android das versões Fire OS 6, 7, 8, 14 e 16 e uma versão Android mais recente. Cada job publica os resultados e falha se uma asserção não passar. O motor nativo é compilado para ARM32/ARM64/x86_64; os emuladores executam x86_64. Cada emulador também recebe um APK de testes Android, exercita sockets reais e coleta cobertura da execução.

## Validação física que permanece necessária

Emuladores não reproduzem o launcher Amazon, o recorte HDMI da TV, os decodificadores de cada Fire Stick, condições reais de Wi-Fi ou o comportamento de todos os aplicativos Apple/Google. Sessões longas, sincronia audiovisual, PIN com emissores Apple reais, DLNA/FCast/Cast V2 com emissores independentes e versões de Fire OS continuam precisando de uma matriz física de testes.

Não marcar recursos experimentais como estáveis apenas porque o CI passou. O Cast V2 continua sem identidade certificada Google; Vega OS continua fora desta versão. A refatoração não muda esses limites.

## Integração, smoke, funcional e regressão

A suíte tem 24 testes JVM em `receiver-core`, 145 testes em `dlna-domain`, 19 testes instrumentados Android, dez testes C++ e 11 cenários de interface/serviço. As categorias se complementam:

- Unitários e de contrato: políticas de sessão, ajuste da imagem, perfis, quadros e parsers.
- Integração: listeners FCast/DLNA/Cast V2, TCP/TLS, HTTP/SOAP, NSD, handlers Android, importação por ContentResolver, autorização e armazenamento.
- Funcionais e ponta a ponta: controle remoto, edição/cancelamento, aplicação persistida dos perfis, reprodução após falha HTTP, gravação e saída do espelhamento.

- Smoke: entrada real do launcher e retomada da atividade, inicialização do receptor JNI e do player, fluxo sintético de mídia e finalização da gravação.
- Regressão: eventos antigos de outra sessão, teclado indevido, cabeçalho SOAP com corpo inválido e assinatura TLS no AndroidKeyStore.
- Concorrência e resiliência: disputa por sessão, encerramento durante inicialização, filas limitadas, disco lento/indisponível, aprovação pendente, quadros inválidos e reconexão TCP.
- Análise estática: Android Lint. A variante `sanitize` existente oferece HWASan/UBSan em ARM64; ela precisa de ambiente compatível e não é executada no emulador x86_64 desta suíte.

Os casos `--case profiles --case 'about version'` verificam o destaque de uma única escolha, a persistência após reabrir o app e a versão exibida em Sobre. Padrão restaura apenas os ajustes de vídeo e áudio gerenciados pelos perfis; o teste Android `FeatureIntegrationTest#defaultProfileRestoresOnlyPresetSettingsAndKeepsExistingProfileNames` confere que nome, PIN e margem não são afetados. Para executar apenas esse teste Android, informe o nome qualificado do método em `--test-class` com `--test-apk`.

O teste GENA do DLNA usa sockets para verificar assinatura, envio da notificação inicial, tentativa de outro callback após erro HTTP, renovação e cancelamento. A suíte também verifica que um receptor é fechado uma única vez quando a inicialização falha durante o encerramento, e que a saída PCM estéreo retoma após falta de amostras e recuo do relógio do emissor.

## Cobertura de todo o código de produção

A meta solicitada é 100% do código próprio, incluindo Android e C++. O gate atual do núcleo (90% de linhas/80% de decisões) é apenas o piso de regressão já comprovado; não representa cumprimento dessa meta. Os relatórios completos incluem todos os arquivos de produção dos três módulos. Fixtures de debug, código gerado e bibliotecas externas ficam fora da medição.

```bash
./gradlew -PbuzinCoverage=true -PbuzinAbis=x86_64 :receiver-core:check :dlna-domain:test :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/test_emulator.py --serial emulator-5554 --apk app/build-coverage/outputs/apk/debug/app-debug.apk --test-apk app/build-coverage/outputs/apk/androidTest/debug/app-debug-androidTest.apk --coverage
./gradlew -PbuzinCoverage=true -PbuzinAbis=x86_64 :app:fullCoverageReport
python3 tools/collect_native_coverage.py --profiles build/emulator-checks --output build/coverage-report
```

O build instrumentado é exclusivo de testes. Android/JVM usa JaCoCo e o C++ usa LLVM source coverage, aplicado apenas aos fontes nativos próprios e seus cabeçalhos. Checkpoints são explícitos antes de encerrar o processo; `force-stop` não garante gravação automática desses dados. O relatório usa as classes após a transformação Hilt e antes da instrumentação JaCoCo, correspondentes às executadas no APK.

`build/coverage-report/summary.json` publica instruções, linhas, decisões, complexidade, métodos e classes para Android/JVM; para C++, linhas, decisões, funções, regiões e instanciações. Métricas sem denominador ficam `null`. Não há uma métrica equivalente a instruções de bytecode no LLVM. MC/DC também é habilitada no C++ com o Clang 21 do NDK r30.

Para exigir literalmente a meta em um gate de aceitação, acrescente `--require-100` ao coletor. Ele falha se qualquer métrica aplicável estiver abaixo de 100%. Relatórios ou arquivos nativos ausentes também geram erro. A suíte não exclui arquivos por terem baixa cobertura.

A medição Android segue a [documentação oficial de cobertura](https://developer.android.com/studio/test/coverage-report), e a instrumentação nativa segue o [LLVM Source-based Code Coverage](https://clang.llvm.org/docs/SourceBasedCodeCoverage.html).

## Unidade nativa e execução de compatibilidade

```bash
python3 tools/build_native_tests.py --abi x86_64 --api 22 --output build/native-tests
python3 tools/test_emulator.py --serial emulator-5554 --apk app/build-legacy-coverage/outputs/apk/debug/app-debug.apk --test-apk app/build-legacy-coverage/outputs/apk/androidTest/debug/app-debug-androidTest.apk --native-tests build/native-tests/native_core_tests --coverage --output build/emulator-checks-legacy
./gradlew -PbuzinLegacy=true -PbuzinCoverage=true -PbuzinAbis=x86_64 :app:fullCoverageReport
python3 tools/collect_native_coverage.py --legacy --profiles build/emulator-checks-legacy --output build/coverage-report-legacy
```

O executável C++ é independente do APK e usa C++23, o mesmo NDK, contadores de cobertura atômicos e UBSan em modo trap. Ele roda exclusivamente no emulador, pela suíte que recusa seriais físicos. Verifica ordem e limites do buffer SPSC, concorrência, passagem pela fronteira de 32 bits, jitter, silêncio, atraso, captura, retomada, contadores e redução de atraso sustentado. Também é compilado para ARM32 e ARM64; a execução automatizada atual usa x86_64.

A regressão de contadores mostrou que `size_t` reduzia posições a 32 bits em ARM32. Agora as posições são `uint64_t` em todas as ABIs. O teste simula uma transmissão longa avançando o contador além de 2³² e inclui uma asserção de compilação para ARM32.

O coletor LLVM combina a biblioteca JNI e o executável de unidade, mantendo somente os fontes/cabeçalhos nativos próprios na medição. A edição principal e a de compatibilidade produzem relatórios separados. Seus resultados não representam uma certificação dos aparelhos físicos Amazon.

O smoke RTSP usa uma conexão TCP real e uma solicitação fragmentada, com aprovação/PIN desligados apenas durante o cenário. O teste aciona a parada explícita do receptor, como o botão do app, e verifica que a porta deixou de aceitar conexões.

O mesmo cenário ocupa uma porta para provocar uma falha real de inicialização, encerra o receptor e o inicia novamente em uma porta disponível. O adaptador mantém a propriedade exclusiva do handle JNI também quando a inicialização falha, evitando destruição dupla no encerramento seguinte.

No Android 5.1, bibliotecas com nomes iguais podem colidir com bibliotecas já carregadas pelo sistema. OpenSSL 3.5.9 LTS é ligado estaticamente com símbolos privados no receptor; o APK não depende de um `libcrypto.so` público do Android.

O cenário RTSP também cobre a criação e saída dos pthreads de callbacks. Esses threads recebem um destrutor TLS que executa `DetachCurrentThread` quando o próprio bridge fez a associação à JVM. O destrutor armazena a JavaVM, sem depender do contexto do receptor já encerrado. A implementação segue as [orientações JNI do Android](https://developer.android.com/ndk/guides/jni-tips).

A atividade de TV dos testes mantém a opção de abrir a atividade principal desligada durante o cenário. Isso evita criar outro host com início automático enquanto o teste verifica o encerramento. As configurações padrão de distribuição continuam definidas em `Prefs`.

O cenário do launcher abre a `MainActivity` real, verifica o início automático do receptor, sai para Home e retoma o app. Formulário, perfis e controles de áudio usam foco e OK; coordenadas de toque não são necessárias. Os emuladores têm o teclado virtual habilitado mesmo quando o host anuncia um teclado físico, para verificar a edição e o cancelamento de forma consistente.

A edição/cancelamento é repetida três vezes para detectar corridas na abertura do diálogo. O teclado só é solicitado pelo controlador da janela do diálogo, após foco da janela e do campo e a instalação da sessão de entrada. Isso usa [WindowInfo.isWindowFocused](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/WindowInfo).

O cenário de calibração altera a margem com setas, salva com OK, cancela com Voltar, verifica os limites 0–10 e restaura a preferência original do emulador.

No Android 5.1, a fábrica NDK de codecs pode retornar um wrapper cujo codec interno é nulo. A implementação [AOSP 5.1](https://android.googlesource.com/platform/frameworks/av/+/android-5.1.1_r1/media/ndk/NdkMediaCodec.cpp) não verifica esse resultado antes de devolver o wrapper. A configuração do bridge força os decodificadores FFmpeg embarcados nesse sistema, evitando a falha nativa; a política vale também para a configuração inicial, antes de chegar uma atualização de preferências. A suíte mantém o smoke AAC real para detectar regressões.

## Vega: fontes e integração no VVD

Em `vega/`, com `nvm use`: `npm ci`, `npm run lint -- --max-warnings 0`,
`npx tsc --noEmit`, `npm test -- --runInBand`, `vega project doctor` e
`npm run build:app`. O SDK oficial deve estar ativo e `/usr/sbin` no PATH
(a compilação nativa usa `sysctl`). Não substituir o runtime interno do SDK.
A configuração TypeScript inclui somente `src/` e `test/`, para não tratar
scripts JavaScript da toolchain gerada como fontes do aplicativo.

O workflow `vega-check.yml` verifica fontes e cópias da marca, sem executar
SDK ou VVD. Para integração real, inicie o VVD e execute na raiz:

```sh
python3 tools/test_vega.py --serial emulator-5554 --vpkg vega/build/aarch64-release/buzincast_aarch64.vpkg
```

A suíte instala/inicia somente no emulador indicado e usa OK, Voltar e setas
para chamar o módulo C++ no sandbox. Confere o arquivo novo de resultados,
TCP/UDP em loopback, bind e adesão multicast; depois repete a navegação e o
resultado. Ela preserva configurações, encerra apenas o Buzin Cast e recusa
seriais físicos/IP. Não anuncia serviços nem valida LAN, mDNS, áudio/vídeo,
layout ou launcher. Em 4/11/2026 passou no VVD ARM64; x86_64/ARMv7 foram
compilados, sem execução registrada. A revisão visual posterior via captura do macOS confirmou tela inicial,
estado e Sobre, após corrigir o corte de Voltar. Captura interna do SDK
continua sem funcionar neste ambiente. Sem equivalência física declarada.

O diagnóstico Vega também testa o parser RTSP do próprio UxPlay com solicitação
fragmentada e entrada malformada, e seu helper AES-128-CTR contra o vetor NIST
SP 800-38A F.5.1. São sete verificações nativas no sandbox ARM64; não constituem
uma sessão AirPlay com áudio/vídeo. Logs da rodada: `build/vega-protocol-checks`.

## Vega OS no Mac — 5 de novembro de 2026

Com SDK/VVD 2.0.13986 instalado e Node da `.nvmrc` pelo nvm:

```sh
cd vega
nvm use
npm ci
npm run lint -- --max-warnings 0
npx tsc --noEmit
npm test -- --runInBand
vega project doctor
npm run build:app
cd ..
python3 tools/test_vega.py --serial emulator-5554 \
  --vpkg vega/build/aarch64-release/buzincast_aarch64.vpkg \
  --output build/vega-media-checks
```

Sete testes Jest cobrem navegação, Voltar, erros do diagnóstico, apresentação
honesta dos codecs, configurações/MIME públicos do SDK e consulta interrompida.
O smoke recusa seriais físicos e verifica sockets, parser RTSP fragmentado,
rejeição de RTSP inválido e vetor AES-CTR dentro do sandbox. Também consulta
os codecs pelo módulo público de mídia, verifica o relatório e repete o
diagnóstico após navegar. Não exige contagens positivas: a API pode devolver
zero por ausência de suporte ou erro de consulta. O VVD ARM64 informou 0/0/1
para H.264/HEVC/AAC. Isso não testa decodificação, saída de som ou recepção.

As três ABIs compilaram; somente ARM64 foi executada. O CI Vega verifica fontes,
tipos, testes Jest e marca sem instalar SDK/VVD. Cobertura global de 100% continua
pendente. Ver o estado atual e os limites em [VEGA_HANDOFF.md](VEGA_HANDOFF.md).

### Reprodução local Vega

O mesmo smoke agora verifica a amostra H.264/AAC incluída no pacote, sem
internet: estado playing com relógio real, pausa com posição estável,
retomada, liberação após Voltar, segunda sessão com ended, Home liberando
recursos e retorno ao app. O relatório nativo de estado é gravado atomicamente;
não registra conteúdo ou URL. Em falha, a suíte coleta logs do próprio app e
encerra somente esse pacote no VVD. Não alcança TVs físicas.

A suíte Jest soma 12 testes, incluindo cancelamento durante initialize,
serialização após deinitialize, falha de inicialização, erro terminal diante
de eventos atrasados e saída no segundo plano. O smoke executado está em
`build/vega-playback-checks/result.json`. A imagem foi revisada no Mac; som
audível, sincronismo, sessões longas e recepção permanecem sem validação física.
O contador de quadros do SDK falhou com código 50001 neste VVD; foi retirado do
diagnóstico em vez de apresentar zero como evidência de vídeo inexistente.
