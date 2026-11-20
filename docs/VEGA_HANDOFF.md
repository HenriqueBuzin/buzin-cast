# Retomada no Mac — paridade entre Vega OS e Fire OS

## Controles de fotos DLNA no Vega

O receptor mostra Anterior, Próxima, Apresentação, Girar, Preencher/Ajustar
e Fechar foto. As setas percorrem os controles e OK ou toque os ativa.
O visualizador mantém as últimas 20 URLs da sessão, baixa a foto ao
selecioná-la e avança após cinco segundos durante a apresentação.
Fechar a foto encerra a exibição e mantém o receptor DLNA disponível.

No VVD ARM64, `tools/test_vega_dlna.py --jpeg …` passou com PNG, JPEG,
navegação e apresentação pelo controle, metadados UPnP da foto atual,
fechamento da foto e retorno ao vídeo. Os testes Jest também verificaram
a geometria do giro e os modos de escala. Lint, TypeScript, 87 testes
Jest e a validação ABI ARM64, ARMv7 e x86_64 passaram. A conferência
visual do giro e do preenchimento em TV física segue pendente.

## Calibração das bordas no Vega

**Configurações → Calibrar bordas** mostra uma moldura de referência.
Use Diminuir e Aumentar para ajustar de 0 a 10% por borda e Salvar margem
para aplicar. Cancelar e Voltar descartam o ajuste em andamento. A margem
começa em 2% e é salva no sandbox. A área de vídeo e a foto DLNA mantêm
a proporção da imagem. As legendas acompanham a área de vídeo.

No VVD ARM64, o controle salvou 3% a partir do padrão, descartou uma
alteração com Voltar e reabriu o ajuste a partir do valor persistido.
Com 4% salvos, `tools/test_vega.py` passou na reprodução local, na pausa,
na retomada e na liberação ao sair. A conferência visual em TV física
segue pendente.

## Perfis de espelhamento AirPlay no Vega

**Configurações → Perfil de espelhamento AirPlay** salva Padrão,
Cinema, Apresentação ou Jogos. Ao abrir o espelhamento, o Vega consulta
H.264 nas combinações de resolução e FPS do perfil e anuncia o modo
escolhido no RTSP `/info`. Padrão mantém 1080p a 30 fps. Uma consulta
sem resposta usa 720p a 30 fps nos três perfis personalizados.
[SDK Vega Media Descriptor](https://developer.amazon.com/docs/vega-api/2.0/README.amazon-devices_keplermediadescriptor).

O worker usa uma meta inicial de 80 ms de mídia em Cinema e Apresentação
e de 25 ms em Jogos. O player também pode abrir após 250 ms com o
quadro estático disponível. A latência real e o comportamento adaptativo
de áudio equivalentes ao Fire OS ainda precisam de validação e trabalho.

No VVD ARM64, `tools/test_vega_airplay_audio.py --video --profiles`
selecionou e salvou os quatro perfis pelo controle e verificou resolução
e FPS na resposta RTSP real. `tools/test_vega_msse_video.py
--profile-cushion` verificou a liberação do player após os quadros
previstos para 0, 25 e 80 ms e confirmou o caso do quadro estático.
Lint, TypeScript, 84 testes Jest e a validação ABI ARM64, ARMv7 e
x86_64 passaram.

## Envio da retransmissão Buzin no Vega

**Estado do port → Testar vídeo AirPlay → Retransmitir** conecta o Vega ao
receptor Buzin no IP e porta mostrados pela TV de destino. O código
temporário autoriza a conexão BZCAST02. O envio usa o próximo IDR com
SPS/PPS e converte áudio para PCM estéreo de 44,1 kHz em um worker
separado. Parar o envio mantém o player local e Voltar fecha ambos.

O canal Kotlin compartilhado pelo Fire OS autenticou o emissor do
Vega e leu vídeo, PCM, keepalive e End. No VVD ARM64,
`tools/test_vega_relay_sender.py` exercitou o mesmo fluxo nativo de
espelhamento com H.264 e áudio ALAC 352, AAC-LC 1024 e AAC-ELD 480 e
512. O receptor de teste recebeu PCM e vídeo decodificável com
FFmpeg em todos os perfis. Lint, TypeScript, 81 testes Jest e as
três ABIs passaram. A interoperabilidade em aparelhos físicos
continua pendente.

## Recepção da retransmissão Buzin no Vega

**Estado do port → Receber retransmissão Buzin** abre o receptor
`BZCAST02` com código temporário. Ele autentica cada mensagem e
entrega vídeo H.264 e PCM estéreo de 44,1 kHz ao pipeline nativo.
O PCM passa por AAC-LC no mux MP4 local. A tela fecha o receptor
ao voltar ou quando o app vai para segundo plano.

O canal Kotlin compartilhado pelo Fire OS pareou com a implementação
nativa do Vega em loopback e trocou vídeo, PCM, keepalive e fim de sessão.
`tools/test_vega_relay.py` rejeitou o código incorreto no VVD ARM64,
enviou mídia sintética, decodificou o MP4 H.264/AAC com FFmpeg e
observou o player do Vega avançar a partir da saída do receptor.
Verificou também a abertura e o fechamento do modo pelo controle.
A gravação AirPlay com conversão ALAC → AAC foi revalidada.
Lint, TypeScript, 80 testes Jest e os VPKGs Release ARM64, ARMv7
e x86_64 passaram. Ensaios com aparelhos físicos ainda estão pendentes.

## Exportação da biblioteca no Vega

**Mídia → Biblioteca de gravações → Exportar** abre um endereço HTTP
para baixar o MP4 selecionado da TV pelo navegador. O endereço aceita o
início da transferência por até dois minutos. A tela fecha o acesso ao
cancelar, voltar ou ir para segundo plano. Um envio completo encerra o
link, e uma conexão interrompida pode tentar de novo dentro do prazo.

O teste nativo comparou o arquivo baixado byte por byte, verificou HEAD,
rejeição de endereço ou método incorreto, tentativa após desconexão e
cancelamento com a conexão bloqueada. No VVD ARM64, o controle abriu e
cancelou o envio pela Biblioteca. Lint, TypeScript, 79 testes Jest e ABI
das três arquiteturas passaram. O download por um navegador na rede
de uma TV física ainda aguarda validação.

## Gravação e biblioteca no Vega

A tela **Estado do port → Testar vídeo AirPlay** permite armar e parar
uma gravação. O gravador começa no próximo quadro-chave H.264,
mantém a linha do tempo do áudio e vídeo e finaliza em MP4 ao parar ou
encerrar a sessão. Um worker separado escreve no sandbox, com fila
limitada e verificação de espaço. **Mídia → Biblioteca de gravações**
abre o player local e pede confirmação antes da exclusão.

No VVD ARM64, `tools/test_vega_local_video.py --transport-only
--alac-conversion --recording` alimentou callbacks sintéticos H.264/ALAC
pelo worker nativo durante a reprodução. A gravação começou depois do
primeiro IDR e salvou H.264/AAC tanto no encerramento normal quanto
ao voltar durante a transmissão. FFmpeg decodificou ambos os arquivos
e o player do Vega abriu uma gravação pela Biblioteca. Lint, TypeScript,
78 testes Jest e ABI das três arquiteturas passaram. A captura de um emissor Apple em aparelho físico ainda aguarda validação.

## Legendas externas no Vega

**Importar legenda** durante um vídeo abre um endereço de uso único por
até dois minutos. O usuário seleciona o arquivo SRT ou WebVTT no navegador
de outro aparelho da mesma rede. A TV valida UTF-8 e marcações de tempo,
mostra as legendas conforme a posição do player e libera o arquivo em
memória ao trocar de vídeo, desligar a legenda ou sair. O fluxo também
funciona na amostra local. O texto usa o estilo da TV.

O teste nativo exercitou o envio dos dois formatos e rejeição de origem,
caminho, tipo e tamanho inválidos. O teste de tela cobriu exibição, pausa,
busca, desativação e encerramento. No VVD ARM64 passaram abertura,
cancelamento, reabertura e fechamento da porta pelo controle. Lint,
TypeScript, 77 testes Jest e os três VPKGs Release passaram. A entrega
do arquivo pelo navegador e a sobreposição sobre vídeo em uma TV física
aguardam validação visual.

## Cast V2 experimental no Vega

**Estado do port → Testar Cast V2** abre o receptor por TLS local e
registra a descoberta DNS-SD. A sessão suporta o fluxo experimental do
Fire OS para mídia por URL, com aprovação configurável na TV. O
certificado autossinado e a chave são gerados no sandbox. Dispositivos e
apps oficiais do Google Cast que exigem autenticação precisam de um
receptor certificado.

O teste `tools/test_vega_cast_v2.py` passou no VVD ARM64 com e sem
`--approval`. Foram verificados TLS, heartbeat, resposta de
autenticação indisponível, lançamento, MP4 HTTP com autoplay
desativado, pausa, busca, retomada, volume, rejeição de URL inválida,
Stop e liberação ao Voltar. Lint, TypeScript, 70 testes Jest e os
VPKGs Release ARM64, ARMv7 e x86_64 passaram. Descoberta por um
emissor na rede física e reprodução HLS/DASH pelo Cast V2 aguardam
validação.

## Aprovação de conexões AirPlay no Vega

**Configurações → Aprovar conexões dos receptores**
protege os três modos AirPlay no Vega. O servidor aguarda a decisão
antes de processar a primeira solicitação. Permitir vale por cinco
minutos para novas conexões AirPlay do emissor enquanto a tela estiver
aberta. O espelhamento também exige PIN verificado para aceitar mídia.

O patch UxPlay 0020 fornece o socket ao callback opcional do Vega.
A preparação aplicou os 20 patches na cópia isolada. Lint, TypeScript
e 70 testes Jest passaram, assim como a validação de ABI nos três
pacotes. No VVD, áudio e espelhamento passaram nos ciclos de recusa,
aprovação, reabertura e Voltar durante a espera. O AirPlay por URL
esperou a aprovação antes de carregar o MP4. Relatórios em
`build/vega-airplay-{audio,video,url}-approval-final`.
A negociação e a reprodução completas com emissor Apple em aparelho
físico continuam pendentes.

## Aprovação de conexões FCast no Vega

**Configurações → Aprovar conexões DLNA e FCast** aplica a preferência
existente aos dois receptores. Cada protocolo mantém suas próprias
decisões temporárias. O FCast aguarda a confirmação antes de enviar a
versão do protocolo ou processar comandos. Uma conexão recusada é
fechada e, depois de Permitir, reconexões do mesmo IPv4 reutilizam a
aprovação por cinco minutos ou até fechar a tela.

O VVD confirmou Recusar, Permitir, negociação FCast v2, reprodução
MP4, pausa, busca, velocidade e volume. A regressão do DLNA confirmou
aprovação, GENA e reprodução. Relatórios em `build/vega-fcast-approval-vvd`
e `build/vega-shared-approval-dlna-vvd`. Lint, TypeScript, 69 testes Jest,
testes nativos com ASan/UBSan e as três validações de ABI passaram.

## Aprovação de conexões DLNA no Vega

**Configurações → Aprovar conexões DLNA** salva a preferência no sandbox.
Quando ativada, descoberta e descrições continuam disponíveis, e o
primeiro comando SOAP ou inscrição GENA do emissor aguarda confirmação
na TV. A aprovação vale por cinco minutos para o IPv4 do emissor.
A recusa vale por 30 segundos e a confirmação expira em 25 segundos.
Fechar o receptor descarta decisões e pedidos pendentes. As inscrições
GENA expiram no máximo ao vencer a aprovação.

O VVD confirmou Permitir e Recusar pelo controle, bloqueio de SOAP e
GENA após a recusa e nova aprovação ao reabrir. O teste nativo também
verificou resposta atrasada com ID anterior, recusa reaplicada e
cancelamento da espera ao fechar. Lint, TypeScript, 69 testes Jest e
validação de ABI nas três arquiteturas passaram.

## Notificações GENA do DLNA no Vega

Os serviços AVTransport, RenderingControl e ConnectionManager aceitam
inscrições com prazo e enviam o estado inicial com sequência zero.
Mudanças de transporte, volume e mudo geram `LastChange`. Renovação e
cancelamento usam o SID e o serviço da inscrição. Cada URL de retorno
usa HTTP com o mesmo IPv4 do cliente inscrito. A fila de notificações é
independente do socket de controle.
[Arquitetura UPnP da OCF](https://openconnectivity.org/wp-content/uploads/2015/11/UPnP-arch-DeviceArchitecture-v1.0-20080424.pdf)

O VVD confirmou a entrega inicial de cada serviço, atualizações de
reprodução, pausa e mudo, renovação, expiração, cancelamento, descarte
das inscrições ao fechar e recusa de callback em outro endereço.
Relatório em `build/vega-dlna-gena-vvd-final`. Lint, TypeScript, 68
testes Jest e as três validações de ABI passaram. Regras de acesso do
app e validação em aparelho físico continuam pendentes.

## Fotos via DLNA no Vega

O receptor aceita JPEG e PNG pela extensão da URI ou pelo recurso DIDL
correspondente à URI enviada. Abre a foto com proporção preservada na
mesma tela em que recebe vídeo. O download tem limite de 16 MiB e a
imagem aceita até cerca de 25 megapixels. O receptor cancela o download ao trocar
de mídia, receber Stop ou fechar a tela. O estado UPnP só muda para
`PLAYING` depois da confirmação de carga pelo componente Image.
[Image no React Native Vega](https://www.developer.amazon.com/docs/react-native-vega/0.83/image)

O VVD confirmou PNG, JPEG sem extensão por DIDL, rejeição de imagem
inválida e cancelamento com Stop enquanto o servidor ainda aguardava
para enviar os bytes. O ensaio voltou a tocar MP4 e encerrou o receptor
em segundo plano com uma foto aberta. Relatório em
`build/vega-dlna-photo-vvd-final`. Lint, TypeScript, 67 testes Jest
e validação ABI das três arquiteturas passaram.

## Receptor DLNA no Vega — 19 de novembro de 2026

**Estado do port → Ativar DLNA** abre um MediaRenderer com descoberta SSDP
e os serviços UPnP AVTransport, RenderingControl e ConnectionManager.
O nome acompanha Configurações e o UUID é salvo no sandbox. Uma URI de
mídia HTTP(S) válida inicia o player compartilhado. O emissor pode
consultar a posição e o transporte, pausar, retomar, buscar, parar,
ajustar o volume e pôr em mudo. Voltar encerra o anúncio e o HTTP e
libera o player.
[Especificações MediaRenderer:1 e serviços UPnP da OCF](https://openconnectivity.org/developer/specifications/upnp-resources/upnp/mediaserver1-and-mediarenderer1/)

No VVD ARM64, `tools/test_vega_dlna.py` fez uma busca SSDP por multicast
e conferiu que a resposta apontava para o servidor HTTP ativo. O teste
carregou as descrições dos três serviços, percorreu os controles SOAP em
um MP4 e conferiu o reinício após Stop, o UUID ao reabrir e a liberação
com Voltar e segundo plano. Relatório em `build/vega-dlna-vvd-final`.
O teste de sockets com ASan/UBSan passou com requisição fragmentada,
cancelamento, reabertura e descarte de uma resposta de sessão anterior.
Ainda faltam notificações GENA, fotos, integração com as regras de acesso
e validação com emissores DLNA em aparelho físico.

## PIN do espelhamento AirPlay — 19 de novembro de 2026

Ao abrir **Testar vídeo AirPlay**, o receptor gera um PIN e o exibe com
quatro dígitos. Os dois anúncios DNS-SD indicam que o pareamento exige
PIN. O patch compartilhado 0019 permite que o receptor Vega exija SRP e
verifique a chave assinante antes dos comandos de mídia. O controle fica
vinculado ao socket verificado. O receptor limita novos desafios SRP
por sessão e gera outro código ao reabrir a tela.

O app no VVD recusou SETUP e volume antes do pareamento e encerrou o
anúncio e o socket em dois ciclos. O núcleo ARM64, testado no mesmo VVD
com um cliente sintético, recusou PIN incorreto, aceitou PIN correto,
verificou a assinatura e liberou o volume só no socket pareado.
Relatórios em `build/vega-airplay-pin-vvd` e
`build/vega-airplay-pin-probe`. O ensaio de áudio isolado, lint,
TypeScript, 63 testes Jest e as três validações de ABI também passaram.
Ainda falta verificar o fluxo completo com um emissor Apple real.

## Descoberta do espelhamento AirPlay — 19 de novembro de 2026

A opção **Testar vídeo AirPlay** anuncia `_airplay._tcp.local` e
`_raop._tcp.local` enquanto o receptor está ativo. Os dois serviços
publicam a porta RTSP da sessão e a mesma identidade persistida no
sandbox. A resposta `/info` usa os mesmos registros TXT. Voltar ou
colocar o app em segundo plano encerra o anúncio e o transporte.
A porta TCP continua disponível para conexão manual se o multicast falhar.

No VVD, dois ciclos passaram com anúncio ativo, leitura dos dois TXT por
RTSP, identidade estável, ganho, mute, fechamento do socket e anúncio
inativo ao sair. Resultado em `build/vega-airplay-discovery-vvd-final`.
Os testes de pacotes com ASan/UBSan verificaram seleção por serviço e TTL
zero no encerramento. Lint, TypeScript, 63 testes Jest e build/ABI em
ARM64, ARMv7 e x86_64 passaram. Ainda falta confirmar que o receptor aparece
e reproduz imagem e áudio com um emissor Apple em aparelho físico.

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

Resultados de transporte/vida útil aprovados em
`build/vega-local-video-final-baseline` e `build/vega-local-video-final-high`.
A repetição High no candidato com cancelamento protegido pelo mutex passou em
`build/vega-local-video-cancel-guard`: 120 frames no ciclo completo e 76/120
no ciclo cancelado com `clientClosed`, player/superfície e produtor liberados.
A regressão AirPlay por URL com pausa/busca/retomada/stop passou em
`build/vega-local-video-url-regression`. Lint, TypeScript, 57 testes Jest e
build/validação dos três VPKGs passaram; o produtor continua fora dos pacotes.
Todos os relatórios de transporte mantêm `visualValidation=false`.

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

Rodada final aprovada com os índices de seek/trailer desativados:
`build/vega-h264-fmp4-stream-baseline` e `build/vega-h264-fmp4-stream-high`,
dois ciclos por perfil e 120 hashes de frames coincidentes em cada arquivo.
Build das três arquiteturas e validação dos VPKGs passaram; o runner de vídeo
permanece excluído dos três pacotes. A regressão ALAC/RTP passou em dois ciclos
em `build/vega-h264-mux-alac-regression`. Nenhuma tela de espelhamento foi ativada.

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

A rodada final no mesmo pacote passou em `build/vega-alac-final-rtp`,
`build/vega-alac-final-aac-lc`, `build/vega-alac-final-eld480`,
`build/vega-alac-final-eld512` e `build/vega-alac-final-controls`: dois ciclos
por formato, clocks até o fim da fixture, flush, contagens completas, portas
liberadas e regressão RTSP/volume/mudo/Voltar/segundo plano. Os 56 testes Jest,
lint e build/validação ABI das três arquiteturas passaram. Os três VPKGs têm
a licença FFmpeg e não contêm runner, headers ou arquivos estáticos do build.
Concessão adiada e disputa prolongada de foco ainda precisam de ensaio;
o worker mantém o prazo de backpressure de três segundos antes de falhar.

## AAC-ELD integrado ao worker — 18 de novembro de 2026

O worker agora aceita AAC-ELD (ct=8), 480/512 amostras, além de AAC-LC
(ct=4), 960/1024, sempre em 44,1 kHz estéreo. A configuração AAC-ELD usa
os mesmos bytes do receptor Fire OS: `f8e85000`/`f8e84000`. A sessão continua
manual e experimental, sem descoberta, PIN ou vídeo. ALAC segue pendente.
Pacotes cujo codec difere do formato negociado são recusados antes do decoder.

O teste portátil passou com AddressSanitizer/UndefinedBehaviorSanitizer e
verifica os quatro formatos, rollover/relógio, troca LC→ELD, manutenção do mudo,
flush/reabertura e rejeição de pares codec/frame incompatíveis. Os 56 testes
Jest e lint sem avisos passaram. A build/validação ABI das três arquiteturas
passou. O texto da tela experimental reflete os formatos implementados.

O runner aceita `--eld 480` ou `--eld 512`, combináveis com `--worker`/`--rtp`.
Reutiliza os tons sintéticos próprios em
`fireos3-probe/src/main/assets/audio_samples.json`, sem nova dependência de
codec ou FDK no VPKG. O modo direto do player passou em dois ciclos por formato:
`build/vega-aac-eld480-native` (735 frames, ~7,90 s) e
`build/vega-aac-eld512-native` (690 frames, ~7,91–8,01 s).
Isso comprova consumo/relógio e liberação no VVD, sem comprovar som audível
ou interoperabilidade Apple.

O transporte também passou em `build/vega-aac-eld480-rtp` (735/747 frames aceitos,
7,981/7,936 s) e `build/vega-aac-eld512-rtp` (690/702 frames, 7,920/8,000 s).
Cada segundo ciclo inclui 12 frames antes do flush. Todos os frames passaram
pela comparação do payload descriptografado e receberam timestamps NTP;
oito/nove duplicatas foram enviadas por ciclo sem aumentar o número aceito.
Player e portas RTP/controle/NTP foram liberados em todos os ciclos. O ensaio
continua com chaves públicas de desenvolvimento, sem negociação FairPlay/RTSP
ou comprovação de som audível/recepção Apple.

A regressão AAC-LC passou no mesmo pacote em `build/vega-aac-lc-eld-regression`
(346/358 frames aceitos, ~7,94–7,98 s). Os controles RTSP, volume/mudo,
Voltar/segundo plano e encerramento/reabertura passaram em dois ciclos em
`build/vega-aac-eld-controls`. Nenhum desses ensaios certifica um emissor Apple.

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

## Worker de áudio ligado ao UxPlay — 18 de novembro de 2026

- Adicionado modo manual **Estado do port → Testar áudio AirPlay**. A sessão
  nativa encaminha formato, amostras, flush e volume UxPlay para um worker
  dedicado; o JavaScript recebe somente estado/posição. Este modo não ativa
  HLS, vídeo ou descoberta e não altera o receptor separado de URLs.
- A primeira configuração implementada é AAC-LC, ct=4, 44,1 kHz estéreo,
  960/1024 amostras por frame, seguindo a configuração do núcleo/Android.
  Outros formatos encerram o experimento com erro explícito. Ainda faltam
  ALAC, AAC-ELD, vídeo e sincronismo A/V; não anunciar esses recursos.
- Fila limitada a 256 eventos/8 MiB e 2 MiB por amostra, além da retenção
  limitada do sink MSSE. O callback só copia/enfileira; inicialização, volume,
  alimentação e liberação do SDK acontecem no worker. Backpressure tem prazo
  limitado; flush invalida a geração anterior e libera o sink antes de reabrir.
- Uma liberação nativa não confirmada permanece marcada como falha e impede
  reabrir outro sink no mesmo worker até reiniciar o app. O teste portátil
  injeta a falha de liberação para verificar essa barreira.
- Posição normalizada a partir do NTP local, com fallback pelo RTP desenrolado
  quando o primeiro NTP não existe. Pacotes atrasados não recuam o relógio.
  Volume usa dB AirPlay, incluindo -144 para mudo. O último volume desejado
  sobrevive à invalidação da fila por formato/flush, incluindo mudo prévio.
- O teste C++ portátil exercita rollover RTP, NTP, descarte atrasado, flush sob
  backpressure, limites, formato inválido, volume/mudo e liberação repetida.
  Passou com `-Wall -Wextra -Werror` e AddressSanitizer/UndefinedBehaviorSanitizer.
  O CI passou a executar o teste e compilar a sintaxe dos novos runners.
- 56 testes Jest, lint sem avisos, TypeScript e build/validação ABI das três
  arquiteturas passaram. O módulo de áudio tem testes de estado/erro e limpeza
  dos timers/segundo plano. A logo e as fontes Android foram preservadas.
- VVD ARM64: `build/vega-airplay-audio-worker-release-guard/result.json` aprovado.
  O runner `tools/test_vega_msse.py --worker` transcodifica a amostra própria
  para AAC-LC estéreo/44,1 kHz, injeta os pacotes no worker em tempo real e exige
  todos os pacotes aceitos e relógio até o fim da amostra antes de encerrar.
  Dois ciclos passaram (~7,95 s), o segundo com flush e nova época de relógio.
- `build/vega-airplay-audio-lifecycle-release-guard/result.json` aprovado: duas
  ativações pelo controle, OPTIONS/SET_PARAMETER RTSP reais, volume -18 dB,
  mudo, fechamento do socket e liberação ao Voltar e ao entrar em segundo plano.
  Os relatórios do sandbox guardam apenas porta/estado/contadores, sem áudio.
- Smoke completo no mesmo pacote passou em
  `build/vega-airplay-audio-smoke/result.json`. A regressão de URL no candidato
  anterior também passou em `build/vega-airplay-audio-url-regression/result.json`.
- Limite da evidência: o áudio de teste é injetado diretamente no worker;
  RTSP foi usado para controles/vida útil. Esta rodada não percorre negociação,
  criptografia e transporte RTP de mídia completos nem valida emissores Apple,
  som audível, latência ou sincronismo físico. Esses passos continuam necessários
  para a meta completa. O modo é experimental, não um AirPlay finalizado.

Repetir os ensaios, com VVD iniciado e build concluído:

```sh
python3 tools/test_vega_msse.py --serial emulator-5554 \
  --vpkg vega/build/aarch64-release/buzincast_aarch64.vpkg \
  --runner vega/build/aarch64-release/bin/buzin-msse-probe --worker \
  --output build/vega-airplay-audio-worker
python3 tools/test_vega_airplay_audio.py --serial emulator-5554 \
  --vpkg vega/build/aarch64-release/buzincast_aarch64.vpkg \
  --output build/vega-airplay-audio-lifecycle
```

## Ponto pausado — áudio MSSE nativo — 16 de novembro de 2026

- `MsseAudioPlayer` usa os bindings oficiais APMF/W3C gerados pelo CMake,
  com inicialização/liberação assíncronas e espera limitada, AAC bruto,
  configuração ASC, timestamps em milissegundos, backpressure e retenção
  limitada dos buffers até o consumo. Ainda não está ligado ao UxPlay.
- `buzin-msse-probe` é um executável de desenvolvimento separado do VPKG.
  Extrai unidades AAC-LC de uma fixture ADTS limitada e verifica dois ciclos
  de reprodução e liberação. O runner Python recusa TVs físicas, gera a
  fixture com FFmpeg a partir da amostra própria e a transmite ao sandbox.
- Build/validação ABI das três arquiteturas passou. O teste real ARM64 passou
  em `build/vega-msse-audio-format/result.json`: dois ciclos de 376 frames,
  relógios de 7,955 s e 7,948 s e liberação confirmada. Isso não comprova som
  audível, recepção AirPlay, AAC-ELD/ALAC, vídeo ou sincronismo A/V.
- A configuração validada usa MIME `audio/aac`, RAW_AAC, criptografia
  desativada e prepara a trilha antes de adicioná-la à fonte. As tentativas
  anteriores deram SOURCE_NOT_SUPPORTED. Não reutilizar seus relatórios como
  resultados aprovados nem atribuir a causa exclusivamente a um dos ajustes.
- Com VVD iniciado, repetir da raiz:
  `python3 tools/test_vega_msse.py --serial emulator-5554 --vpkg vega/build/aarch64-release/buzincast_aarch64.vpkg --runner vega/build/aarch64-release/bin/buzin-msse-probe --output build/vega-msse-audio`.
- Próximo: ligar formatos/amostras/flush/volume UxPlay ao worker de áudio,
  alinhar os timestamps NTP e integrar vídeo/superfície; preservar validação
  explícita dos demais codecs e dos emissores reais. A meta completa segue
  pendente. Trabalho pausado por Henrique após esse teste aprovado.
- A integração remota foi concluída em `0258add`, preservando ambos os lados
  do conflito de checkpoint e as atualizações Fire OS.

## Menu selecionado e foco por ativação — 16 de novembro de 2026

- Conferido o Fire OS atual: `TvSidebar` mantém a página selecionada marcada
  quando o foco está no conteúdo. `touchFocus` move o foco no toque concluído.
  A seleção em `MainScreen` é estado da sessão; não é uma preferência em disco.
- Vega mantém o menu nas páginas Estado/Mídia/Sobre/Configurações, com borda e
  semântica `accessibilityState.selected` independentes do foco. Ao Voltar ao
  início, prefere o último item selecionado. As telas de reprodução/receptores
  mantêm seu modo próprio e liberam mídia no encerramento.
- `TvButton` compartilha a ativação dos botões da aplicação: chama o foco nativo
  antes da ação de Pressable. Press-down/up sem ativação não dispara foco ou
  ação. Mantém as ações de teclado/controle e os estados visuais existentes.
- 53 testes Jest passaram, incluindo seleção preservada ao focar o conteúdo,
  troca de seleção e restauração ao voltar; teste do botão usa o mock de foco
  nativo do próprio React Native. Lint sem avisos, TypeScript e build/validação
  ABI das três arquiteturas passaram.
- O smoke real ARM64 passou em
  `build/vega-navigation-focus-smoke/result.json`: diagnósticos, navegação,
  amostra, pausa/retomada, reabertura/fim, segundo plano e repetição. A primeira
  rodada expôs que o menu mantido montado não restaurava o foco no retorno;
  remontar o grupo ao alternar início/página corrigiu o comportamento no VVD.
  O runner agora espera a seleção de Mídia restaurada ao reabrir o player.
- Toques reais e revisão visual desta mudança ainda precisam de validação.
  A captura nativa da imagem VVD informou falta de suporte DMABUF; o snapshot
  QMP estava preto. Não tratar esses arquivos como prova visual aprovada.
  A paridade completa continua pendente.

## AirPlay por URL ligado ao player — 16 de novembro de 2026

- A sessão UxPlay entrega Play/Seek/Rate/Stop à sessão de reprodução Vega;
  posição, duração, taxa e prontidão observadas no player retornam em
  `/playback-info`. Há identidade sintética persistida apenas no sandbox,
  chave local, porta dinâmica e encerramento ao sair ou entrar em segundo plano.
- Acesso experimental em **Estado do port → Testar AirPlay por URL**. Admite
  conexão manual de IPv4 privado/loopback; não anuncia DNS-SD nem espelhamento.
  PIN, descoberta e emissores Apple reais continuam pendentes. HLS/DASH usam o
  adaptador existente, mas esta rodada AirPlay validou somente MP4 por HTTP.
- O ensaio real `tools/test_vega_airplay_url.py` passou no VVD ARM64 em
  `build/vega-airplay-url-clock/result.json`: GET da mídia, relógio avançando
  após posição inicial, pausa estável, busca, retomada, Stop e liberação nativa
  ao Voltar. O servidor de fixture atende Range/206. O cliente mantém a sessão
  de controle aberta nas respostas POST sem corpo/Content-Length do UxPlay.
- Identificada e corrigida falha compartilhada: o setter nativo com cabeçalhos
  rejeita `{}`. URLs sem cabeçalhos agora usam `player.src`; URLs com cabeçalhos
  preservam o setter específico. Ambos já chamam load no SDK, sem chamada extra.
- 51 testes Jest, lint, TypeScript e build/validação ABI das três arquiteturas
  passaram. O teste exige avanço do relógio antes de pausar: o getter pode
  expor a posição pedida enquanto a busca nativa inicial ainda está pendente.
- Regressão FCast MP4 com cabeçalhos e smoke geral passaram no mesmo pacote:
  `build/vega-airplay-url-fcast-regression/result.json` e
  `build/vega-airplay-url-smoke/result.json`, ambos `passed: true`.
- Esta entrega não completa AirPlay: saída de amostras comprimidas, áudio,
  relógio A/V, descoberta, pareamento e validação física continuam pendentes.

## Sessão UxPlay nativa — 16 de novembro de 2026

- Adicionada `AirPlaySession` C++ com propriedade RAII de UxPlay/identidade,
  início/encerramento repetíveis, admissão de loopback ou IPv4 privado e fila
  limitada para callbacks. Amostras comprimidas são copiadas com codec e
  timestamps local/remoto; eventos URL, posição, taxa, flush/reset seguem uma
  fila com limite de 128 eventos, 8 MiB e 2 MiB por amostra.
- A classe não anuncia DNS-SD e não declara capacidade de renderização: os
  bits de funções permanecem zerados. O construtor de registros compartilhado
  com Android é C sem JNI; a saída de mídia Vega ainda precisa consumi-los.
- O diagnóstico nativo instancia a sessão, faz OPTIONS RTSP e Play HTTP real
  para uma URL de documentação (sem fazer download), confere URL/posição no
  callback e repete dois ciclos com liberação e fila vazia. O modo testado
  admite somente loopback. A chave de teste permanece no sandbox.
- Build/validação ABI das três arquiteturas e smoke completo ARM64 passaram:
  `build/vega-airplay-lifecycle/result.json` tem `passed: true` e
  `airplaySessionLifecycle: true`, além dos demais diagnósticos e cenários de
  reprodução da amostra. O smoke exige agora o novo diagnóstico como gate.
- Isto prova transporte e callback de URL, não reprodução AirPlay. Faltam
  conexão desses eventos ao player, saída das amostras comprimidas, relógio A/V,
  descoberta compatível e validação com emissores reais.

## Escopo reafirmado — interface atual do Fire OS

A referência de paridade passa a ser a versão Fire OS atual, incluindo seleção
persistente e navegação por toque mencionadas por Henrique nesta retomada.
Na ocasião, ambos estavam pendentes no Vega. A seção de menu selecionado
acima registra o avanço atual e a validação de toque ainda necessária; nome
salvo e foco básico, isoladamente, não comprovam esses comportamentos. A estimativa informal de cerca
de 20% é de paridade funcional, não de tempo restante, e não conta esses itens
como entregues.

## Núcleo AirPlay isolado e reproduzível — 16 de novembro de 2026

- Identificada dependência acidental do build Vega nas modificações que o
  Gradle Android já havia aplicado ao submódulo UxPlay neste checkout.
- `tools/prepare_vega_airplay.py` agora extrai o objeto Git fixado
  `df67c212a433cf6dda3676dd40c097900d24e645`, aplica os 11 patches Buzin e gera
  a cópia `vega/generated/UxPlay`. O CMake Vega compila essa cópia; os hooks de
  Release/Debug a preparam antes do build, sem modificar o submódulo Android.
- Manifesto local registra SHA-256 de cada patch e da árvore resultante. Cache
  alterado manualmente é rejeitado para preservar as alterações. O teste de
  staging usa uma pasta temporária real, aplica todos os patches, repete a
  preparação e verifica a rejeição de uma edição sem apagá-la. O CI passa a
  verificar esse caminho e a reagir a alterações dos patches compartilhados.
- Teste de staging, lint, TypeScript, 49 testes Jest e build/validação ABI das
  três arquiteturas passaram. A regressão completa ARM64 no VVD passou em
  `build/vega-isolated-core-smoke-retry/result.json`: sete diagnósticos nativos,
  amostra, pausa/retomada, reabertura, fim, liberação no segundo plano e
  repetição pelo controle.
- Uma tentativa anterior detectou interface multicast ainda ausente ao boot.
  O runner repete apenas `multicastStage=interface`/`errno=19`, com limite de
  dez tentativas e mantendo a exigência final de todos os diagnósticos true.
  Falhas de permissão, join/leave ou protocolo não são ignoradas.
- Próximo: implementar a sessão nativa UxPlay e o adaptador de callbacks,
  conectando mídia/relógios ao Vega antes de anunciar recepção AirPlay. A
  existência da biblioteca compilada ainda não significa um receptor ativo.

## Primeira execução HLS e DASH no MSE — 16 de novembro de 2026

- `tools/generate_vega_adaptive_samples.py` gera HLS VOD MPEG-TS e DASH fMP4
  com áudio/vídeo a partir da amostra própria do pacote, usando FFmpeg local.
  As mídias geradas ficam em `build/vega-adaptive-samples`, fora do Git.
- O runner FCast aceita `--media mp4|hls|dash`, serve apenas o inventário local
  de fixtures e agora também exige cabeçalhos personalizados no manifesto e
  segmentos. A seleção do protocolo reconhece MIME HLS/DASH e extensões de
  manifestos, mantendo HTTP(S), limites e rejeição de credenciais/cabeçalhos
  inválidos. Manifestos em linha continuam rejeitados.
- O primeiro HLS expôs `navigator.userAgent` ausente durante a importação do
  Shaka. O adaptador fornece a identificação Buzin Cast/Vega com token AFT para
  selecionar o perfil Amazon TV dos patches, sem declarar um modelo físico.
- HLS e DASH passaram individualmente no VVD ARM64: posição inicial, pausa,
  busca, volume, velocidade, retomada, Stop e encerramento pelo controle.
  Relatórios: `build/vega-hls-useragent/result.json` e
  `build/vega-dash-runtime/result.json`. A conferência final também passou:
  `build/vega-hls-verified/result.json` e `build/vega-dash-verified/result.json`
  têm `passed: true`, cabeçalhos em cada manifesto/segmento e EOF após Voltar.
  Lint, TypeScript, 49 testes Jest e build/validação ABI das três arquiteturas
  passaram. O subconjunto HLS/DASH por URL agora está habilitado no FCast.
- Limites: fixtures VOD locais de cerca de oito segundos, sem live, ABR entre
  múltiplas qualidades, DRM, emissor comercial ou hardware físico. Não houve
  nova inspeção visual ou medição de áudio audível/sincronismo nesta etapa.
- O Shaka emitiu avisos não terminais de polyfills opcionais de screen/EME;
  não oferecer DRM baseado neste ensaio. AirPlay e os demais receptores ainda
  não foram portados.

## Adaptador Shaka/MSE candidato — 16 de novembro de 2026

- Criado adaptador próprio `adaptivePlayback.ts` sobre os objetos W3C reais do
  SDK e Shaka compilado. Faz attach/load, encaminha cabeçalhos às requisições e
  oferece destruição idempotente. Wrappers proprietários de exemplo não foram
  copiados. DOMParser vem de @xmldom/xmldom; codecs/MSE vêm do sistema Vega.
- `playbackSession` serializa a troca de fonte, espera a carga antes de pedir
  Play numa superfície já disponível, cancela o adaptador ao sair e ainda
  libera o player nativo se a destruição adaptativa falhar. Uma troca de fonte
  também cancela a carga de um manifesto pendente antes de aguardar sua saída.
  A API interna aceita
  `mimeType` para a futura seleção de HLS/DASH; o validador público FCast ainda
  rejeita manifestos adaptativos enquanto o MSE não for exercitado no VVD.
- `prepare_vega_shaka.py --build` agora disponibiliza o JS em `vega/generated`,
  fora do Git, e roda nos hooks anteriores aos builds Release/Debug. Build novo
  exige Git, curl, Python com extração tar segura e Java 21+ para o compilador.
  Foram incluídos no pacote os avisos/licenças Shaka, xmldom e base-64.
- Lint, TypeScript, 47 testes Jest e build/validação ABI das três arquiteturas
  passaram. A regressão FCast MP4 real no VVD ARM64 passou novamente em
  `build/vega-adaptive-progressive-regression/result.json`.
- Isso ainda não comprova HLS/DASH: falta gerar fixtures locais, exercitar a
  inicialização real do Shaka e os globais de compatibilidade, validar troca/
  busca/encerramento adaptativos e só então abrir esses MIME types no FCast.

## Dependência para HLS/DASH — 16 de novembro de 2026

- Criado `tools/prepare_vega_shaka.py --build`, seguindo a integração oficial
  [Shaka para Vega](https://developer.amazon.com/docs/vega/2.0/media-player-shaka-player).
  Fixa Shaka 4.16.13 no commit `969d23b040e5e2e19e06d99d27ff6ad2c24fc45f`,
  confere SHA-256 do pacote Amazon 4.16.13-r1.2, aplica os 33 patches e verifica
  a árvore resultante antes de compilar a distribuição completa Release.
- Build concluída neste Mac: `build/vega-adaptive/shaka-player/dist/` contém
  `shaka-player.compiled.js` (cerca de 951 KiB). Reexecução com cache também
  passou; relatório com hashes em `build/vega-adaptive/build-report.json`.
- Código de terceiros, patches e dependências npm ficam em `build/`, ignorado
  pelo Git. A fonte upstream usa Apache-2.0; preservar LICENSE e os demais
  avisos ao empacotar. Os wrappers de exemplo Amazon não foram copiados para
  as fontes públicas do app.
- Ainda não integrado ao bundle Vega. Próximo: conectar Shaka às APIs W3C/MSE
  reais do SDK com adaptador de ciclo de vida, cabeçalhos, busca e encerramento;
  gerar amostras HLS e DASH locais e validar via FCast no VVD. O validador FCast
  continua rejeitando manifestos adaptativos até essa integração ser verificada.

## Nome persistido do receptor — 16 de novembro de 2026

- A página Configurações edita o nome via Turbo Module real. O armazenamento
  fica em `/data/receiver-name`, com validação UTF-8, limite de 48 bytes,
  rejeição de controles/ponto/barra invertida e gravação por renomeação de
  temporário. Arquivo ausente usa Buzin Cast; falha de escrita é visível.
- O anúncio FCast lê o nome persistido ao iniciar e conserva o sufixo aleatório
  para diferenciar instâncias. A troca de nome é aplicada na próxima ativação.
- Codegen do módulo regenerado pela ferramenta oficial. Testes nativos cobrem
  padrão, persistência, nome acentuado, entrada inválida, falha de escrita,
  arquivo corrompido e recuperação; CI inclui o teste de armazenamento.
- Passaram lint, TypeScript, 45 testes Jest, testes nativos de configuração e
  pacotes DNS-SD e build/validação ABI das três arquiteturas. O FCast ARM64
  completo passou novamente: `build/vega-settings-fcast-regression/result.json`.
- A edição pelo teclado do Vega e a resolução do nome personalizado por um
  emissor em LAN física ainda não foram exercitadas. Não confundir os testes
  da tela com esse teste de interação real.
- Próximos trabalhos continuam incluindo AirPlay áudio/vídeo, HLS/DASH, DLNA,
  Cast V2, perfis, aprovação, legendas, biblioteca e as variantes regionais.

## Política HTTP e integração FCast — 16 de novembro de 2026

- Os logs contínuos mostraram superfície criada, associação ao VideoPlayer e
  chamada a Play. O bloqueio real era a política HTTP ausente: a pilha Vega
  registrava `no cleartext policy configured, blocking HTTP`, antes de pedir
  qualquer byte ao servidor da amostra.
- Adicionada a seção documentada `network-traffic-policy.cleartext` com
  `allowed-domains = ["*"]`: um receptor recebe URLs HTTP escolhidas pelo
  emissor, incluindo servidores LAN sem domínio fixo. A validação de URLs do
  protocolo permanece em vigor. Referência:
  [política HTTP no manifesto](https://developer.amazon.com/docs/vega/2.0/develop-your-app-with-webview).
- Removido o `load()` duplicado após setters de fonte, que já chamam `load()`
  no SDK instalado; teste verifica uma única carga com os cabeçalhos intactos.
- O runner FCast agora captura logs desde a abertura, encerra seu processo de
  coleta no finally e exige EOF/reset após Voltar, drenando até 256 KiB de
  estados previamente enfileirados por no máximo cinco segundos. Receber um
  byte antigo no TCP não é evidência de listener ainda ativo.
- Lint, TypeScript, doctor, 44 testes Jest e build/validação ABI nas três
  arquiteturas passaram. O ensaio com política HTTP já passou em reprodução,
  pausa, busca, volume, velocidade, retomada, rejeição de pedidos inválidos e
  Stop. A repetição ARM64 com a checagem de EOF corrigida passou inteira:
  `build/vega-fcast-verified/result.json` tem `passed: true`, inclusive
  `httpCustomHeaders` e `remoteClosedReceiver`. Isso valida TCP → receptor
  nativo → player Vega com uma amostra local HTTP; não valida descoberta
  multicast na LAN física, HLS/DASH, som audível ou emissores comerciais.

## Primeira recepção FCast no VVD — 16 de novembro de 2026

- `tools/test_vega_fcast.py` conectou por TCP ao receptor real, negociou
  versão 2 e completou ping/pong. O Play por URL não atingiu estado Playing
  no prazo. Relatório local: `build/vega-fcast-runtime/result.json`.
- Testar callbacks estáveis da superfície não resolveu; essa tentativa foi
  removida. A causa do Play pendente ainda não está comprovada.
- O runner agora conserva as últimas 24 observações de estado/volume, resumo
  de pedidos HTTP da amostra e diagnóstico de reprodução nas falhas. Esses
  dados são locais e não incluem URLs pessoais ou mídia recebida do usuário.
- Corrigido `stop()` idempotente de sessão antiga: chamadas repetidas retornam
  sua promessa sem substituir a barreira de liberação de outra sessão. O teste
  de regressão segura deinitialize da segunda sessão e verifica que a terceira
  só inicializa depois da liberação. Os 43 testes Jest passaram.
- Próximo: repetir o FCast com os novos diagnósticos e corrigir o Play pendente,
  antes de afirmar recepção por URL validada. Ainda faltam os demais receptores
  e a paridade do inventário Vega.

## Multicast e smoke de reprodução recuperados — 16 de novembro de 2026

- O diagnóstico identificou `IP_ADD_MEMBERSHIP` falhando com `ENODEV` (19)
  quando usava `INADDR_ANY`. A seleção explícita de uma interface IPv4 ativa,
  não loopback e com `IFF_MULTICAST` fez a adesão/saída passar no sandbox.
  A descoberta FCast também passa a ignorar interfaces sem multicast.
- O relatório agora inclui `multicastStage` e `multicastErrno`, sem registrar
  nomes de interfaces ou endereços pessoais. Não há mudança de privilégios.
- O smoke ARM64 `tools/test_vega.py` passou integralmente: sete resultados
  nativos, consulta de mídia, reprodução da amostra, pausa mantendo relógio,
  retomada, liberação ao voltar, reabertura, término em 7,999 s, liberação em
  segundo plano e retorno/repetição do diagnóstico. Resultado local:
  `build/vega-multicast-interface/result.json` (`passed: true`). Isso substitui
  a falha de multicast descrita no registro anterior; não prova descoberta na
  LAN física nem recepção AirPlay ou paridade.
- O teste nativo do codec DNS-SD passou. Build e validação ABI das três
  arquiteturas passaram para a seleção explícita do diagnóstico.

## Ciclo de reprodução — 16 de novembro de 2026

- Corrigida a barreira de liberação: falha em `deinitialize` continua registrada,
  mas a promessa resolvida após o tratamento permite abrir outra sessão.
- O player guarda a superfície atual e ignora destruição atrasada de uma
  superfície anterior. Rejeições assíncronas de Play são vinculadas à geração
  da fonte para não derrubar uma mídia carregada depois.
- Os 42 testes Jest passaram, incluindo as três regressões, assim como lint,
  TypeScript e build Release/validação ABI em ARM64, ARMv7 e x86_64.
- Nova tentativa de runtime iniciou o VVD e executou o diagnóstico no sandbox.
  `tcpLoopback`, `udpLoopback`, `wildcardBind`, `rtspParser`,
  `malformedRtspRejected` e `aesCtrVector` retornaram true, mas
  `multicastMembership` retornou false. O smoke abortou nesse gate antes de
  exercitar a reprodução. Relatório local em
  `build/vega-lifecycle-smoke/result.json`; não declarar validação de runtime
  das correções de player ou da descoberta com base nesta execução.
- Próximo: investigar a falha de adesão multicast no sandbox e voltar a rodar
  os cenários de player; continuar os receptores e a paridade do inventário.

## Revisão de pacotes DNS-SD — 16 de novembro de 2026

- Corrigida a detecção de perguntas mDNS: leitura limitada da seção de perguntas,
  nomes comprimidos, comparação sem distinção entre maiúsculas/minúsculas,
  validação de classe e tipo e consultas SRV/TXT/A do serviço/host. Pacotes
  truncados e ponteiros inválidos são rejeitados. Texto solto no payload não
  dispara uma resposta.
- Teste C++ independente exercita o codec real sem anunciar serviços na LAN e
  confere registros PTR/SRV/TXT/A, porta, endereço de documentação, consultas
  comprimidas e rejeição de truncamento. Esse teste também integra o CI Vega.
- Atualizado o teste de interface que ainda esperava a mensagem anterior.
- Verificação desta revisão: teste nativo de pacotes, lint, TypeScript, os 39
  testes Jest e build Release/validação ABI das três arquiteturas passaram.
- Limites ainda abertos do anúncio: probing e resolução de conflitos de nomes,
  supressão de respostas conhecidas, tratamento completo de QU/consultas legadas
  e troca de interface. Consultas legadas fora da porta 5353 são ignoradas para
  evitar respostas com ID/pergunta incorretos. Ainda não é uma implementação
  completa do RFC 6762 nem uma descoberta validada no Vega físico.

## Avanço no Vega — 16 de novembro de 2026

- A `main` foi sincronizada com `origin/main` e ficou como a única branch local
  e remota, conforme solicitado por Henrique.
- O módulo nativo agora inclui anúncio e resposta DNS-SD em `_fcast._tcp.local`
  durante a sessão FCast, com registros PTR/SRV/TXT/A, porta TCP real, endereço
  IPv4 ativo, TXT `v=2` e `appName=Buzin Cast`, além de goodbye ao sair. O
  anúncio só começa quando FCast é ativado pelo usuário.
- Depois desta mudança, lint sem avisos, TypeScript e build Release das ABIs
  ARM64, ARMv7 e x86_64 passaram; a validação ABI do pacote passou. O aviso
  conhecido de uso de `dlopen` em `libBuzinNetwork.so` permanece.
- O VVD inicia e informa `ready`, mas encerra logo depois e não aparece em
  `vega device list`/ADB. O smoke `tools/test_vega.py` não pôde alcançar o
  simulador; portanto a nova descoberta foi compilada, mas ainda não foi
  exercitada em runtime. Logs do VVD mostram avisos de renderização OpenGL.
- Permanecem pendentes validação do anúncio no VVD e com emissores reais, HLS/
  DASH, AirPlay com áudio/vídeo, DLNA, Cast V2, configurações, perfis e demais
  recursos do inventário. Não declarar paridade Vega ou prontidão de loja.

Alvo de versões: Vega OS 1.0 a 2.0. Matriz, SDKs e linhas em paralelo em
[VEGA_OS_VERSIONS.md](VEGA_OS_VERSIONS.md).

## Ambiente preparado neste Mac — 9 de novembro de 2026

- Mac arm64/macOS 27.2 partiu sem ferramentas Vega. Instalados Homebrew, Rosetta,
  as dependências do guia oficial, `cmake`, `ninja`, nvm 0.41.0, Node 26.13.0,
  Vega CLI 1.4.9 e SDK 2.0.13986 (ativo).
- `npm ci`, lint sem avisos, TypeScript e 39 testes Jest passaram.
  `vega project doctor` passou. `npm run build:app` gerou os VPKGs release
  ARM64, ARMv7 e x86_64.
- `vega exec vpt validate` passou nas três arquiteturas, com o aviso dlopen já
  conhecido. `tools/test_vega.py` passou no VVD ARM64 (`passed: true`), com
  resultado local em `build/vega-setup-checks`. Sem validação física; paridade
  e receptores seguem pendentes.
- SDK 0.23.9221 instalado ao lado do 2.0.13986 para a futura linha OS 1.1. O
  2.0.13986 segue ativo. O VVD do 0.23 não foi iniciado.
- Android: Temurin 25.0.5, plataforma 37.0, build-tools 37.0.0, NDK r30 e
  `platform-tools` em `~/Library/Android/sdk`. `tools/install_cmake.py` não cobre
  Mac; `local.properties` (fora do Git) aponta `cmake.dir` para o CMake 4.4.5 do
  Homebrew, que precisa continuar nessa versão. `:receiver-core:check`,
  `:dlna-domain:test` e `assembleDebug` passaram. A edição `-PbuzinLegacy=true`
  não foi compilada neste Mac.
- O build falhou primeiro porque o submódulo `libplist` estava vazio. Restaurado
  com `git submodule update --init --force` no commit fixado (`fe3dc34`).
  `ffmpeg` e `openssl-cmake`, também vazios, foram restaurados da mesma forma
  nos commits fixados; o Vega não os usa, mas a build Android precisa deles.
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
- Os relatórios antigos em `%USERPROFILE%\Documents\Codex\2026-10-31\va\outputs` e o build WSL em `/var/tmp/buzin-cast-build` são locais do Windows; não estarão no clone do Mac. Não transcrever métricas sem conferir os relatórios. Reexecutar ou obter os arquivos necessários.
- Não instalar builds, reiniciar ou limpar dados das TVs da rede durante a preparação no Mac. Os três Fire Sticks anteriormente usados rodam Fire OS, não Vega.
- Preservar licenças, créditos, patches UxPlay e avisos de terceiros. Registrar novas dependências e suas obrigações.

## Prompt para o Codex do Mac

> Continue o Buzin Cast neste repositório. Leia `docs/VEGA_HANDOFF.md`, `docs/WORK_CHECKPOINT.md`, `docs/TESTING.md`, `docs/FEATURES.md` e `vega/README.md`. Quero levar Vega OS ao mesmo nível das duas edições Fire OS, preservando os recursos, a logo aprovada e o código Android compartilhado. Este é o Mac de desenvolvimento; ainda não tenho Fire TV com Vega OS. Prepare o SDK oficial, gere e integre um projeto Vega reproduzível, compile e teste no simulador, e avance no port dos receptores com as APIs reais do SDK. Registre o que foi implementado, testado e o que ainda depende de hardware. Não declare paridade completa com base só no simulador. Atualize o ponto de retomada e faça commit ao terminar cada entrega verificável.
