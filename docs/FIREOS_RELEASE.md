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

A conferência cobre pacote, API mínima, ausência de filtro de API máxima,
versão e ausência de depuração. Não comprova assinatura, licença Amazon,
restrições regionais, funcionalidades ou compatibilidade física. Um
relatório com seis manifestos válidos não equivale a seis APKs reconstruídos.
Continuam necessários os testes dos releases e a revisão do console.
