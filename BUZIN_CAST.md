# Buzin Cast

Receptor para Fire TV com Fire OS. Gratuito, sem anúncios, assinaturas ou limite artificial de tempo por sessão.

## Estado do projeto

### Navegação e autoria 0.5.7

A tela de conexão mantém as órbitas e remove o pulso que imitava um sinal. Nas configurações da TV, os campos de nome, porta, valores avançados e opções personalizadas só iniciam edição após OK. O formulário único de outra TV mostra IP em cima e porta/código lado a lado: setas percorrem os campos, OK inicia a digitação e Voltar fecha o teclado. Não há uma janela de edição extra por campo nesse formulário. O comportamento de toque em celulares continua separado.

Henrique Buzin consta como criador e mantenedor do Buzin Cast na tela Sobre e na documentação, preservando jqssun e os demais autores da base. A GPL v3 permanece, incluindo sua permissão de uso comercial. Consulte [autoria e licença](AUTHORS.md) e a [revisão de arquitetura](docs/ARCHITECTURE_REVIEW.md).

O desaparecimento do ícone após substituir o APK foi reproduzido no Fire OS 6: o launcher registrou erro Glide/ContentResolver ao abrir o recurso, embora o arquivo e seu ID permanecessem no pacote. Reiniciar o aparelho restaurou a logo aprovada, sem alterar a arte nem apagar dados. É uma falha de carregamento após atualização local; não depende de a conexão ADB estar ativa. A conferência do launcher deve fazer parte da instalação, sem prometer que toda versão do Fire OS tenha o mesmo comportamento.

Validação no emulador API 36 em modo TV: 65 passos nas configurações normais e 80 nas avançadas com resolução personalizada, sem abertura do teclado; formulário com três campos, navegação para cima/baixo sem IME, OK para digitar e Voltar seguido de Descer sem reabertura. Cancelar preservou o nome salvo. O helper de retorno de foco passou a verificar o resultado booleano da solicitação, repetindo tentativas recusadas, em vez de considerar apenas a ausência de exceção. Compilação, 144 testes existentes, lint sem erros e assinatura v2 passaram. A versão foi instalada no terceiro Fire Stick API 25; os dois anteriores seguem sem atualização nesta etapa.

### Marca e foco para TV 0.5.6

Ícone aprovado no Fire TV API 25: B à esquerda, Buzin à direita e CAST abaixo, mantendo o fundo integrado ao launcher. As variantes normais e de TV usam a mesma arte, de 128 a 512 pixels conforme a densidade. A interface mantém a marca vetorial; a resolução de vídeo depende do hardware e do emissor, separadamente da escala da interface.

O foco dos botões usa contornos vetoriais com antialias, dentro dos limites e com o mesmo formato do controle. A ampliação fracionária do botão focado foi removida. Iniciar/Parar ocupa a altura mínima de interação, evitando o contorno deslocado. A tela inicial passa para uma coluna rolável quando o espaço disponível é estreito.

Uma atividade apenas de debug permite conferir a tela inicial em resoluções simuladas, sem iniciar o receptor nem alterar preferências. Exige a permissão DUMP e não integra a versão de publicação. O novo ícone foi aprovado pelo usuário no terceiro Fire Stick; a instalação desta versão nos dois aparelhos anteriores ficou para depois, conforme solicitado.

Validação: compilação, 144 testes existentes, assinatura v2 e lint sem erros (100 avisos e uma indicação). Renderização da tela inicial conferida no emulador API 36 em 1280×720, 1920×1080 e 3840×2160, além do layout estreito com rolagem. A tentativa de 7680×4320 foi limitada pelo emulador a 7272×4320; essa escala também renderizou, mas 8K exato e desempenho em TV 8K física permanecem sem validação. Isso não acrescenta decodificação de vídeo 8K.

### Ícone integrado ao launcher 0.5.4

A arte de TV passa a ser quadrada, sem esticar o banner horizontal dentro do ícone. Usa a marca e o nome Buzin Cast com fundo cinza #5A5A5A, igual ao fundo lateral observado no launcher Fire OS 6 dos aparelhos de teste. Isso integra visualmente o ícone às faixas adicionadas pelo sistema; não remove os painéis internos do launcher. O banner horizontal continua separado para launchers que o utilizam. O redimensionamento repete os pixels da borda para evitar transparência e filetes claros introduzidos pela interpolação. Outros temas/versões podem usar cores diferentes. Preferências de imagem e transmissão não mudam.

Validado nos dois Fire Sticks API 25: mesma arte na lista, com fundo contínuo nos limites laterais do quadrado (RGB 90/90/90 na captura). Foi necessário reiniciar após a atualização e limpar o cache da Appstore, preservando seus dados, para substituir as miniaturas antigas. Compilação, 144 testes existentes, lint sem erros e assinatura v2 passaram. A entrada TvLauncher delega para MainActivity e mantém uma única opção de abertura.

### Perfis e ferramentas de transmissão 0.5.0

Ícones de launcher receberam variantes por densidade e arte horizontal para TV. No Fire OS 6, o ícone passou a aparecer após reiniciar, mas a lista ainda manteve a imagem quadrada com faixas laterais durante a última conferência. A remoção dessas faixas permanece pendente de validação; incluir o banner no APK não garante que o launcher Amazon substitua sua miniatura em cache. A versão 0.5.0 foi instalada nos dois Fire Sticks de teste.

- **Cinema, Apresentação e Jogos:** um toque aplica resolução/FPS, decodificação e buffer de áudio juntos. Cinema usa 1080p/30 e buffer automático; Apresentação, 1080p/60; Jogos, 720p/60, baixa latência e buffer de 25 ms. A mudança reinicia o receptor; não elimina a latência do protocolo ou da rede.
- **Calibração guiada:** Ferramentas → Calibrar imagem, ou Configurações → Imagem. Mostra os quatro cantos; esquerda/direita ajustam a margem entre 0 e 10%, OK salva e Voltar cancela. O ajuste fica salvo neste Fire TV, com padrão de 2%; não detecta automaticamente o recorte físico da televisão.
- **Aprovar conexões:** opção desligada por padrão, independente do PIN AirPlay, também desligado. Identifica protocolo/IP e permite aceitar ou recusar pelo controle, antes de processar a conexão AirPlay, FCast/Cast V2 ou comandos DLNA. A autorização dura 5 minutos por protocolo/IP; sem resposta em 25 segundos, recusa. Não é identificação criptográfica do emissor. A retransmissão Buzin continua usando seu código próprio.
- **Recuperação de rede:** ligada por padrão, reanuncia os receptores após uma mudança da rede local. O player tenta novamente até três vezes após erros de conexão/timeout; streams por URL podem retomar quando a origem volta. Uma mudança de interface reinicia o receptor e encerra a sessão atual; espelhamento AirPlay depende de reconexão pelo emissor.
- **Legendas externas:** botão CC em vídeos por URL, com importação local SRT/VTT UTF-8 de até 2 MB, renderização sobre o vídeo e opção de remover. Exige seletor de arquivos instalado, que pode não vir no Fire TV. Não adiciona legendas ao espelhamento nem baixa arquivos de sites.
- **Diagnóstico:** Ferramentas → Diagnóstico de conexão mostra IP, estado do receptor, VPN e configurações locais, com orientação sobre rede de convidados, isolamento e descoberta. ↑/↓ percorrem o texto. Sem conexão do emissor, não afirma ter detectado que ele está em outra rede.

Validação técnica: compilação ARM32/ARM64/x86_64, 144 testes de domínio, lint sem erros e assinatura v2. No emulador API 36, o AirPlay nativo recusou com 403 e liberou com 200; os três perfis persistiram os valores esperados; OK salvou a calibração e Voltar preservou o valor anterior. SRT e VTT apareceram sobre vídeo sintético. Uma origem HTTP inicialmente indisponível voltou a reproduzir após ficar disponível, e uma troca de rede reanunciou o receptor. Não equivale a validar reconexão de espelhamento Apple ou sessões longas de todas as funções em hardware.

### Margem padrão 0.4.7

A margem de imagem passa a ser **2% por borda como padrão em todas as novas instalações**, após o usuário confirmar o encaixe na Samsung. O ajuste continua disponível em Configurações → Imagem → Encaixe da imagem. Preferências já salvas em cada aparelho são preservadas; a TV .100 já está em 2%. Automático permanece disponível como encaixe sem margem extra. A validação visual na LG ainda será feita pelo usuário.

### Limpeza do código 0.4.6

O receptor limpa o código e o aviso de pareamento ao parar ou reiniciar, inclusive quando a opção Solicitar código é desligada. Isso evita manter um PIN antigo visível após desativar a proteção. O padrão continua desligado.

Validado no emulador API 36: solicitação RTSP de PIN respondeu 200, código de quatro dígitos apareceu e desligar a opção removeu o diálogo após reinício do receptor. O pareamento criptográfico completo com um emissor Apple ainda não foi validado. O vídeo sintético do diagnóstico foi injetado sem erro; 144 testes existentes, compilação, lint sem erros e assinatura v2 passaram. A versão 0.4.6 foi preparada e testada no emulador, sem instalar ou reiniciar a TV .100 durante o teste visual do usuário.

### Encaixe e código de conexão 0.4.5

Configurações → Imagem → Encaixe da imagem identifica o ajuste padrão como **Automático**, em vez de 0%. Ele utiliza o ajuste de proporção que já existia, encaixando o quadro inteiro na superfície disponível sem margem artificial; adapta-se à resolução da superfície e do vídeo. Não é um novo detector de bordas físicas ou de overscan do painel. Fontes com proporção diferente da TV continuam podendo ter barras para preservar a imagem inteira. As margens opcionais de 2%, 3%, 5% e 8% foram mantidas para TVs que cortam as bordas.

No teste da TV 192.168.0.100, o modo automático preencheu a tela, mas o usuário confirmou corte nas bordas físicas. A margem de 3% foi restaurada provisoriamente para manter o quadro inteiro. O Fire OS informa saída 1920 × 1080 com `overscan_enabled=0` e `overscan_values=0.0 0.0 0.0 0.0`; essa informação não detecta recortes feitos posteriormente pelo painel da TV. A marca atual é Samsung; o modelo e a configuração de imagem ainda precisam ser conferidos. O usuário também observou o corte em uma LG.

O controle de PIN existente foi organizado em **Configurações → Segurança do AirPlay → Solicitar código (experimental)**. É opcional e permanece desligado por padrão; não controla DLNA/FCast/Cast V2. O PIN é aleatório por execução do receptor, e o comportamento de novo pareamento depende do emissor. Se a geração criptográfica falhar, o receptor agora falha ao iniciar, em vez de substituir o código por 1234.

Espelhar/estender são escolhas do macOS, não modos que o receptor possa impor ao computador. A documentação Apple descreve a escolha antes de compartilhar e a possibilidade de salvar um padrão que pula essa etapa. Ainda é necessário validar a apresentação dessa janela e a extensão de tela com o Mac do usuário; não foram adicionados botões fictícios na TV. Consulte [Conexão pelo Mac](docs/MAC_CONNECTION.md).

O usuário localizou o ajuste do macOS 27.2 e confirmou que o menu para escolher estender/espelhar apareceu. Isso valida a apresentação do seletor nesse Mac, não uma sessão estendida completa. A versão 0.4.5 foi instalada na TV .100. No teste técnico, `POST /pair-pin-start RTSP/1.0` respondeu 200 e mostrou um código de quatro dígitos na TV; autenticação completa pelo Mac permanece para validação posterior. O PIN foi devolvido ao estado desligado, e a margem provisória permanece 3%. O usuário ficou responsável pela conferência visual da imagem, enquanto os testes técnicos seguem isolados da TV.

### Encerramento da abertura 0.4.4

Os círculos desaparecem gradualmente até 80% da sequência, antes da preparação da tela principal em 82%. Na 0.4.3, essa preparação pausava o relógio com os círculos ainda visíveis, que depois retomavam o movimento. Agora a preparação acontece com apenas a marca e o nome completos em tela; os círculos não reaparecem durante o fade final.

0.4.4 instalada e gravada no AFTMM/API 25. Os quadros confirmaram o desaparecimento dos círculos, a espera com a marca estática e o fade para a tela inicial, sem retomada dos círculos da abertura. Compilação/lint sem erros, 144 testes existentes passando e assinatura APK v2 verificada.

### Fluidez 0.4.3

A tela principal é composta depois que o traço, o preenchimento e o nome terminam. Sua montagem ocorre sob a marca já completa, antes do fade final; o relógio da abertura aguarda dois quadros nessa preparação. Isso retira o trabalho da tela inicial do trecho em que o B está sendo desenhado. A animação mantém 2,4 segundos de movimento, além do tempo de preparação da tela principal. Voltar, toque ou qualquer tecla continuam pulando a abertura.

Na investigação, a prévia 0.4.2 estava gravada em aproximadamente 10 fps. Uma medição independente, sem captura, também registrou um quadro de cerca de 154 ms no aparelho. O log mostrou compilação JIT da tela inicial nesse período; essa coincidência motivou a separação das etapas. Não é prova de que toda pausa observada venha da mesma causa.

Após instalar 0.4.3 no AFTMM/API 25, a repetição sem captura amostrou 118 quadros: mediana de 5,94 ms e percentil 95 de 7,47 ms. Apenas o primeiro quadro passou de 50 ms (107,58 ms); os demais ficaram abaixo de 33 ms. São medições pontuais, não uma garantia para toda abertura. A inicialização a frio ainda pode ter quadros lentos antes do trecho estável. A tecla Direita pulou a abertura e o retorno à tela principal foi conferido. A nova prévia foi capturada em 640 × 360 para reduzir o custo de gravação, sem interpolação de movimento. Compilação, lint, 144 testes existentes e assinatura v2 passaram.

### Abertura 0.4.2

O símbolo B é desenhado por um traço de luz seguindo os contornos da marca; depois o fundo menta preenche o símbolo e o nome Buzin Cast é revelado da esquerda para a direita. As ondas surgem após a formação da letra. A abertura dura 2,4 segundos, continua pulável e mantém o tamanho da marca fixo. A geometria final reproduz `buzin_mark.xml`, sem novas imagens rasterizadas.

Versão 0.4.2 instalada no AFTMM/API 25. A gravação da abertura a frio confirmou o traço progressivo, preenchimento e revelação do nome, com retorno à tela inicial. Compilação/lint sem erros, 144 testes existentes passando e assinatura v2 verificada.

### Correção 0.4.1

A abertura na TV mantém logo e nome em tamanho fixo, com fade gradual e ondas de conexão. O zoom de 22% e o deslocamento da marca foram retirados; o logo passou de 96 dp para 84 dp. O relógio da animação começa após dois quadros de composição, para reduzir saltos durante a inicialização a frio. O tema de TV usa a mesma cor de fundo e não acrescenta uma transição de janela sobre a animação do app.

APK 0.4.1 instalado no AFTMM/API 25. A abertura a frio foi gravada e os quadros inspecionados: tamanho fixo e opacidade progressiva, com retorno à tela inicial. Compilação e lint passaram sem erros; os 144 testes existentes continuam passando. A assinatura APK v2 foi verificada.

### Novidades 0.4.0

- Abertura animada na TV: a marca surge com ondas de conexão e desaparece para a tela inicial em 1,5 segundo. Pode pular pelo controle/toque; não repete na troca de abas nem bloqueia o início do receptor. Animações seguem a escala de duração do sistema.
- Identidade em movimento: órbitas discretas na tela inicial, entrada das páginas e foco com escala/contorno animados. Sem efeitos sobre a reprodução em tela cheia.
- FCast v2 experimental em Configurações → Conexão, desativado por padrão: recepção de áudio/vídeo por URL HTTP(S), incluindo MIME e cabeçalhos, posição inicial, pausa/retomada, busca, parada, volume e velocidade. Descoberta local e estados de reprodução. Um controlador; rede local confiável; não usa PIN/TLS. Manifestos em linha, fotos, playlists e versões 3/4 ainda não são suportados. Não é Google Cast.
- Cast V2 experimental, separado do FCast: TLS, descoberta e receptor padrão de mídia por URL, controles e estados. Sem a identidade certificada que os apps oficiais Google exigem; não equivale a Chromecast completo. Não oferece espelhamento Chrome nem apps de streaming específicos. Desativado por padrão; opção mostra a limitação. Detalhes e referências em [docs/FEATURES.md](docs/FEATURES.md).
- O player compartilhado identifica qual protocolo possui a reprodução para evitar que controles DLNA de uma sessão anterior interrompam uma transmissão de outro protocolo.
- Comparativo funcional e próximas etapas com referências AirScreen, AriannaCast, iMirror, AirReceiver, AirPin e FCast em [docs/FEATURES.md](docs/FEATURES.md). Ter todas as funções dessas referências continua sendo objetivo, não estado atual.

### Correção 0.3.1

Em Ferramentas → Outra TV, os campos IP, porta e código foram reunidos em um único formulário, aberto pelo botão **Conectar a outra TV**. A página principal não contém mais campos de texto, portanto navegar com as setas não ativa o teclado. O botão Voltar/Cancelar fecha o formulário e devolve o foco ao botão de conexão. No Fire TV AFTMM foi verificado: navegação até as seções inferiores sem editor/teclado aberto, abertura do formulário apenas após OK, e retorno à navegação após Voltar.

### Novidades 0.3.0

- Nova identidade com símbolo B, ícone de app e banner 16:9. Os PNGs podem ser gerados novamente com `powershell.exe -NoProfile -File tools/GenerateBrandAssets.ps1` no Windows. A marca usada na interface também possui uma versão vetorial Android em `buzin_mark.xml`.
- Tela inicial para TV com navegação lateral, estado do receptor, nome de conexão e três instruções. Textos promocionais sobre gratuidade/anúncios e avisos de DRM foram retirados das telas; o comportamento continua gratuito e sem anúncios. Diagnóstico aparece no menu apenas quando as opções avançadas estiverem ativadas.
- Nome padrão **Buzin Cast**, com alteração em **Configurações → Nome do receptor**. O nome é persistido e deve ser não vazio, sem caracteres de controle e com até 63 bytes UTF-8.
- **Configurações → Imagem → Ajustar bordas da TV**: margem simétrica de 0%, 2%, 3%, 5% ou 8%, aplicada imediatamente ao espelhamento. O padrão global é 0%; uma margem de 3% foi aplicada para teste no aparelho do usuário, que relatou imagem ultrapassando a borda da TV. Não altera o tamanho da imagem original gravada ou retransmitida.
- O renderizador agora preserva a proporção do quadro inteiro na superfície disponível, com barras quando necessário. Há testes para fonte Mac 16:10, conteúdo retrato, ultrawide e margens. A confirmação do resultado na borda física da TV depende de um novo teste com o emissor Apple.
- Direção do produto: um receptor de transmissão de referência, com identidade própria, sem anúncios, gratuito e sem temporizador comercial. A compatibilidade com cada protocolo continua condicionada à implementação e aos testes documentados; Google Cast permanece pendente.

### Novidades 0.2.0

- Aba **Ferramentas**, navegável com o controle remoto.
- Gravação manual do **espelhamento AirPlay** em MKV, sem reencodar: vídeo H.264/H.265 e áudio PCM estéreo. Prepare antes de conectar, ou reconecte para receber SPS/PPS e um quadro-chave. Não grava outros aplicativos, reprodução de URL/HLS/DLNA nem conteúdo protegido. Exibe REC em tela e uma notificação durante a gravação. Para ao desconectar ou faltar espaço, sem temporizador comercial.
- Biblioteca local com reprodução, exportação para apps compatíveis instalados e exclusão com confirmação. Diretório: armazenamento de arquivos externos privados do app, `recordings/`; removido ao desinstalar. PCM sem compressão consome cerca de 10,6 MB/min, além do vídeo.
- Retransmissão **Buzin Cast → Buzin Cast** por rede local: vídeo AirPlay já comprimido e áudio PCM. Ative Receber na segunda TV e digite IP, porta e código no emissor. Pareia com código aleatório de 12 caracteres e usa AES-GCM com chave derivada por PBKDF2, sequência autenticada e filas limitadas. O código de pareamento expira em cinco minutos. Esse protocolo próprio não é Google Cast, AirPlay emissor ou retransmissão para AirScreen.
- Visualizador de fotos **JPEG e PNG via DLNA**, com giro, ajuste/preenchimento, navegação pelas últimas 20 fotos recebidas na sessão e apresentação automática a cada 5 segundos. Imagens com limite de 16 MB e decodificação reduzida para até 3840 × 2160. Ative DLNA e mantenha o app aberto quando o sistema impedir abertura em segundo plano.
- Atalho para **Miracast nativo** nos Fire TV com o componente `com.amazon.cast.sink.DisplayMirroringSinkActivity`. Se não existir, mostra a limitação. Não implementa um novo motor Miracast, e a gravação/retransmissão não se aplica ao receptor do sistema.
- Na versão 0.2.0, Google Cast/Chromecast ainda não estava implementado. A versão 0.4.0 acrescenta o receptor Cast V2 experimental descrito acima; a compatibilidade certificada continua pendente. O Cast Connect oficial depende de Google Play Services e não fornece um receptor universal pronto para Fire OS.
- O perfil de até 3840 × 2160 já pode ser selecionado em Configurações. O limite é ajustado às capacidades do decodificador. A resolução efetiva depende do emissor, codec, aparelho e rede; 4K não foi confirmado em todos os modelos.

Os arquivos de teste de vídeo em `dlna-domain/src/test/resources/mirror/` são padrões sintéticos de 64 × 48 pixels gerados com FFmpeg. O receptor `DebugMediaReceiver` existe somente no APK de debug e exige `android.permission.DUMP`, permitindo injetar esses padrões via ADB nos testes. Não entra na compilação release.

Protótipo em desenvolvimento. A presença de código de uma função não significa que ela foi validada em todos os modelos. Dispositivo inicial: Fire TV Stick 4K de primeira geração (AFTMM), Fire OS 6.7.1.1. O mantenedor confirmou o funcionamento do AirPlay na versão 0.1.0. A versão 0.2.0 acrescenta os recursos experimentais descritos acima.

A base utiliza [android-airplay-server](https://github.com/jqssun/android-airplay-server), commit `a46cca0`, e seus submódulos fixados, incluindo [UxPlay](https://github.com/FDH2/UxPlay). Os nomes internos do código foram preservados para manter a integração JNI e a rastreabilidade. O identificador de instalação do Buzin Cast é `com.buzincast.tv`.

## Primeira versão

- Recepção AirPlay: espelhamento, áudio e reprodução de vídeo, herdados da base aberta.
- Descoberta na rede local, tela cheia e navegação por controle remoto.
- Nome do receptor configurável e PIN opcional experimental.
- Ajustes de resolução e FPS, decodificação por hardware e ajustes de áudio.
- Execução em segundo plano, conforme as permissões e restrições do sistema.
- Identidade Buzin Cast, tema escuro e instruções iniciais em português e inglês.
- Perfil inicial: H.264, 1920 × 1080, 30 fps; ajustes disponíveis nas configurações.
- Sem anúncios, rastreadores publicitários, SDK de cobrança ou temporizador comercial.
- Receptor DLNA experimental para áudio e vídeo, baseado no LeanCast, com descoberta SSDP, descrição UPnP, controles SOAP e eventos GENA. Ative em Configurações. Desativado por padrão até validação em aparelho; não usa o PIN AirPlay e exige reiniciar o receptor após trocar de rede.

## O que ainda falta validar ou implementar

1. Validar DLNA de áudio, vídeo e fotos com aplicativos emissores reais, além dos testes sintéticos.
2. Compatibilidade Google Cast certificada e apps específicos: o Cast V2 experimental já recebe mídia por URL de emissores sem exigência da identidade Google. Ainda faltam autenticação certificada, espelhamento Chrome e compatibilidade dos aplicativos; o SDK Cast Connect não fornece um receptor universal para Fire TV.
3. Miracast: o atalho nativo está implementado. Ainda falta verificar uma sessão com emissor Miracast real. Durante o teste de abertura no Fire TV, a depuração ADB perdeu a conexão; não foi confirmado o retorno à rede nem o espelhamento Miracast.
4. Validar gravação com sessões AirPlay longas, codecs/fontes diferentes e sincronização audiovisual; o muxer e o caminho de áudio nativo passaram nos testes sintéticos.
5. Validar retransmissão entre duas TVs físicas, áudio audível e latência. O teste emulador → Fire TV estabeleceu pareamento criptografado e iniciou decodificadores de vídeo/áudio. O protetor de tela ocultou a imagem; foram acrescentados abertura automática do app e manutenção de tela ligada, cuja validação final no aparelho ficou pendente após a queda do ADB.
6. Validar a apresentação de fotos com vários aplicativos DLNA e controles remotos diferentes.
7. Confirmar qualidade até 4K nos aparelhos, codecs, redes e fontes que suportarem; o seletor e os limites do decodificador já existem.
8. Controle de acesso por protocolo e ajustes de compatibilidade.
9. Testes de reconexão, sincronização audiovisual, sessões longas e uso em segundo plano.
10. Preparação e submissão de uma versão gratuita à Amazon Appstore.

Fire OS 6+ (API 25+) é o alvo de instalação atual. O APK inclui ARM de 32 e 64 bits e x86_64 para testes. Isso não equivale a validação em todas as versões de Fire OS. Fire OS 5 requer estudo separado. Vega OS fica fora desta etapa.

## Compilar

### Verificação desta versão

- APK de debug 0.4.0 compilado com ARM de 32 e 64 bits e x86_64.
- 144 testes de protocolo, autenticação, gravação, ajuste de proporção/bordas e enquadramento FCast/Cast V2 passaram, sem falhas ou testes ignorados.
- FCast no Fire TV AFTMM: versão e ping/pong, rejeição de URL local/velocidade inválida, vídeo sintético por HTTP, pausa, busca, retomada, volume e velocidade preservando pausa passaram.
- Cast V2 no Fire TV AFTMM com PyChromecast 13.1.0 como emissor independente: conexão TLS, estado/lançamento do receptor padrão, vídeo sintético por HTTP, pausa, busca, volume, mudo, retomada e parada/encerramento passaram. Isso não valida emissores oficiais Google ou descoberta automática em emissores reais. Script reproduzível: `tools/test_cast_v2.py`.
- O teste Cast V2 foi repetido após reconectar sem reiniciar o receptor: todos os controles e o estado IDLE após Stop passaram. O desafio DeviceAuth recebeu erro explícito, sem resposta de identidade Google. O ajuste TLS foi verificado no Fire OS API 25 com chave privada no AndroidKeyStore.
- Navegação no Fire TV: Configurações permanece em uma linha; Ferramentas tem zero EditTexts na página e o foco chega ao botão Conectar a outra TV sem abrir o teclado, inclusive com a escala animada de foco.
- Android Lint executado sem erros; os avisos ainda devem ser revisados antes da publicação.
- Assinatura APK v2 verificada; versão de teste, não destinada à publicação na loja.
- Instalação e abertura verificadas em emulador Android 16; receptor AirPlay iniciou sem falha de carregamento nativo.
- DLNA no emulador: descrição UPnP e SOAP responderam HTTP 200; um WAV local de teste chegou ao estado PLAYING, informou duração e progresso, e respondeu a Pause e Stop.
- Arquivos MKV com H.264/HEVC e PCM estéreo gerados pelo muxer foram inspecionados com ffprobe e decodificados pelo FFmpeg sem erros.
- Gravação sintética executada no emulador e no Fire TV AFTMM; os arquivos salvos foram recuperados e decodificados sem erros. Um teste adicional no emulador alimentou AAC no motor nativo C++, verificando a entrega do PCM pela nova ponte JNI à gravação.
- Foto enviada por SOAP/DLNA ao emulador abriu no visualizador, com imagem e controles verificados visualmente. O modo de apresentação mantém um histórico de até 20 URLs durante a sessão; não salva cópias permanentes das fotos.
- AirPlay 0.1.0 foi confirmado pelo usuário no Fire TV. A regressão com um emissor Apple real na versão 0.2.0, a recepção SSDP entre aparelhos físicos e a sincronização audiovisual em sessões longas continuam pendentes.

O código nativo usa OpenSSL e FFmpeg com ferramentas Unix. Use Linux, WSL ou macOS com Java 17+ (21 recomendado), Git, C/C++, make, Perl e Android SDK/NDK. No Windows, o caminho de compilação previsto é WSL, não o Gradle nativo do Windows.

```sh
git -c core.autocrlf=false clone --recurse-submodules https://github.com/HenriqueBuzin/buzin-cast.git
cd buzin-cast
# Defina ANDROID_HOME para o SDK do ambiente de compilação.
./gradlew :dlna-domain:test assembleDebug lintDebug
```

SDK de compilação: Android 36. NDK: 27.0.12077973. O Gradle utiliza os arquivos de versões fixados no projeto. Para compilar apenas uma arquitetura durante o desenvolvimento:

```sh
./gradlew assembleDebug -PbuzinAbis=armeabi-v7a
```

Não versionar `local.properties`, chaves de assinatura, senhas, APKs de build ou diretórios de cache. A assinatura de produção deve ser configurada pelo mantenedor antes da publicação; a chave de debug não deve ser usada na loja.

## Instalar para testar

Ative a depuração ADB no Fire TV e conecte o computador à mesma rede. Substitua o IP de exemplo pelo IP da TV e autorize o computador na tela da TV:

```sh
adb connect 192.168.1.100:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.buzincast.tv/io.github.jqssun.airplay.MainActivity
```

Abra a Central de Controle do iPhone/iPad/Mac, selecione Espelhar a Tela e escolha Buzin Cast. Rede de convidados ou isolamento entre dispositivos pode impedir descoberta. O protótipo não fornece acesso remoto pela internet.

## Licenças e publicação

O aplicativo derivado mantém a licença GNU GPL v3 e os avisos dos autores da base. Consulte `LICENSE`, `THIRD_PARTY_NOTICES.md` e as licenças nos submódulos. Ao distribuir APKs, disponibilize o código-fonte correspondente, patches, instruções de compilação e licenças. O histórico original e o README original são preservados.

Antes de anunciar uma versão como pronta: validar todos os recursos anunciados, fechar os textos de privacidade conforme o comportamento real, assinar a versão, testar navegação com controle remoto e preparar os materiais da Amazon. O envio à loja e sua aprovação ainda não ocorreram.

Conteúdo protegido por DRM não é suportado pela base AirPlay. 4K depende do fluxo de origem e do aparelho. Buzin Cast é um projeto independente, sem afiliação com Apple ou Amazon.
