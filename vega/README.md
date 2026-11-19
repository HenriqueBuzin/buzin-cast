# Buzin Cast para Vega OS

Port separado com SDK oficial 2.0.13986, React Native 0.83 e integrações C++23.
Identidade `com.buzincast.tv`; logo e ícone aprovados preservados. As duas
edições Fire OS mantêm sua interface nativa e o código já testado.
O Vega recebe FCast v2 experimental por URLs. O menu **Estado do port**
oferece testes de AirPlay por URL, áudio e espelhamento. O espelhamento
anuncia o receptor por DNS-SD e pede o PIN exibido na TV. O receptor
DLNA experimental anuncia por SSDP. Cast V2 está pendente.

## Estado verificado em 19 de novembro de 2026

- VPKGs release ARM64, ARMv7 e x86_64 compilados e aprovados na validação ABI.
- Aplicativo instalado e executado no VVD ARM64; controle, foco, páginas de
  estado/mídia/Sobre e Voltar verificados, incluindo revisão visual do macOS.
- Sockets TCP/UDP, bind de rede e adesão multicast testados no sandbox.
- Fontes compartilhadas UxPlay, libplist, llhttp e PlayFair compiladas com
  OpenSSL 3.5.9 LTS. Parser RTSP fragmentado, rejeição de mensagem inválida e
  vetor AES-CTR NIST aprovados. Os modos AirPlay usam a sessão UxPlay nativa.
- Página Mídia consulta o módulo público Vega Media Descriptor: o VVD informou
  zero resultados H.264/HEVC e um AAC. Lista vazia também pode indicar erro da
  consulta; esses números não comprovam reprodução ou capacidades de TVs físicas.
- Reprodução local com VideoPlayer/superfície nativa: amostra H.264/AAC de oito
  segundos incluída no pacote. Imagem visível, pausa/retomada, fim da amostra,
  reabertura e liberação no segundo plano passaram no VVD. Som audível e
  sincronismo com emissores físicos ainda não foram medidos.
- Lint sem avisos, TypeScript e 67 testes Jest passaram.
- FCast v2 experimental aceita um controlador na porta TCP 46899 e anuncia
  `_fcast._tcp.local` com TXT `v=2` e `appName=Buzin Cast`. Lint, TypeScript e
  build Release das três ABIs passaram após a integração. A descoberta ainda
  não foi observada em runtime nesta sessão.

O teste `tools/test_vega_fcast.py` passou no VVD ARM64 com HTTP e cabeçalhos
personalizados, posição inicial, pausa, busca, volume, velocidade, retomada,
Stop e fechamento pelo controle. A política HTTP do manifesto permite URLs
escolhidas pelo emissor; a validação do protocolo limita esquemas a HTTP(S).
HLS e DASH locais também passaram no VVD ARM64 pelo MSE. Descoberta e
recepção com emissores físicos, streams ao vivo e troca de qualidade ainda
precisam de validação.

AirPlay por URL passou no VVD ARM64 com MP4 HTTP, posição inicial, relógio
avançando, pausa, busca, retomada, Stop e liberação ao Voltar. É ativado em
**Estado do port → Testar AirPlay por URL**, por conexão manual em porta dinâmica,
sem descoberta ou PIN. Isso ainda não comprova recepção com iPhone/Mac, HLS por
AirPlay ou espelhamento. Identidade/chave ficam apenas no sandbox.

O nome do receptor pode ser editado em Configurações e é preservado no sandbox.
Ele será usado no próximo anúncio FCast ou AirPlay.

O menu permanece visível nas páginas de consulta/configurações e mantém a
seleção destacada enquanto o foco está no conteúdo. Voltar restaura o último
item no início. Os botões solicitam foco nativo na ativação concluída;
a navegação pelo controle passou no VVD, e toques reais/revisão visual desta
mudança ainda aguardam validação.

O modo **Estado do port → Testar áudio AirPlay** encaminha callbacks UxPlay a um
worker nativo para ALAC de 16 bits (352), AAC-LC (960/1024) e AAC-ELD
(480/512), estéreo/44,1 kHz, com fila limitada, flush e volume/mudo. ALAC usa
FFmpeg/PCM e uma sessão própria de foco; AAC usa MSSE.
No VVD passaram áudio injetado, controles/vida útil por RTSP e o ensaio de
desenvolvimento RTP/UDP com AES-CBC, NTP, duplicatas e liberação das portas.
Esse ensaio usa chaves públicas de teste; negociação completa e emissores Apple
reais ainda precisam ser validados. Este modo é manual, sem descoberta, PIN
ou vídeo.

O modo **Estado do port → Testar vídeo AirPlay** gera um PIN de quatro
dígitos ao abrir. O receptor anuncia `_airplay._tcp.local` e
`_raop._tcp.local`, exige pareamento e verificação da chave e encerra o
anúncio ao sair ou ir para segundo plano. A saída usa H.264 e AAC ou
ALAC convertido para AAC. No VVD, os testes verificaram anúncio e
encerramento, bloqueio de comandos sem PIN e transporte criptografado
com amostras sintéticas. Ainda faltam testes de descoberta, pareamento e
reprodução com um emissor Apple em aparelho físico.

**Estado do port → Ativar DLNA** permite enviar mídia HTTP(S), consultar
estado e posição, pausar, buscar, retomar, parar e ajustar volume ou mudo.
A descoberta SSDP e os comandos passaram no VVD com um MP4. O receptor
também abre JPEG e PNG de até 16 MiB e cerca de 25 megapixels. Notificações GENA e
validação em aparelho físico estão pendentes.

## Ambiente e build

O bundle inclui Shaka/MSE para HLS/DASH experimentais no FCast. Os hooks de build preparam a versão fixada pelo script
`tools/prepare_vega_shaka.py`; precisam de Git, curl, Python 3.12+ e Java 21+.
A distribuição gerada fica em `vega/generated` e não é versionada.
Os hooks também preparam o UxPlay fixado com os 19 patches Buzin numa cópia
isolada nessa pasta; inicialize os submódulos antes do primeiro build.


Use o nvm existente e o [SDK oficial Vega](https://developer.amazon.com/docs/vega/2.0/install-vega-sdk).
`.nvmrc` fixa Node 26.13.0 LTS; `package-lock.json` fixa as dependências.
React/React Native e APIs do sistema seguem o perfil OS 2.0 do SDK.
Não substituir os runtimes internos do fornecedor. Uma instalação nova com
SDK/VVD e primeiros builds precisa de pelo menos 10 GiB livres.

```sh
vega sdk install 2.0.13986
vega sdk use 2.0.13986
cd vega
nvm install
nvm use
npm ci
npm run lint -- --max-warnings 0
npx tsc --noEmit
npm test -- --runInBand
vega project doctor
npm run build:app
vega virtual-device start
```

## Teste no simulador

Na raiz do repositório, com o VVD iniciado e o pacote compilado:

```sh
python3 tools/test_vega.py --serial emulator-5554 \
  --vpkg vega/build/aarch64-release/buzincast_aarch64.vpkg \
  --output build/vega-media-checks
```

Para repetir o ensaio AirPlay por URL no VVD:

```sh
python3 tools/test_vega_airplay_url.py --serial emulator-5554 \
  --vpkg vega/build/aarch64-release/buzincast_aarch64.vpkg \
  --output build/vega-airplay-url
```

O script recusa TVs físicas. Exercita o controle real, os oito resultados
nativos, consulta de mídia, Sobre/Voltar e repetição. Resultados e comandos ficam
no diretório indicado. Também verifica a amostra local, pausa/retomada,
fim, reabertura e segundo plano. Os relatórios do sandbox
`/data/network-probe.json`, `/data/media-capabilities.json` e
`/data/playback-state.json` guardam somente resultados, estados e segundos,
sem mídia ou endereços. O runner `build/<arquitetura>-release/bin/buzin-network-probe`
é um artefato separado e não integra o VPKG.

## Trabalho restante

Integrar saída AirPlay/MSSE, relógios e receptores; portar configurações e os
demais recursos Fire OS. Recepção com emissores reais,
sincronismo e sessões longas dependem de validação em Fire TV Vega físico.
Somente ARM64 foi executada no simulador. O build mantém o aviso dlopen do
OpenSSL e a auditoria npm tem pendências nas ferramentas Amazon; não está
pronto para publicação. A meta de cobertura global ainda não foi atingida.

A [retomada](../docs/VEGA_HANDOFF.md) registra resultados, limites e próximos
passos. O plano para Vega OS 1.0 a 2.0 está em
[versões em paralelo](../docs/VEGA_OS_VERSIONS.md). Consulte também [testes](../docs/TESTING.md) e
[dependências/licenças](../docs/DEPENDENCIES.md). Preservar os avisos em
`assets/raw/licenses` e os termos das ferramentas Amazon.
