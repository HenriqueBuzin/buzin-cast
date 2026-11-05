# Buzin Cast para Vega OS

Projeto integrado a partir do template oficial `helloWorld` do SDK 2.0.13986,
com React Native 0.83 e identidade `com.buzincast.tv`. A interface continua
sendo uma prévia; ainda não há receptor ou serviço anunciado na rede.
A arte aprovada é copiada sem alterações para `src/assets/buzin_banner.png`.

## Build verificada no Mac

Em 4/11/2026, `npm run build:app` gerou VPKGs release ARM64, ARMv7 e
x86_64, com logo e ícone aprovados. `vega project doctor`, lint sem avisos,
TypeScript e três testes Jest passaram. O pacote ARM64 passou em `vpt validate`.
A imagem do simulador está sendo instalada; a execução ainda não foi validada.
Os registros abaixo descrevem a primeira tentativa e são históricos.

## Ambiente e comandos

Use o nvm já instalado no Mac e o [instalador oficial Vega](https://developer.amazon.com/docs/vega/2.0/install-vega-sdk).
Reserve pelo menos 10 GiB livres para a instalação e os primeiros builds;
o espaço real varia com caches e imagens do simulador.

```sh
vega sdk install 2.0.13986
vega sdk use 2.0.13986
cd vega
nvm install
nvm use
npm ci
npm run lint
npx tsc --noEmit
npm test -- --runInBand
vega project doctor
npm run build:app
vega virtual-device start
# Depois de confirmar a geração do pacote:
# vega run-app <caminho-do-pacote.vpkg>
```

`.nvmrc` fixa Node 26.13.0 LTS. `package-lock.json` fixa a árvore npm.
Não usar o Node 21 do host nem substituir o runtime interno do SDK.
As versões React/React Native seguem o perfil oficial do Vega 2.0; atualizar
com a matriz do SDK, sem presumir compatibilidade de uma versão upstream.
A política de versões estáveis/LTS e as pendências de dependências estão em
[DEPENDENCIES.md](../docs/DEPENDENCIES.md).

## Validação no Mac em 4 de novembro de 2026

- Apple Silicon arm64, macOS 27.2; CLI Vega 1.4.7 instalada.
- Template gerado fora do repositório e integrado com manifesto, configurações e lock.
- Manifesto: Vega mínimo/target 2.0, launcher `com.amazon.category.main`.
- `npm ci`, teste de renderização e TypeScript passaram. Lint verificado.
- Bundle JavaScript, mapa e bytecode Hermes gerados pela build release.
- **VPKG não gerado e simulador não executado.** A instalação do VVD foi
  interrompida por falta de espaço. O gerenciador reverteu também o SDK;
  `vega --version` mostra apenas a CLI até reinstalar o conjunto.
- `vega project doctor`, antes da reversão, aceitou o manifesto e a dependência
  gerenciada. A árvore transitiva ainda não estava instalada nessa conferência.
- O audit npm mantém vulnerabilidades nas ferramentas do template, incluindo
  dependências Amazon sem correção indicada. Não tratar esta base como pronta para publicação.

Acompanhe os próximos passos em [VEGA_HANDOFF.md](../docs/VEGA_HANDOFF.md).
Controle remoto, persistência, rede, módulo nativo, codecs e receptores ainda
precisam ser implementados e testados. Recepção real, sincronismo e sessões
longas continuam dependendo de Fire TV Vega físico.
