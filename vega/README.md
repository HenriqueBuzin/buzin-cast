# Buzin Cast para Vega OS

Port separado com SDK oficial 2.0.13986, React Native 0.83 e integrações C++23.
Identidade `com.buzincast.tv`; logo e ícone aprovados preservados. As duas
edições Fire OS mantêm sua interface nativa e o código já testado.
O Vega ainda é uma prévia: não recebe nem anuncia AirPlay, DLNA, FCast ou Cast V2.

## Estado verificado em 5 de novembro de 2026

- VPKGs release ARM64, ARMv7 e x86_64 compilados e aprovados na validação ABI.
- Aplicativo instalado e executado no VVD ARM64; controle, foco, páginas de
  estado/mídia/Sobre e Voltar verificados, incluindo revisão visual do macOS.
- Sockets TCP/UDP, bind de rede e adesão multicast testados no sandbox.
- Fontes compartilhadas UxPlay, libplist, llhttp e PlayFair compiladas com
  OpenSSL 3.5.9 LTS. Parser RTSP fragmentado, rejeição de mensagem inválida e
  vetor AES-CTR NIST aprovados. Isso ainda não inicia um receptor AirPlay.
- Página Mídia consulta o módulo público Vega Media Descriptor: o VVD informou
  zero resultados H.264/HEVC e um AAC. Lista vazia também pode indicar erro da
  consulta; esses números não comprovam reprodução ou capacidades de TVs físicas.
- Reprodução local com VideoPlayer/superfície nativa: amostra H.264/AAC de oito
  segundos incluída no pacote. Imagem visível, pausa/retomada, fim da amostra,
  reabertura e liberação no segundo plano passaram no VVD. Som audível e
  sincronismo com emissores físicos ainda não foram medidos.
- Lint sem avisos, TypeScript, doctor e 12 testes Jest passaram.

## Ambiente e build

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

O script recusa TVs físicas. Exercita o controle real, os sete resultados
nativos, consulta de mídia, Sobre/Voltar e repetição. Resultados e comandos ficam
no diretório indicado. Também verifica a amostra local, pausa/retomada,
fim, reabertura e segundo plano. Os relatórios do sandbox
`/data/network-probe.json`, `/data/media-capabilities.json` e
`/data/playback-state.json` guardam somente resultados, estados e segundos,
sem mídia ou endereços. O runner `build/<arquitetura>-release/bin/buzin-network-probe`
é um artefato separado e não integra o VPKG.

## Trabalho restante

Integrar amostras AirPlay/MSSE, relógios, descoberta e receptores;
portar configurações e os demais recursos Fire OS. Recepção com emissores reais,
sincronismo e sessões longas dependem de validação em Fire TV Vega físico.
Somente ARM64 foi executada no simulador. O build mantém o aviso dlopen do
OpenSSL e a auditoria npm tem pendências nas ferramentas Amazon; não está
pronto para publicação. A meta de cobertura global ainda não foi atingida.

A [retomada](../docs/VEGA_HANDOFF.md) registra resultados, limites e próximos
passos. O plano para Vega OS 1.0 a 2.0 está em
[versões em paralelo](../docs/VEGA_OS_VERSIONS.md). Consulte também [testes](../docs/TESTING.md) e
[dependências/licenças](../docs/DEPENDENCIES.md). Preservar os avisos em
`assets/raw/licenses` e os termos das ferramentas Amazon.
