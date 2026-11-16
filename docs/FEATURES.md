# Referências e escopo do Buzin Cast

Inventário das funções divulgadas nas fontes abaixo, consultadas em 31/10/2026. Não é uma auditoria de todos os menus, versões ou aplicativos existentes. Usamos comportamento documentado como referência e código aberto somente quando a licença permite. Não extraímos código de APKs proprietários.

## Referências consultadas

- [AirScreen](https://www.airscreen.app/): AirPlay, Cast, Miracast e DLNA; recepção de mídia/espelhamento; gravação; duplicação para outro receptor; controle de acesso; aceleração por hardware; UHD; serviço em segundo plano e renderização Cast.
- [AriannaCast](https://play.google.com/store/apps/details?id=com.ariannacast.device): receptor AirPlay no Fire TV/Android TV; emissor de tela e áudio no Android; descoberta local; nome configurável; código de conexão; áudio com capa/metadados e volume pelo celular; transmissão em segundo plano. As modalidades comerciais não fazem parte do Buzin.
- [iMirror](https://github.com/prat3ik/iMirror): espelhamento iPhone/iPad/Mac; AAC-ELD, AAC-LC e ALAC; vídeo por URL e controles; fotos diretas do iOS; baixa latência com H.264 por hardware; PIN opcional e controle reverso DACP pelo controle da TV.
- [AirReceiver](https://play.google.com/store/apps/details?id=com.softmedia.receiver): AirPlay, Cast, Wireless Display e DLNA; fotos/música/vídeos; apresentação de fotos; sincronização de áudio com AirExpress; compatibilidade AirParrot; emissor Windows AirSender; serviço em segundo plano, nome e início no boot.
- [AirPin](https://airpin.cn/product2_pp_en.html): AirPlay/DLNA no receptor; emissores Android e Windows e modalidade de espelhamento USB. As aplicações emissoras são produtos separados.
- [FCast](https://docs.fcast.org/): protocolo aberto de recepção de mídia com descoberta e controles remotos. Versões recentes também especificam playlists, TLS, eventos e espelhamento. O Buzin começa pelo subconjunto de reprodução por URL da versão 2; não anuncia versões 3/4.

## Disponível no código

- Perfis Cinema/Apresentação/Jogos, calibração guiada dos quatro cantos, aprovação opcional pelo controle e diagnóstico local com navegação por setas.
- Recuperação: reanúncio após mudança de rede e até três tentativas do player em falhas de conexão/timeout; AirPlay pode exigir reconexão no emissor.
- Legendas locais SRT/VTT UTF-8 de até 2 MB em vídeos por URL, via seletor de arquivos e botão CC.
- AirPlay: tela e áudio, vídeos URL/HLS, metadados/capa, DACP, nome, PIN experimental, resolução/FPS, hardware e execução em segundo plano.
- DLNA experimental: vídeo, áudio e fotos JPEG/PNG, controle de reprodução/volume, SSDP/UPnP. Ativação separada nas configurações.
- Gravação MKV de espelhamento AirPlay, biblioteca, reprodução, compartilhamento e exclusão.
- Retransmissão própria entre Buzin Cast com pareamento e transporte criptografado. Não retransmite para AirScreen/AirReceiver.
- Fotos DLNA: giro, ajuste/preenchimento, histórico da sessão e apresentação.
- Atalho ao Miracast do sistema, quando existe; não é um motor Miracast próprio.
- Perfis até 4K, condicionados ao emissor, decodificador e rede; calibração das bordas sem recorte da fonte.
- FCast v2 experimental: um controlador TCP na rede local, descoberta `_fcast._tcp`, URL HTTP(S) de áudio/vídeo/HLS/DASH, MIME e cabeçalhos, posição inicial, pausa/retomada, parada, busca, volume, velocidade, estados e ping/pong. Sem PIN/TLS; desativado por padrão. Limita quadros a 32.000 bytes, comandos e tempo de leitura parcial. Requer reinício ao trocar de rede.
- Cast V2 experimental: canal TLS 1.2 com chave própria no AndroidKeyStore, protobuf limitado a 64 KiB, descoberta `_googlecast._tcp` com modelo identificado como experimental, um controlador, heartbeat, estado/lançamento do receptor de mídia padrão e comandos de URL, reprodução, pausa, busca, parada, volume e mudo. Porta dinâmica anunciada por mDNS para evitar conflito com o receptor Amazon em 8009. Sem PIN nem identidade certificada Google: responde erro ao desafio DeviceAuth. Não inclui credenciais extraídas ou certificados de outro produto; não oferece YouTube/Netflix/espelhamento Chrome nem infraestrutura Cast Connect. Desativado por padrão.
- Interface própria: órbitas de sinal, entrada das páginas e foco animado, sem campos de texto que abram teclado durante a navegação principal.

## Próximas implementações, em ordem

1. Fotos diretas AirPlay (`/photo`, cache e encerramento de sessão), com limites e tratamento de PIN/pareamento.
2. Emissor Android de tela e áudio por MediaProjection, com consentimento do sistema. Captura de áudio interno depende da versão Android e da permissão do app de origem.
3. Descoberta automática das outras TVs Buzin Cast no fluxo de retransmissão.
4. FCast: validar emissores reais e acrescentar fotos, manifestos em linha, playlists e versões atuais com transporte seguro. O modo v2 atual não oferece esses recursos.
5. Validar aprovação de conexões com emissores físicos DLNA/FCast/Cast V2 e autenticação completa de PIN Apple. O controle opcional por protocolo/IP já está implementado.
6. Compatibilidade Google Cast certificada: o experimento Cast V2 não resolve a identidade exigida por apps Google. [Vibecast](https://github.com/emilsvennesson/vibecast#certificates) e [openchromecast](https://github.com/yukarikaname/openchromecast) documentam a mesma dependência. O SDK [Cast Connect](https://developers.google.com/cast/docs/android_tv_receiver/core_features) permite integração oficial em Android TV com infraestrutura Cast, não fornece por si só um receptor universal para Fire OS. O [registro de testes/publicação](https://developers.google.com/cast/docs/registration) de um app não transforma o Fire TV em aparelho certificado. Ainda falta validar emissores Cast V2 reais e obter um caminho suportado para os apps que exigem autenticação.
7. Emissor Windows/macOS e USB: projetos específicos; não vêm automaticamente com o receptor Android.
8. Sincronização multiroom AirExpress/AirPlay e múltiplas telas simultâneas: investigação de protocolo, relógio e recursos do dispositivo.
9. Validar Miracast físico, sessões longas, reconexão, 4K e versões de Fire OS; preparar a publicação Amazon.

## Limites da promessa de compatibilidade

Nenhuma versão atual reúne todas as funções dessas referências. As próximas etapas são escopo de desenvolvimento, sem garantia de compatibilidade universal. Fire OS 6+ (API 25) tem a edição principal; Fire OS 5 (API 22) tem uma edição compatível com código compartilhado e dependências próprias. O inventário de recursos acima descreve essas edições Fire OS, com as limitações de validação física registradas em [testes](TESTING.md). Vega OS ainda tem apenas uma prévia de interface; o [port no Mac](VEGA_HANDOFF.md) tem como objetivo alcançar os mesmos recursos. Conteúdo protegido segue as permissões do serviço de origem. Sem anúncios, cobrança ou temporizador comercial em todas as funções do Buzin.

**Fire OS 5 (API 22) é o piso dos APKs atuais.** A meta do projeto é cobrir todos os Fire TV Sticks, inclusive em firmwares antigos quando houver um modelo identificável e um caminho de distribuição e teste. A [Amazon lista o primeiro Stick, de 2014, com Fire OS 5](https://developer.amazon.com/docs/fire-tv/fire-os-overview.html). Para firmwares anteriores ao Android 5.0 (API 21), uma edição separada precisaria de interface sem Compose, ferramentas nativas compatíveis e validação das dependências de mídia e dos codecs. O suporte a esses firmwares segue em investigação.

O alvo anterior a investigar é o firmware original do Stick de primeira geração
(`AFTM`). [Relatos da época documentam a atualização direta de Fire OS 3
para Fire OS 5](https://www.golem.de/news/fire-os-5-fire-tv-geraete-erhalten-grosses-update-1602-119089.html).
O Fire OS 3 era baseado no Android 4.2 (API 17). Ainda falta confirmar um
aparelho com esse firmware e a disponibilidade de distribuição pela loja.

O protótipo em `fireos3-probe` usa Android Views e o NDK r23, a última série
com suporte à API 17 segundo o [histórico do NDK](https://developer.android.com/ndk/downloads/revision_history).
O build gera bibliotecas ARM de 32 bits e x86. O protótipo testa a navegação
por setas e OK, o carregamento JNI do parser HTTP `llhttp` usado pelo UxPlay
e a decodificação de um quadro H.264 via `MediaCodec` Java. Compile com
`./gradlew -PbuzinFireOs3Probe=true :fireos3-probe:assembleDebug :fireos3-probe:lintDebug`.
O protótipo tem um ID de pacote próprio e serve para teste local.
O emulador Android 4.2 x86 passou na navegação, na decodificação H.264 e no
teste do parser. Também exibiu um quadro H.264 recebido via HTTP do host.
A biblioteca ARM foi compilada para API 17. O emulador
instalado no Windows rejeitou a imagem ARM da API 17; a execução ARM
continua pendente em um ambiente compatível ou no Stick físico.

O protótipo 0.2 já tem uma ponte de vídeo persistente: callback nativo,
fila limitada, SPS/PPS Annex-B e `MediaCodec` Java com Surface. Acrescenta
mDNS/DNS-SD com TXT e porta reais do receptor, limitado a uma interface
IPv4 privada. Áudio, HLS, fotos e H.265 ainda não são anunciados.
Dois ciclos de autoteste passaram na API 17, incluindo consulta multicast,
RTSP e vídeo sintético. Isso não é uma sessão real com um emissor Apple.

O build de produção atual vincula `libmediandk`, que começa na API 21;
o protótipo não vincula essa biblioteca. Na prova 0.3, os decodificadores
FFmpeg e o buffer temporal da produção também compilam para API 17; o teste
leva ALAC/AAC sintéticos até um AudioTrack real, silenciado no teste.
O áudio de uma sessão de rede e o sincronismo A/V ainda não estão integrados.
Ainda faltam demais protocolos,
telas completas e paridade funcional, além de validação com emissor real e
Stick antigo. A meta permanece a mesma aplicação por geração, com variantes
regionais de download e gravação. Consulte o progresso em [TESTING.md](TESTING.md).
