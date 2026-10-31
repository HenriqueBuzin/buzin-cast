# Buzin Cast

Receptor para Fire TV com Fire OS. Gratuito, sem anúncios, assinaturas ou limite artificial de tempo por sessão.

## Estado do projeto

Protótipo em desenvolvimento. A presença de código de uma função não significa que ela foi validada no Fire TV. Modelo inicial informado pelo mantenedor: Fire TV Stick 4K de primeira geração, Fire OS 6.7.1.1. Testes em dispositivo real ainda pendentes.

A base utiliza [android-airplay-server](https://github.com/jqssun/android-airplay-server), commit `a46cca0`, e seus submódulos fixados, incluindo [UxPlay](https://github.com/FDH2/UxPlay). Os nomes internos do código foram preservados para manter a integração JNI e a rastreabilidade. O identificador de instalação do Buzin Cast é `com.buzincast.tv`.

## Primeira versão

- Recepção AirPlay: espelhamento, áudio e reprodução de vídeo, herdados da base aberta.
- Descoberta na rede local, tela cheia e navegação por controle remoto.
- Nome do receptor configurável e PIN opcional experimental.
- Ajustes de resolução e FPS, decodificação por hardware e ajustes de áudio.
- Execução em segundo plano, conforme as permissões e restrições do sistema.
- Identidade Buzin Cast, tema escuro e instruções iniciais em português e inglês.
- Perfil inicial: H.264, 1920 × 1080, 30 fps; ajustes disponíveis nas configurações.
- Sem anúncios, rastreadores publicitários, SDK de cobrança ou temporizador comercial.
- Receptor DLNA experimental para áudio e vídeo, baseado no LeanCast, com descoberta SSDP, descrição UPnP, controles SOAP e eventos GENA. Ative em Configurações. Desativado por padrão até validação em aparelho; não usa o PIN AirPlay e exige reiniciar o receptor após trocar de rede.

## Escopo solicitado e ainda em desenvolvimento

1. Validar a integração DLNA de áudio e vídeo em aparelhos reais e acrescentar apresentação de fotos.
2. Compatibilidade Google Cast: investigar um motor receptor adequado a Fire OS. O SDK Cast Connect para Android TV não é um receptor universal que se possa simplesmente adicionar ao Fire TV.
3. Miracast: investigar integração com a função nativa nos modelos compatíveis; não prometer recepção universal por aplicativo.
4. Gravação voluntária da transmissão, com indicação visível e gestão dos arquivos locais.
5. Retransmissão para outro receptor, com protocolo e interoperabilidade definidos.
6. Reprodução de fotos com controles de apresentação.
7. Perfis de qualidade até 4K nos aparelhos, codecs, redes e fontes que suportarem.
8. Controle de acesso por protocolo e ajustes de compatibilidade.
9. Testes de reconexão, sincronização audiovisual, sessões longas e uso em segundo plano.
10. Preparação e submissão de uma versão gratuita à Amazon Appstore.

Fire OS 6+ (API 25+) é o alvo de instalação atual. O APK inclui ARM de 32 e 64 bits e x86_64 para testes. Isso não equivale a validação em todas as versões de Fire OS. Fire OS 5 requer estudo separado. Vega OS fica fora desta etapa.

## Compilar

### Verificação desta versão

- APK de debug 0.1.0 compilado com sucesso, com ARM de 32 e 64 bits e x86_64.
- 119 testes do módulo de protocolo DLNA passaram, sem falhas ou testes ignorados.
- Android Lint: 0 erros, 53 avisos e 1 sugestão; os avisos ainda devem ser revisados antes da publicação.
- Assinatura APK v2 verificada; versão de teste, não destinada à publicação na loja.
- Instalação e abertura verificadas em emulador Android 16; receptor AirPlay iniciou sem falha de carregamento nativo.
- DLNA no emulador: descrição UPnP e SOAP responderam HTTP 200; um WAV local de teste chegou ao estado PLAYING, informou duração e progresso, e respondeu a Pause e Stop.
- Espelhamento AirPlay com iPhone/Mac, recepção SSDP entre aparelhos físicos, saída audiovisual real e desempenho no Fire TV ainda não foram validados. O teste do emulador não substitui esses testes.

O código nativo usa OpenSSL e FFmpeg com ferramentas Unix. Use Linux, WSL ou macOS com Java 17+ (21 recomendado), Git, C/C++, make, Perl e Android SDK/NDK. No Windows, o caminho de compilação previsto é WSL, não o Gradle nativo do Windows.

```sh
git -c core.autocrlf=false clone --recurse-submodules https://github.com/HenriqueBuzin/buzin-cast.git
cd buzin-cast
# Defina ANDROID_HOME para o SDK do ambiente de compilação.
./gradlew :dlna-domain:test assembleDebug lintDebug
```

SDK de compilação: Android 36. NDK: 27.0.12077973. O Gradle utiliza os arquivos de versões fixados no projeto. Para compilar apenas uma arquitetura durante o desenvolvimento:

```sh
./gradlew assembleDebug -PbuzinAbis=armeabi-v7a
```

Não versionar `local.properties`, chaves de assinatura, senhas, APKs de build ou diretórios de cache. A assinatura de produção deve ser configurada pelo mantenedor antes da publicação; a chave de debug não deve ser usada na loja.

## Instalar para testar

Ative a depuração ADB no Fire TV e conecte o computador à mesma rede. Substitua o IP de exemplo pelo IP da TV e autorize o computador na tela da TV:

```sh
adb connect 192.168.1.100:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.buzincast.tv/io.github.jqssun.airplay.MainActivity
```

Abra a Central de Controle do iPhone/iPad/Mac, selecione Espelhar a Tela e escolha Buzin Cast. Rede de convidados ou isolamento entre dispositivos pode impedir descoberta. O protótipo não fornece acesso remoto pela internet.

## Licenças e publicação

O aplicativo derivado mantém a licença GNU GPL v3 e os avisos dos autores da base. Consulte `LICENSE`, `THIRD_PARTY_NOTICES.md` e as licenças nos submódulos. Ao distribuir APKs, disponibilize o código-fonte correspondente, patches, instruções de compilação e licenças. O histórico original e o README original são preservados.

Antes de anunciar uma versão como pronta: validar todos os recursos anunciados, fechar os textos de privacidade conforme o comportamento real, assinar a versão, testar navegação com controle remoto e preparar os materiais da Amazon. O envio à loja e sua aprovação ainda não ocorreram.

Conteúdo protegido por DRM não é suportado pela base AirPlay. 4K depende do fluxo de origem e do aparelho. Buzin Cast é um projeto independente, sem afiliação com Apple ou Amazon.
