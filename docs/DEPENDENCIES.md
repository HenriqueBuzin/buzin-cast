# Versões e política de atualização

Verificado em 2 de novembro de 2026. Usamos a última linha LTS quando o fornecedor oferece essa política; nas demais dependências, a última versão estável publicada. Alpha, beta, RC e snapshots não entram na aplicação. As versões são fixadas no catálogo Gradle e os submódulos em commits de tags estáveis.

## Ferramentas e linguagens

- Temurin/OpenJDK 25.0.5+8: última atualização da linha Java 25 LTS. O build e os testes JVM usam JDK 25. O bytecode da aplicação permanece no nível 17 para o pipeline Android, sem exigir uma JVM Java 25 no Fire Stick.
- Kotlin 2.4.21: estável atual. Kotlin não chama essa versão de LTS; a linha 2.4 oferece uma janela de suporte da biblioteca padrão JVM.
- Android Gradle Plugin 9.5.0 e Gradle 9.8.2: estáveis, com distribuição Gradle verificada por SHA256.
- NDK r30, revisão 30.0.16248370: LTS atual, com Clang 21.
- C++23 e C23 nos fontes nativos próprios. Bibliotecas C de terceiros preservam seu dialeto de compilação.
- CMake 4.4.5: estável, instalado de asset oficial Kitware com verificação SHA256 por `tools/install_cmake.py`.
- SDK de compilação/target Android 37. A edição principal exige API 25 (Fire OS 6); a edição de compatibilidade exige API 22 (Fire OS 5).

## Bibliotecas

- Compose BOM 2026.09.00; Hilt 2.60.1; KSP 2.3.13.
- AndroidX Core 1.19.1; Activity 1.13.0; Lifecycle 2.11.0; Media 1.8.0.
- Coroutines 1.11.0; Media3 1.11.1; Oboe 1.11.0.
- OpenSSL 3.5.9, da linha 3.5 LTS com suporte até abril de 2030.
- FFmpeg 9.0.2; libplist 2.8.0; UxPlay 1.73.7.
- JaCoCo 0.8.16; AndroidX Test Runner 1.7.0; extensão JUnit 1.3.0; JUnit 4.13.2.
- kxml2 2.3.0 é o artefato JVM de teste atual; o Android fornece XmlPullParser em execução. A metadata antiga de `net.sf.kxml:kxml2` ainda anuncia 2.2.2, por isso não substituímos o artefato 2.3.0 existente por uma versão mais antiga.

O helper `openssl-cmake` não publica uma linha LTS nem releases versionadas. Ele permanece em commit fixado e revisado; a versão da biblioteca OpenSSL que ele compila é definida separadamente. Não confundir o commit de um script de build com uma versão estável publicada da biblioteca.

As ações do GitHub também usam releases estáveis fixadas: Checkout 7.0.1, Setup Java 6.1.0, Upload Artifact 7.0.2 e Android Emulator Runner 2.38.0.

## Fontes de verificação

- [Java LTS](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) e [releases Temurin 25](https://github.com/adoptium/temurin25-binaries/releases).
- [Releases Kotlin](https://kotlinlang.org/docs/releases.html), [AGP](https://developer.android.com/build/releases/gradle-plugin) e [Gradle](https://gradle.org/releases/).
- [NDK LTS](https://developer.android.com/ndk/downloads) e [CMake](https://github.com/Kitware/CMake/releases).
- [AndroidX estáveis](https://developer.android.com/jetpack/androidx/versions) e metadata dos repositórios oficiais Google Maven/Maven Central.
- [OpenSSL LTS e downloads](https://openssl-library.org/source/), [FFmpeg](https://ffmpeg.org/download.html), [libplist](https://github.com/libimobiledevice/libplist/releases) e [UxPlay](https://github.com/FDH2/UxPlay/releases).

A troca das versões não prova compatibilidade. Validar compilação ARM32/ARM64/x86_64, lint, JVM, integração e smoke no emulador. A instalação nas TVs em uso permanece adiada.

## Edição de compatibilidade Fire OS 5

Compile com `-PbuzinLegacy=true`. A saída fica em `app/build-legacy`, separada da edição principal. As duas edições compartilham código e identidade do aplicativo; os binários devem receber filtros de dispositivos distintos na Amazon Appstore. Não usamos `overrideLibrary` para burlar mínimos declarados.

As saídas normais e instrumentadas também são isoladas: `app/build`, `app/build-legacy`, `app/build-coverage` e `app/build-legacy-coverage`. A propriedade `-PbuzinCoverage=true` seleciona a pasta instrumentada da edição correspondente. Execute builds do mesmo checkout em sequência; arquivos Gradle, patches e caches do projeto continuam compartilhados.

Esta edição usa as versões estáveis mais recentes cujos manifests AAR ainda aceitam API 22, verificadas diretamente no repositório oficial:

- Compose BOM 2025.11.01, com Compose UI 1.9.5 e Material3 1.4.0.
- AndroidX Core 1.17.0; Activity 1.11.0; Lifecycle 2.9.4; Media 1.7.0.
- Media3 1.8.1.

Java 25 LTS, Kotlin 2.4.21, Gradle/AGP, NDK r30 LTS, CMake, Hilt, Coroutines, Oboe, OpenSSL 3.5.9 LTS e FFmpeg 9.0.2 são compartilhados nas versões atuais. O NDK r30 declara mínimo API 21, portanto não precisa ser rebaixado para API 22.

Os controles de áudio pertencem ao aplicativo e usam a interface Player comum às duas linhas Media3. DataStore, Hilt Navigation e o pacote Media3 UI Compose não utilizados pelo novo conjunto de controles foram removidos das dependências. `desugar_jdk_libs` 2.1.5 cobre APIs Java em sistemas antigos.

O acesso à rede, à resolução física, aos fones e ao AndroidKeyStore tem caminhos específicos para Android 5.1. Esses caminhos devem passar por lint e execução API 22, além dos testes da edição principal.
