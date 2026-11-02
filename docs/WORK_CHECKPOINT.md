# Retomada — 2 de novembro de 2026

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

- Repositório Windows: `C:\Users\henri\Documents\GitHub\buzin-cast`.
- Build WSL Ubuntu-20.04: `/var/tmp/buzin-cast-build`.
- Ferramentas WSL: `/var/tmp/buzin-cast-tools`; JDK `jdk-25.0.5+8`, SDK `android-sdk`, Gradle user home `gradle`.
- Logs/artefatos: `C:\Users\henri\Documents\Codex\2026-10-31\va\work` e `...\outputs`.
- APKs atuais: `outputs\buzin-cast-0.6.0.apk` e `outputs\buzin-cast-0.6.0-compat.apk`. Evitar o antigo arquivo `buzin-cast-0.6.0-debug.apk`.
- Principal SHA256: `32aa025b06ddd21b2a7c19fefa335e65de67cb7eebe9dc331ccc79f119457a29`.
- Compatível SHA256: `0ca7d21746c8060a78f2c3200b94e76d9e098e9780358d123c714a308f2e59a4`.
- Resultados recentes em `outputs\buzin-cast-0.6.0-checks`: `stable-isolated-api36`, `stable-calibration-media-api36`, `compat-isolated-smoke-api22`, `compat-isolated-ui-api22`, `compat-media-api22`. A falha de calibração na primeira pasta foi resolvida pela execução direcionada na segunda; preservar os registros anteriores para rastreabilidade.
- Relatório principal mais recente no WSL: `build/coverage-report/summary.json`, gerado de `app/build-coverage/reports/jacoco/fullCoverageReport`. Consolidar também o relatório completo da edição compatível ao retomar.
- Emuladores próprios: `emulator-5580` (API 36) e `emulator-5582` (API 22). Não matar o servidor ADB global; ele compartilha as conexões com as TVs.

## Fire Sticks e próximos passos

Atualização concluída: a edição principal 0.6.0/código 22 foi instalada com sucesso nos três IPs abaixo. O cache da Appstore foi limpo pela opção **Limpar cache**, sem apagar dados; os aparelhos foram reiniciados e reconectados ao ADB. A logo aprovada foi confirmada visualmente nas três listas de apps. Capturas locais: `outputs/cozinha-apps-final-060.png`, `outputs/quarto-apps-final-060.png`, `outputs/sala-apps-final-060.png`. A cozinha exibiu um aviso de controle remoto após reiniciar; ele foi dispensado com OK.

A arte permanece idêntica. `docs/approved-brand.json` e `tools/verify_brand.py` protegem os arquivos e referências do launcher no CI. Só alterar a identidade mediante pedido explícito de Henrique. Não confundir falha de cache do sistema com autorização para redesenhar a logo.

- Cozinha/Samsung: `192.168.0.100:5555`.
- Quarto: `192.168.0.121:5555`.
- Sala: `192.168.0.153:5555`.
- Os três são AFTMM/API 25 e receberam a edição principal. Instalação e conferência do ícone/cache concluídas; retomar o desenvolvimento/testes pendentes depois, conforme solicitado.
- Preservar a logo aprovada: B à esquerda, Buzin acima e CAST abaixo à direita, sem borda externa. O launcher Fire OS pode manter cache após atualizar o APK; antes de limpar dados, verificar logs e tentar apenas cache/reinício do launcher. Não apagar dados/configurações do usuário.
- CI de `8019bae`: execução `41528642737`. A anterior `41524350857` foi cancelada por estar superada. O commit deste checkpoint inicia outra execução; verificar o resultado mais recente no GitHub antes de afirmar aprovação da matriz de 12 APIs.
- Continuar elevando cobertura real de todo o código próprio Android/C++, sem excluir arquivos por baixa cobertura. Ainda faltam caminhos de erro, USB/mDNS, gestos, controles e diversas transições. Emuladores não certificam sincronismo, Wi-Fi, desempenho, 4K ou comportamento de todos os Fire OS físicos. Vega fica fora do escopo.
