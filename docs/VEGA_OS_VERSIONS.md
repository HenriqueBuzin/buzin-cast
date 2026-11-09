# Vega OS 1.0 a 2.0 — versões em paralelo

Levantamento de 9 de novembro de 2026, no Mac arm64 / macOS 27.2, com Vega CLI
1.4.9. Alvo definido por Henrique: **Vega OS 1.0, 1.1, 1.2 e 2.0**.

Este arquivo descreve o plano. Hoje só existe a linha `vega/`, com mínimo 2.0.
Nenhum build para OS 1.x foi feito ou testado.

## Matriz

| Vega OS | SDK disponível | React Native | Linha | Situação |
|---|---|---|---|---|
| 2.0 | 2.0.13986 | 0.83 | `vega/` | Em desenvolvimento |
| 1.2 | 2.0.13986 com mínimo 1.2, ou 0.24.x | 0.83 | `vega/` | Viável, não iniciado |
| 1.1 | 0.21.x a 0.23.x (mais novo: 0.23.9221) | 0.72 | Nova, separada | Viável em tese, não iniciado |
| 1.0 | Nenhum listado | Desconhecido | — | Bloqueado |

`vega sdk list-remote` lista do 2.0.13986 ao 0.21.4652 (setembro de 2025), o
mais antigo. As notas do SDK 0.21 já exigem OS 1.1. A documentação atual não
descreve OS 1.0. Não anunciar suporte a 1.0 sem SDK que compile e meio de testar.

## Por que são duas linhas

- O runtime React Native 0.83 só existe a partir do OS 1.2. Um app 0.83 não pode
  ter mínimo abaixo de 1.2; o menor `min` aceito nesse caminho é `1.2`.
- Cada app roda em exatamente um runtime, definido pelas dependências
  `@amazon-devices/react-native-*`. Portanto OS 1.1 exige outro projeto com
  React Native 0.72, e não uma opção do projeto atual.
- OS 1.2 e 2.0 cabem em uma só linha pelo intervalo do manifesto.

## Linha `vega/` — OS 1.2 a 2.0

Para baixar o mínimo, usar o comando oficial, sem editar o manifesto à mão:

```sh
vega project update-manifest --os-min 1.2 --os-version 2.0
```

Depois disso:

- Proteger com `isPresentOnOS` (`@amazon-devices/kepler-compatibility`) as APIs
  mais novas que o mínimo.
- Conferir se `react-native-w3cmedia ~2.3.0` e `keplermediadescriptor ~1.1.0`
  existem no OS 1.2.
- Testar no mínimo e no alvo, não só em 2.0.

A Amazon registra que `min = "1.2"` adiciona trabalho sem alcançar mais aparelhos
hoje, pois todos os aparelhos Vega recebem as atualizações e o OS 2.0 já chegou
a todos. A decisão de incluir mesmo assim é de produto.

## Linha separada — OS 1.1 (ainda não criada)

- Gerar pelo template do SDK 0.23.9221 em uma pasta nova, por exemplo
  `vega-rn072/`, ao lado de `vega/`, com manifesto, lock e `.nvmrc` próprios.
- Não duplicar lógica. Compartilhar o C++ de protocolo (`vega/cmake`, submódulos
  em `app/src/main/cpp/third_party`) e o TypeScript sem dependência de runtime,
  como `fcastProtocol.ts`. A forma de compartilhar ainda precisa ser escolhida.
- Refazer a verificação dos módulos nativos: Turbo Module, W3C Media e Media
  Descriptor têm linhas próprias para React Native 0.72.

## Ferramentas lado a lado

Os SDKs convivem em `~/vega/sdk`; só um fica ativo globalmente. Neste Mac já
estão instalados 2.0.13986 (ativo) e 0.23.9221. Para trocar:

```sh
vega sdk use 0.23.9221
vega sdk ls
```

Para não depender do SDK global, cada linha pode fixar o seu com um
`vega-sdk-requirements.json` na própria pasta (documentado, ainda não testado):

```json
{"vegaSdkVersion": "0.23.9221"}
```

O Node é por pasta, via nvm: `vega/.nvmrc` fixa 26.13.0. A linha 0.72 terá o seu
`.nvmrc`, com a versão aceita pelo SDK antigo, ainda a confirmar. Rodar
`nvm use` ao entrar em cada pasta.

Cada linha compila em seu próprio `build/`. Não trocar o SDK global enquanto
outro build estiver em andamento.

## Em aberto

- **Publicação:** a documentação lida não diz se a loja aceita VPKGs separados
  por versão de OS no mesmo app. Confirmar antes de criar a segunda linha.
  Pelo manifesto, `min = "1.2"` e `target = "2.0"` indicam um só pacote para a
  primeira linha.
- **Teste em 1.1:** confirmar se o VVD do SDK 0.2x ainda inicia neste Mac. Os
  aparelhos em 1.1 são elegíveis ao 2.0, então achar um físico nessa versão pode
  ser difícil.
- **Convivência:** não confirmado se um app 0.72 feito com SDK 0.23 também roda
  em OS 1.2 e 2.0. O SDK 2.0 ainda lista o runtime 0.72, com suporte anunciado
  até 2027 e sem novos recursos.
- **OS 1.0:** procurar um canal oficial de SDK; hoje não há.

## Fontes

- [Versão de OS alvo](https://developer.amazon.com/docs/vega/2.0/target-os-version)
- [Versão de OS no manifesto](https://developer.amazon.com/docs/vega/2.0/manifest-os-version)
- [Compatibilidade entre versões](https://developer.amazon.com/docs/vega/2.0/upgrade-os-version-compatibility)
- [Gerenciar versões do SDK](https://developer.amazon.com/docs/vega/2.0/sdk-manager)
- [Notas do SDK 0.21](https://developer.amazon.com/docs/vega/0.21/vega-release-notes)
- [Notas do SDK 0.24](https://developer.amazon.com/docs/vega/0.24/vega-release-notes)
- [React Native 0.83 no SDK 0.24](https://community.amazondeveloper.com/t/react-native-0-83-available-on-sdk-v0-24/28929)
