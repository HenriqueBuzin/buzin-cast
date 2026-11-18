# Local export fixture

`sample.mp4` is an original synthetic blue frame sequence, generated without
external media. It contains ten H.264 baseline frames at 32 × 32 pixels and ten
frames per second, with no audio. Presentation timestamps are 0 through 900000
microseconds in steps of 100000.

Generate an equivalent fixture with FFmpeg:

```sh
ffmpeg -f lavfi -i 'color=c=blue:s=32x32:r=10:d=1' -an -c:v libx264 -profile:v baseline -pix_fmt yuv420p -movflags +faststart sample.mp4
```

The export contract runs the real Media3 Transformer and uses Android's
MediaExtractor to check the published video's track, dimensions, sample count
and presentation timestamps. No internet server or household device is needed.
