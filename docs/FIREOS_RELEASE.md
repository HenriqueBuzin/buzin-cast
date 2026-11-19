# Release coordenado para Fire TV Stick

`gradle/fireos-release.properties` define a versão e os códigos das três
edições Android, em ambas as fichas. Os dois projetos Gradle leem esse
arquivo. Ao preparar a próxima versão, alterar o plano antes de compilar.

- `legacy.general` / `legacy.nodownload`: API17, códigos 48/49,
  frontend nativo `:fireos-legacy`.
- `compat.general` / `compat.nodownload`: API22, códigos 50/51,
  `:app` com `-PbuzinLegacy=true`.
- `modern.general` / `modern.nodownload`: API25, códigos 52/53, `:app`.

São seis APKs Fire OS. Vega mantém o build separado no Mac/Ubuntu.
A ficha geral usa `com.buzincast.tv`; a ficha sem download/gravação para
Alemanha e Áustria usa `com.buzincast.tv.deat` e sua própria chave pública.
Os sufixos de versão são `-legacy`, `-compat` e, na edição regional,
`-nodownload`. A prévia nativa mantém pacote separado e código 1.

A [Amazon exige códigos distintos e crescentes com a API mínima](https://community.amazondeveloper.com/t/what-does-the-error-message-failed-to-upload-binary-higher-minimum-android-api-level-requires-higher-version-code-mean/2253).
A [seleção dos dispositivos de cada APK é manual](https://developer.amazon.com/docs/app-submission/device-filtering-and-compatibility.html#how-multiple-apk-support-on-amazon-differs-from-google-play).
A numeração não substitui essa seleção. Antes de publicar, conferir também
os códigos que já existem em produção e no Live App Testing em cada ficha;
o plano local não é uma consulta ao console.

Use os mesmos grupos de aparelhos nas duas fichas. A atribuição abaixo é
o plano local e ainda precisa ser conferida no console da Amazon.

- **Nativa (48/49)** — Fire TV Stick de 1ª geração (2014, `AFTM`).
  Atribuir esse modelo à edição nativa mantém o mesmo APK para o firmware
  original Fire OS 3/API17 e para aparelhos atualizados ao Fire OS 5/API22.
- **Compatível (50/51)** — Fire TV Stick de 2ª geração e Basic Edition
  (`AFTT`), Fire OS 5/API22. Selecionar ambos se aparecerem separadamente.
- **Moderna (52/53)** — Stick 4K de 1ª geração (`AFTMM`), Fire OS 6/API25.
  No Fire OS 7/API28, selecionar Stick de 3ª geração (`AFTSSS`), Stick Lite
  e Stick HD de 2024 (`AFTSS`) e Stick 4K Max de 1ª geração (`AFTKA`).
  No Fire OS 8/API30, selecionar Stick 4K de 2ª geração (`AFTKM`), Stick
  4K Max de 2ª geração (`AFTKRT`) e Stick 4K Plus (`AFTMA08C15`).

Conferir o nome comercial de cada opção. A Amazon lista soundbars com o
mesmo código `AFTMM` do Stick 4K de 2018. Manter a seleção limitada a Sticks
e reservar o 4K Select de 2025, o HD de 2026 e o 4K de 3ª geração de 2026
para a faixa Vega. O catálogo oficial consultado em 19/11/2026 lista Fire
OS 14 e 16 como famílias do sistema, mas nenhum Stick Fire OS nessas famílias.
[Catálogo de dispositivos](https://developer.amazon.com/docs/device-specs/identify-fire-tv-devices.html) ·
[Famílias Fire OS](https://developer.amazon.com/docs/fire-tv/fire-os-overview.html).

## Conferência repetível

No Windows, o script abaixo gera os releases compatível e moderno com a
numeração do plano. Use `-Target compat` para API22 e `-Target modern`
para API25. Acrescente `-RegionalAppstoreAssets` para a ficha DE/AT.

```powershell
./tools/Build-FireOs.ps1 -Target compat -Release -PosixBin /caminho/msys64/usr/bin
./tools/Build-FireOs.ps1 -Target modern -Release -PosixBin /caminho/msys64/usr/bin -RegionalAppstoreAssets /caminho/local/chave-de-at
```

Os arquivos ficam em `outputs/fireos/coordinated`, com o nome da edição
(`compat.general.apk`, por exemplo). Para a edição nativa, use
`-Target legacy -Release` com o nome e código explícitos definidos no plano.

O comando abaixo gera manifestos de release reais nas seis configurações,
copia cada resultado imediatamente e confere o conjunto. Não compila APKs,
não instala apps e não altera a loja. O diretório regional contém somente
a chave pública da ficha DE/AT; não deve ser adicionado ao Git.

```powershell
./tools/Check-FireOsRelease.ps1 -RegionalAppstoreAssets /caminho/local/chave-de-at -Python python
```

Saída padrão: `outputs/fireos-release-manifests/report.json`. O parâmetro
`-Editions` permite um subconjunto, por exemplo `legacy.general,compat.general`.
Somente uma execução com as seis edições exige o conjunto completo.

O verificador também aceita os APKs finais, sem depender dos nomes dos arquivos:

```powershell
python tools/check_fireos_release.py --apk legacy.general=outputs/fireos/legacy-store/fireos-legacy-release.apk --apk legacy.nodownload=outputs/fireos/legacy-store-nodownload/fireos-legacy-release.apk --aapt /caminho/android-sdk/build-tools/aapt
```

Repetir `--apk EDICAO=CAMINHO` para cada arquivo e acrescentar `--require-all`
para exigir os seis. `--manifest` aceita XML gerado pelo Gradle. Não repetir
uma edição na mesma chamada. `--output` salva o JSON; erros encerram com
status 1. Sem artefatos, a chamada só verifica o plano e informa
`completeSet=false`.

A conferência padrão cobre pacote, API mínima, ausência de filtro de API
máxima, versão e ausência de depuração. Para verificar as assinaturas dos
APKs, passe também `--apksigner-jar` com o caminho de `lib/apksigner.jar` do
Android SDK Build Tools. A conferência valida a assinatura a partir da API
mínima de cada APK e exige o mesmo certificado nos arquivos da mesma
ficha. O JSON registra `signaturesVerified` e a impressão digital do
certificado de cada APK. Os testes de execução, a licença Amazon, as
restrições regionais e a revisão da seleção de aparelhos permanecem etapas
separadas.

Para conferir se os testes de abertura usaram os APKs entregues, acrescente
`--smoke EDICAO=RELATORIO_JSON` para cada execução. O verificador compara a
soma SHA-256, o pacote, o certificado, a API do emulador e o resultado do
RTSP e da chave da ficha. Com `--require-smoke`, exige doze execuções nas
duas fichas: API17 e 22 para os APKs nativos, API22 para os compatíveis e
API25, 28 e 30 para os modernos. Essa matriz cobre a abertura local em
emuladores Android. Testes físicos e da licença Amazon seguem separados.

## Execução do APK nativo no Android 5.1

As duas edições nativas assinadas, 48 e 49, passaram no emulador API22.
O teste verificou abertura, RTSP, navegação, tela Sobre, chave pública da
ficha e assinatura v1. Assim, a mesma edição tem evidência de execução
nas APIs 17 e 22. O AFTM físico e a licença da loja ainda exigem validação.

Para repetir no emulador API22, adicione `--device-sdk 22` à chamada de
`tools/test_legacy_store.py` para o APK nativo. Os relatórios locais do
ensaio estão em `outputs/fireos/coordinated/legacy.general-api22-early`
e `outputs/fireos/coordinated/legacy.nodownload-api22-early`.

## Execução dos APKs compatíveis no Android 5.1

Os APKs 50 e 51 passaram na API22 x86-64, com abertura pelo atalho da TV,
resposta RTSP, menu, tela Sobre e chave pública da ficha. As capturas de
Ferramentas mostram o botão de gravação na edição geral e a seção sem
gravação na edição DE/AT. Isso verifica a interface de gravação nesse
ensaio. O teste de abertura não exercita um download nem uma gravação real.

O relatório local `outputs/fireos/coordinated/signed-48-51.json` confirma
pacote, versão, API mínima e certificado para os quatro APKs disponíveis
neste ensaio. Os relatórios de abertura estão nas pastas
`compat.general-density160` e `compat.nodownload-smoke` no mesmo diretório.
Esses APKs foram gerados com as mudanças compartilhadas de UxPlay até o
patch 0019. A compilação e validação com o patch 0020 permanecem pendentes.

## Execução do APK moderno geral

O APK 52 passou nos emuladores API25, API28 e API30, cobrindo as bases
Android dos Fire OS 6, 7 e 8. Os três relatórios registram a mesma soma
SHA-256, a assinatura, a chave pública da ficha, a abertura pelo atalho,
o RTSP, o menu e a tela Sobre. Em `outputs/fireos/coordinated`, os
relatórios ficam em `modern.general-api25`, `modern.general-api28-retest`
e `modern.general-api30-retest`. Na API28, o primeiro teste capturou um
erro do app de mensagens do emulador. Na API30, o redirecionamento fechou
a conexão antes de o receptor responder. A repetição passou com o log
filtrado pelo processo do BuzinCast e a espera pela primeira resposta RTSP.
