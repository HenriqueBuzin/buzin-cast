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

O direcionamento pretendido é a edição nativa para o Stick original,
inclusive firmware antigo, a compatível para Sticks que exigem API22 e
a moderna para Sticks API25 ou superior. O mesmo modelo recebe um único
APK por ficha: atribuir manualmente o modelo original à edição nativa
também atende suas unidades atualizadas. Conferir os modelos físicos no
console e excluir televisores, Cubes, tablets e outros dispositivos.
Essa intenção ainda precisa ser validada contra a seleção real da loja.

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
