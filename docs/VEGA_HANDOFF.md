# Retomada no Mac — paridade entre Vega OS e Fire OS

Atualizado em 4 de novembro de 2026, após alinhamento com Henrique.

## Objetivo autorizado

Levar Vega OS, Fire OS 6+ e Fire OS 5 ao mesmo nível de recursos e experiência do Buzin Cast. As duas edições Fire OS já compartilham o código do aplicativo. Vega precisa de um port próprio; a tela de prévia não representa um receptor funcional.

Henrique tem outro computador com macOS, onde o Codex continuará o trabalho. Ainda não há Fire TV com Vega OS disponível. Usar o simulador para avançar e registrar separadamente o que exige aparelho físico. Fazer commit ao terminar cada entrega verificável.

## Estado conferido no repositório

- Fire OS 6+: `app`, versão `0.6.0`, código `22`, mínimo API 25.
- Fire OS 5: mesmo módulo e fontes, com `-PbuzinLegacy=true`, versão `0.6.0-compat`, código `21`, mínimo API 22. Há dependências compatíveis e caminhos específicos para o sistema antigo, inclusive áudio. Não duplicar o aplicativo em uma terceira implementação Android.
- Vega OS: `vega/App.tsx` apenas exibe a marca e a mensagem de prévia. Não há projeto completo gerado pelo SDK, manifesto Vega, módulo nativo, descoberta, receptor ou pipeline de áudio/vídeo nessa pasta. Não há VPKG compilado ou validado registrado.
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
- Os relatórios antigos em `C:\Users\henri\Documents\Codex\2026-10-31\va\outputs` e o build WSL em `/var/tmp/buzin-cast-build` são locais do Windows; não estarão no clone do Mac. Não transcrever métricas sem conferir os relatórios. Reexecutar ou obter os arquivos necessários.
- Não instalar builds, reiniciar ou limpar dados das TVs da rede durante a preparação no Mac. Os três Fire Sticks anteriormente usados rodam Fire OS, não Vega.
- Preservar licenças, créditos, patches UxPlay e avisos de terceiros. Registrar novas dependências e suas obrigações.

## Prompt para o Codex do Mac

> Continue o Buzin Cast neste repositório. Leia `docs/VEGA_HANDOFF.md`, `docs/WORK_CHECKPOINT.md`, `docs/TESTING.md`, `docs/FEATURES.md` e `vega/README.md`. Quero levar Vega OS ao mesmo nível das duas edições Fire OS, preservando os recursos, a logo aprovada e o código Android compartilhado. Este é o Mac de desenvolvimento; ainda não tenho Fire TV com Vega OS. Prepare o SDK oficial, gere e integre um projeto Vega reproduzível, compile e teste no simulador, e avance no port dos receptores com as APIs reais do SDK. Registre o que foi implementado, testado e o que ainda depende de hardware. Não declare paridade completa com base só no simulador. Atualize o ponto de retomada e faça commit ao terminar cada entrega verificável.
