# Testes automatizados

A versão 0.6.0 acrescenta testes de comportamento e medição de cobertura. Automação completa dos comandos da suíte não equivale a cobertura de 100% do aplicativo nem a compatibilidade garantida com todos os emissores e aparelhos físicos.

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
compilados, sem execução registrada. A captura visual no Mac permanece com
problema gráfico documentado no handoff. Sem equivalência física declarada.
