# Amostra local de reprodução

`playback-sample.mp4` é um padrão sintético de oito segundos, gerado para o
Buzin Cast. Não contém mídia de terceiros. H.264 Baseline, 320×180, 15 fps;
AAC estéreo, 48 kHz; tom de 440 Hz atenuado. O player usa volume de 15%.
Serve para verificar o pipeline e o controle, sem baixar conteúdo da internet.
Não comprova sincronia AirPlay, qualidade, resolução máxima ou codecs de uma TV.

Gerado com FFmpeg usando:

```sh
ffmpeg -f lavfi -i 'testsrc2=size=320x180:rate=15:duration=8' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=8' \
  -af 'volume=0.08' -c:v libx264 -profile:v baseline -level 3.0 \
  -pix_fmt yuv420p -crf 30 -g 15 -c:a aac -b:a 64k -ac 2 \
  -movflags +faststart -shortest playback-sample.mp4
```

O encoder não é incorporado ao aplicativo. Somente o arquivo gerado é incluído.
O arquivo fica em `file:///pkg/assets/raw/media/playback-sample.mp4` no sandbox,
conforme o layout oficial de pacotes Vega.
