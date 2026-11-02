# Revisão de arquitetura do Buzin Cast

## Implementação 0.6.0

As primeiras extrações foram realizadas: módulo JVM `receiver-core` com `SessionCoordinator`, protocolos tipados, tokens de geração, perfis negociados, recuperação, `ReceiverGroup` e logs assíncronos. DLNA/FCast/Cast V2 agora dependem de `ReceiverPlaybackPort` e de um Context, sem acessar o serviço ou o ExoPlayer diretamente. `AirPlayReceiverAdapter` possui a criação, inicialização e destruição do motor JNI; configurações de renderização e callbacks permanecem no host, preservando a integração nativa.

O player vincula seus callbacks e controles remotos à geração da sessão. Uma parada AirPlay não encerra mídia local/FCast de outra origem. A navegação lateral foi extraída para `TvSidebar`, compartilhada pelo app e pelo suporte de teste de UI. Logs carregam e persistem em um worker com fila e histórico limitados, fora do construtor/fluxo de UI. Os perfis consultam capacidades AVC anunciadas pelo aparelho; isso não garante desempenho sustentado nas condições reais da rede.

Os detalhes de execução, gates de cobertura e limites estão em [TESTING.md](TESTING.md). O serviço ainda é o host Android da integração; futuras extrações podem reduzir seus callbacks e separar os grupos de Configurações sem reescrever o motor que funciona.

## Avaliação original

Revisão estática em 01/11/2026, durante a versão 0.5.7. Abrange os caminhos centrais de serviço, interface, preferências, player, descoberta, aprovação, gravação e retransmissão, além do pipeline de build. Não representa auditoria integral de todas as dependências ou validação de todos os emissores físicos.

## Avaliação

Há uma base funcional para evoluir: separação entre renderizadores, ponte JNI e UI; StateFlow para estado; limites de quadros e concorrência nos receptores; filas limitadas na gravação/retransmissão; testes de domínio e build/lint no CI. A principal dívida é concentrar o controle de vários protocolos e modos de reprodução no mesmo serviço e ViewModel.

O caminho recomendado é preservar o núcleo AirPlay que já funciona e extrair responsabilidades com testes de comportamento. Não trocar de motor ou reescrever tudo para organizar pastas.

## Prioridades encontradas

### 1. Um controlador de sessão compartilhado

`AirPlayService.kt` reúne início/fim do nativo, descoberta, PIN, aprovação, áudio, player, gravação, retransmissão e notificações. `FCastReceiver.kt` e `CastV2Receiver.kt` dependem diretamente desse serviço e acessam seu player; a posse do player usa strings como `fcast` e `castv2`.

Proposta: extrair `SessionCoordinator` com comandos tipados e uma sessão identificada por protocolo, geração e dispositivo. Ele decide se pode aceitar outra origem e publica um estado coerente: parado, aguardando aprovação, conectando, transmitindo, recuperando ou encerrando. Cada callback tardio precisa carregar a geração da sessão. Hoje há proteções locais, como `videoPlaySeq` e `generation`, que devem ser preservadas e unificadas gradualmente.

Benefício: reduzir conflitos ao alternar AirPlay, vídeos por URL e outra TV. Risco arquitetural identificado; não foi demonstrada uma falha física em todos esses cruzamentos nesta revisão.

### 2. Tirar persistência de logs do caminho da interface

`MainViewModel.addLog()` chama `persistLogsLocked()`, que reescreve a lista inteira em disco enquanto mantém o lock. A carga inicial lê o arquivo no construtor do ViewModel. A lista é limitada a 9.999 linhas, mas o custo por mensagem aumenta conforme cresce.

Proposta: `LogRepository` com fila limitada, escrita em IO por lotes, rotação por tamanho e exportação de um snapshot. Manter ordem e descarte explícito sob pressão. Isso remove I/O síncrono de quem chama o ViewModel. Ainda é necessário medir o impacto real no Fire OS; não se atribui toda travada observada a esse trecho.

### 3. Contrato único de navegação para TV

Os campos editáveis espalhados por Configurações e pelo formulário de retransmissão permitiam ativar entrada de texto apenas ao ganhar foco. A 0.5.7 introduz controles próprios: uma configuração abre edição após OK; a retransmissão usa um único formulário, com setas para passar pelos campos e OK para digitar.

Próximo passo: catálogo pequeno de componentes TV (ação, alternância, seleção, edição e diálogo), com retorno de foco definido. Separar `MainScreen` em navegação, tela de conexão e telas de mídia; separar Configurações por grupos sem alterar os dados salvos.

Nesta revisão também foi corrigido o helper de solicitação de foco: ausência de exceção não significava que o pedido tinha sido aceito. Ele agora verifica o retorno booleano e repete pedidos recusados. Em teste por teclas, sair da edição devolveu o foco ao nome do receptor.

### 4. Ciclo de vida verificável por protocolo

Os receptores usam uma combinação de Handler, threads, executores e callbacks JNI. Já existem `close()`, deadlines e limites importantes; mantê-los é essencial. Inicialização/finalização continuam coordenadas pelo serviço.

Proposta: contrato `ReceiverAdapter` com start/stop idempotentes, estado, capacidades e erros tipados. O adaptador AirPlay encapsula JNI; DLNA, FCast e Cast V2 oferecem comandos comuns ao coordenador. Não colocar o processamento de áudio/vídeo em uma fila de UI. Acrescentar testes de parar durante conexão, rede mudando, callback depois do encerramento e liberação de portas/locks após falha.

### 5. Separar qualidade da interface de capacidade de reprodução

A UI usa dp/sp e vetores; codecs e resolução efetiva dependem de hardware, emissor e protocolo. O experimento Cast V2 não possui identidade Google certificada. Esses limites devem continuar explícitos.

Proposta: `DeviceCapabilities` com codecs, resoluções e modo de baixa latência realmente anunciados pelo dispositivo. Exibir opções pertinentes, preservar fallback e registrar a configuração negociada. Evitar prometer 8K de vídeo apenas por a interface escalar.

## Organização proposta

Começar por pacotes e interfaces dentro do app; criar módulos Gradle somente quando houver uma fronteira estável ou benefício de testes/reuso.

```text
UI de TV / UI de toque
        ↓ comandos / estado
SessionCoordinator + SettingsRepository + LogRepository
        ↓ contratos
AirPlayAdapter | DlnaAdapter | FCastAdapter | CastV2Adapter
        ↓ reprodução
MirrorPipeline | MediaPlayer | CaptureController | RelayController
        ↓ plataforma
JNI / MediaCodec / AudioTrack / rede / arquivos
```

O módulo `dlna-domain` já existente continua isolado. O primeiro código extraído deve ser estado/comandos de sessão e logs, antes de multiplicar módulos.

## Sequência de evolução

1. Consolidar regressões de TV: percorrer menu com setas sem teclado, OK editar, Voltar cancelar, voltar ao mesmo controle e atravessar listas longas. Validar também atualização de APK e ícone no launcher real.
2. Extrair logs e medir abertura/troca de tela em Fire OS 6. Registrar mediana/p95 de frames, memória e filas sem coletar conteúdo transmitido.
3. Introduzir coordenador de sessão e migrar um protocolo por vez; manter adaptador de compatibilidade para o serviço atual durante a transição.
4. Padronizar ciclo de vida e erros dos receptores. Testes de concorrência, falhas e sessões longas passam a ser condição para promover um recurso experimental.
5. Preparar release: assinatura de produção, atualização preservando preferências, avisos completos de dependências e matriz física de Fire OS/modelos. Vega permanece fora do escopo atual.

## Ideias de produto após a estabilização

- Assistente inicial curto: nome da TV, calibração e demonstração dos quatro cantos.
- Descoberta das outras TVs Buzin na rede, com confirmação por código, evitando digitar IP e porta.
- Indicador discreto de qualidade: codec, resolução negociada, perdas e recomendação de perfil. Mostrar detalhes só quando solicitado.
- Reconexão e diagnóstico com mensagens específicas por protocolo, diferenciando recuperar anúncio, vídeo por URL e espelhamento que exige ação do emissor.
- QR local para instruções e envio de legendas, com serviço opt-in e sessão temporária, caso seja desenvolvido. Não expor painel de controle aberto automaticamente.
- Favoritos de TVs e calibração por aparelho. Automação de perfis somente quando não interromper uma sessão ativa.

Esses itens são propostas, não funcionalidades já implementadas.

## Identidade e distribuição

O launcher Fire OS registrou falha Glide/ContentResolver ao abrir o recurso de ícone após substituir o APK; o arquivo e seu ID continuavam presentes no pacote. Isso demonstra a falha de carregamento do launcher, sem provar sozinho a causa interna do cache. A arte aprovada permanece. Atualizações locais precisam incluir uma conferência do launcher; a publicação Amazon também exige suas próprias imagens de catálogo.

Henrique Buzin é identificado como criador e mantenedor das contribuições Buzin Cast. A base GPL v3 exige preservar seus termos e créditos; a distribuição oficial gratuita não elimina a permissão de uso comercial concedida pela GPL. Consulte [autoria e licença](../AUTHORS.md).
