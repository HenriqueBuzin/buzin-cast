# Ferramentas locais para Fire TV Stick

Os scripts usam ADB já autorizado e exigem que a TV esteja livre. Configure
o SDK Android/Python com Pillow no computador; nenhum script instala SDKs.

Passe um IP/serial diretamente ou copie `firesticks.example.json` para
`firesticks.local.json` e cadastre apelidos para seus aparelhos. O arquivo
local é ignorado pelo Git. Nunca coloque IPs domésticos, chaves, senhas ou
arquivos de assinatura nos exemplos públicos.

```powershell
./tools/Test-FireStick.ps1 -Fire meu-stick
./tools/Test-FireStick.ps1 -Fire meu-stick -Suite store -RestoreProduction
./tools/Test-FireStick.ps1 -Fire meu-stick -Suite production
./tools/Test-FireStick.ps1 -Fire meu-stick -Suite store -Locales pt-BR,en-US,de-DE
./tools/Atualizar-logo-BuzinCast.ps1 -Fire meu-stick
./tools/Atualizar-logo-BuzinCast.ps1 -Fire meu-stick -Restart -OpenScreenshot
./tools/FireStick.ps1 -Fire meu-stick -Action Info
./tools/FireStick.ps1 -Fire meu-stick -Action Install -ApkPath ./outputs/app.apk
./tools/FireStick.ps1 -Fire meu-stick -Action Open
./tools/FireStick.ps1 -Fire meu-stick -Action Screenshot
./tools/FireStick.ps1 -Fire meu-stick -Action Capture -Locales pt-BR,en-US
python ./tools/audit_public_repo.py
python ./tools/audit_public_repo.py --history
```

`FireStick.ps1` reúne Info, Install, Open, Stop, Screenshot, Apps, Cache,
Capture e Uninstall. Fora de Capture, `Edition both` usa a edição principal;
`-Edition deat` direciona a regional. `Install` usa a identidade do APK passado
e falha se houver conflito de assinatura; não desinstala silenciosamente a
instalação anterior. `Uninstall` é explícito e apaga os dados da edição.
O auditor do repositório lista apenas arquivos/linhas e tipos de ocorrência;
nunca imprime o valor encontrado. Ele verifica alguns padrões conhecidos e
não garante detectar todo tipo de segredo.

`amazon_media_ui.mjs` contém rotinas reutilizáveis para o tab API documentado
do CUA: selecionar idioma, remover capturas em lotes de duas, iniciar upload
de três arquivos e conferir contagem/dimensões. É carregado pelo agente em
uma sessão autenticada no console. Cada chamada é limitada para permitir
conferência entre etapas. Não usa APIs privadas, não armazena credenciais,
não publica apps e não confirma declarações legais.
Depois de importar o módulo no REPL CUA, chame
`media.setPointFactory((x, y) => [x, y])` para construir as coordenadas no
mesmo contexto das chamadas nativas. O helper sempre obtém uma observação
atual antes de clicar; após mudança de rolagem/foco ele pode conferir e
recalcular o alvo, sem reutilizar coordenadas antigas.

## Capturas e verificação de abertura

### Desenvolvimento da próxima versão

`Build-FireOs.ps1 -Target modern|compat|probe -PosixBin <bin-do-MSYS2> -Check`
prepara o PATH e o NDK correspondente e compila debug; `-Abis x86_64` limita
a ABI do app para teste em emulador. `probe` usa NDK r23 e suas duas ABIs fixas.
O script exige as ferramentas instaladas, restaura o ambiente do shell no fim
e não assina produção nem publica. Execute primeiro em um ambiente de build
configurado; caches de CMake incompletos podem exigir reparo local.

O teste API 17 também consulta mDNS por UDP e RTSP, exercita o player
persistente com H.264 sintético e confere início/parada e fechamento da porta.
Faz dois ciclos e desinstala o pacote isolado ao terminar; `--cycles 1` reduz
a execução e `--keep-installed` mantém a cópia parada para investigação.
Não equivale a espelhamento de um iPhone/Mac real nem à aplicação completa.
Na prova 0.3 também decodifica ALAC, AAC-LC e AAC-ELD (480/512), confere o
buffer temporal e o relógio de saída AudioTrack com volume zero. Os pacotes
são sintéticos; não anuncia áudio no receptor persistente. Para regenerar as
amostras, consulte `LEGACY_AUDIO_FIXTURES.md`.

Os scripts `test_focus_navigation.py` e `test_fireos3_probe.py` recusam TVs
físicas e testam a nova indicação de foco e a ponte de vídeo API 17 em emulador.
Comandos e limites em `docs/TESTING.md`. O protótipo API 17 não é uma edição
publicável com paridade funcional.

O teste percorre os APKs locais em `outputs`, abre início/configurações/Sobre,
espera as animações e mede a estabilidade dos quadros. Mostra o foco do
controle na página aberta no menu lateral e mantém Fechar no Sobre.
Rejeita quadros vazios, escurecidos, com menu incompleto ou sem indicação de
foco. Salva PNGs originais e folhas de conferência em `outputs/firestick-captures`.
O relatório não equivale a aprovação visual, teste de espelhamento ou licença
da Appstore; confira as imagens antes de publicar.

`all` executa as capturas por idioma e as quatro variantes de produção;
sem `-Locales`, inclui todos os 22 idiomas da edição geral e os 21 da
regional 0.6.5, além das variantes de inglês e espanhol usadas pela loja.
Com `-Locales` (ou `-Idiomas`), captura somente os códigos BCP 47 informados.
Use `-Edition normal` para incluir sueco; a regional 0.6.5 ainda não contém
esse idioma. Uma lista incompatível é rejeitada em vez de gerar capturas
em inglês com rótulo incorreto. `-Edition deat` limita o teste à regional;
o padrão `both` testa ambas. A lista de idiomas afeta a captura com os
auxiliares; os APKs finais são testados no idioma real do sistema.
variantes com API mínima superior à do aparelho são puladas. `store` exige
APKs auxiliares com seleção de idioma em `outputs/buzin-capture-normal.apk`
e `outputs/buzin-capture-deat.apk`. Nunca envie esses auxiliares à Amazon.
Os nomes dos quatro APKs finais estão na lista `variants` do script Python;
atualize-a quando a versão mudar. A captura atualmente exige resolução real
1920×1080 e o layout da versão 0.6.6. Em outro layout/resolução, o script deve
falhar e ser ajustado, em vez de aceitar capturas incorretas.

**O teste remove as edições testadas e seus dados locais ao terminar.** Use somente
no dispositivo indicado, sabendo que a instalação anterior será substituída.
`-RestoreProduction` reinstala a versão geral ao final, sem restaurar dados.
Com `-Edition deat`, a instalação principal é preservada (seu processo é
parado durante o teste para evitar conflito de portas). Com `both`, as duas
edições são substituídas e removidas.
O script não altera fichas nem publica aplicativos na Amazon.

## Atualização do ícone na lista de aplicativos

O script de cache veio da ferramenta local “Limpar Cache FireStick”. Abre
as configurações da Amazon Appstore, encontra exatamente o botão de limpar
cache pelos rótulos configurados, aciona-o e salva a lista de aplicativos.
Não usa `pm clear`, não pressiona Limpar dados e não apaga dados do BuzinCast.
Se o idioma diferir, passe `-CacheLabels 'rótulo exato'`.
`-Restart` permite reiniciar o Stick explicitamente; o padrão não reinicia.
As capturas ficam em `outputs/cache-refresh`; `-OutputDirectory` muda o destino.
A atualização da arte também depende da imagem disponível nos servidores
da Amazon. Limpar o cache não garante que uma arte ainda não publicada apareça.
