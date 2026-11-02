# Créditos e componentes

**Buzin Cast — criação e manutenção: Henrique Buzin.** Copyright © 2026 Henrique Buzin nas contribuições próprias; GNU GPL v3. A autoria dos componentes originais permanece preservada.

Este projeto deriva de **AirPlay Receiver for Android**, de jqssun e contribuidores, sob GNU GPL v3. O código original e seu histórico foram preservados. Alterações Buzin Cast: identidade, tela de conexão, tradução inicial, configurações padrão para Fire TV e ajustes de compilação.

Na versão 0.2.0, o código Buzin Cast também acrescenta muxer Matroska, transporte de retransmissão autenticado, captura PCM no caminho nativo, visualizador DLNA de fotos e controles de gravação/retransmissão. Os padrões sintéticos de vídeo e áudio dos testes foram gerados com FFmpeg; não são mídia de terceiros.

- android-airplay-server: https://github.com/jqssun/android-airplay-server — GNU GPL v3; `LICENSE` na raiz.
- LeanCast: https://github.com/addozhang/leancast — Apache 2.0, commit `cf535945a119202b56d8481c707f78aee33e13b5`. Importados o módulo de domínio DLNA, seus testes e os adaptadores `ControlServer`, `SubscriptionRegistry`, `SsdpAgent` e `Net`. Licença integral em `licenses/LeanCast-Apache-2.0.txt`. Alterações Buzin Cast: integração com o player existente, sincronização de sessão, limite de concorrência HTTP e restrição do endereço de callback GENA ao dispositivo solicitante.
- UxPlay: https://github.com/FDH2/UxPlay — GNU GPL v3 e avisos específicos dos componentes internos; consultar `app/src/main/cpp/third_party/UxPlay`.
- FFmpeg: https://github.com/FFmpeg/FFmpeg — LGPL 2.1 ou posterior para a configuração sem componentes GPL; consultar `COPYING*` e `LICENSE.md` no submódulo.
- libplist: https://github.com/libimobiledevice/libplist — LGPL 2.1 ou posterior; consultar o submódulo.
- openssl-cmake: https://github.com/viaduck/openssl-cmake — MIT; aviso no `CMakeLists.txt` do submódulo.
- OpenSSL 3.5.9 LTS: https://github.com/openssl/openssl — Apache 2.0; baixado pelo processo de compilação.
- AndroidX, Jetpack Compose, Media3 e Oboe — consultar licenças dos respectivos artefatos e repositórios Android/Google, normalmente Apache 2.0.
- Kotlin, kotlinx.coroutines, Dagger/Hilt e KSP — consultar as licenças dos artefatos fixados em `gradle/libs.versions.toml`.

Esta lista é um índice. Os avisos e as licenças originais completos, inclusive dos componentes incorporados, continuam aplicáveis e devem acompanhar a distribuição. Antes da primeira distribuição pública, revisar o inventário completo das dependências transitivas e incorporar seus avisos ao pacote de distribuição.
