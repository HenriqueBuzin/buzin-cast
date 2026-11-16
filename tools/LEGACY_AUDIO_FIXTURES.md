# Amostras sintéticas de áudio da API 17

`generate_legacy_audio_samples.py` produz `audio_samples.json` no protótipo.
São tons gerados matematicamente, sem gravações de usuários ou mídia externa:

- ALAC de 16 bits, 352 amostras por pacote.
- AAC-LC, 1024 amostras por pacote.
- AAC-ELD sem SBR, 480 e 512 amostras por pacote.

Todos usam 44100 Hz e dois canais. O teste confere PCM não nulo, amplitude,
quantidade decodificada, saída pelo mesmo buffer temporal da produção e avanço
do relógio de um AudioTrack real. O volume do AudioTrack fica em zero durante o
teste. As amostras não verificam sozinhas o protocolo de um emissor real ou
sincronização entre áudio e vídeo.

Para regenerar, instale FFmpeg/ffprobe no computador. Compile separadamente
o [FDK AAC v2.0.3](https://github.com/mstorsjo/fdk-aac/tree/v2.0.3), commit
`716f4394641d53f0d79c9ddac3fa93b03a49f278`, e nosso auxiliar de teste:

```powershell
git clone --depth 1 --branch v2.0.3 https://github.com/mstorsjo/fdk-aac.git outputs/fdk-aac-test-encoder
cmake -S outputs/fdk-aac-test-encoder -B outputs/fdk-aac-test-build -G Ninja -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build outputs/fdk-aac-test-build --parallel 4
g++ -std=c++17 -O2 -static -I outputs/fdk-aac-test-encoder/libAACenc/include -I outputs/fdk-aac-test-encoder/libSYS/include tools/legacy_aac_fixture_encoder.cpp outputs/fdk-aac-test-build/libfdk-aac.a -o outputs/legacy_aac_fixture_encoder.exe
python tools/generate_legacy_audio_samples.py --eld-encoder outputs/legacy_aac_fixture_encoder.exe
```

Adapte o compilador e o nome da biblioteca ao ambiente. O exemplo de link usa
GCC/MinGW. A biblioteca FDK e o executável auxiliar ficam em `outputs/`, ignorado
pelo Git; **não são incluídos no APK**. O app usa os decodificadores FFmpeg que
já existem na produção. O gerador verifica a configuração AAC-ELD emitida pelo
FDK contra os bytes usados pelo receptor, antes de salvar os pacotes.

AAC-LC de 960 amostras ainda não tem uma amostra de regressão nesta suíte.
