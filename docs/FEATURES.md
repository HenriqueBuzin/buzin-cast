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
5. Controle de acesso separado para DLNA/FCast e aprovação de novas conexões.
6. Compatibilidade Google Cast certificada: o experimento Cast V2 não resolve a identidade exigida por apps Google. [Vibecast](https://github.com/emilsvennesson/vibecast#certificates) e [openchromecast](https://github.com/yukarikaname/openchromecast) documentam a mesma dependência. O SDK [Cast Connect](https://developers.google.com/cast/docs/android_tv_receiver/core_features) permite integração oficial em Android TV com infraestrutura Cast, não fornece por si só um receptor universal para Fire OS. O [registro de testes/publicação](https://developers.google.com/cast/docs/registration) de um app não transforma o Fire TV em aparelho certificado. Ainda falta validar emissores Cast V2 reais e obter um caminho suportado para os apps que exigem autenticação.
7. Emissor Windows/macOS e USB: projetos específicos; não vêm automaticamente com o receptor Android.
8. Sincronização multiroom AirExpress/AirPlay e múltiplas telas simultâneas: investigação de protocolo, relógio e recursos do dispositivo.
9. Validar Miracast físico, sessões longas, reconexão, 4K e versões de Fire OS; preparar a publicação Amazon.

## Limites da promessa de compatibilidade

Nenhuma versão atual reúne todas as funções dessas referências. As próximas etapas são escopo de desenvolvimento, sem garantia de compatibilidade universal. Fire OS 6+ (API 25) é o alvo atual; Fire OS 5 precisa de adaptação. Vega OS fica para depois. Conteúdo protegido segue as permissões do serviço de origem. Sem anúncios, cobrança ou temporizador comercial em todas as funções do Buzin.
