# Retomada — 19 de novembro de 2026

## Cast V2 experimental no Vega

O menu Estado do port agora abre o receptor experimental de mídia
por URL com TLS e descoberta DNS-SD. No VVD ARM64, passaram MP4,
controles, liberação ao Voltar e a espera de aprovação antes do TLS.
Veja [Retomada Vega](VEGA_HANDOFF.md).

## Aprovação AirPlay no Vega

A preferência de aprovação agora inclui AirPlay por URL, áudio e
espelhamento. O VVD confirmou a espera antes da execução do comando,
recusa, reapertura com nova confirmação e bloqueio de SETUP de
espelhamento sem PIN. Veja [Retomada Vega](VEGA_HANDOFF.md).

## Numeração coordenada dos seis APKs Fire OS

Os candidatos nativos usavam 48/49, acima dos códigos 44–47 que ainda
estavam configurados para compatível/moderno. Isso contrariava a regra
Amazon de código crescente com a API mínima ao enviar múltiplos APKs.
`gradle/fireos-release.properties` agora é compartilhado pelos projetos:
nativo API17 48/49, compatível API22 50/51 e moderno API25 52/53.
O build de produção nativo rejeita versões explícitas fora desse plano.

`tools/Check-FireOsRelease.ps1` gera e copia manifestos de release nas seis
configurações. A execução completa passou em
`outputs/fireos-release-matrix/report.json`: seis identidades reais,
`completeSet=true`, `deviceTargetingVerified=false`. O verificador
`tools/check_fireos_release.py` também aceitou os dois APKs nativos
assinados existentes, cujos códigos e nomes não mudaram, em
`outputs/fireos-release-legacy-check.json`.

Sete controles negativos foram rejeitados: pacote incorreto, depuração,
filtro de API máxima, código antigo, perda de API17, inversão da ordem dos
códigos e conjunto incompleto. Evidência:
`outputs/fireos-release-negative/report.json`. O runner também invalida
um relatório anterior quando falha; esse caminho foi exercitado com um
interpretador inexistente em `outputs/fireos-release-runner-error`.

Os manifestos não são quatro novos APKs compatível/moderno. Reconstruir
essas quatro edições com 50–53 e testar os releases antes de publicar.
O console Amazon exige atribuição manual de modelos por APK: conferir
somente os Fire TV Sticks físicos e os códigos já publicados/LAT. O plano
local não consulta nem modifica a loja. Ver [Release Fire OS](FIREOS_RELEASE.md).
Persistem também os gates de licença, hardware ARM e paridade abaixo.
Nenhuma TV foi usada; as 12 artes aprovadas continuam intactas.

## Aprovação FCast no Vega

A preferência salva para DLNA também ativa a confirmação no FCast.
O VVD confirmou que Recusar fecha o socket, que Permitir libera a
negociação e que vídeo, controles e encerramento continuam funcionando.
Veja [Retomada Vega](VEGA_HANDOFF.md).

## Aprovação DLNA no Vega

A opção **Aprovar conexões DLNA** pede confirmação na TV e guarda
somente a preferência. O VVD confirmou Permitir e Recusar pelo
controle, bloqueio de SOAP e GENA após a recusa e nova confirmação
depois de fechar e reabrir o receptor. Veja [Retomada Vega](VEGA_HANDOFF.md).

## API17 — integração 0019 reconstruída e validada

Os dois candidatos assinados foram reconstruídos após o merge de pareamento
UxPlay 0019. Os dois builds `-Check` e os dois testes de release passaram:
assinatura v1, chave por ficha, abertura R8, RTSP, navegação e Sobre.

- Geral: `30208ca30b87592cd68039944edc89047350791d2a0ae580f4bb4916ef4954ad`.
- DE/AT: `c3706c1f836b5346f5d84eea8aea6a0462e25440d650c454ea330abdd73004cb`.
- Builds: `outputs/legacy-pin19-store-build.log` e
  `outputs/legacy-pin19-deat-store-build.log`.
- Releases: `outputs/legacy-pin19-store-smoke/report.json` e
  `outputs/legacy-pin19-deat-store-smoke/report.json`.

O teste de transporte passou novamente seus 12 cenários em
`outputs/legacy-pin19-transport`. O teste real de PIN/SRP na prévia API17
também passou, com 18 entradas de setup inválidas e 13 pacotes pair-verify
inválidos, seguidos de autenticação válida na mesma conexão. UI, segundo
plano, modo desativado e parada passaram em
`outputs/legacy-pin19-proof-single-peer/report.json` e `instrumentation.txt`.
O novo campo `require_verified_pin` permanece zerado no inicializador do
Android; não foi ativada silenciosamente uma política diferente de acesso.

Duas tentativas iniciais do teste adicional usaram sequências incorretas:
SRP em nova conexão sem pair-pin-start e substituição do cliente RAOP ao
abrir conexões auxiliares antes de retomar a original. As falhas estão
preservadas em `outputs/legacy-pin19-proof` e
`outputs/legacy-pin19-proof-sequenced`; o teste final mantém a mesma conexão
para pacotes inválidos e autenticação válida. Não houve correção do app
motivada por essas falhas do cliente de teste.

Nenhuma TV física foi usada. Permanecem os limites de Appstore/LAT, ARM
físico, códigos/dispositivos e paridade descritos abaixo; os artefatos
continuam `submissionReady=false`. As 12 artes aprovadas foram conferidas.

## Notificações DLNA no Vega

GENA entrega o estado inicial e avisa os emissores de mudanças no
transporte, volume e mudo. O VVD confirmou inscrição, renovação,
expiração e cancelamento. Veja [Retomada Vega](VEGA_HANDOFF.md).

## Fotos DLNA no Vega

O receptor exibe JPEG e PNG, inclusive URI sem extensão com o recurso
DIDL correspondente. O VVD confirmou a carga pelos eventos do componente,
a rejeição de imagem inválida e a troca de vídeo para foto e de volta
para vídeo. Notificações GENA, regras de acesso e validação física
continuam pendentes. Veja [Retomada Vega](VEGA_HANDOFF.md).

## Registro anterior — integração posterior aos testes 0018

O merge incorporou também o patch compartilhado UxPlay 0019 de pareamento.
Os APKs e resultados abaixo foram produzidos antes dessa integração; não
validam o patch 0019. Reconstruir e testar o conjunto antes de qualquer LAT
ou submissão. Os registros e alterações das duas frentes foram preservados.

## API17 — transporte 0018 incorporado nos candidatos assinados

Os APKs foram reconstruídos com o patch UxPlay 0018, após revisar o novo
callback: o inicializador C do Android mantém `mirror_end_on_eof=false`,
preservando reconexão dentro da mesma sessão. O teste nativo novo cobre esse
modo e o encerramento no EOF usado pelo Vega, com sockets reais na API17.

`MirrorTransportTest.cpp` passou 12 cenários de cabeçalho interrompido,
payload interrompido e codec não suportado. Confere reinício do mesmo objeto,
parada idempotente, liberação da porta e nenhuma acumulação de descritores.
O proprietário coleta também a thread que terminou sozinha. O controle
negativo com o C anterior ao patch falhou em `descriptors()==baseline`;
o código corrigido completou todos os cenários com status zero. Evidências:

- `outputs/legacy-eof-native-x86/mirror-transport-result.json` e `run.txt`.
- `outputs/legacy-eof-negative/result.json`; diagnóstico do dispositivo
  identificou a asserção de descritores (exit 139 no Bionic antigo).
- `outputs/legacy-eof-native-arm/mirror-transport-result.json`: compilado,
  não executado, com `passed=null`.

A regressão de criptografia também foi repetida após a extensão do runner: os 66 casos passaram com status zero (`outputs/legacy-eof-crypto/mirror-crypto-result.json`).

Os dois builds `-Check` passaram. Assinatura, chave por ficha, abertura,
RTSP, navegação, seleção persistente e Sobre passaram novamente nos releases.
Os hashes conferem com os arquivos exportados:

- Geral: `b7826490e3fc1b30c1a92c2bb89beee890f1160b17e833ec22cc12fd01c642c0`.
- DE/AT: `74b661d48adb09ab3d7f49957d86bb2aa36fbc67f04a11086b7806d4895c4818`.
- Builds: `outputs/legacy-eof-store-build.log` e
  `outputs/legacy-eof-deat-store-build.log`.
- Execução: `outputs/legacy-eof-store-smoke/report.json` e
  `outputs/legacy-eof-deat-store-smoke/report.json`.

Nenhuma TV física foi usada e os runners removeram suas instalações.
As 12 artes aprovadas permanecem intactas. Os candidatos seguem sem envio
e com `submissionReady=false`: ainda faltam coordenação dos códigos e
dispositivos com as edições moderno/compatível, licença pela Appstore/LAT,
validação ARM/Stick físico e demais itens de paridade. Estes testes de
transporte não constituem validação de reprodução ou negociação FairPlay.

## Receptor DLNA no Vega

O modo DLNA anuncia um MediaRenderer por SSDP e toca mídia HTTP(S) no
player compartilhado. Descoberta, comandos SOAP, reprodução MP4 e
liberação do player passaram no VVD ARM64. Notificações GENA, fotos,
regras de acesso e validação física continuam pendentes.
Veja [Retomada Vega](VEGA_HANDOFF.md).

## Pareamento AirPlay no Vega

O espelhamento exibe um PIN gerado na abertura e exige verificação da
chave antes de aceitar mídia. No VVD, o pacote recusou comandos sem
pareamento e o núcleo ARM64 passou com um cliente PIN/SRP sintético.
Ainda falta validar com um emissor Apple real. Veja [Retomada Vega](VEGA_HANDOFF.md).

## Descoberta AirPlay no Vega

O espelhamento experimental publica AirPlay e RAOP via DNS-SD ao abrir a
tela de teste. Usa a identidade local e a porta RTSP real. O receptor manual
continua disponível quando o anúncio não sobe. O VVD confirmou dois ciclos
de ativação e encerramento e a identidade dos TXT devolvidos por `/info`.
Os três VPKGs passaram na validação de ABI. Falta a descoberta e reprodução
com emissor Apple em aparelho físico. Veja [Retomada Vega](VEGA_HANDOFF.md).

## API17 — CTR validado e guarda de pilha compatível com x86 antigo

Os candidatos foram reconstruídos com o patch compartilhado UxPlay 0017.
O teste `MirrorCryptoTest.cpp`, também usado no Vega, agora pode ser compilado
por `build_native_tests.py --legacy --mirror-crypto` com NDK r23/C++17 e o
OpenSSL do runtime Android. Na API17 x86, passaram 66 combinações de
fragmentos curtos/vazios, saída in-place/separada e troca de chave, comparando
todos os 4097 bytes com a referência. O processo e seu teardown encerraram
com status zero. Evidência: `outputs/legacy-ctr-native-x86-global`.
O teste ARM também compilou em `outputs/legacy-ctr-native-arm`; `passed=null`
registra corretamente que não houve execução ARM.

O primeiro ensaio x86 abortou antes da comparação CTR: o NDK usa TLS slot 5
como canário, mas o Bionic 4.2 ainda permite que pthread keys usem esse slot.
O diagnóstico observou zero antes do OpenSSL e um ponteiro depois, com a
guarda global estável. A reserva aparece no Bionic 4.3. O runtime x86/API17
passou a usar `-mstack-protector-guard=global`, mantendo stack protector forte.
A inspeção de `OPENSSL_init_crypto` confirmou proteção presente e ausência
do acesso à guarda TLS. UBSan por trap permanece ligado no teste.
O prefixo de build separado impede reutilizar o OpenSSL antigo, pois o
wrapper considera uma instalação existente pronta mesmo se CFLAGS mudou.
ARM e o frontend moderno mantêm suas opções anteriores.

Os dois builds completos com `-Check` passaram. Os releases foram verificados
com `test_legacy_store.py`: assinatura v1, chaves por ficha, abertura, RTSP,
navegação, seleção persistente e Sobre. Os hashes testados conferem com os
exports finais; as capturas de referência continuam pixel a pixel iguais.

- Geral: `03d83a8f0c15ad385ed7cd5ff319a89c31a8bf09e96bdfd08fbfef4d814c050f`.
- DE/AT: `4df30ea768ba20162e46a111d0cf8bd5e06ef477880ece9e692bcaab18bc0768`.
- Builds: `outputs/legacy-ctr-global-store-build.log` e
  `outputs/legacy-ctr-global-deat-store-build.log`.
- Releases em execução: `outputs/legacy-ctr-store-smoke/report.json` e
  `outputs/legacy-ctr-deat-store-smoke/report.json`.
- PIN/SRP dentro do app, com cliente criptográfico independente, entradas
  inválidas, UI, anúncio, segundo plano e parada:
  `outputs/legacy-ctr-pin/report.json`, aprovado.

Auditoria pública de arquivos/histórico sem achados e 12 artes preservadas.
Nenhuma TV física foi usada. As instalações de teste foram removidas.
Ainda faltam licença concedida pela Appstore/LAT, execução ARM/Stick físico,
coordenação dos códigos/dispositivos com os APKs moderno/compatível e os
demais itens de paridade. Os candidatos continuam `submissionReady=false`
e não foram enviados; estes ensaios não certificam negociação FairPlay.

A sincronização seguinte trouxe o patch compartilhado UxPlay 0018 de
encerramento/reconexão do transporte, descrito no registro Vega abaixo.
Os artefatos e ensaios desta seção são anteriores a ele. Recompilar e
verificar também esse ciclo na API17 antes de LAT/publicação.

## Reconexão do espelhamento — 19 de novembro de 2026

O worker encerra a saída quando o transporte de vídeo termina e descarta
dimensões, configuração, áudio negociado e timestamps do emissor anterior.
Fechar um socket RTSP auxiliar já não invalida a saída. Quando o consumidor
HTTP desconecta ou expira, a saída é liberada e a sessão aguarda o fim do
transporte antes de aceitar outra mídia.

O patch compartilhado 0018 fecha o socket de mídia corretamente e faz o
dono do transporte aguardar a thread, mesmo quando ela terminou por conta
própria. O Vega pede que o EOF de mídia encerre a thread. A preparação
isolada do UxPlay passou com 18 patches.

O teste com ASan/UBSan verificou recuperação após falha do consumidor e
descarte dos parâmetros anteriores. No VVD, `--mirror-reconnect` validou
dois emissores TCP/AES-CTR sintéticos seguidos, com novo URL, EOF e liberação
de portas e worker. O teste RTSP confirmou que fechar uma conexão auxiliar
preserva a geração, e que Voltar ou background fecham o receptor.
Relatórios em `build/vega-mirror-reconnect` e `build/vega-mirror-auxiliary`.
As três arquiteturas passaram na validação de ABI. Ainda falta testar a
negociação com um emissor Apple e confirmar imagem e som no aparelho.


A regressão do fluxo criptografado em `build/vega-mirror-reconnect-regression`
entregou os 120 quadros no primeiro ciclo. Voltar encerrou o segundo em
46/120 com `clientClosed` e liberou produtor, player e superfície. O
relatório mantém `visualValidation=false`.

## Volume RTSP na sessão de espelhamento — 19 de novembro de 2026

O controle de volume AirPlay aceita de -30 a 0 dB e -144 dB para mute.
A sessão mantém só o último comando pendente e devolve o nível atual
em GET_PARAMETER. O player converte o nível para ganho linear e conserva
o valor na troca de fonte. Reaplica o ganho quando começa a reprodução,
pois o pipeline Vega pode aceitar a chamada inicial antes de estar pronto.

No VVD, `test_vega_airplay_audio.py --video` verifica ganho, mute, rejeição
do nível inválido, leitura RTSP e reinício da sessão sem volume herdado.
O teste de áudio isolado também passou. Os 63 testes Jest cobrem a entrega
do comando e a troca assíncrona de fonte. Lint, TypeScript, build e ABI
das três arquiteturas passaram. A validação em aparelho e emissor real
continua pendente.


Com `test_vega_airplay_url.py --live --volume --media-file ...`, o
player em reprodução registrou ganho linear 0,125893 para -18 dB e
zero para o mute. Voltar liberou o player. O ensaio usa o arquivo
produzido pela conversão ALAC e a sessão por URL que compartilha o
controle de volume com o espelhamento. Resultado em
`build/vega-mirror-volume-player-final`. A observação vem da API do
player. Ainda falta medir o áudio nos alto-falantes.

## ALAC convertido para AAC-LC no espelhamento — 19 de novembro de 2026

A sessão manual volta a aceitar ALAC. A saída de vídeo decodifica os
pacotes de 352 amostras e codifica AAC-LC estéreo a 44,1 kHz antes de
montar o mesmo MP4 que carrega H.264. O codificador retém uma fração de
quadro PCM, descarta o pacote de priming e preserva o início no relógio
NTP. Em uma lacuna, só completa o AAC em andamento com silêncio e
mantém o restante da lacuna. Ao terminar, completa a última fração de
quadro. O muxer limita a espera inicial de vídeo a 32 quadros e 8 MiB.

Os testes portáteis com ASan/UBSan verificaram atraso, lacunas, ordem e
encerramento. No VVD, `--av-mux --audio-profile alac-aac` converteu 1003
pacotes ALAC em 345 pacotes AAC por ciclo. O MP4 preservou 120 quadros
idênticos e a diferença de 80 ms entre as trilhas. O host decodificou
353280 quadros PCM. Relatório em `build/vega-alac-aac-file`.

O patch mantém o ensaio ALAC direto para diagnóstico. Esse caminho ficou
preso em carregamento no player do VVD. A saída do app usa a conversão.
Ainda faltam validação com emissor Apple, áudio audível, imagem exibida
e medição do sincronismo no aparelho.


No ensaio `test_vega_local_video.py --alac-conversion --transport-only`,
o player consumiu a saída HTTP enquanto o worker ainda produzia os
fragmentos. O ciclo completo recebeu 120 quadros e 1003 pacotes ALAC,
com relógio final de 8,09 s. Voltar cancelou o segundo ciclo em 61/120
quadros e 500/1003 pacotes. Player, produtor e superfície foram liberados.
O contador de vídeo permaneceu zero. Relatório em
`build/vega-alac-aac-stream`. Builds das três arquiteturas, lint, TypeScript
e 62 testes Jest passaram.

## AAC-ELD e ALAC no espelhamento Vega — 19 de novembro de 2026

A sessão de espelhamento aceita AAC-LC (960/1024 amostras) e AAC-ELD
(480/512), estéreo a 44,1 kHz. O muxer também gera ALAC de 352 amostras
no probe e usa a mesma configuração do receptor de áudio isolado. O player
do VVD ficou em carregamento com a trilha ALAC, inclusive começando de zero.
Por isso a sessão manual mantém o vídeo ativo sem essa trilha. Troca de perfil
inicia outra saída. O receptor de áudio isolado mantém seu caminho ALAC próprio.

O ensaio `test_vega_msse_video.py --av-mux --audio-profile PERFIL` usa
os callbacks e o muxer compilados para ARM64 no VVD. Cada perfil passou
por dois ciclos, com 120 quadros e o atraso de 80 ms. O demuxer do host
confirmou bytes, timestamps e duração de cada pacote. A decodificação
gerou a contagem PCM esperada para AAC-LC (346 pacotes), AAC-ELD 480
(735), AAC-ELD 512 (690) e ALAC (1003). Relatórios locais em
`build/vega-mirror-audio-{lc,eld480,eld512,alac}`. Os testes portáteis
com ASan/UBSan e os builds das três arquiteturas também passaram.

A verificação do arquivo ainda deixa pendentes a imagem, o áudio audível,
o sincronismo durante reprodução e a negociação com um emissor Apple real.


Com `test_vega_airplay_url.py --media-file ARQUIVO --live`, o player do VVD
carregou os dois fMP4 AAC-ELD e o AAC-LC desde zero. O relógio avançou e Voltar liberou
o player. O ensaio completo de controles mostrou que a busca falha nos
arquivos sem índice. Os contadores do SDK ficaram em zero mesmo com a
superfície associada. O relatório de ALAC ficou em carregamento e não avançou.
Resultados em `build/vega-mirror-start-final-*`, `build/vega-mirror-player-*`
e `build/vega-mirror-start-alac`.

## Áudio AAC-LC na sessão de espelhamento — 19 de novembro de 2026

A sessão AirPlay encaminha os callbacks AAC-LC (codec 4, 960 ou 1024
amostras por pacote) ao mesmo worker de H.264. As duas trilhas usam uma
origem NTP comum. O worker aguarda áudio, IDR e o próximo quadro de vídeo
para calcular a duração inicial antes de anunciar o fMP4 ao player. Calcula
as durações seguintes pelo próximo timestamp, preserva lacunas e descarta
sobreposição de áudio acima da margem de arredondamento de um tick. Filas
de entrada, amostras retidas e amostra em processamento compartilham o limite
existente de 8 MiB e 256 eventos. A espera inicial com amostras retidas tem
prazo de três segundos. Flush invalida as duas trilhas e abre nova época.

Os testes com sanitizadores cobrem início em ambas as ordens, atraso de 80 ms,
cadência variável de vídeo, perda de pacote atrasado, flush e espera sem áudio.
O probe `--av-mux` passou a usar os mesmos helpers de callback e o worker
do app. Em dois ciclos no VVD, o arquivo gerado preservou 120 quadros, 346
pacotes AAC e 80 ms de atraso. O probe escreve em arquivo para conferência
independente no host. A tela manual também passou em dois ciclos de
abertura, Voltar/background e fechamento do socket e worker.
Relatórios em `build/vega-av-worker-final` e `build/vega-av-worker-ui`.

O modo sem AAC-LC negociado continua com vídeo apenas. Faltam AAC-ELD/ALAC
nesta saída, validação com emissor Apple e verificação de imagem, som e
sincronização durante reprodução no aparelho. O ensaio de arquivo mede a
linha do tempo do contêiner. O ensaio da tela mede o ciclo de vida do receptor.

## Linha do tempo comum no MP4 de espelhamento — 19 de novembro de 2026

O muxer nativo e a saída HTTP local aceitam uma trilha opcional AAC-LC estéreo
a 44,1 kHz, com 960 ou 1024 amostras por pacote. A inicialização espera um
pacote de cada trilha. Assim, o muxer preserva o início relativo no MP4.
Também preserva lacunas entre fragmentos e rejeita intervalos sobrepostos.
O modo só de vídeo mantém o cabeçalho imediato.

Os testes com ASan/UBSan cobrem início em ambas as ordens, atraso inicial de
80 ms, lacunas, sobreposição, encerramento e a saída HTTP com ambas as trilhas.
Os três builds e a validação de ABI passaram. No VVD ARM64, o probe
`--av-mux` produziu dois arquivos com 120 quadros H.264 e 346 pacotes AAC-LC
por ciclo. O arquivo recuperado preservou os timestamps e o atraso de 80 ms.
No host, os quadros decodificados e os bytes de áudio coincidiram com as fontes
e o AAC foi decodificado sem erro. Relatório em `build/vega-av-mux-native`.

O worker do app ainda precisa conectar os callbacks de áudio a essa saída
usando a época NTP da mesma sessão. Continuam pendentes AAC-ELD no muxer,
sincronismo durante reprodução no Vega e validação com um emissor real.

## API17 — candidatos de produção assinados e SDK Appstore

`Build-FireOs.ps1 -Target legacy -Release` agora produz os pacotes reais
`com.buzincast.tv` e `com.buzincast.tv.deat`, com versões explicitamente
informadas, R8 e assinatura de produção v1/v2. Reutiliza a configuração local
de assinatura da edição moderna sem incluir credenciais no repositório.
Os candidatos locais desta etapa são 0.6.7-legacy/código 48 e
0.6.7-legacy-nodownload/código 49; revisar códigos e atribuição de dispositivos
junto dos outros APKs antes de enviá-los à loja.

O release de loja inclui SDK Appstore 3.0.9, receivers protegidos DRM/IAP,
o inicializador `AppstoreBilling` compartilhado e a chave pública da ficha.
O SDK declara suporte desde API14. As prévias debug continuam isoladas,
inclusive no build de checagem de produção. O build exige chave pública RSA
válida e recusa PEM privado/inválido e reutilização da chave geral no DE/AT.
O teste negativo real do Gradle confirmou essa última rejeição em
`outputs/legacy-store-wrong-key-check.log`. A ausência de versão/chave regional
e os argumentos de loja em build de prévia também foram rejeitados.

Os dois builds com `-Check` passaram. A prévia normal foi recompilada com
instrumentação e manteve o hash `ca4934cb584e76c073aabef7a25d388d8bb1e59c238add18f181255b60fd81d6`
da etapa anterior. Toque/DPAD durante reinício do receptor passaram em
`outputs/legacy-store-preview-gestures/report.json`.
`test_legacy_store.py` instalou os APKs
reduzidos sem instrumentação em API17 x86 e confirmou assinatura v1, chaves,
ABIs, abertura, resposta RTSP nativa, navegação, seleção do menu e Sobre.
As capturas válidas foram inspecionadas; o `screencap` do guest saiu preto
até no launcher. O fallback usa o framebuffer do console do emulador,
sem redesenhar a interface. Evidências finais:

- `outputs/legacy-store-final-smoke/report.json`.
- `outputs/legacy-store-deat-final-smoke/report.json`.
- Build geral: `outputs/legacy-store-final-build.log`.
- Build DE/AT: `outputs/legacy-store-deat-build.log`.

Os dois APKs usam o mesmo certificado de produção. SHA-256 dos APKs:

- Geral: `44fa72ec14c49d6dd0cf6db8341652485e2a26b5ea23dccc24078cf40e9fa7bd`.
- DE/AT: `140b0c72908f4c1b91a67ee68b903f913cba60166116a2eaac5bd1cd48c20998`.

O SDK executou a verificação e retornou `UNKNOWN_ERROR` neste emulador sem
cliente Appstore. Isso não é uma licença concedida nem certificação de DRM.
Os candidatos continuam com `submissionReady=false`; não foram submetidos.
Faltam validação da licença pela Appstore/LAT, execução ARM/Stick físico,
layouts YUV proprietários e os demais itens de paridade. Nenhuma TV da casa
foi utilizada. As instalações de teste foram removidas pelos runners.
Auditoria pública de arquivos/histórico sem achados e as 12 artes aprovadas
preservadas. As três capturas de referência do release estão em
`docs/images/fireos3-store-release-*.png`, vindas do framebuffer do emulador.

A sincronização seguinte trouxe do Mac o patch compartilhado UxPlay 0017,
descrito abaixo. Os hashes e ensaios desta etapa de SDK são anteriores a esse
patch. Recompilar os candidatos e validar a regressão CTR também na API17
antes de usá-los em LAT ou publicação; eles continuam não prontos para envio.

## Transporte criptografado do vídeo AirPlay — 19 de novembro de 2026

O probe de desenvolvimento agora passa a amostra H.264 pelo receptor TCP de
espelhamento do UxPlay. Usa uma chave de teste pública, sincroniza NTP por
loopback e compara os bytes descriptografados e timestamps antes de enviar
cada quadro ao worker de produção. O player recebe a URL do fMP4 pela tela
AirPlay por URL. O probe permanece fora dos VPKGs.

O teste ASan/UBSan encontrou uma falha quando um fragmento chegava menor que o
saldo do bloco AES anterior. O patch compartilhado 0017 passa cada fragmento
ao mesmo contexto CTR sem preencher ou descartar blocos entre pacotes. O teste
confere partições curtas, chamadas vazias, buffers sobrepostos e troca de chave.
O staging Vega agora aplica 17 patches. O CI roda o teste de regressão.

No VVD ARM64, `build/vega-mirror-ctr-fixed-high/result.json` registra 120
frames com bytes e timestamps verificados no ciclo completo. O player avançou
com 35 frames produzidos. Ao sair com Voltar no segundo ciclo, o fluxo encerrou
em 61/120 e liberou sockets TCP/NTP, produtor, player e superfície. As três
arquiteturas passaram no build e na validação de ABI. Ainda faltam negociação
FairPlay com emissor real, comprovação de imagem exibida e sincronismo A/V.
O relatório mantém `fairPlayNegotiation=false` e `visualValidation=false`.


## API17 — atraso AAC medido e apresentação dos downloads

Concluída a etapa de compensação do atraso identificada no checkpoint abaixo.
`AacEncoderPriming` configura o encoder escolhido e mede a saída com sinal
sintético e correlação, com limites de memória/tempo e cache de oito
configurações em memória. No emulador, o componente Google apresentou 2048
amostras em mono 8 kHz e estéreo 44,1/48 kHz; esse número não é imposto a
outros componentes. A calibração ocorre antes de abrir os codecs da conversão.

O conversor drena o PCM real e acrescenta padding suficiente para o encoder
entregar a cauda antes do EOS. `Mp4AudioPriming` mantém o preroll no arquivo,
recompõe STTS com escala racional e adiciona edit lists padrão para apresentar
somente o conteúdo. Preserva offsets, lacunas reais e o reinício de encoder
entre períodos DASH. A publicação do arquivo só ocorre após finalizar os
metadados; falha deixa a limpeza remover o parcial privado.

A comparação RGB encontrou também uma declaração nclx incompleta escrita
pelo muxer antigo como RGB/valores reservados. O exportador agora omite
somente declarações incompletas e preserva metadados SDR/HDR válidos.
Não há override de cor no verificador externo.

Os quinze downloads passaram no emulador API17 e na decodificação independente
FFmpeg/ffprobe em `outputs/legacy-aac-color-download/report.json`. Incluem
duas conversões AAC em períodos DASH, timestamps com meio segundo de lacuna,
quadros B com áudio convertido e início de áudio deslocado. Os testes
conferem início dentro de 5 ms da origem, cauda, duração, frames de vídeo,
cancelamento/liberação/reinício e reprodução local. O DASH de origem já tem
cerca de 22,5 ms de lacuna entre períodos; a comparação usa os segmentos reais
e limita o silêncio adicional a 10 ms. Os oito testes JVM novos passaram.
A regressão de criptografia também foi repetida após a extensão do runner: os 66 casos passaram com status zero (`outputs/legacy-eof-crypto/mirror-crypto-result.json`).

Os dois builds `-Check` passaram. Download e gravação permaneceram bloqueados
na edição DE/AT, conforme `outputs/legacy-aac-color-deat-download/report.json`
e `outputs/legacy-aac-color-deat-recording/report.json`. Os runners removeram
as instalações de teste ao terminar.

SHA-256 dos APKs debug desta etapa:

- Geral: `ca4934cb584e76c073aabef7a25d388d8bb1e59c238add18f181255b60fd81d6`.
- DE/AT: `8bc7c854162d5fe1b7d5799093a14a6dd562a3d13392f25b5623b96793ae6d4c`.

As 12 artes aprovadas permanecem intactas; auditoria pública de arquivos e
histórico sem achados. Nenhuma TV física foi usada. Os APKs continuam prévias
debug isoladas. Ainda faltam layouts YUV proprietários, produção/licença/
assinatura, validação ARM e com emissores/Sticks antigos reais e outros itens
de paridade: esta etapa não conclui o suporte universal a Fire TV Stick.

## API17 — reamostragem e downmix dos downloads

`LegacyPcmConverter` agora possui o libswresample estaticamente incluído,
habilitado somente quando `BUZIN_RAOP_LEGACY_AUDIO` está ativo. Mantém o
build moderno com sua configuração anterior. Java serializa o handle e
o JNI valida taxas, máscara/permutação, buffers diretos, contagens e limites
antes de acessar/alocar. Entrada: PCM intercalado U8/S16/S24/S32/float,
1–8 canais e 1–384 kHz. Cada chamada aceita até 16.384 quadros e limita a
saída a 262.144 quadros. Packed S24 é expandido para S32 sem alterar escala.

O exportador alimenta o AAC mono/estéreo em uma taxa válida entre 8 e 48 kHz,
com contador de quadros para timestamps, contrapressão e drenagem do filtro
antes do EOS do encoder. Downmix inclui centro/LFE, normaliza o ganho e
usa as posições da máscara de canais. O primeiro teste 5.1 detectou a ordem
Vorbis crua do `OMX.google.vorbis.decoder`: o SoftVorbis/Tremolo AOSP até
API25 entrega essa ordem sem máscara. O adaptador limita a permutação a
esse componente/versões sem máscara; os demais mantêm a ordem declarada.

Passaram os onze downloads, reprodução local, decodificação FFmpeg/ffprobe,
conversão/cancelamento/reinício e os testes JNI de formatos, entradas
inválidas, identidade, relação fracionária, pacotes, oito falantes e filtro.
O teste 96→48 kHz preservou 880/1760 Hz e suprimiu o alias do tom de 30 kHz;
os seis falantes 5.1 foram roteados corretamente, sem saturar a cauda alta.
Evidência geral: `outputs/legacy-pcm-final-download/report.json` e regressão
ALAC/gravação em `outputs/legacy-pcm-final-recording/report.json`.
Os dois builds `-Check` passaram; os gates DE/AT de download/gravação estão
em `outputs/legacy-pcm-final-deat-*/report.json`.

SHA-256 dos APKs debug finais:

- Geral: `9f305564d3884fcb1737bf3023455f0a7b300a4d181cb6b8717ef95b6457210c`.
- DE/AT: `99acc881b4511f3224d2953db5d19960c4322411b471a9aebf98880b351e77a9`.

O próximo ponto concreto é a compensação do atraso inicial do áudio:
`outputs/legacy-pcm-final-timing.json` compara o início do sinal nas duas
origens e nos MP4s e mede cerca de 42,65–42,67 ms acrescentados à conversão
a 48 kHz. Investigar priming de decoder/encoder e metadados/timestamps;
não corrigir usando uma constante arbitrária para todos os componentes.
Também permanecem layouts YUV proprietários, produção/licença/assinatura,
validação com emissores e Sticks antigos reais e demais itens de paridade.
Nenhuma TV física foi usada; os APKs continuam prévias debug isoladas.

## API17 — conversão de download com codecs do sistema

`LegacyUrlDownload` mantém a cópia direta de faixas aceitas pelo muxer e
liga `LegacyDownloadTranscoder` nas outras faixas decodificáveis. O caminho
novo usa buffers MediaCodec API16, sem Surface/AudioTrack; converte para
AVC Baseline/AAC-LC e preserva as faixas que dispensam conversão. Fica no
mesmo worker do exportador, com entrada/saída limitada e contrapressão,
sem carregar o arquivo inteiro nem executar um processo FFmpeg no aparelho.

O teste revelou o aborto do SoftAVC do Android 4.2 quando o buffer calculado
é menor que seu padrão QCIF, além da exigência de dimensões múltiplas de 16.
A entrada agora recebe padding, retirado da imagem visível por recorte SPS
padrão, preservando pixels e proporção. O nível AVC é escolhido entre os
anunciados para comportar blocos/fps/bitrate. Também são tratados CSD em
buffers separados, NALs crus do encoder antigo, PCM maior que a entrada do
AAC e liberação independente de todos os recursos em falhas/cancelamento.

As três amostras novas são padrões sintéticos e tom de 880 Hz: VP8/Vorbis
em WebM, AVC/Vorbis e VP8/AAC em Matroska. O teste no emulador exige nove
exports, reprodução local e decodificação independente com FFmpeg/ffprobe;
as conversões preservam os 120 quadros e dimensões de 160×90, cores e tom.
Cancelamento com quatro codecs ativos, liberação, ausência de arquivo
parcial e reinício no mesmo exportador também passaram em
`outputs/legacy-transcode-cancel-download`. Os nove testes JVM de SPS passaram.
A rodada final `outputs/legacy-transcode-final-download` passou com erro RGB
médio de 0,2649/255 na conversão de vídeo e tom de 879,93 Hz no áudio.
Os dois builds com `-Check` passaram; download e gravação bloqueados na
edição regional passaram em `outputs/legacy-transcode-final-deat-*`.
SHA-256 dos APKs debug finais:

- Geral: `33ead798d140ab7054c067c9108cdc4a1e81ea1e139d4fd8b19cb13ca1f80c87`.
- DE/AT: `1e92ac16e940a7ba927a91714806b7539ac45dc9df2eaa6fe76fbf44ecc5a72d`.

As falhas anteriores e logs estão em `outputs/legacy-transcode-*`.
Auditoria de arquivos/histórico público sem achados e 12 artes aprovadas
preservadas. Todos os APKs de teste foram desinstalados na limpeza.

Ainda falta ampliar a conversão para reamostragem/downmix e layouts YUV
proprietários ou outros limites fora dos codecs disponíveis. O caminho
atual aceita PCM 16-bit mono/estéreo de 8–48 kHz e YUV420 planar/NV12;
falhas não publicam um MP4 parcial. Permanecem produção/licença/assinatura,
validação ARM/real com emissores e Sticks antigos e demais itens de paridade.
Nenhuma TV física foi usada; os APKs continuam prévias debug isoladas.

## API17 — retransmissão integrada ao serviço e à interface

`LegacyRelay` liga o transporte compartilhado a `LegacyRelayPlayback` e ao
tap nativo de AVC/PCM. Ferramentas oferece receber, conectar e parar, com
formulário por controle/toque e bordas de foco. Usa os recursos existentes
nos 22 idiomas; os erros internos ficam nos diagnósticos, com mensagem
traduzida na interface. O formulário reduz sua altura junto com a janela
quando o teclado ocupa espaço, mantendo os botões fora da área rolável.

A saída API17 usa MediaCodec/Surface e AudioTrack com o relógio PCM comum.
Mantém um quadro de preparação antes da primeira Surface, fila AVC de
4 MiB/16 quadros e PCM de 256 KiB/64 pacotes. Ganho/foco acorda o worker
mesmo sem novos pacotes. O serviço estaciona o vídeo nativo antes de ligar
a Surface da retransmissão e só o restaura depois de ambos os workers
encerrarem e liberarem a saída. Cache de binding evita recriar o codec a
cada atualização de estado. A captura de saída combina gravação e envio;
as restrições DE/AT continuam no gravador/download, sem arquivo de relay.

A retransmissão também pode funcionar com o receptor nativo desligado.
O serviço observa a rede enquanto a tela está aberta e tem uma ação própria
de início para manter a sessão sem Activity, preservando o pedido nativo
desligado. Depois do fim da sessão solta a trava de energia e deixa de ser
foreground quando não há outra função ativa. Mudança de rede com o receptor
nativo desligado não deve produzir um erro falso de inicialização nativa.

As integrações comprovaram imagem/PCM, relógio de áudio, foco,
segundo plano/retorno com outra Activity, captura nativa de envio, tomada
por espelhamento nativo e URL, parada local/global, modo independente e
destruição/reabertura de todas as Activities. A sessão pendente usa um
booleano do transporte, exposto somente na cópia gerada API17: mudar o
idioma durante a conexão não encerra o serviço por comparar textos traduzidos.

`--relay-app` passou nas duas edições, incluindo essa troca de idioma:
`outputs/legacy-relay-app-locale-diagnostic/report.json` (geral) e
`outputs/legacy-relay-app-final-deat-relay-app/report.json` (regional).
Os SHA-256 dos APKs debug são, respectivamente,
`96c7f8382187cfa597f47f4b1b7002c33b9ca7b4ac6e6f32515d20d96efdae89` e
`346b80fc269b04fa9277ddc866804b4cecdf3677e5d327307e8d7e4126ab3df0`.
Uma primeira rodada geral falhou esperando o handshake de envio;
o teste ganhou diagnóstico do estado/par e passou na repetição, sem mudar
o APK. Não foi estabelecida a causa dessa falha transitória.
Ferramentas e formulário foram conferidos visualmente. Nenhuma TV física
foi usada; todos os testes desinstalam as prévias ao terminar.

A regressão anterior da mesma integração passou em transporte independente,
URL/URL nativa, gravação, download, PIN, mídia do sistema, DLNA e FCast;
relatórios `outputs/legacy-relay-app-regression-*/report.json` (para PIN,
mídia do sistema, DLNA e FCast usar os diretórios `regression-final-*`).
A primeira tentativa de PIN perdeu o servidor ADB isolado; a repetição
passou após recuperá-lo. Os 18 recursos usados pela interface estão presentes
nos 22 diretórios de idiomas. Build/check passou nas duas variantes;
`audit_public_repo.py --history` não encontrou dados privados e
`verify_brand.py` confirmou as 12 artes aprovadas.
No APK geral final passaram novamente transporte independente, URL/retorno
ao receptor e mídia do sistema (`outputs/legacy-relay-app-final-regression-*`).
No regional final passaram as negativas de download e gravação
(`outputs/legacy-relay-app-final-deat-download` e `-recording`).

Ainda faltam outros formatos/transcodificação de download, produção
(SDK/licença/assinatura), validação com emissores e Sticks reais e demais
itens de paridade. Os testes de relay não comprovam High Profile ou 1080p
físico: a imagem sintética decodificada é AVC Baseline de 160×90.

## API17 — canal e transporte de retransmissão compartilhados

`SecureMirrorChannel` agora aceita a implementação de cifra. O motor JVM
atual foi isolado numa classe própria, mantendo o padrão moderno e o
formato de rede. API17 fornece AES-256-GCM pelo OpenSSL já incluído, com
limites JNI antes de alocação/cópia, verificação de tag e limpeza das cópias
nativas. Não rebaixa protocolo, PBKDF2, sequência, AAD, nonce ou tag.

O build copia `MirrorRelay.kt` da edição moderna, adaptando R, injeção da
cifra e uma fila com limite adicional de 8 MiB. As âncoras exigem revisão
se as linhas adaptadas mudarem. A implementação compartilhada continua
limitando tentativas, prazo de pareamento, timeouts e um par por sessão.

`outputs/legacy-relay-final-relay` passou no emulador API17, com cliente Python
independente de PBKDF2/AES-GCM: recepção e envio com bytes exatos de vídeo/PCM,
keepalive, End, código incorreto, tag, sequência, tamanho, truncamento,
pacote inválido, ocupado e parada durante handshake. Passaram os limites
da cifra, o orçamento de bytes da fila e a ausência de threads restantes.
O teste não depende de duas pontas com o mesmo motor criptográfico.

A compilação geral com `-Check` passou, assim como `dlna-domain:check`,
incluindo o gate de cobertura e o motor JVM padrão. As regressões PIN,
Cast V2/TLS e gravação passaram com os artefatos finais da edição geral.
O teste de PIN revelou uma condição de corrida na própria verificação:
o serviço publica o PIN antes do retorno assíncrono da Activity de segundo
plano. Agora aguarda apresentação, foco e notificação, mantendo as mesmas
exigências e acrescentando `isShown()` antes de publicar o código ao host.

`outputs/legacy-relay-deat-relay` passou pelos mesmos 12 estágios. Os testes
negativos DE/AT de download e gravação também passaram. Os 12 recursos de
marca e a auditoria direcionada de arquivos/histórico público passaram.
Os testes usaram somente o emulador próprio; nenhuma TV foi ocupada.

SHA-256 das prévias conferidas:

- Geral: `6869e2456c15f46a4757bcfb2a4386eb49e6b6c3168c8b510c7d3a67614945db`.
- DE/AT: `8eb40c01eac58cf6ce3bc5ee777d0fc04e271c69f390a61ff44e85e38fb6f3f6`.

Essa etapa NÃO oferece retransmissão no menu: ainda falta integrar o
transporte ao serviço, renderização/sincronismo de vídeo e PCM, arbitragem
de Surface/foco, captura de saída e interface. O próximo passo continua
sendo a função completa, sem limitar a meta ao transporte. Transcodificação,
produção e validação física também permanecem pendentes.

## API17 — HTTPS do player, exportador e fotos

O ensaio público reproduziu uma falha concreta: o HEAD do Sintel/W3C
falha com `unsupported protocol` usando a fábrica padrão do Android 4.2,
mas retorna 200 com TLS 1.2 ativado e validação normal de certificado/domínio.
`LegacyHttps` agora configura apenas conexões próprias antes da API20,
com TLS 1.2 e cifras AES. Preserva as raízes e o verificador de domínio
da plataforma, sem fábrica global, certificado instalado ou trust-all.
Player/exportador usam um adaptador do hook interno da Media3 1.2.1;
o tratamento HTTP/Range/redirecionamento/erro continua no código da biblioteca.
O carregador de fotos aplica a mesma política a cada redirecionamento.

`outputs/legacy-https-general-verified` passou. O teste inclui conexões
públicas com ECDHE/ECDSA e ECDHE/RSA, reprodução Baseline 320×240,
avanço do relógio, pausa/retomada, foto com redirecionamento HTTPS e
exportação MP4 do Sintel. O ffprobe confirmou 1.253 pacotes AVC High e
2.435 AAC-LC; o FFmpeg decodificou integralmente as duas faixas sem erro.
Um fixture isolado exige recusa de identidade não confiável e de domínio
incorreto, mesmo quando a identidade é aceita como âncora apenas no teste.
Fábricas globais/verificador são preservados e não sobram sockets nativos.

O decodificador de software do emulador API17 falhou com o Sintel High
Profile; o extrator Android também não enumerou faixas do MP4 exportado,
embora a inspeção/decodificação independente tenha passado. Não considerar
High Profile validado no Stick. Os arquivos públicos no host ficam em
`outputs`, ignorado pelo Git; o teste limpa os arquivos/app no emulador.

As regressões de download (MP4/HLS/DASH/múltiplos períodos/B-frames,
decodificação independente, biblioteca e cancelamento) e URLs nativas/FCUP
passaram com os APKs atuais. As duas compilações com `-Check` passaram;
as últimas recompilações atualizam apenas o teste e os artefatos de prévia.
A edição DE/AT passou em `outputs/legacy-https-deat-https`: mesmos testes de
TLS, certificados, domínio, playback e foto, sem iniciar exportação. O teste
negativo `outputs/legacy-https-deat-download` confirmou o bloqueio regional.
Os 12 recursos de marca foram preservados; a auditoria direcionada de
arquivos e histórico público não encontrou ocorrências.

SHA-256 das prévias conferidas:

- Geral: `ce835264340a5da9cf9a45d2e65dd9e7fb05ca7f456cdb82780a3ea28a1daad2`.
- DE/AT: `3430f87aa3d1594a35918aa57cb6c08bc51220022ac655ab566c5fb8bf3784c8`.

Permanecem a retransmissão, transcodificação de outros formatos,
licença/SDK/assinatura de produção, emissores e Sticks físicos e demais
itens da paridade. Este ensaio cobre HTTPS público real, mas não todos os
servidores/raízes/cifras, TLS 1.3 exclusivo, DRM ou live.

## API17 — Cast V2 e TLS compatível com clientes modernos

O transporte `CastV2Receiver.kt` é copiado do app moderno. A adaptação
troca apenas a fábrica de identidade/sockets; âncoras e SHA-256 do trecho
original exigem revisão quando a política compartilhada mudar. Há opção
desligada por padrão, ciclo de vida por rede/nome/aprovação e porta dinâmica.
O trabalho de identidade e inicialização ocorre no worker de protocolos.

API17 não tem AndroidKeyStore. Uma identidade própria RSA-2048/SHA256 fica
em arquivo atômico privado, com limites de tamanho, validade e verificação
de correspondência chave/certificado. O certificado é explicitamente não-CA.
O parser X509 antigo trata a sequência vazia de BasicConstraints como CA;
o adaptador valida a codificação DER exata emitida pelo próprio gerador.

O primeiro servidor JSSE passou entre dois endpoints API17, mas o cliente
moderno recusou a conexão. As listas capturadas comprovaram a falta de
cifras atuais em comum. O servidor final usa o OpenSSL já incluído no app,
com TLS 1.2, AES-GCM e compatibilidade ECDHE/AES-CBC com clientes antigos.
Não inclui RC4, DES ou cifras anônimas. Não altera provedores TLS globais.

O adaptador interno usa IDs com registro/refcount nativo. Fechar remove o
ID, chama shutdown para acordar I/O e só libera memória/descritor depois
da última operação. Accept tem espera interrompível e os buffers são
limitados a 64 KiB. Um contador exige zero objetos vivos ao desligar.

`outputs/legacy-castv2-final` passou: descoberta, TLS real, protobuf,
DeviceAuth explicitamente indisponível, reprodução e controles, aprovação,
quadros grandes recusados, persistência e regeneração de identidade,
encerramento durante handshake e ausência de recursos TLS restantes.
O cliente independente negociou TLS 1.2 com AES256-GCM, conferiu o
certificado público/assinatura/não-CA e heartbeat/erro de DeviceAuth.
A inspeção ELF de ARMv7 e x86 confirmou os nove métodos JNI, símbolos
OpenSSL privados e ausência de dependência do SSL/crypto do sistema.

As duas compilações com `-Check` passaram. Na edição geral passaram as
regressões FCast, DLNA, URLs nativas/FCUP, PIN e gravação. Na edição DE/AT,
`outputs/legacy-castv2-deat-castv2` passou pela mesma integração Cast, e os
testes negativos de download e gravação passaram. As execuções afetadas por
desconexão do servidor ADB foram repetidas após reconectar o emulador;
o polling do host foi reduzido, mantendo os ACKs e as verificações.
Os 12 recursos de marca e os textos Cast nos 22 idiomas foram conferidos.
A auditoria direcionada dos arquivos e do histórico público passou.

SHA-256 das prévias conferidas:

- Geral: `887c408c57cae0f6aa32683c62e7ca9a70a1893acc4801d4c28cc9c28559e945`.
- DE/AT: `037234f8b1e84ef601e89c26a5e35e2ff1fb21e96ed816e8aacc6d4a843aba5d`.

São previews debug, sem assinatura/SDK de produção. Os testes foram feitos
no emulador API17, sem usar as TVs. Retransmissão, outros formatos de download,
HTTPS real do player, testes físicos e demais itens da paridade permanecem
pendentes. O Cast mantém as limitações experimentais do app moderno.

## API17 — PIN experimental e validação do pareamento nativo

`ReceiverOptions` repassa `requirePin` ao JNI sem alterar o padrão das
assinaturas antigas. O receptor gera um PIN aleatório por execução com
o CSPRNG existente e falha na inicialização se a geração falhar. Anuncia
o sinal de senha nos dois serviços DNS-SD e entrega o callback nativo à
janela/notificação. Mudar a opção reinicia o receptor. Fechar, parar,
reconfigurar, iniciar mídia ou encerrar a sessão limpa a indicação.

As chaves públicas registradas ficam limitadas a 16 na fila da sessão,
sem arquivo de confiança persistente. Callbacks rejeitam tamanhos e
formatos inválidos; fechar a fila elimina as chaves e ignora retornos
tardios. O texto do log nativo que revelava o PIN foi filtrado.

Os patches compartilhados 0013–0016 validam estado e tamanhos no SRP,
recusam confirmação antes da prova e repetição depois de liberar o estado,
e rejeitam verificadores/chaves de cliente inválidos antes de os usar.
Isso evita acesso a contexto nulo e leitura de campos menores que os
tamanhos copiados. A preparação isolada do núcleo Vega foi atualizada
para o inventário de 16 patches e passou com proteção de edições locais.
O app Vega não foi compilado nem testado neste PC.

As integrações geral e DE/AT passaram com cliente independente: PIN errado,
SRP correto, prova do servidor, tag GCM inválida, nova troca SRP após falha,
confirmação AES-GCM/identidade do servidor, resposta X25519/Ed25519 e 18
entradas malformadas. Janela/foco, notificação, segundo plano, modo
desligado e parada também passaram. As capturas foram conferidas visualmente.
Os relatórios são `outputs/legacy-pin-general/report.json` e
`outputs/legacy-pin-deat-pin/report.json`.

Passaram as duas compilações com `-Check`, os testes JVM e lint. A edição
geral passou novamente por aprovação de conexões, URLs nativas/FCUP e
gravação MKV com validação independente dos pacotes. Na edição DE/AT,
download e gravação continuam bloqueados e passaram pelos testes negativos.
Os 12 recursos de marca foram preservados. A auditoria direcionada de
arquivos/histórico público não encontrou ocorrências.

SHA-256 das prévias conferidas:

- Geral: `696bf00082118d8a293df9c7a451d597cb209ed94fb16456bb313c277ce65200`.
- DE/AT: `5cf849fabb4ce7934741a90d712cf4426072b1db00446ee0f00c2160d5ec6dd2`.

As mudanças compartilhadas de pareamento ainda precisam ser incorporadas
e verificadas nos próximos builds moderno/compat antes de publicá-los.

A implementação continua experimental, como no app moderno. Não comprova
interoperabilidade/retomada de emissores Apple físicos nem autenticação
universal de todos os caminhos do protocolo. Os APKs são previews debug;
licença/SDK, assinatura de produção, Cast V2, retransmissão, outros formatos
de download e testes em Sticks antigos reais continuam pendentes.

## API17 — aprovação de conexões

A prévia compartilha `ConnectionApproval` com o app moderno, sem adaptar
sua regra de autorização: um pedido pendente, 25 segundos para responder,
autorização por protocolo/IP durante cinco minutos e recusa temporária de
30 segundos. O padrão continua desligado. Não substitui o PIN de pareamento.

A admissão nativa preserva a verificação de rede e só depois chama a porta
Java; uma exceção JNI recusa a conexão. O callback não detém o monitor da
fila de vídeo e a parada invalida uma autorização ainda em espera. FCast
e DLNA passam pelo mesmo coordenador e conferem a geração após a resposta.
Mudança da opção reinicia os receptores para retirar clientes anteriores.

A janela usa os textos compartilhados, começa com Recusar focado e recusa
ao voltar/fechar ou sair da Activity. Uma solicitação em segundo plano
reabre a Activity. Não registra endereços dos solicitantes no diagnóstico.

As duas edições passaram em API17: RTSP/FCast/DLNA, aceitação/recusa,
isolamento por protocolo, resposta tardia, timeout real, cancelamento ao
parar e retorno do segundo plano, retirada do cliente anterior ao ativar
a opção e captura da janela. As capturas foram conferidas visualmente.
Os textos usados estão presentes nos 22 idiomas compartilhados.

Os dois builds com `-Check` passaram, incluindo lint e o teste JVM que
confirma que uma aprovação bloqueada não segura o monitor da fila de vídeo.
Na edição geral passaram as regressões de FCast, DLNA, URL nativa/FCUP e
download dos seis MP4s com FFmpeg/ffprobe e comparação de quadros B.
Na edição DE/AT passaram os bloqueios de download e gravação.
Os relatórios estão em `outputs/legacy-approval-final-*` e
`outputs/legacy-approval-deat-*`; os APKs continuam sendo previews debug,
sem integração/assinatura de produção para a loja.

SHA-256 dos APKs conferidos:

- Geral: `cf22eb40385e3d644e8075467691501251bde10f231944f49332c18eff08a6f9`.
- DE/AT: `c118e5df9a6d98cefc1bd3ad9cc9606d5c6f17847497e3547e1ad4150f275c2e`.

Os manifestos sintéticos DASH foram normalizados para LF antes do cálculo
de hashes. ElementTree preservava CRLF de nós textuais do FFmpeg, enquanto
o Git os normalizava no commit, divergindo do inventário de recursos.
Os 19 hashes também foram conferidos contra os blobs preparados para commit.

Permanecem pendentes PIN, Cast V2, retransmissão, transcodificação de mais
formatos, licença/produção e validação em Sticks/emissores físicos antigos.

## API17 — download de URLs para MP4

`LegacyUrlDownload` lê amostras codificadas de MediaPeriod/SampleStream
na sua própria thread e usa Media3 Muxer 1.2.1. Não adiciona Transformer
API21 nem força minSdk de dependência. Seleciona vídeo/áudio compatíveis,
preserva cabeçalhos, exige HTTP(S) também em URIs filhas e recusa DRM,
janelas live/dynamic e mudanças incompatíveis de formato. A exportação
começa do início do item, percorre períodos e não depende de Surface,
decoder ou relógio de reprodução. Há orçamento por amostra/buffer,
timeout de carregamento e verificações de espaço livre. O arquivo `.part`
é privado e só é publicado por rename depois de fechar o container.
Falha/cancelamento remove somente o temporário criado pelo próprio job.

O serviço oferece download/progresso/cancelamento nos controles de vídeo,
mantém a vida do serviço/wakelock enquanto necessário e cancela no Stop
ou destruição. O worker só é criado no primeiro download. Ferramentas
lista vídeos salvos com reprodução local, exportação e exclusão confirmada;
o FileProvider só expõe as pastas privadas previstas. URLs locais entram
por factory confiável; o construtor de rede continua recusando file/content.
BuildConfig bloqueia UI, início, listagem e criação do worker em DE/AT.
Quatro textos novos existem nos 22 idiomas. Cornish mantém a indicação
de rascunho/revisão fluente que já acompanha as traduções desse idioma.

A integração revelou e corrigiu prefixos Annex-B de três bytes em TS,
tags de idioma BCP47 na API17 e timestamps de quadros B no muxer 1.2.
O reparo de composição adiciona CTTS assinado e ajusta durações sem mover
os dados de mídia: o moov anterior vira free e o novo é anexado antes da
publicação. Há limites de metadados, verificação de contagem e testes JVM
de payload/offsets, rejeição de estrutura inválida e overflow.

`outputs/legacy-download-final` passou com MP4, HLS, DASH, dois períodos,
quadros B e reexportação do arquivo reparado. FFmpeg/ffprobe confirmaram
os seis arquivos, sem mensagens de erro; ambos os casos B produziram 120
hashes de quadros idênticos à origem. Os demais arquivos também passaram
por reprodução local. A API17 x86 tem decoder AVC Baseline e extrator
antigo sem CTTS assinado: não tratar esses testes como suporte físico
AVC Main. O leitor Media3 de CTTS foi comprovado pela reexportação.

Uma interrupção deixou APK de teste inválido; o build foi reempacotado.
Os scripts agora verificam ZIP/CRC e resultado textual da instalação antes
de iniciar instrumentação, além da estrutura/hash ao exportar previews.
A compilação das duas edições, lint e os sete testes JVM novos passaram.
Também passaram as regressões gerais de URL/legendas, URL nativa/FCUP,
gravação, FCast e DLNA. Na edição DE/AT, passaram download/gravação
bloqueados e os cenários de URL nativa/FCUP e serviço/legendas.
Uma queda do ADB interrompeu a primeira regressão de gravação; a repetição
com os mesmos APKs concluiu e removeu os pacotes de teste.

APKs de prévia conferidos (SHA-256):

- Geral: `40722febad4a3498ee0119b5c17d4d358eb072b0423852054be94d1a44d5a65f`.
- DE/AT: `552218a3c5ec8e139584223bdd260d8f0322ef35895260a73bc934224ec60787`.

São previews debug, sem integração/assinatura final para publicar na loja.
O verificador de marca preservou os 12 recursos aprovados; a auditoria
direcionada não encontrou ocorrências nos arquivos ou histórico públicos.
As telas de Ferramentas passaram nos testes em português e árabe (incluindo
layout RTL) e em alemão na edição restrita. As três capturas foram conferidas
visualmente; a imagem em português está em
`docs/images/fireos3-native-download-tools.png`.

A paridade completa permanece pendente: transcodificação para formatos
que o muxer não aceita, Cast V2, retransmissão, aprovação/PIN, licença/SDK
e assinatura de produção, testes físicos e demais itens do plano. HTTPS
real, live/DRM, uso prolongado/memória em hardware antigo e o defeito nativo
intermitente não ficam comprovados por esses testes sintéticos.

## API17 — gravação MKV e HLS pelo canal reverso

A prévia geral integra `LegacyMirrorCapture` ao mesmo `MatroskaRecorder`
da produção. O worker de vídeo fornece AVC original com SPS/PPS e dimensões
conhecidas mesmo sem Surface; o worker de áudio fornece apenas os intervalos
PCM com timestamps reais, antes do ganho local. A fila de gravação fica
limitada a 8 MiB/120 pacotes, sem I/O de disco nas threads de recepção/decoder.
Inicia no próximo IDR, fecha ao parar o espelhamento/receptor e preserva o
arquivo já gravado ao terminar por erro/limite. Capturas sem IDR não deixam
arquivos. Exige 128 MiB livres para iniciar e encerra abaixo de 32 MiB.

Ferramentas oferece preparar/parar, estado, biblioteca, reprodução local,
exportação por FileProvider restrito à pasta de gravações e exclusão com
confirmação. Usa os textos já traduzidos e os componentes de foco por
controle/toque. Na API17, usa armazenamento interno privado sem pedir
permissão ampla de escrita. O seletor local de arquivo não passa pelo
construtor de URLs de rede: a fonte FileDataSource aceita somente a URI
selecionada, enquanto manifestos e segmentos remotos continuam HTTP(S)-only.
Os controles, início e listagem da captura ficam bloqueados na edição DE/AT.

`--recording` passou em `outputs/legacy-recording-review`: captura sintética
via callbacks nativos H.264/ALAC, MKV real, reprodução local com formato PCM
44100 Hz estéreo selecionado no worker Media3, URI de exportação, exclusão,
arquivo vazio, limite de bytes e encerramento com captura armada.
`ffprobe` confirmou 60 pacotes AVC e 188 PCM. O extrator MKV do Android 4.2
omite PCM; essa omissão não é usada como prova de ausência de áudio.
As duas edições passaram em build, lint e testes JVM com
`Build-FireOs.ps1 -Target legacy -Instrumentation -Check` (com `-NoDownload`
na regional). As regressões de URL/legendas e URL nativa/FCUP passaram nas
duas edições; DE/AT passou também no gate negativo de gravação.
Relatórios: `outputs/legacy-recording-regression-*`,
`outputs/legacy-recording-deat-*` e `outputs/legacy-recording-native-probe`.
O probe nativo completo passou após os taps de vídeo/PCM, incluindo
ALAC/AAC, relógio A/V, troca de Surface, reprodução sem Surface e parada.
APKs debug testados (SHA-256):

- Geral: `35209c3f602ac54c30781c56caf795610eacc6fab15327945928a1c41d00d738`.
- DE/AT: `c337b75a684f0d8dc0840c95331fc99e5aef68ea081701c5d04eaaa9c1996cb8`.

`test_legacy_app.py --tools-only` acrescenta captura/verificação dos controles
de gravação. Sem `--locales`, percorre todos os idiomas; com uma lista,
restringe-se a ela. Passou em inglês, pt-BR e árabe (incluindo layout RTL),
com captura preparada/parada sem arquivo vazio, e em alemão com a edição
restrita sem controles de gravação. Também aceita a conexão local TCP de
um emulador em servidor ADB isolado, com autenticação do console para
screenshots e verificação `ro.kernel.qemu` antes de qualquer instalação.
Referência visual conferida: `docs/images/fireos3-native-recording-tools.png`.
Nenhuma TV física foi usada; as 12 imagens aprovadas do launcher permanecem
intactas. Os APKs continuam sendo prévias sem assinatura/SDK de produção.

O cenário `--native-url` agora exige também Upgrade PTTH em `/reverse`,
requisições `/event` reais e respostas `/action` bplist para master/media,
reprodução através do proxy HLS nativo e pausa/busca/Stop. A versão inicial
falhou ao consultar o snapshot imediatamente; esperar a publicação assíncrona
com limite de tempo comprovou estado ready/duração/posição corretos.

Ainda faltam download de URLs, Cast V2, retransmissão, aprovação/PIN,
licença/SDK e assinatura de produção, além das demais pendências do plano.
Canal reverso sintético não comprova interoperabilidade Apple, HTTPS real,
live/DRM, sincronismo físico, sessões longas ou armazenamento de um Stick
antigo. O defeito nativo intermitente também continua pendente.

## API17 — callbacks de URL AirPlay

`probe.c` registra play/wait-ready/scrub/rate/stop/playback-info/playlist-remove
e resets de HLS. A URL atravessa JNI como bytes UTF-8 limitados, evitando
NewStringUTF para caracteres suplementares. O receptor ativa `hls` e os
bits de vídeo/HLS somente quando o listener Java aceita URL; o probe sem
frontend de URL continua sem anunciar essas funções.

`LegacyNativePlayback` mantém snapshots imutáveis e tokens por item.
Wait-ready aguarda no objeto Java, com limite de 10 s, e sai em ready,
substituição ou fechamento. Atualizações não usam o lock do handle JNI;
Stop/close libera o worker de HTTP antes de destruir o receptor. Relatórios
de gerações antigas e relatórios após finish são recusados. O serviço liga
os comandos ao Media3 e publica posição/duração/rate/ready/buffering. O
fechamento de um canal preparado/pausado encerra a URL; buffering e reprodução
não são confundidos com abandono intencional.

O encerramento por erro também fecha as filas Java antes de JNI aguardar
os workers HTTP. Falha/reinício da sessão nativa encerra a URL pertencente
àquela sessão; callbacks já enfileirados não iniciam mídia após a falha.

Uma época de fluxo nativo distingue áudio/espelhamento novos de callbacks
de conexão ou de um fluxo anterior ainda encerrando. Assim, o flush do
áudio RTP ao entrar em URL não cancela a própria URL; um fluxo nativo novo
continua podendo tomar a saída, como na etapa anterior.

O patch UxPlay 0012 faz `/server-info` usar os recursos habilitados em vez
de anunciar sempre 0x27F (inclusive fotos/FairPlay não habilitados). O task
de patches aceita acrescentar patches a um prefixo cujo hash e arquivos
anteriores conferem. Continua recusando alteração de patches anteriores e
edição local dos arquivos já modificados; não executa reset. A migração e
uma nova execução idempotente passaram neste checkout.

Seis testes JVM cobrem tokens, controles/valores inválidos, ready/close/
substituição concorrentes, frontend sem URL e abandono versus buffering.
`test_legacy_gestures.py --native-url` usa HTTP nativo real, corpo bplist
com UUID por item e X-Apple-Session-ID estável por conexão. Passou em
`outputs/legacy-native-url-general-final-native-url`: RTP ALAC -> MP4, HLS direto, AAC, estado
remoto, pausa/busca/velocidade, tecla local, Stop/EOF, fonte inválida,
abandono pausado versus tocando, Stop durante `/play` aguardando mídia e
reinício explícito do receptor. A resposta native readyToPlay é inteiro 1,
aceito pelo parser de teste junto com boolean true.

A compilação final geral e DE/AT passou com `-Check -Instrumentation`, incluindo
os seis testes JVM novos e lint. O diagnóstico nativo completo passou em
`outputs/legacy-native-url-probe-final`: DNS/RTSP, H.264, ALAC/AAC, timeline,
troca de Surface, saída de áudio e encerramento. Um APK incremental antigo
do probe falhou com `AbstractMethodError` no novo método default do listener;
após limpar e recompilar o módulo consumidor, o mesmo cenário passou.
Repetir essa compilação limpa se esse erro reaparecer após mudanças na interface.

Os oito cenários finais passaram: `native-url`, `url-service`, `fcast` e
`dlna` nas duas edições. Os relatórios estão em
`outputs/legacy-native-url-general-final-*` e
`outputs/legacy-native-url-deat-final-*`, todos com limpeza por desinstalação.
O cenário de URL inclui legendas externas e retomada do áudio nativo no
mesmo processo. Hashes SHA-256 dos APKs debug efetivamente testados:

- Geral: `4be96c32bea4a02445ef2c240e17dda9d2c041e34290f4793da05bfc21f266b0`.
- DE/AT: `a55a9b8013f1594da2316a773c936b15ff98864d4a818212725a6294026932e6`.

São prévias API17 em emulador, sem assinatura/SDK de produção integrado;
não foram submetidas à loja nem instaladas nas TVs da casa. As 12 imagens
aprovadas do launcher foram preservadas, e a auditoria de código/histórico
público não encontrou ocorrências nas regras verificadas.

FCUP/canal reverso, emissores Apple físicos, live/DRM e HTTPS real ainda
precisam de validação; os testes HTTP diretos não cobrem esses cenários.
Seguem também download/gravação, demais recursos do plano, produção e o
defeito nativo intermitente. Isso não conclui a paridade completa.

## API17 — receptor persistente durante URL

O serviço não encerra mais UxPlay/descoberta ao abrir uma URL. Primeiro
`detachVideoOutput` pede ao worker AVC para retirar a Surface; só depois de
liberar o codec e a Surface anterior ele confirma a passagem ao serviço.
O player Media3 só começa após essa confirmação. Épocas descartam replies
de passagens canceladas. Pedidos após o fechamento do worker também recebem
confirmação sem aguardar uma thread já encerrada.

O receptor mantém listener e identidade durante URL. Sua saída de áudio
fica localmente silenciada enquanto Media3 tem a saída; ao chegar áudio
nativo ou um novo modo de espelhamento, a URL fecha antes da devolução da
Surface/ganho. O foco existente é aplicado ao novo player, e o wakelock não
é liberado pelo fechamento de URL quando ainda pertence ao receptor ativo.
Reiniciar explicitamente o serviço ou mudar de rede continua podendo
substituir a sessão nativa.

O teste de URL agora mantém o mesmo socket RTSP desde antes da primeira
carga até a tomada de saída por ALAC nativo. Inclui cancelamentos rápidos,
verificação de codec/Surface nativos retirados durante URL, Back, 404,
Stop, legendas e comprovação de Media3 fechado antes de liberar ganho
nativo. O roteiro de áudio faz sua própria reinicialização explícita mais
adiante; a identidade é conferida antes dessa etapa intencional.
Stop seguido imediatamente por Play agora mantém a última URL validada
enquanto o player anterior fecha. Pausa, posição, volume e velocidade
recebidos durante essa passagem ficam associados à nova carga; Stop pode
cancelá-la. O snapshot dos protocolos mostra buffering durante a espera.
Sem isso, a nova disponibilidade permanente do listener mascarava o fato
de Media3 ainda estar fechando e o próximo Play recebia erro.
O probe verifica confirmação de detach após aposentar Surfaces usadas pelo
decoder real, continuidade de áudio/RTSP, retomada por IDR e reply tardio
após fechar o receptor.

Build ARMv7/x86, lint e testes JVM passaram nas duas edições. URL, FCast
(incluindo Stop/Play e a sequência de pausa/busca/volume/velocidade) e DLNA
passaram em `outputs/legacy-output-handoff-url-service-final`,
`outputs/legacy-output-handoff-fcast-final` e
`outputs/legacy-output-handoff-dlna-final`; as três suítes também passaram
nos diretórios correspondentes com `-nodownload-final`.
SHA256 geral/DE-AT:
`b4bc432e8a7b6eee39edfca4177c21fb72511114d3a4ed5bac2739a9149fa43b` e
`49d821fefc5d0551f27104b91b0324727039b0e3e89d048e4a328027116c564a`.
O probe fez dois ciclos completos em `outputs/legacy-output-handoff-native-final`;
o APK foi preservado em `outputs/legacy-output-handoff-probe.apk`, SHA256
`a8d1f4f1a26034916e151b184f073bba4e22f1aea0cfe02c569a815cfc665c80`.
Os DEX desse probe foram conferidos com as novas verificações de confirmação
e reply tardio. As instalações temporárias foram removidas. Não foi usada
nenhuma TV física nesta etapa. Marca e auditoria pública/histórico passaram.

Isso prepara a ligação dos callbacks AirPlay de URL; eles ainda não foram
portados e os bits de recursos correspondentes não foram habilitados.
Permanecem download/gravação, demais recursos do plano, testes físicos,
produção e a investigação do defeito intermitente de dlfree. Não há ainda
paridade completa com o app atual. As logos seguem preservadas.

## API17 — legendas externas

O player agora aceita uma fonte de legenda isolada, carregada de bytes já
importados pelo usuário. A fábrica de vídeo/manifestos continua HTTP-only;
não passa a abrir arquivos/ContentProviders por causa de uma URI remota.
O frontend compartilha `ExternalSubtitles` com o app moderno: SRT/VTT UTF-8,
BOM aceito, limite de 2 MiB. O arquivo intermediário do importador é removido.
Texto de cues é limitado a 4000 caracteres, como no frontend Compose.

O botão CC abre um diálogo traduzido com escolher arquivo/desativar. Na
API17 usa ACTION_GET_CONTENT; ACTION_OPEN_DOCUMENT só existe desde API19.
A legenda é mesclada ao vídeo no worker Media3, preservando posição,
playWhenReady e velocidade. O serviço rejeita resultados de sessões antigas
e mantém uma permissão temporária de execução enquanto o seletor fica
aberto (até cinco minutos), inclusive com segundo plano desativado.

O teste de URL foi ampliado com ContentProvider sintético exclusivo do
APK de testes, importação SRT/BOM VTT pelo resultado de Activity, cues
reais, texto na View, pausa/posição/velocidade, desativação, formatos
inválidos e limite de tamanho. O provider precisa estar no primeiro DEX
para iniciar no processo próprio do APK de testes antes do runner instalar
multidex; `main-dex-rules.pro` garante isso, sem incluí-lo no app.
O roteiro verifica pausa/posição antes de dar Play para observar cues,
pois o renderer Media3 1.2.1 só os entrega ao iniciar a reprodução.

O receptor DLNA compartilhado agora distingue buffering de Pause. O teste
de DLNA adiciona legenda com atraso HTTP de 1,2 s (maior que seu tick de
500 ms) e exige retomada com texto, sem PAUSED_PLAYBACK indevido.
Build ARMv7/x86, lint e testes JVM passaram nas duas edições.
Os relatórios `outputs/legacy-subtitles-final` e
`outputs/legacy-subtitles-dlna-final` passaram no emulador API17.
`outputs/legacy-subtitles-formats-final` passou novamente MP4/HLS/DASH/AAC,
velocidade, fontes sem extensão/MIME e a recusa de segmento local válido.
As mesmas suítes de serviço/CC e DLNA passaram na edição DE/AT em
`outputs/legacy-subtitles-nodownload-final` e
`outputs/legacy-subtitles-dlna-nodownload-final`. As instalações temporárias
foram removidas. O provider e os textos sintéticos não existem nos DEX dos
APKs do app. Marca aprovada e auditoria pública/histórico passaram.
SHA256 geral/DE-AT:
`fb1d519f3798c88cee78bd4876996a67c990b769c5b5b5a7db255e8303e95481` e
`1010a595f99f0ea2bc03a2094915dadd34267ad83cd9486edace6288d23644d9`.

Continuam pendentes AirPlay por URL, arbitragem sem suspender o receptor
nativo, download/gravação e demais recursos do plano. HTTPS real, sessões
longas, defeito nativo intermitente e validação física/produção ainda não
estão concluídos. As legendas foram verificadas com mídia sintética, não
com todos os formatos/transmissões ou emissores físicos.

## API17 — receptor DLNA e fotos compartilhadas

O alvo antigo passou a compilar os mesmos `DlnaReceiver`, `ControlServer`,
`SubscriptionRegistry` e `SsdpAgent` do app atual. DLNA começa desativado e
tem chave traduzida nas configurações. O serviço acompanha rede/nome e
desativa os transportes fora da thread principal. Getters usados por SOAP
leem um snapshot imutável publicado pelo serviço; comandos continuam no main.
FCast e DLNA usam o mesmo adaptador com proprietário/época por protocolo.

`PhotoActivity` compartilha a interface nativa, histórico e controles do app
atual. O Sync altera somente os bindings de R, lifecycle e loader, com guardas
que falham se os marcadores upstream mudarem. A base API17 mantém o serviço
visível enquanto a foto está aberta e aplica bordas de foco/toque aos seis
botões. O loader preserva o limite de 16 MiB, grava temporariamente no cache
privado, valida HTTP(S) em cada redirecionamento e decodifica com amostragem
até 1920×1080 para reduzir a pressão no heap dos aparelhos antigos.

Foi corrigida também a exceção assíncrona de prepareAndPlay no receptor DLNA
compartilhado: falhas na callback de carga/foto passam a notificar o estado
de erro da sessão sem escapar para o main. O runner
`tools/test_legacy_gestures.py --dlna` exercita descoberta SSDP, descrição,
SOAP, vídeo HTTP, pausa/busca/play/stop, URL rejeitada após o despacho SOAP,
foto sintética e controles de girar/escala, erro de imagem, limpeza de cache,
desativação e reativação do listener. Usar somente emulador isolado API17.

Build ARMv7/x86, lint e testes JVM passaram nas duas edições. DLNA passou em
`outputs/legacy-dlna-final` e `outputs/legacy-dlna-nodownload-final`. O roteiro
aguarda PLAYING no protocolo antes de enviar Pause; a primeira rodada final
falhou na espera da pausa, que antes podia começar ainda em TRANSITIONING.
Agora o instrumentation grava a stack completa em caso de falha.
FCast também passou nas duas edições em `outputs/legacy-dlna-fcast-final`
e `outputs/legacy-dlna-fcast-nodownload-final`. Todos os testes desinstalaram
as prévias temporárias. Não foi utilizada nenhuma TV física nesta etapa.
SHA256 dos APKs geral/DE-AT, respectivamente:
`de48282f5b58ec13151c3c459c155e1add6dfc4ed5e10c9547da25620c0f866b` e
`c272564c2395bcb596aaa6260383a2519a8ef0c31fa06fa7a1191c30da1b4984`.

Essa etapa não conclui a paridade: ainda faltam AirPlay por URL,
legendas/faixas, download/gravação e demais recursos do plano. GENA/volume,
slideshow/histórico prolongado e limites de fotos ainda precisam de testes
de integração específicos no API17. Permanecem a arbitragem nativa/URL,
HTTPS real, consumo prolongado, defeito intermitente de dlfree, produção e
validação física. Vega segue separado no Mac. As logos não foram alteradas.

## Worker de vídeo ligado à sessão AirPlay — 18 de novembro de 2026

- `AirPlayVideoWorker` recebe cópias dos callbacks UxPlay em fila de até
  256 eventos/8 MiB, incluindo o callback em processamento; cada access unit
  tem no máximo 2 MiB. Libera a cópia concluída antes de devolver seu orçamento
  à fila. Overflow, entrada inválida ou consumidor desconectado encerram a
  saída, marcam erro e produzem comando de parada.
- SPS/PPS e IDR inicial são obrigatórios. O parser Annex B limitado é
  compartilhado com o muxer. Mudança de dimensões invalida fila e parâmetros;
  flush preserva a configuração negociada, mas exige novo IDR e nova época.
  Mudança de SPS/PPS recria muxer/URL. Comandos de saída são coalescidos para
  não reter URLs antigas. NTP local em ns vira PTS relativo em microssegundos;
  timestamps duplicados/anteriores são descartados.
- Esta primeira etapa pressupõe H.264 sem reordenação: DTS=PTS. A duração do
  primeiro frame é estimada em 30 Hz; depois usa a cadência observada, limitada
  a um segundo por amostra. Ainda não é sincronismo A/V nem suporte a B-frames.
- `AirPlaySession` inicia e para o worker dentro de seu rollback de transporte;
  encaminha tamanho, amostras, flush/reset e stop. Só a URL efêmera cruza o
  TurboModule. `BuzinNetwork` possui o worker antes da sessão, preservando a
  ordem de destruição, e liga a saída fMP4 real no app nas três arquiteturas.
- Estado do port tem `Testar vídeo AirPlay`: modo manual experimental, sem
  descoberta/PIN/áudio integrado. O receptor JS aceita nesse modo somente URLs
  de loopback com o token gerado nativamente; permite substituições rápidas de
  formato e rejeita comandos de carga do modo URL. Não oferece pausa manual
  de uma saída ao vivo. Recursos de descoberta AirPlay continuam zerados.
- Contratos C++ com ASan/UBSan passaram: cópia de payload, gating de IDR,
  timestamps, flush, dimensão/SPS/PPS, cancelamento durante startup/escrita,
  limites por bytes/eventos, falha do consumidor e barreira de liberação.
  CI executa o novo teste. Lint, TypeScript, 62 testes Jest, regressão portátil
  ALAC/mux/saída e build/validação dos três VPKGs passaram.
- A nova tela passou em dois ciclos VVD de OPTIONS/Voltar/background, com
  worker parado, fila vazia e EOF do socket RTSP em `build/vega-video-worker-ui`.
  Isso não negocia mídia Apple. O probe de mídia agora alimenta o mesmo helper
  de callback e worker com fixture própria; não executa FairPlay/RTSP/AES do
  espelhamento. Integração criptografada real, pixels, A/V, reconexões completas
  e paridade com Fire OS continuam pendentes.


Evidência do worker em VVD High: `build/vega-video-worker-stream` passou com
`nativeCallbackWorker=true`, 120 frames/179947 bytes no ciclo completo e
61/120 frames no ciclo cancelado, `clientClosed`, filas vazias e recursos
liberados. A reprodução começou com 39/120 frames aceitos. O relatório mantém
`visualValidation=false`, com contadores total/dropped zero. A regressão de
UI/volume/mute/encerramento de áudio passou em dois ciclos em
`build/vega-video-worker-audio-regression`; a nova tela passou em
`build/vega-video-worker-ui`. Nenhum desses relatórios negocia mídia Apple real.

## API17 — Media3, formatos e velocidade

`LegacyUrlPlayer` usa Media3 1.2.1 no lugar de MediaPlayer. Essa tag declara
minSdk 16; 1.3.0/1.3.1 já declaram 19. A build continua com minSdk 17,
sem `overrideLibrary`. O player e suas callbacks permanecem na HandlerThread
do runtime, com geração por carga, foco/ganho separados e posse explícita
das superfícies. ExoPlayer não pede foco em paralelo ao serviço.
Foram incluídos licença e aviso Media3 nos assets do runtime.

MP4, HLS/MPEG-TS, DASH/fMP4 e AAC por HTTP usam a mesma fábrica de fontes,
com MIME explícito (inclusive URL sem extensão) e cabeçalhos propagados aos
segmentos. Uma camada de resolução exige HTTP(S), sem credenciais embutidas,
também para URIs filhas. Limites de conexão/leitura são 10/15 s e a política
de erro usa parâmetro de repetição 2; demora e retentativas ainda precisam de
teste com falhas.
Não há fallback de um manifesto remoto para arquivos/ContentProviders.

O backend configura velocidade de 0,25 a 4; o seletor de Views usa textos
compartilhados e devolve o foco ao controle ao fechar. Pausa permanece pausa
ao trocar a velocidade. FCast aplica a velocidade inicial mesmo enquanto o
receptor nativo está encerrando e aceita mudanças via opcode 10; o estado e
RemoteControlClient (API18+) recebem a velocidade atual. Busca/posição usam
long até o limite de um ano já adotado no contrato FCast; não há mais o teto
de Integer.MAX_VALUE em milissegundos. MIME/limites têm cobertura JVM.

`tools/generate_legacy_url_fixtures.py` produz somente padrão H.264 e silêncio
AAC de 8 s, playlists/segmentos e manifesto de hashes. Não lê mídia pessoal.
`tools/test_fireos3_probe.py --url-player --cycles 2` passou em
`outputs/legacy-media3-formats-final`: controles, posição inicial, troca de
Surface, EOF/repetição, 404/recuperação, substituição rápida, URLs sem extensão,
HLS/DASH com segmentos e cabeçalho, busca/quadro e liberação. O relógio do AAC
mediu 0,523/2,094 e 0,513/2,055 nas configurações 0,5×/2×. Um manifesto com um
segmento MPEG-TS local válido é recusado. O runner registra esses valores.

Validação de serviço/controle: `outputs/legacy-media3-transition-final`,
`outputs/legacy-media3-fcast-final` e `outputs/legacy-media3-fcast-nodownload-final`.
As três suítes passaram, assim como build ARMv7/x86, lint e testes JVM das
duas edições. SHA256 dos APKs geral/DE-AT, respectivamente:
`02c9555be634024ea03147f21cb6cc3fb9f5139c16e16a2363db49c63812095e` e
`2ea8a7b85603ce0368fbe94244dccbbb4d3a69b029a7cd873959afec6ce2eb60`.
O probe dos dois ciclos foi preservado em `outputs/legacy-media3-121-probe.apk`,
SHA256 `a291b20027d7ffdd651a8502538f5b588146746b15d97189236012cbbb1f4fed`.
Avisos/licença e versão 1.2.1 foram conferidos nos APKs; fixtures ficam só
no probe/testes. Marca e auditoria pública do histórico foram verificadas.
O modo `--url-service` também decodifica ALAC e exercita AudioTrack/foco após
fechar URL, mantendo o mesmo processo/serviço. O teste do menu agora aguarda
o layout e usa foco/tecla reais para abrir/escolher, antes de verificar a
restauração do foco. Os primeiros roteiros acionavam performClick sem
estabelecer a origem de foco e não representavam essa sequência do controle.

Ainda faltam DLNA/AirPlay por URL, fotos, legendas/faixas, gravação/download,
metadados/estado visual próprio para URL sem vídeo e demais recursos do plano.
O servidor nativo ainda é suspenso durante URL; a arbitragem precisa alcançar
o comportamento do app atual antes de considerar a paridade concluída.
Produção e validação de HTTPS/certificados reais, live/DRM, troca de bitrate,
redes lentas e consumo de memória com conteúdo longo continuam pendentes;
os arquivos pequenos não cobrem esses cenários. A falha nativa intermitente de
`dlfree` e a validação física continuam pendentes. Não há paridade completa.

## Posse e cancelamento da saída de vídeo — 18 de novembro de 2026

- `VideoSampleSink` define a fronteira nativa para o futuro worker de vídeo:
  access units Annex B e timestamps entram; somente a URL local sai.
  `Fmp4VideoOutput` possui muxer e endpoint na mesma sessão. Reabrir cria
  configuração, porta e token novos; erro na inicialização libera o listener.
- `LocalVideoStream.cancel()` acorda o produtor e interrompe sockets sem
  destruir fila/muxer ou fazer join na thread de controle. `stop()` continua
  pertencendo ao worker dono, que cancela, destrói o muxer e libera o servidor.
  Publicação do endpoint é protegida para não sobrescrever cancelamento
  concorrente com o reset inicial da fila. A geração da sessão deve impedir
  que um pedido antigo cancele uma futura substituição.
- O probe `--serve` passou a usar esse dono comum, em vez de gerenciar muxer e
  servidor separadamente. A integração aos callbacks UxPlay ainda é o próximo
  passo; nenhum anúncio de espelhamento foi habilitado.
- Contratos portáteis passaram com ASan/UBSan: bytes MP4 recebidos iguais ao
  muxer de referência, dois ciclos, IDR/configuração inválida, cancelamento
  pelo controle durante backpressure de AVIO, espera de EOF cancelada,
  reabertura após cancelamento e liberação de porta pelo destrutor.
  O CI executa esses casos junto ao ALAC/mux em `test_vega_alac_decoder.py`.
- Build/validação dos três VPKGs passou; probes e bibliotecas estáticas
  permanecem excluídos dos pacotes. Renderização visual, emissor Apple real,
  integração ao worker/callbacks e sincronismo A/V continuam pendentes.


Ensaio High do dono comum aprovado em `build/vega-video-output-owned`:
120 frames/179947 bytes no ciclo completo, reprodução observada com 43/120
frames produzidos; Voltar encerrou o segundo ciclo em 62/120 com `clientClosed`,
produtor/player/superfície liberados. Os contadores de vídeo continuaram zero;
o relatório mantém `visualValidation=false` e não certifica imagem exibida.

## Fluxo local de vídeo e diagnóstico da superfície — 18 de novembro de 2026

- Adicionado `LocalVideoStream`, servidor HTTP exclusivo de loopback, porta
  dinâmica e caminho com token aleatório. Usa uma única conexão GET de mídia,
  HTTP chunked, sem busca; HEAD e início em Range zero são aceitos. Pedidos
  têm limite de 4 KiB/três segundos. Não anuncia serviço na LAN.
- A fila retém no máximo 8 MiB e 2048 chunks, incluindo o chunk em envio.
  Escrita aguarda espaço por até três segundos; sockets são não bloqueantes,
  com verificação de cancelamento a cada 25 ms e prazo de falta de progresso.
  Diferencia `clientClosed`, `consumerTimeout` e `outputBackpressure`.
  Encerramento acorda produtores e libera worker, fila e listener.
- Teste C++ com ASan/UBSan passou: resposta binária/chunked, HEAD, URL/Range
  rejeitados, reabertura com token novo, reuso da porta, consumidor desconectado,
  limites de bytes/chunks, timeout, encerramento sob backpressure e cliente
  parado no cabeçalho. O CI passou a executar o contrato portátil.
- O probe `--serve` produz H.264 da fixture em ritmo real e envia ao muxer/
  endpoint nativo; o runner ativa a tela AirPlay por URL existente e entrega
  somente a URL ao JavaScript. Confere consumo antes de todos os frames terem
  sido produzidos, conclusão de oito segundos, Voltar e cancelamento antecipado.
  O produtor é um executável de desenvolvimento separado, fora do VPKG; ainda
  não está ligado aos callbacks de espelhamento da sessão UxPlay do app.
- `playbackSession` registra em `/data/video-quality.json` somente contadores
  numéricos e associação da superfície, sem mídia, URL ou handle. Consulta
  indisponível/falha de diagnóstico não interrompe reprodução. Ao liberar o
  player, marca a superfície como desassociada. Os 57 testes Jest passaram,
  incluindo falha de métricas e descarte de handle antigo.
- Evidência inicial: `build/vega-local-video-lifecycle` passou nos contratos de
  transporte/vida útil. O player começou com 36–38/120 frames produzidos,
  avançou até ~8 s e liberou player/superfície; no segundo ciclo Voltar encerrou
  a produção antes de completar 120 frames. Isso não confirma imagem exibida.
- A execução estrita em `build/vega-local-video-session-header` falhou porque
  o SDK retornou total/dropped de vídeo iguais a zero, apesar de superfície
  associada e relógio avançando. A tentativa GUI não trouxe captura utilizável.
  O teste estrito continua exigindo contadores positivos; `--transport-only`
  verifica os contratos independentes e registra `visualValidation=false`.
  Não usar o resultado de transporte para declarar renderização certificada.
- Próximo: integrar a posse do muxer/endpoint ao worker e aos callbacks AirPlay,
  com encerramento explícito pela sessão; integrar áudio/época comum e tratar
  reconexão/mudança de formato. Frames visíveis, A/V e emissor Apple real
  continuam pendentes, assim como a paridade completa do aplicativo.

## API17 — FCast compartilhado e logger nativo

`syncSharedTransport` compila na prévia API17 o mesmo `FCastReceiver.kt`
usado pelo app moderno, sem uma segunda implementação dos comandos TCP.
O frontend antigo fornece apenas os bindings de rede e descoberta: JmDNS
publica `_fcast._tcp.local.` com porta e TXT `v=2`/`appName` fora da thread
da UI. Registro, fechamento DNS-SD e multicast têm um worker próprio.
O serviço fecha os adaptadores em outro worker e invalida comandos de
instâncias substituídas antes de liberar as portas e recriar o receptor.

FCast é opt-in, inicialmente desligado, com a mesma opção compartilhada e
traduzida que informa uso da rede local sem PIN. `ReceiverPlaybackPort` liga
carregamento HTTP(S), cabeçalhos, posição inicial, pausa, reprodução, busca,
volume, parada e estado à arbitragem do player. Uma conexão AirPlay aberta
também bloqueia FCast antes de chegar mídia: callbacks nativos de conexão
alimentam um contador, zerado ao fechar e protegido de callbacks tardios.
O getter não bloqueia a UI no mutex usado para destruir o receptor.

O teste de um peer nativo ocioso revelou um aborto real em
`logger_log`: a callback do logger UxPlay não era configurada. Um reset de
socket emitia um aviso e disparava a asserção `logger->callback`. O runtime
agora instala o sink Android antes de `raop_init2`; o cenário passou e o
mesmo aviso ficou registrado sem derrubar o processo. Isso não encerra a
investigação anterior de endereço inválido em `dlfree`.

`tools/test_legacy_gestures.py --fcast` cobre navegação PTR e resolução/TXT,
handshake TCP v2, ping, recusa de arquivo local, recusa enquanto há um peer
AirPlay ocioso, HTTP com cabeçalho, MP4 sintético, pausa/busca/volume/estado,
retomada/parada, reabertura da porta e timeout de quadro incompleto. Verifica
a liberação do worker e do multicast. A busca direta por um nome ainda não
publicado falhou no primeiro roteiro; navegar o tipo de serviço encontrou o
anúncio correto. O runner agora salva logcat automaticamente nas falhas.
Relatórios locais: `outputs/legacy-fcast-final`,
`outputs/legacy-fcast-nodownload-final`,
`outputs/legacy-fcast-native-regression` e `outputs/legacy-fcast-url-regression`.
As quatro suítes passaram no emulador API17/1280×720. Os APKs conferidos têm
SHA256 geral `64f05a0fe5604fc3dd32d7c13a760098e11df38821c754b6a2678f226299c56d`
e DE/AT `082fec6dc91db8c0aaf7ed2d789b8ec17395e2d39203a6ecb6d21a996c71fcb6`.
Build ARMv7/x86, lint e testes JVM passaram nas duas edições; recursos de
marca aprovados e auditoria pública do histórico foram conferidos.

**Paridade completa continua pendente.** Na época deste ensaio, MediaPlayer
aceitava somente velocidade 1. A etapa posterior acima passou para Media3
1.2.1 e acrescentou velocidade e os formatos ensaiados. Os demais itens e a
validação física continuam pendentes.


## Vídeo — mux H.264/fMP4 nativo — 18 de novembro de 2026

- Implementado `H264Fmp4Muxer`: recebe access units H.264 Annex B completas,
  valida limites/NALs, exige IDR inicial e SPS/PPS correspondentes e produz
  MP4 fragmentado incremental sem decodificar/recodificar. O FFmpeg fixado
  converte SPS/PPS para avcC, incluindo extensões High, e preserva timestamps.
- Entrada limitada a 2 MiB/128 NALs por access unit e 64 KiB de configuração.
  Timestamps DTS são estritamente crescentes; duração, parâmetros, mudança
  de formato e uso após fechamento são verificados. Cada frame é descarregado
  como fragmento imediatamente. Índices de seek/trailer são desativados para
  evitar guardar um índice crescente de todos os fragmentos em sessões ao vivo.
  Mudança de SPS/PPS exige uma nova sessão.
- A saída usa callback nativo com barreira de exceções C/C++; falha de escrita
  fica marcada e impede novas escritas/finalização falsa. `finish()` é idempotente.
  O consumidor futuro deve impor limite/timeout à fila de saída: o muxer não
  oferece sozinho controle de consumo de rede.
- O teste portátil com a revisão FFmpeg fixada passou com ASan/UBSan no código
  próprio: saída incremental antes do fim, entrada inválida, IDR, timestamps,
  troca de configuração, encerramento repetido e falhas do consumidor.
  O CI executa esse ensaio junto do ALAC em `tools/test_vega_alac_decoder.py`.
- No VVD ARM64, o executável separado remuxou a fixture própria em dois ciclos
  (`build/vega-h264-fmp4-native`). O arquivo de saída foi lido como bytes e
  decodificado no host: os hashes de todos os 120 frames coincidiram com a
  entrada. Isso comprova preservação dos frames, sem comprovar renderização
  no Vega. Os campos `surfaceAttached` e `rendered` permanecem falsos.
- Ensaios MSSE com `video/avc`, `video/h264`, AVCC e Annex B falharam em
  negociação/parser no VVD. Logs em `build/vega-msse-video-avcc`,
  `build/vega-msse-video-annexb` e `build/vega-msse-video-h264-mime`.
  Não está demonstrado se a causa é formato, superfície ausente ou backend.
  O experimento está isolado em `kepler/probes/MsseVideoPlayer`, fora do app.
- Próximo: servir o fMP4 por um endpoint de loopback com fila limitada e
  ligá-lo ao VideoPlayer/superfície de URL já validados; depois integrar os
  callbacks/vida útil AirPlay, áudio e relógio A/V. Ainda não há espelhamento
  AirPlay exibido, vídeo ativo no app, negociação Apple completa ou validação
  física. Não anunciar esse muxer como recepção/renderização finalizada.

## ALAC com saída PCM — 18 de novembro de 2026

- Adicionado o perfil ALAC RAOP de 352 amostras, 16 bits, estéreo/44,1 kHz.
  O worker seleciona um sink composto: AAC-LC/AAC-ELD permanecem no MSSE;
  ALAC usa o decoder FFmpeg fixado e AudioStream APMF. Não há JNI/Oboe.
- O MSSE recusou ALAC no VVD com erro 4 (`build/vega-alac-native`). O caminho
  implementado decodifica e valida um frame planar de 352 amostras, converte
  para PCM intercalado e escreve na saída nativa com foco próprio MEDIA.
- AudioStream exige no mínimo um bloco nativo por escrita. Escrever os pacotes
  menores individualmente causou timeout. O sink agrupa PCM e retém somente
  a fração do próximo bloco; o tamanho/configuração nativa é limitado a um
  segundo de frames. Flush encerra/descarta essa fração, libera saída/foco e
  reinicia o decoder na próxima geração. Falha de liberação impede reabertura.
- A sessão pede foco no próprio processo que escreve PCM; aguarda concessão
  antes de iniciar, respeita pausa e a política de duck/mute do sistema,
  bloqueia escrita após parada permanente e libera o foco ao encerrar.
  Disputa real de foco e retomadas longas ainda precisam de validação.
- O decoder é limitado a pacotes de 64 KiB e um frame de perfil fixo; formatos
  diferentes são recusados. O teste portátil verifica sinal PCM não nulo,
  amplitude/canais, repetição/recriação e rejeição de entradas inválidas.
  `tools/test_vega_alac_decoder.py` compila a revisão FFmpeg fixada e passou
  com sanitizers no código próprio; esse ensaio foi acrescentado ao CI.
- No VVD, o sink PCM direto passou em dois ciclos de 1003 frames, ~7,95 s,
  em `build/vega-alac-pcm-blocks`. O caminho RTP/AES-CBC/NTP/worker/PCM passou
  em `build/vega-alac-pcm-rtp`: 1003/1015 frames aceitos, ~7,99 s, 11/12
  duplicatas sem aumentar a contagem aceita, flush e portas liberadas.
- Os testes continuam com fixture sintética e chaves públicas diretamente no
  contexto RTP. Não validam negociação RTSP/FairPlay de chaves, emissor Apple,
  som audível, perda de pacotes/concealment, latência ou sincronismo A/V físico.
  ALAC em outro perfil, vídeo, descoberta/PIN e a meta completa seguem pendentes.

## Vega — AAC-ELD implementado — 18 de novembro de 2026

AAC-ELD de 480/512 amostras agora usa o sink MSSE no mesmo worker que AAC-LC,
com configuração correspondente ao formato negociado, relógio/flush e volume.
O teste C++ com sanitizers verifica troca de codec preservando mudo, nova época,
formatos inválidos e isolamento entre codecs. Os 56 testes Jest, lint e build
com validação ABI das três arquiteturas passaram. O ensaio direto do player
passou em dois ciclos para cada formato usando tons sintéticos próprios.
A negociação completa com Apple, ALAC, vídeo, A/V e validação física continuam
pendentes. Os relatórios do transporte desta entrega estão registrados em
[VEGA_HANDOFF.md](VEGA_HANDOFF.md); a meta completa permanece ativa.

## API17 — integração do player por URL ao serviço e à Activity

O serviço agora aceita `LegacyUrlRequest` por um ponto de entrada interno.
Encerra a sessão nativa antes de transferir a TextureView ao MediaPlayer;
no fim, erro, Parar ou Voltar, aguarda o fechamento do player e a liberação
das superfícies antes de recriar o receptor. A descoberta nativa fica
temporariamente indisponível durante essa reprodução. Pedidos substituídos
e callbacks de instâncias antigas não controlam a nova sessão.

A Activity tem controles estáveis de reprodução/pausa, busca de 10 s,
posição/duração e parada, usando textos compartilhados e o estilo de foco
existente. Teclas de mídia e RemoteControlClient passam a controlar o player
local; metadados usam o nome do app e não expõem a URL/cabeçalhos.
Perda transitória ou permanente de foco pausa e silencia; Play explícito
recupera foco. A liberação de uma tecla de busca não recupera foco sozinha.
Erros de URL usam a mensagem genérica traduzida, separada do erro de início
do receptor. Os recursos de marca permanecem os aprovados.

`tools/test_legacy_gestures.py --url-service` exercita o serviço real, HTTP
com cabeçalho da fixture, MP4 sintético, pixels da TextureView, controles de
UI/teclas, perda e recuperação de foco, busca, Voltar, 404, nova reprodução,
Parar e retorno ao receptor. O runner desinstala os dois APKs ao terminar.
Relatórios locais: `outputs/legacy-url-service-final` e
`outputs/legacy-url-service-nodownload-final`, ambos aprovados no emulador
API17/1280×720. SHA256 dos APKs: geral
`e8c5db696bddfb80463c9ba8abdbb5513fd7464b41233d2283405f33370163a9`;
DE/AT `ca82f50f97997f7964f45adc87201fa6f6463a6b5408cfde93cfb4168284ba47`.
Build ARMv7/x86, lint e testes JVM das duas edições passaram.
A regressão nativa de controles,
metadados, áudio sem metadados, foco e três ciclos de espelhamento sem tela
está em `outputs/legacy-url-service-native-regression`.

**A etapa posterior acima ligou o FCast ao ponto de entrada.**
Ainda não há paridade completa de URLs recebidas nessa prévia. DLNA/AirPlay por
URL, HTTPS real, HLS/DASH, áudio por URL, legendas, velocidade, timeout/rede,
retomada e testes físicos ainda precisam de trabalho. A investigação do
encerramento nativo intermitente continua aberta; a regressão aprovada não
prova que a causa foi corrigida. Ainda não há paridade completa nem APK de
produção para todos os Sticks.


## Alvo antigo — base do player por URL

`LegacyUrlPlayer` usa MediaPlayer da plataforma em HandlerThread própria.
Tem preparação assíncrona, posição inicial, pausa/reprodução, busca, fim,
repetição após fim, ganho/foco separados e troca/retirada de Surface com posse
explícita. Cada carregamento retorna um identificador, comparável ao
`State.generation`; pedidos e callbacks substituídos não alteram a nova fonte.
O encerramento libera player, alvos e worker. `LegacyUrlRequest` valida URL
HTTP(S), credenciais embutidas, posição e limites/cabeçalhos de HTTP.

`tools/test_fireos3_probe.py --url-player --cycles 2` passou em
`outputs/legacy-url-player-verified`, API17/1280×720: HTTP real com cabeçalho
customizado, MP4 sintético 160×90/4 s, preparação pausada iniciando em 1 s,
pausa repetida antes de começar, reprodução, busca ao início, troca de
Surface, término/repetição, erro 404, recuperação e substituição rápida.
Build ARMv7/x86, lint e quatro testes novos de validação passaram.
A primeira tentativa encontrou leitura do estado de erro da fonte antiga
antes de executar o novo carregamento; o identificador de pedido resolveu
a ambiguidade. Uma repetição perdeu ADB no segundo ciclo; a rodada final
completa usou servidor separado. A fixture está só no probe/testes.

**A integração posterior com serviço/Activity está descrita acima.**
O adaptador FCast foi ligado na etapa acima. Faltam DLNA/AirPlay por URL, mantendo
arbitragem e foco com espelhamento. HLS/DASH, HTTPS real,
legendas, velocidade, recuperação/timeout de rede e demais funções ainda
precisam ser portados e validados. O limite de busca deste backend segue o
inteiro da API antiga; não tratá-lo como paridade concluída. O encerramento
nativo intermitente registrado anteriormente também continua pendente.

## Vega — ensaio RTP/AES/NTP concluído — 18 de novembro de 2026

O executável de desenvolvimento agora envia a fixture AAC-LC por UDP ao núcleo
RTP UxPlay, com AES-CBC e troca real de mensagens NTP em loopback. O callback
compartilhado com a sessão de produção encaminha as amostras ao worker/MSSE;
cada payload descriptografado é comparado byte a byte com a fixture. O ensaio
exercita duplicatas, rollover da sequência/timestamp RTP, volume e flush após
drenar as primeiras 12 amostras. Ao parar, tenta vincular novamente as portas
RTP/controle/NTP sem opções de reuso para verificar sua liberação.

`build/vega-airplay-audio-rtp-verified/result.json` passou no VVD ARM64: dois
ciclos, 346 e 358 frames, relógios de 7,980 s e 8,029 s, timestamps NTP em todos
os frames, quatro/cinco duplicatas enviadas sem reprodução duplicada e portas
liberadas. O segundo ciclo inclui flush e nova época. A regressão de controles
RTSP, volume/mudo, Voltar e segundo plano passou no mesmo pacote em
`build/vega-airplay-audio-rtp-controls/result.json`. Build/validação ABI das três
arquiteturas passou; o runner separado não integra o VPKG.

O teste usa chaves públicas determinísticas diretamente no contexto RTP de
desenvolvimento. Não valida negociação RTSP/FairPlay de chaves, emissores Apple,
perda/retransmissão de pacotes, som audível ou hardware físico. ALAC/AAC-ELD,
vídeo e sincronismo A/V continuam pendentes. O modo manual permanece experimental;
a paridade completa não está concluída.

## Investigação do encerramento nativo — marcos e repetição

O probe registra fases de criação, codecs, RTSP, replay de áudio, relógio
A/V, troca de superfície e encerramento. O runner registra SHA256 do APK,
salva as fases por ciclo e continua salvando logcat completo em falhas.
Esses diagnósticos não alteram o decoder nem a política de reprodução.

`outputs/legacy-native-phase-stress/report.json` passou em três ciclos,
com seis sessões completas de streaming. Exercitou ALAC/AAC-LC/AAC-ELD,
H.264, relógio A/V, saída sem tela, troca de Surface e encerramento de RTSP.
Os perfis anunciados alternaram 1080p/30 e 720p/60; isso não significa que
a amostra decodificada tinha essas resoluções. Build ARMv7/x86, lint e testes
JVM do runtime/probe passaram. Logs de fases foram conferidos no emulador.

O endereço inválido em `dlfree` da primeira regressão não foi reproduzido
nessas seis sessões. A causa segue sem confirmação e **não foi aplicada
uma correção nativa**; não encerrar a pendência nem anunciar estabilidade de
produção. Seguir investigando se reaparecer com os marcos disponíveis,
preservando os logs anteriores. A paridade dos protocolos/player, funções
restantes, produção e validação física continua no objetivo.

## Alvo antigo — áudio sem metadados

O AudioTrack avisa quando sua saída começa/termina, permitindo solicitar foco
mesmo sem título, capa ou identificação DACP. O serviço inicia o ganho local
em zero e só o libera após foco concedido. Áudio sem comandos não registra
botões de música inutilizáveis. A inatividade retira saída e foco, preservando
um bloqueio por perda permanente; pacotes novos não desfazem esse bloqueio.
Reiniciar explicitamente o receptor permite uma nova solicitação.

`--system-media` passou nas duas edições em API17/1280×720:
`outputs/legacy-plain-audio-general-verified` e
`outputs/legacy-plain-audio-regional-verified`. A fixture ALAC percorre o
decoder nativo e AudioTrack silenciado, sem metadados/identidade, com disputa
real de foco, liberação após inatividade, bloqueio permanente e reinício.
Builds ARMv7/x86, lint e 62 testes JVM passaram. Os assets de áudio são
compartilhados somente com o APK de instrumentação. O inventário FEATURES
foi atualizado para refletir as etapas de rede/metadados/controles já feitas.

Os runners aceitam transporte TCP local de emulador com verificação de API17
e `ro.kernel.qemu=1`, permitindo servidor ADB separado sem reiniciar conexões
das TVs. A captura alternativa usa o console local autenticado, preservando
a validação de pixels; o token não é registrado. Falhas salvam logcat local.

**Pendência nativa aberta:** a primeira regressão encerrou o processo com
`INVALID HEAP ADDRESS IN dlfree`, na thread do autoteste de streaming.
Evidências locais em `outputs/legacy-plain-audio-native-regression`.
Uma tentativa posterior perdeu ADB; outra completou streaming e falhou no
comando de captura pelo serial TCP. A rodada completa em
`outputs/legacy-plain-audio-native-final` passou, incluindo H.264, codecs de
áudio, relógio A/V e troca de display. Isso não confirma a causa nem corrige
o encerramento anterior: investigar antes de liberar produção. Nenhuma
sessão com transmissor real ou Stick físico foi certificada. O objetivo
completo permanece pendente, incluindo protocolos/player restantes,
gravação/download e segurança/licença.

## Vega — worker AAC e controles RTSP — 18 de novembro de 2026

A sessão UxPlay agora delega áudio/formato/flush/volume ao worker nativo no modo
manual Estado do port → Testar áudio AirPlay. AAC-LC estéreo/44,1 kHz é o primeiro
formato implementado. 56 testes, lint/TypeScript, teste C++ com sanitizers e três
ABIs passaram. No VVD, dois ciclos de áudio injetado no worker, flush, controles
RTSP reais, encerramento/reabertura e smoke geral passaram. Não confundir isso
com negociação completa ou recepção Apple validada: ambas continuam pendentes,
assim como outros codecs, vídeo e A/V. Evidências e próximo passo em
[VEGA_HANDOFF.md](VEGA_HANDOFF.md). A meta completa permanece ativa.


## Alvo antigo — foco de áudio independente dos controles de música

Espelhamento mantém o foco de áudio sem registrar os botões/capa de música.
Trocar de modo não restaura o ganho nem descarta uma perda de foco. Ducking
reduz o ganho a 20%; perda transitória silencia e permite o retorno pelo
Android. Perda permanente mantém o silêncio mesmo se o Android antigo
devolver GAIN; exige nova solicitação explícita. Durante espelhamento não
envia PAUSE ao transmissor de música. O botão da tela e Play/Pause consideram
o bloqueio local, permitindo recuperar o som com uma única ação.

As duas edições passaram em API17/1280×720: geral em
`outputs/legacy-mirror-focus-general-fixed`, regional em
`outputs/legacy-mirror-focus-regional-final`. O roteiro disputa foco real
durante três ciclos sem Surface, verifica ausência de comandos de música,
manutenção do silêncio após trocar de modo e recuperação por uma única
tecla Play/Pause enviada pelo host. Também repete metadados, RTSP/DNS/HTTP,
retorno pelo launcher e limpeza ao parar. Builds ARMv7/x86, lint e 62 testes
JVM passaram nas duas edições; logos e auditoria pública sem achados.
O primeiro teste encontrou o retorno indevido do som após perda permanente;
a correção passou na rodada completa. Os testes usam pacotes sintéticos,
não um transmissor real. APKs temporários removidos e emulador encerrado.

A etapa de foco está concluída no código e nos testes. O objetivo completo continua pendente:
transições com vídeo real e validação física, foco no áudio sem metadados,
demais protocolos/player, gravação/download e segurança/licença de produção.
As prévias permanecem debug e não são artefatos prontos para a loja.

## Alvo antigo — isolamento entre música e espelhamento

O modo de espelhamento agora começa no recebimento do vídeo codificado,
independentemente de existir Surface ou quadro renderizado. O estado visual
de vídeo continua separado. Ao entrar no modo, a fila de metadados é
invalidada, capa/faixa/progresso são limpos e pacotes de música são ignorados
até o reset nativo. O serviço retira o cliente do Android e os controles de
música nesse intervalo. O reset permite novos metadados sem restaurar a
faixa anterior nem reiniciar o receptor. Eventos de metadados e mudança de
modo passam pela mesma trava na fila de callbacks.

`--system-media` passou em `outputs/legacy-mirror-general-tv` e
`outputs/legacy-mirror-regional-tv`, em API17 com display 1280×720. Cada edição
exercitou três ciclos de pacote de vídeo sintético sem tela, TEARDOWN RTSP
real e metadados novos, preservando a instância/porta/endereço do receptor.
`return-ui.xml` confirma a faixa nova ao voltar pelo launcher. Builds
ARMv7/x86, lint e 62 testes JVM passaram; a regressão com H.264 e áudio
sintéticos passou em `outputs/legacy-mirror-native-regression`.
As primeiras execuções usavam o display 640×320 padrão do AVD; a conferência
da tela encontrou o título fora da área visível pela rolagem. O roteiro agora
exige formato de TV antes de instalar. Os hashes da cópia usada durante o
lint foram conferidos contra a exportação final. APKs temporários removidos;
nenhuma TV física usada. Logos e auditoria pública continuam aprovadas.

Isso comprova o isolamento de modos pelos callbacks e o retorno à interface;
não certifica uma sessão real de espelhamento com transmissor Apple.
Permanecem no objetivo transições com
vídeo real e validação física, demais protocolos/player, gravação/download e
segurança/licença de produção. As prévias continuam debug, não publicáveis.

## Alvo antigo — cliente de mídia do Android

O serviço API17 publica faixa e capa em `RemoteControlClient` e recebe
teclas pelo Android também em segundo plano. A capa do sistema é uma cópia;
a imagem da UI permanece válida após a liberação. Activity e receptor privado
compartilham o mapeamento de teclas. A perda de foco silencia e pede pausa;
ducking aplica ganho de 20%. O retorno do foco não dispara Play automático,
e Play explícito recupera foco. Parada e vídeo retiram o cliente; eventos
antigos não controlam uma nova ativação.

`tools/test_legacy_gestures.py --system-media` passou na geral em
`outputs/legacy-system-media-general-final` e na regional em
`outputs/legacy-system-media-regional-checked`: metadados/capa reais no
cliente da plataforma, disputas de foco, comandos HTTP após teclas enviadas
pelo host com o app em segundo plano, retorno pelo launcher e encerramento
sem religar por Play. Builds ARMv7/x86, lint e 60 testes JVM passaram nas duas.
A regressão de áudio/vídeo e display passou em
`outputs/legacy-system-media-native-regression`. Nenhuma TV física foi usada;
os APKs temporários foram removidos. Detalhes e falhas iniciais do roteiro
estão em [TESTING.md](TESTING.md). Logos aprovadas preservadas e auditoria de
dados privados atual/histórica sem achados.

A meta completa continua ativa: alternância áudio/espelhamento e recuperação
dos modos, demais protocolos/player, gravação/download, segurança/licença de
produção e validação física ainda precisam avançar. São prévias debug API17,
com IDs separados, não APKs prontos para publicar. Vega continua no outro PC.

## Vega — ponto pausado: áudio MSSE nativo

A saída AAC pelo SDK nativo passou em dois ciclos no VVD ARM64: 376 frames por
ciclo, relógio até aproximadamente 7,95 s e liberação confirmada. Três ABIs
compilaram e passaram a validação. O sink ainda não está conectado ao UxPlay;
vídeo, NTP/A-V, outros codecs e validação física continuam pendentes. Runner,
comando e evidências em [VEGA_HANDOFF.md](VEGA_HANDOFF.md). Henrique pediu pausa
e commit/push neste ponto. Não declarar o app finalizado.


## Vega — seleção do menu e foco por toque — 16 de novembro de 2026

O menu permanece nas páginas de consulta/configurações, com seleção semântica
independente do foco e restauração do último item ao voltar ao início. Botões
compartilham foco nativo na ativação concluída, inclusive por toque. 53 testes,
lint sem avisos, TypeScript, três ABIs e smoke real ARM64 passaram. Toques reais
e revisão visual ainda estão pendentes; detalhes e evidências em
[VEGA_HANDOFF.md](VEGA_HANDOFF.md). Próxima etapa: continuar saída de áudio/vídeo
AirPlay e demais comportamentos do Fire OS, preservando a meta completa.

## Alvo antigo — metadados da reprodução

Título, artista, álbum, capa e progresso agora chegam pelos callbacks nativos
à interface API17. A capa mantém a proporção e é reduzida fora da UI; a fila
coalesce atualizações e invalida trabalho antigo ao trocar de transmissor.
O relógio trata rollover, pausa e limites. Dados inválidos preservam a última
faixa/capa válida. O anúncio inclui os três tipos de metadados; as capacidades
de áudio comprimido e as restrições existentes continuam separadas.

O teste `tools/test_legacy_gestures.py --metadata` combina controles reais de
RTSP/DNS-SD/HTTP com pacotes **sintéticos** de metadados, capa e progresso
entregues pelo worker C. Passou nas duas edições em
`outputs/legacy-metadata-general-final-checked` e `outputs/legacy-metadata-regional-final`.
Os 60 testes JVM do alvo antigo, builds ARMv7/x86 e lint passaram nas duas.
Regressão de áudio/vídeo, descoberta e display passou em
`outputs/legacy-metadata-native-regression`; gestos regionais também passaram.
A interface completa passou em inglês/árabe em
`outputs/legacy-metadata-general-ui-verified`, incluindo o Sobre e perfis.
Corrigidos os literais Java corrompidos e a sintaxe de rolagem dos scripts no
Android antigo. O modo `--about-only` salva também a hierarquia XML para revisão.
[Prévia renderizada das Views nativas](images/fireos3-native-metadata.png),
inspecionada visualmente; não é captura de vídeo/Surface nem de uma TV física.
APKs temporários removidos após os testes. Detalhes em [TESTING.md](TESTING.md).

A meta completa continua ativa: faltam integração com o cliente de mídia do
sistema antigo e alternância áudio/espelhamento, os demais protocolos/player,
gravação/download, segurança/licenciamento de produção e validação física.
Os APKs 0.6.7-legacy-preview continuam debug, com IDs separados da publicação.

## Alvo antigo — comandos ao transmissor

O receptor API17 recebe a identificação DACP do UxPlay, resolve o transmissor
na descoberta da sessão e envia os nove comandos por HTTP fora da UI. A tela
mostra anterior, pausa/reprodução, próxima e volume após resolver o endpoint;
as teclas de mídia também funcionam. Credenciais e respostas ficam vinculadas
à sessão. Fila limitada, cancelamento, timeouts, erros e redirecionamentos são
tratados; uma resposta atrasada não desfaz um encerramento nativo posterior.

O teste completo da geral passou em `outputs/legacy-dacp-focus-verified`, com
identificação RTSP nativa, DNS-SD/HTTP reais, toque/foco, teclas, erro HTTP e
troca entre dois transmissores de teste. A captura de rede encontrou o anúncio
tardio que invalidava uma consulta única; há agora até três consultas limitadas.
O mesmo teste encontrou perda de foco ao tocar Parar: esse clique passou a
atualizar os widgets existentes. A regressão de áudio/vídeo passou em
`outputs/legacy-dacp-native-regression`, e a de gestos da geral passou em
`outputs/legacy-dacp-general-gestures`. 55 testes JVM do alvo antigo passaram.
Na regional, os mesmos controles e gestos passaram em
`outputs/legacy-dacp-regional-verified` e `outputs/legacy-dacp-regional-gestures`.
Build/lint e os 55 testes passaram também nessa edição. As rodadas removeram
seus APKs temporários, sem usar TVs físicas.
Detalhes, comandos e tentativas anteriores em [TESTING.md](TESTING.md).

A meta de paridade continua ativa. Ainda faltam metadados/capa e integração
com a sessão de mídia do sistema antigo, reprodução/protocolos restantes,
gravação/download, segurança/licença de produção e testes físicos. Os APKs
continuam prévias debug com IDs separados; não publicar como versão pronta.

## Alvo antigo — recuperação de rede

O serviço API17 aguarda uma interface local antes de iniciar, observa mudanças
fora da UI e recria o receptor/descoberta quando o endereço muda, preservando
a identidade. Parar cancela a recuperação, inclusive offline; a preferência
de recuperação pode exigir reinício manual. Falhas têm tentativas limitadas,
sem renovar o limite por sinais repetidos. O monitor para junto da solicitação;
a espera sem rede não mantém o bloqueio de CPU do receptor.

`tools/test_legacy_network.py` passou seus sete cenários em ambas as edições:
`outputs/legacy-network-general-final/report.json` e
`outputs/legacy-network-regional-final/report.json`. Usou troca real de IP e
interface do emulador, RTSP e DNS-SD em outro processo, sem TVs físicas. A
primeira execução encontrou o sufixo ligado/desligado no seletor do roteiro;
o script passou após reconhecer esse texto. Builds ARMv7/x86, lint e 48 testes
JVM do alvo antigo passaram. A primeira build regional encontrou testes de
metadados DLNA em alteração simultânea; a rodada final passou com a revisão
atualizada. Consulte [TESTING.md](TESTING.md) para o contrato e comando.

A regressão final passou em dois ciclos de DNS/RTSP, áudio/vídeo e trocas de
display; toque/DPAD durante reinícios passou nas duas edições. O ciclo de vida
também passou, incluindo morte do processo e dois reboots reais. Relatórios
`outputs/legacy-network-runtime-regression`, `outputs/legacy-network-*-gestures`
e `outputs/legacy-network-service-regression`. Uma tentativa anterior do
runtime perdeu o daemon ADB; o runner preserva a falha, e a repetição completa
passou. Os APKs temporários foram removidos após os testes.

O alvo ainda é uma prévia debug 0.6.7, com IDs separados dos apps publicados.
Faltam controles, protocolos/reprodução de mídia, gravação/download, aprovação
e licenciamento de produção, além de interoperabilidade e hardware antigo.
A meta continua sendo paridade completa, nas edições geral e sem download;
essa etapa não autoriza publicar a prévia como aplicativo pronto.

## Vega — AirPlay por URL no player — 16 de novembro de 2026

O caminho UxPlay → fila nativa → player → playback-info passou no VVD ARM64 com
MP4 HTTP: posição inicial com relógio avançando, pausa, busca, retomada, Stop e
liberação ao Voltar. Teste manual na página Estado do port; sem descoberta ou
espelhamento AirPlay. Corrigido também carregamento sem cabeçalhos no SDK.
51 testes Jest, lint/TypeScript, builds das três ABIs, regressão FCast MP4 e
smoke completo passaram. Evidências e
limites em [VEGA_HANDOFF.md](VEGA_HANDOFF.md). A meta completa segue pendente.


## Vega — sessão AirPlay nativa — 16 de novembro de 2026

UxPlay agora tem classe de sessão C++ com fila limitada de callbacks e ciclo de
vida. OPTIONS RTSP e Play HTTP chegaram ao callback em dois ciclos no sandbox;
smoke completo ARM64 passou. Ainda não há reprodução ou anúncio AirPlay.
Paridade inclui também seleção persistente e toque do Fire OS atual, conforme
reafirmado por Henrique. Próximos passos em [VEGA_HANDOFF.md](VEGA_HANDOFF.md).


## Vega — UxPlay isolado — 16 de novembro de 2026

O build Vega prepara uma cópia UxPlay do commit fixado, com os 11 patches Buzin,
independente das alterações do submódulo Android. Testes de preparação/cache,
lint/TypeScript, 49 testes, builds das três ABIs e smoke completo ARM64 no VVD
passaram. A sessão receptora AirPlay e sua saída de mídia ainda precisam ser integradas. Detalhes em
[VEGA_HANDOFF.md](VEGA_HANDOFF.md).


## Vega — HLS e DASH locais — 16 de novembro de 2026

As duas fixtures VOD passaram individualmente no VVD ARM64 via FCast e
Shaka/MSE, incluindo controles e encerramento. A etapa final também passou,
exigindo cabeçalhos no manifesto e em cada segmento. 49 testes Jest e os builds
das três ABIs passaram. Gerador local e comandos em
[VEGA_HANDOFF.md](VEGA_HANDOFF.md). Isso não comprova live, DRM, ABR ou hardware
físico; o escopo completo do port continua ativo.


## Vega — adaptador MSE candidato — 16 de novembro de 2026

Shaka passou a integrar a preparação do bundle e há adaptador próprio ligado ao
ciclo de vida da sessão, com cabeçalhos e destruição idempotente. 47 testes,
lint/TypeScript e builds das três ABIs passaram; FCast MP4 continuou passando no
VVD ARM64. HLS/DASH seguem bloqueados no protocolo até validar MSE com fixtures
locais. Próxima etapa detalhada em [VEGA_HANDOFF.md](VEGA_HANDOFF.md).


## Vega — preparação HLS/DASH — 16 de novembro de 2026

Shaka 4.16.13 com os 33 patches oficiais Amazon compilado localmente, com fonte,
árvore e artefatos fixados por hashes. Reexecução pelo script
`tools/prepare_vega_shaka.py --build` passou. Artefatos em `build/vega-adaptive`,
fora do Git. HLS/DASH ainda não estão conectados ao app; integração MSE e testes
locais no VVD são a próxima etapa. Detalhes em [VEGA_HANDOFF.md](VEGA_HANDOFF.md).


## Vega — nome e FCast — 16 de novembro de 2026

Nome do receptor editável em Configurações e persistido no sandbox; aplicado
no próximo anúncio FCast. 45 testes Jest, testes nativos de persistência e
DNS-SD, lint, TypeScript e builds das três ABIs passaram. Regressão FCast real
no VVD ARM64 passou com HTTP/cabeçalhos e todos os controles do subconjunto v2.
Edição pelo teclado Vega e descoberta personalizada na rede física ainda
pendentes. Escopo completo e próximos recursos em [VEGA_HANDOFF.md](VEGA_HANDOFF.md).

## Alvo antigo — interface nativa em desenvolvimento

- O backend API17 foi extraído para `fireos3-runtime`, compartilhado pela
  prova `fireos3-probe` e pelo novo frontend `fireos-legacy`. O núcleo moderno
  continua separado. JNI, filas, descoberta, áudio e vídeo mantêm os mesmos
  caminhos da prova; nome/porta agora são opções reais de sessão.
- Frontend em Views da plataforma, sem Compose/Media3: início, Ferramentas,
  configurações, diagnóstico avançado e Sobre, com toque/controle, seleção
  persistente do menu e foco preso ao modal. O Sobre é desenhado na própria
  Activity para evitar cantos pretos que ocorreram com Dialog na API17.
- Strings dos 22 idiomas e launcher/banner vêm diretamente dos recursos do
  app atual durante o build. Não há novas versões das logos ou traduções
  duplicadas. O rodapé usa o ano atual e `Buzin Solutions LTDA`.
- Prévias isoladas `com.buzincast.tv.legacy.preview` e
  `com.buzincast.tv.deat.legacy.preview`, versão `0.6.7-legacy-preview` e
  sufixo `-nodownload` na regional. São debug, não assinadas para produção,
  sem integração da licença Amazon. Não enviar esses APKs à loja.
- Início/parada, nome/porta, autostart ao abrir e manter a tela durante vídeo
  têm implementação real. Diagnóstico usa a sessão atual. A posse do receptor
  agora é do `LegacyReceiverService`, com background/boot descritos abaixo.
- Regressão após extração: 33 testes JVM (32 runtime + um fixture no probe),
  build ARMv7/x86 e lint passaram. Os dois ciclos completos do receptor
  API17 passaram em `outputs/fireos3-shared-runtime-checks/report.json`.
  Teste inicial da nova interface passou, incluindo teclado, nome/porta
  confirmados via RTSP, parada, toque/D-pad e captura do Sobre sem cantos
  pretos. Rodada completa e regional registradas em `docs/TESTING.md`.
  Os 22 idiomas foram conferidos em duas rodadas; o hebraico precisou do
  qualificador histórico `iw` na API17 e passou após a correção. A regional
  passou em alemão/inglês, com controle RTSP real. Build moderno x86_64,
  lint e núcleo também passaram. Capturas públicas em
  `docs/images/fireos3-native-*.png`; nenhuma TV física foi controlada.
- Script público `tools/test_legacy_app.py`: sem `--locales` percorre todos
  os idiomas; com lista, somente os informados. Recusa aparelhos físicos,
  usa IDs de prévia permitidos e limpa instalação/túneis/XML ao terminar.
- Calibração agora integrada, sem abrir outra Activity/parar o receptor.
  Usa `DisplayFit` compartilhado para as quatro pontas e o transform do
  vídeo; `tv_margin` de 0–10% só é salvo com confirmação. Controle e toque
  disponíveis, com rascunho no estado salvo. O teste mede as pontas por pixels,
  confere limites, OK/Salvar/Cancelar/Voltar, preferência em disco e o mesmo
  socket RTSP antes/durante/depois. Inglês, português e árabe passaram em
  `outputs/legacy-calibration-ime-verified/report.json`. Corrigido o teclado
  de extração API17 que podia reaparecer após editar nome/porta. Capturas
  exigem dois quadros iguais. Builds ARMv7/x86, lint, 33 testes do runtime/probe
  e 145 testes do domínio compartilhado passaram.
  A edição sem download repetiu o cenário completo em alemão e passou em
  `outputs/legacy-calibration-nodownload-verified/report.json`. Prévias
  removidas, emulador próprio fechado, nenhuma TV física usada. Captura
  pública: `docs/images/fireos3-native-calibration.png`.
- Perfis ligados ao `QualityProfiles` compartilhado: mesmos nomes persistidos,
  edição atômica de resolução/FPS e buffers, seguida de um reinício. JNI aplica
  os modos ao plist; áudio usa buffer fixo/adaptativo e percentil efetivos.
  AFTM anterior à API21 usa modos AVC publicados pela Amazon; APIs novas
  consultam o codec. Emulador API17 debug usa 720p/30. Validação física dos
  modos anunciados no firmware original continua pendente.
  Dois ciclos finais passaram em `outputs/legacy-profiles-final-verified`,
  incluindo buffer real de 25 ms, três políticas nos quatro codecs,
  anúncios 1080p/30 e 720p/60 e espera/descarte/flush/retomada de áudio.
  A mídia sintética pequena não comprova desempenho físico nesses modos.
  O relógio AudioTrack agora preserva a referência ao reler um contador
  sem avanço; previsão fica dentro do PCM aceito e expira após 250 ms
  sem atualização. Lacunas curtas de metadados aguardam até 100 ms, sem
  relaxar os limites A/V. Fila limitada a 16 quadros e 4 MiB, para acomodar
  250 ms a 60 fps. A fixture envia áudio em tempo real sem criar uma thread
  nativa por pacote; testes separados de callbacks nativos permanecem.
  Relatórios das falhas anteriores de clock e ADB foram preservados.
  UI passou em inglês/português/árabe e regional em alemão, incluindo os
  quatro perfis, preferências, `/info`, nome/porta e calibração. Builds
  ARMv7/x86, lint, 41 testes do alvo antigo, 34 do núcleo e 181 do domínio
  aprovados. Captura: `docs/images/fireos3-native-profiles.png`.
  APKs finais repetiram o roteiro completo de UI em português e alemão:
  `outputs/legacy-profiles-packaged-normal-private-retry` e
  `outputs/legacy-profiles-packaged-regional-private`, ambos aprovados e
  desinstalados. Nova opção `--adb-server-port` isola o capturador dos testes
  concorrentes. O emulador próprio foi encerrado; nenhuma TV foi usada.

- Troca da saída de vídeo implementada em `setVideoSurface`: o worker só
  libera a Surface/SurfaceTexture anterior depois de soltar o codec. Alvos
  pendentes substituídos e ofertas após encerramento também são liberados.
  Na API17, recria o codec, conserva SPS/PPS nas trocas de display e aguarda
  IDR; reset do stream limpa a configuração. Sem display, drena vídeo e
  mantém áudio/conexão. O ensaio verifica duas trocas com áudio avançando,
  mesmo socket RTSP e atualizações reais da TextureView na retomada, usando
  IDRs sem novos SPS/PPS. Relatório diagnóstico aprovado em
  `outputs/legacy-surface-diagnostic`; a tentativa isolada inicial que não
  retomou a tela está preservada em `outputs/legacy-surface-regression`.
  Esse marco validou apenas a base de renderização; a integração ao serviço
  foi feita na etapa seguinte, descrita abaixo.
  A rodada final passou em `outputs/legacy-surface-headless-final/report.json`:
  dois ciclos com duas sessões cada, iniciando RTSP/descoberta/áudio sem display
  antes da primeira Surface e repetindo as retiradas/trocas. Passaram 45 testes
  do alvo antigo (41 runtime, um probe, três frontend), builds ARMv7/x86 e lint
  do probe e das duas edições. Prévia removida e emulador próprio encerrado;
  nenhuma TV física foi controlada. A integração seguinte transfere a posse
  da sessão ao serviço, mantendo a mesma saída de vídeo validada.

- Serviço integrado: a Activity observa o estado e oferece/retira a Surface,
  sem ser dona do receptor. Home/Voltar/nova Activity mantêm o mesmo socket
  RTSP quando background está ligado. Ao desligar a opção, encerra ao sair e
  retoma ao voltar; Parar explícito é preservado no retorno. Boot é opt-in e
  inicia sem Activity. Serviço/receiver privados; foreground do tipo
  connectedDevice, notificação com a marca e wake lock parcial limitado à
  sessão. `TextureTarget` retém cada textura até view e decoder a soltarem.
  A retomada `START_STICKY` exige novo processo respondendo sem abrir UI;
  comandos explícitos já recebidos prevalecem sobre uma entrega nula tardia.
  O cenário completo geral passou em português em
  `outputs/legacy-service-ui-retry`, e o APK final repetiu ciclo de vida,
  morte do processo e dois reboots em `outputs/legacy-service-focused-final`.
  Uma primeira execução teve falha ADB; outra rodada regional encontrou o
  input do próprio emulador congelado após reboot. Relatórios preservados.
  O capturador agora fixa rotação de TV e aguarda input pronto/descongelado.
  `--lifecycle-checks` inclui essa bateria no primeiro idioma;
  `--lifecycle-only` executa apenas serviço/recuperação (inglês ou lista).
  Builds ARMv7/x86, lint e os 45 testes JVM do alvo passaram nas duas edições.
  Captura pública: `docs/images/fireos3-native-service-settings.png`.
  Uma rodada regional posterior encontrou toque perdido durante reconstruções
  de menu por callbacks do serviço. Atualizações assíncronas agora preservam
  controles/foco e alteram só status/diagnóstico. A regressão instrumentada
  `LegacyGestureChecks` mantém toque e OK pressionados durante um stop/start
  nativo, observa a troca real de sockets RTSP e confere a navegação após UP.
  Geral aprovada em `outputs/legacy-service-gesture-platform-final`. Build com
  `-Instrumentation`; execução por `tools/test_legacy_gestures.py`. O runner
  API17 usa Instrumentation da plataforma e o multidex do app, sem expor hooks
  no produto; JSON inclui hashes e confirma desinstalação de app e teste.
  A regional também passou nessa regressão em
  `outputs/legacy-service-regional-gesture-final`. A geral repetiu a bateria
  completa após a correção em `outputs/legacy-service-stable-final`, aprovada:
  processo/boot/background, perfis, nome/porta e calibração.
  Regional completa aprovada em alemão/árabe em
  `outputs/legacy-service-regional-stable-final`. Revisão visual corrigiu a
  ordem LTR da versão em árabe e respiro relativo do logo. Captura pública:
  `docs/images/fireos3-native-service-rtl-about.png`.
  Os APKs exportados após isso passaram novamente nos gestos:
  `outputs/legacy-service-general-export-gestures` e
  `outputs/legacy-service-regional-export-gestures`.
  O helper preserva as edições em `outputs/fireos/legacy-preview` e
  `outputs/fireos/legacy-preview-nodownload`, com APK, teste opcional e
  `artifact.json` (ID/versão/hashes). Confirmado por build sequencial e hashes;
  o Gradle podia remover os APKs antigos ao alternar o buildDirectory.
  A rodada árabe após o ajuste visual passou em
  `outputs/legacy-service-rtl-visual-verified` (inclusive os quatro perfis e
  calibração). Cópias exportadas conferidas com os hashes dos relatórios de
  gestos. Prévias/testes removidos, emulador próprio encerrado; nenhuma TV usada.



- Ainda faltam controles completos, DLNA/FCast/Cast V2,
  URL/HLS/fotos/legendas, gravação/download/retransmissão na edição geral,
  recuperação de rede, aprovação/PIN/DACP, licença/assinatura e validação
  com emissores reais e Stick antigo. A meta permanece o mesmo app/funções
  em todos os Fire Sticks com Fire OS; não considerar esta prévia paridade.
  Próximo marco: recuperação de rede em background e configurações/controles
  avançados, antes dos demais protocolos. A validação física de background,
  boot e reprodução no firmware antigo continua pendente.

## Fichas Amazon, traduções e artefatos atuais

- Geral: mídia corrigida e reenvio concluído; status `SUBMITTED`, versão
  0.6.6 (principal código 42; compatível código 40),
  com sueco. Release `amzn1.devportal.apprelease.5f0aca6e049d405994dee3ab957a1512`.
  A versão 0.6.4 continua publicada até aprovação da atualização.
- DE/AT: mantém os APKs 0.6.5-nodownload (39) e
  0.6.5-compat-nodownload (38), com a chave pública da ficha regional.
  Capturas corrigidas e reenvio concluído; status `SUBMITTED`.
  Ficha em alemão e inglês US obrigatório, sem adicionar
  sueco à descrição regional.
- Cobertura configurada no console: 232 países/territórios no geral,
  excluindo Alemanha e Áustria; os dois países somente na edição regional.
  Juntas cobrem as 234 opções oferecidas pelo console nesta conferência.
  Isso não garante disponibilidade onde a Amazon não distribui apps ou
  suporte a Vega/firmwares históricos ainda não implementados.
- Geral: 15 localizações de descrição completas; 11 localizações de imagens
  completas. Os textos foram relidos no console, sem “AirPlay” nos campos
  da ficha. Cópias locais em `store/amazon/listing.*.json`.
- App: 22 idiomas próprios, com 304 strings traduzíveis presentes em cada
  tradução. Sueco adicionado em `values-sv`; placeholders conferidos. A
  lista fixa “Language Support” do console só oferece os 21 itens antigos;
  sueco está no APK e na descrição sueca, mas não existe checkbox sueco.
- Capturas definitivas em `store/amazon/images/screenshots/normal` e
  `store/amazon/images/screenshots/deat`. Início, configurações e Sobre,
  sem Diagnóstico visível. O conjunto sueco é validação adicional local;
  não há localização sueca de imagens no console. Logos aprovadas preservadas.
- Quarto: execução automática concluída, com 54 capturas (36 de idiomas do
  geral, seis regionais e 12 das quatro variantes finais). Produção 0.6.6
  reinstalada e usuário retomou a TV. Cozinha: uso autorizado posteriormente
  para refazer seis capturas regionais com identificação 0.6.5; auxiliar
  removido e instalação principal preservada. As duas TVs estão liberadas;
  não voltar a controlá-las sem coordenar com Henrique. Sala não foi usada.
- Seleção nova: sete Sticks na principal e dois na compatível, sem tablets,
  Automotive ou TVs integradas. Uma Xiaomi indevidamente selecionada na
  ficha geral anterior foi retirada da próxima versão. Não criar regras
  amplas de inclusão de novos dispositivos; conferir modelos a cada envio.
- APKs locais em `outputs/buzin-cast-0.6.6.apk` e
  `outputs/buzin-cast-0.6.6-compat.apk`, assinados para produção. Builds e
  lint vital passaram. ZIP, certificado, chave pública e preservação das
  classes Amazon conferidos; código temporário de captura ausente nos
  binários finais. A validação de licença pela Appstore continua dependendo
  da instalação oficial/LAT e da análise da Amazon.
- Os dois reenvios foram concluídos após a correção da mídia, sob autorização
  de Henrique. Notas da 0.6.6 completas nas 15 localizações. As duas páginas
  confirmaram `SUBMITTED`, com estimativa de 20/nov/2026 às 20:00 PST.
  O histórico da Amazon registra a submissão às 19:57 GMT-8 de 15/nov
  (16/nov no Brasil). Isso confirma o envio, não aprovação/publicação.
  Provas locais ignoradas: `outputs/amazon-normal-submitted-final.jpg` e
  `outputs/amazon-deat-submitted-final.jpg`. DRM Yes nos quatro APKs;
  mantidos os sete Sticks da principal e dois da compatível, sem outros tipos.
- Conferência final de mídia: 13 conjuntos completos (11 geral + 2 DE/AT),
  78 posições de capturas e 52 posições de artes. Dimensões e ordem
  conferidas após reabrir as fichas. O ícone de 114 px foi alinhado em
  todos os conjuntos. As quatro artes canônicas de cada ficha foram
  exportadas do console e coincidem pixel a pixel com os arquivos locais.
  English (U.K.) foi recriado e conferido após a confirmação de remoção.
- A primeira revisão visual falhou: algumas capturas ainda tinham abertura
  sobreposta ou conteúdo em transição. Agora as 42 fontes foram refeitas
  esperando a renderização, usando foco real do controle e verificação de
  quadros, menu completo, brilho e dimensão. Conferência visual das folhas
  de contato concluída, sem novas falhas. O Sobre mantém foco em Fechar;
  a borda do menu ao fundo fica para a próxima versão, junto do toque.
  As 42 capturas locais definitivas são RGB 1920×1080. As 15 descrições,
  recursos, palavras-chave e notas gerais coincidem com os JSONs do Git;
  os dois textos regionais estão em `store/amazon/deat`.
- Limpeza no quarto: cópia temporária de captura `com.buzincast.tv.deat`
  0.6.6/código 43 removida enquanto estava inativa. Permanece instalada a
  edição principal final de produção 0.6.6/código 42.
- Scripts públicos em `tools/FIRESTICK_TOOLS.md`: ações de ADB, capturas,
  cache, auditoria de privacidade e etapas de mídia da Amazon pelo CUA.
  `tools/firesticks.local.json` contém os três apelidos/IPs, é ignorado e
  não pode ser commitado. Sem lista de idiomas, o capturador seleciona todos
  os idiomas próprios e variantes da loja; uma lista limita as capturas.
- Privacidade: IPs domésticos e caminhos pessoais encontrados em três
  documentos antigos. Arquivos atuais sanitizados; buscas por padrões de
  chaves privadas/tokens não acharam ocorrências. O PEM do app é público.
  Henrique autorizou reescrever os dois branches publicados. Atualização
  atômica com leases concluída no GitHub: main sanitizado em `8b6cb28` e
  codex/privacy-de-at em `5ab9365beac0db74b23243f7a4e5f4b8eb5ef2b8`.
  As árvores dos arquivos atuais foram comparadas antes da troca e eram
  idênticas; auditoria de arquivos e histórico passou depois do push. Bundle
  privado de recuperação em área ignorada. Não usar push --mirror. O checkout
  do Mac precisa de sincronização preservando trabalho local antes de novo
  push; não mesclar automaticamente o histórico antigo de volta.
  Política regional atualizada no console para o commit sanitizado acima e
  confirmada na revisão antes do envio.

## Registro anterior — 5 de novembro de 2026

**9/nov — Vega:** ambiente preparado neste Mac e alvo ampliado para Vega OS 1.0
a 2.0. Ver [VEGA_HANDOFF.md](VEGA_HANDOFF.md) e
[VEGA_OS_VERSIONS.md](VEGA_OS_VERSIONS.md).

## Tarefas futuras registradas por Henrique

- [~] **16/nov — próxima versão 0.6.7 iniciada a pedido de Henrique:** toque,
  borda persistente no destino ativo do menu (inclusive atrás do Sobre) e
  continuidade do alvo Fire OS 3 do primeiro Stick. Códigos reservados:
  compat 44, compat sem download 45, principal 46 e principal sem download 47.
  Os envios Amazon 0.6.6/0.6.5 permanecem intactos; não publicar o protótipo.
  A prova API 17 encaminha vídeo da thread nativa para uma fila Java limitada
  e MediaCodec. Teste sintético com imagem passou em dois ciclos no emulador;
  A etapa seguinte já acrescentou descoberta e player persistente (ver abaixo);
  sessão real, áudio e paridade funcional ainda pendentes.
  Ver comandos e limites em `docs/TESTING.md`.
  Toque e borda persistente implementados e conferidos na fixture de TV da
  API 28: menu, perfil, controle após toque, conteúdo e Sobre. Relatório e
  capturas em `outputs/focus-navigation-tv`. As TVs físicas não foram usadas.
  Builds debug principal/API 25 e compilação compat/API 22 passaram; lint
  passou nas duas edições. A prova API 17 compilou ARMv7/x86 com lint e cinco
  testes da fila; a reprodução sintética foi repetida em dois ciclos.
  Núcleo: 25 testes e gate de cobertura passaram após corrigir a substituição
  de logs no Windows. Android usa explicitamente rename POSIX; o backend JVM
  usa movimentação atômica e não introduz NIO no caminho Android antigo.
  **Avanço seguinte — Fire OS 3:** protótipo isolado 0.2/código 2 com JmDNS
  3.6.5, adaptação UTF-8 para API 17, desugaring/MultiDex e anúncios ligados
  ao TXT/porta/chave pública do receptor nativo. Modo persistente H.264 com
  MediaCodec Java configurado pelo SPS/PPS do fluxo, sem áudio/HLS/fotos/H.265.
  Somente IPv4 privado com multicast; admissão na sub-rede ou loopback.
  Dois ciclos passaram na API 17: mDNS por socket multicast separado,
  RTSP OPTIONS e /info, vídeo sintético pela ponte real, parar/reiniciar e
  fechar a porta TCP. Dezesseis testes unitários e lint passaram; ARMv7/x86
  compilados. Relatório em `outputs/fireos3-streaming-checks/report.json`.
  O script aceita `--cycles` e remove o pacote de teste ao terminar, salvo
  `--keep-installed`. TVs físicas e fichas Amazon não foram alteradas.
  **Avanço seguinte — protótipo 0.3/código 3:** `FfmpegDecoder` e
  `TimelineBuffer` compartilhados com a produção, compilados para API 17
  sem `libmediandk`/Oboe. Ponte JNI com pacotes limitados e padding FFmpeg,
  leitura PCM e fechamento serializados. Quatro fixtures próprias: ALAC/352,
  AAC-LC/1024 e AAC-ELD/480 e /512; gerador reproduzível com FDK AAC somente
  no host, fora do APK. Dois ciclos completos passaram na API 17 x86, cada
  um repetindo o teste de áudio e a regressão mDNS/RTSP/vídeo/início/parada.
  PCM não nulo, amplitude e avanço do AudioTrack silenciado conferidos.
  ARMv7/x86, lint e os 16 testes JVM passaram. Dependências ELF das duas
  ABIs conferidas sem `libmediandk` ou Oboe. Relatório local ignorado em
  `outputs/fireos3-audio-checks/report.json`; prova pública em
  `docs/images/fireos3-audio-probe.png`. Pacote removido e emulador fechado;
  nenhuma TV física usada. O receptor de rede ainda anuncia somente vídeo.
  **Avanço seguinte — protótipo 0.4/código 4:** áudio do receptor ligado a
  uma fila de 64 pacotes/256 KiB e ao worker AudioTrack/FFmpeg; formato,
  volume, consulta de volume e flush encaminhados por JNI. Gerações isolam
  formatos e descartam áudio antigo. A saída é liberada por flush/stop e
  após dois segundos sem entrada; novos pacotes a recriam. Stop idempotente.
  Anúncios `_raop`/ALAC/AAC ativados somente na sessão com player, sem PCM
  bruto ou metadados. O perfil padrão do app moderno permanece preservado.
  Autoteste: mesmos callbacks registrados invocados em pthreads, quatro
  formatos, avanço real do PCM, volume SET/GET_PARAMETER por RTSP, anúncios
  multicast dos dois serviços, limpeza e retomada. O teste é silenciado.
  Dois ciclos completos passaram na API 17 x86 com duas baterias de
  streaming por ciclo. ARMv7/x86, lint e 24 testes JVM aprovados; build
  moderno x86_64/lint/núcleo também aprovados. Relatório ignorado em
  `outputs/fireos3-live-audio-verified-checks/report.json`, captura pública
  em `docs/images/fireos3-live-audio-probe.png`. Pacote removido, emulador
  fechado e nenhuma TV física usada. Nenhum APK foi publicado nesta etapa.
  Ainda não há sessão negociada/criptografada/RTP com emissor Apple real.
  **Avanço seguinte — protótipo 0.5/código 5:** relógio A/V implementado a
  partir dos intervalos PCM efetivamente aceitos/lidos, com posições de
  amostra de 64 bits, gaps/overlap/overflow/trim e separação de padding.
  `AudioPlaybackClock` relaciona NTP do PCM ao head real de AudioTrack,
  incluindo wrap de 32 bits, reset, relógio antigo e countdown para PCM
  ainda enfileirado. Nunca compara NTP Unix diretamente a nanoTime.
  O decoder H.264 recebe tags relativas com mapa limitado ao PTS original,
  espera quadros adiantados (até 250 ms) e descarta mais de 150 ms de atraso;
  sem relógio válido, continua como vídeo sozinho. Idle sem codec agora
  espera na fila por até um segundo, sem polling a cada 10 ms.
  Primeiro teste com uma imagem não gerou saída do decoder; a suíte agora
  aquece com o fluxo, usa três IDRs e exige o timestamp de origem esperado,
  espera real, entrega dentro de 25 ms no relógio de software, descarte do
  quadro 500 ms atrasado e nova geração de relógio após flush em até 200 ms.
  Dois ciclos completos na API 17 x86 passaram com duas baterias por ciclo.
  ARMv7/x86, lint, 30 testes JVM e 13 testes C++ em execução API 17 passaram;
  os C++ também compilaram para ARMv7. Build moderno x86_64/lint/núcleo passou.
  Relatório ignorado em `outputs/fireos3-sync-verified-checks/report.json`,
  prova pública em `docs/images/fireos3-avsync-probe.png`. Pacote e executável
  temporários removidos, emulador fechado, TVs físicas não usadas. Não mede
  sincronismo físico/HDMI e não equivale a uma sessão Apple negociada.
  Comando C++ público agora funciona no Windows/Linux/Mac e tem `--legacy`
  para NDK r23/API17/C++17 sem runtime de instrumentação.
  Próximo marco: migrar as telas/configurações e funções restantes para o
  alvo antigo, mantendo idiomas e as duas edições regionais; também falta
  validação com emissor real e Stick antigo. Não declarar paridade nem
  publicar este protótipo.

- [x] **0.6.7 em desenvolvimento:** borda acompanha também o item tocado e o
  destino ativo do menu mantém a borda, inclusive atrás do Sobre. O foco do
  modal permanece em Fechar. Validação automatizada e visual concluída na
  API 28; não ampliar os dispositivos suportados na ficha por causa disso.
  As capturas são reais; não editar imagens para simular indicação de foco.

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
  oficial que esse é um conjunto separado e menor do que os idiomas do
  binário. As strings do app (`values-*/strings.xml`) foram traduzidas para
  os 20 idiomas da lista de "binary supported languages" do console.
  **10/nov:** Cornish (`values-kw`) criado também, fechando os 21 idiomas da
  lista — tradução de melhor esforço, já que boa parte do jargão técnico de
  configurações (buffer, codec, bitrate, jitter, VSYNC) não tem equivalente
  atestado em córnico e ficou como empréstimo do inglês.
  **11/nov:** 103 chaves de texto cobrem fotos, legendas, perfis, calibração,
  ferramentas, gravação, retransmissão, diagnóstico e avisos de conexão e
  exportação de logs. Conferidas as chaves e os parâmetros nos 21 idiomas.
  A revisão por falante fluente de córnico continua pendente.
- [x] Preparar edições específicas para Alemanha e Áustria sem funções de
  gravação, cópia, download ou armazenamento de vídeo/áudio. Revisar a
  política e validar os binários antes da distribuição.
  **9/nov:** flag de build `-PbuzinNoDownload=true` criada, esconde o botão
  de download via `BuildConfig.VIDEO_DOWNLOAD_ENABLED`. APK de release gerado
  e assinado com sucesso. Decisão inicial foi publicar a versão padrão (com
  download) para todos os mercados, incluindo DE/AT, por não ter achado base
  jurídica para a restrição.
  **10/nov (achado e corrigido):** a própria Amazon confirma a restrição por
  escrito — [Fire TV FAQ](https://developer.amazon.com/docs/fire-tv/faq-general.html),
  pergunta "Are there any other criteria for distributing apps for Amazon
  Fire TV or Fire TV Stick in Germany and/or Austria?": não é permitido
  distribuir apps que habilitem copiar, gravar, baixar ou armazenar
  vídeo/áudio no aparelho, em cartão SD ou em armazenamento externo
  conectado, na Alemanha e/ou Áustria. A flag `-PbuzinNoDownload=true`
  só escondia o botão de download — a gravação local do AirPlay (tela
  Ferramentas: gravar, lista de gravações, reproduzir, exportar, excluir)
  continuava ativa. Corrigido com uma nova flag `BuildConfig.RECORDING_ENABLED`
  (mesma condição `!noDownloadEdition`), travando tanto a UI em
  `ToolsScreen.kt` quanto as funções do `MainViewModel.kt` por segurança
  dupla. A retransmissão ao vivo para outro Buzin Cast e a visualização de
  fotos via DLNA foram revisadas e mantidas — nenhuma das duas grava
  conteúdo em armazenamento persistente, só encaminham/exibem em memória.
  **Decisão de Henrique:** a submissão em andamento (sem essa correção)
  exclui Alemanha e Áustria da distribuição por país direto no console; a
  edição sem-download corrigida (de verdade, sem gravação) entra na próxima
  atualização, depois que a submissão atual for aprovada. **Lembrete de
  Henrique:** o version code da edição sem-download precisa ser diferente
  do da edição padrão e da compat (a Amazon não aceita APKs duplicados na
  mesma submissão) — corrigido para 33 (padrão=32, compat=31,
  sem-download=33) em `app/build.gradle.kts`.
  **10/nov:** Live App Testing criado no console (Test 1, "0.6.3 Live Test")
  para servir de base da próxima atualização (6.4): vai reunir Cornish e a
  reinclusão de Alemanha/Áustria (com a edição sem-download corrigida de
  verdade). Confirmado por Henrique: o resto das pendências (DRM manual,
  Fire TV Stick-only, etc.) fica fora do escopo desta 6.4, para depois.
  **11/nov:** a 0.6.4 tem quatro códigos exclusivos: compat com download
  e gravação (34), compat sem os dois recursos (35), principal com os
  recursos (36) e principal sem os recursos (37). As edições compat usam
  API 22 e as principais usam API 25.
- [~] Publicar somente para Fire TV Stick, contemplando todas as gerações
  já lançadas e as futuras. Fire OS confirmado em Sticks físicos: **3, 5,
  6, 7 e 8**. O Stick de 2014 recebeu atualização direta do 3 para o 5.
  Fire OS 1, 2, 4, 14 e 16 seguem sem modelo Stick confirmado. Atualizar
  a lista quando houver evidência de outro firmware em um Stick.
  Vega OS segue no outro computador, com a investigação de 1.0, 1.1, 1.2 e 2.0.
  Confirmar os nomes comerciais, níveis reais de API e compatibilidade antes
  de cada disponibilização. Vega continua fora desta publicação enquanto seu
  receptor e o VPKG não forem validados.
  **Meta revisada por Henrique:** cobrir todas as versões de Fire OS e
  Vega OS que rodaram em Fire TV Stick físico, com as mesmas funções e
  variantes com e sem download e gravação. Confirmar se a loja ainda atende
  os firmwares antigos e como testá-los. Compilar
  os APKs Fire OS neste Windows e deixar os VPKGs para macOS ou Ubuntu nativo.
  Fire OS 5
  (API 22) é o piso dos APKs atuais. Uma edição abaixo de API 21 exigiria
  substituir Compose, revisar dependências nativas e de mídia e validar no
  firmware antigo. Ver [limites de compatibilidade](FEATURES.md#limites-da-promessa-de-compatibilidade).
  **9/nov — dispositivos-alvo no console:** conferido em Target your app >
  Supported devices que a categoria "Fire TV" (100 aparelhos) inclui Smart TVs
  com Fire TV embutido (ex.: TCL) e caixas Fire TV (Gen 1/2/3) além dos Fire TV
  Stick propriamente ditos. Decisão de Henrique: manter todos os tipos de
  dispositivo Fire TV habilitados, não restringir só a Stick. Amazon Vega TV
  (Fire TV Stick 4K Select, HD 2ª geração, 4K) segue desmarcado, conforme a
  decisão de deixar Vega fora por enquanto. Availability também já está em
  "All countries and regions where Amazon sells apps" — sem exclusão de país.
  **10/nov — lembrete de Henrique:** mesmo que não se consiga o SDK/ambiente
  para testar de verdade nos outros tipos de aparelho, não esquecer de
  reavaliar publicar somente para Fire TV Stick (aparelho) como forma de
  reduzir a superfície sem cobertura de teste. Fica para depois da 6.4 —
  não é parte do escopo da 6.4 (que é só Cornish + correção da edição
  sem-download/gravação para Alemanha e Áustria), fica para uma atualização
  seguinte.
  **11/nov — próxima publicação:** selecionar somente os aparelhos Fire TV
  Stick no console, incluindo as gerações já validadas, e preparar os idiomas
  da ficha e a distribuição por país com as variantes regionais. A meta de
  desenvolvimento é ter o mesmo Buzin Cast e as mesmas funções em todas as
  gerações, respeitando as restrições por país. Fire OS 3 está em protótipo
  local neste Windows. O trabalho em Vega OS continua no outro computador.
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
  `%USERPROFILE%\Documents\Buzin Cast\releases\0.6.1`. Arquivo completo de
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
  estão em `%USERPROFILE%\Documents\Buzin Cast\releases\0.6.0`.
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

- Repositório Windows: `%USERPROFILE%\Documents\GitHub\buzin-cast`.
- Build WSL Ubuntu-20.04: `/var/tmp/buzin-cast-build`.
- Ferramentas WSL: `/var/tmp/buzin-cast-tools`; JDK `jdk-25.0.5+8`, SDK `android-sdk`, Gradle user home `gradle`.
- Logs/artefatos: `%USERPROFILE%\Documents\Codex\2026-10-31\va\work` e `...\outputs`.
- APKs atuais: `outputs\buzin-cast-0.6.0.apk` e `outputs\buzin-cast-0.6.0-compat.apk`. Evitar o antigo arquivo `buzin-cast-0.6.0-debug.apk`.
- Principal SHA256: `32aa025b06ddd21b2a7c19fefa335e65de67cb7eebe9dc331ccc79f119457a29`.
- Compatível SHA256: `0ca7d21746c8060a78f2c3200b94e76d9e098e9780358d123c714a308f2e59a4`.
- Resultados recentes em `outputs\buzin-cast-0.6.0-checks`: `stable-isolated-api36`, `stable-calibration-media-api36`, `compat-isolated-smoke-api22`, `compat-isolated-ui-api22`, `compat-media-api22`. A falha de calibração na primeira pasta foi resolvida pela execução direcionada na segunda; preservar os registros anteriores para rastreabilidade.
- Relatório principal mais recente no WSL: `build/coverage-report/summary.json`, gerado de `app/build-coverage/reports/jacoco/fullCoverageReport`. Consolidar também o relatório completo da edição compatível ao retomar.
- Emuladores próprios: `emulator-5580` (API 36) e `emulator-5582` (API 22). Não matar o servidor ADB global; ele compartilha as conexões com as TVs.

## Fire Sticks e próximos passos

Atualização concluída: a edição principal 0.6.0/código 22 foi instalada com sucesso nos três IPs abaixo. O cache da Appstore foi limpo pela opção **Limpar cache**, sem apagar dados; os aparelhos foram reiniciados e reconectados ao ADB. A logo aprovada foi confirmada visualmente nas três listas de apps. Capturas locais: `outputs/cozinha-apps-final-060.png`, `outputs/quarto-apps-final-060.png`, `outputs/sala-apps-final-060.png`. A cozinha exibiu um aviso de controle remoto após reiniciar; ele foi dispensado com OK.

A arte permanece idêntica. `docs/approved-brand.json` e `tools/verify_brand.py` protegem os arquivos e referências do launcher no CI. Só alterar a identidade mediante pedido explícito de Henrique. Não confundir falha de cache do sistema com autorização para redesenhar a logo.

- Cozinha/Samsung: `<FIRE_TV_IP>:5555`.
- Quarto: `<FIRE_TV_IP>:5555`.
- Sala: `<FIRE_TV_IP>:5555`.
- Os três são AFTMM/API 25 e receberam a edição principal. Instalação e conferência do ícone/cache concluídas; retomar o desenvolvimento/testes pendentes depois, conforme solicitado.
- Preservar a logo aprovada: B à esquerda, Buzin acima e CAST abaixo à direita, sem borda externa. O launcher Fire OS pode manter cache após atualizar o APK; antes de limpar dados, verificar logs e tentar apenas cache/reinício do launcher. Não apagar dados/configurações do usuário.
- CI de `8019bae`: execução `41528642737`. A anterior `41524350857` foi cancelada por estar superada. O commit deste checkpoint inicia outra execução; verificar o resultado mais recente no GitHub antes de afirmar aprovação da matriz de 12 APIs.
- Continuar elevando cobertura real de todo o código próprio Android/C++, sem excluir arquivos por baixa cobertura. Ainda faltam caminhos de erro, USB/mDNS, gestos, controles e diversas transições. Emuladores não certificam sincronismo, Wi-Fi, desempenho, 4K ou comportamento de todos os Fire OS físicos. Vega fica fora do escopo.
