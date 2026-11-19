"""Independent PCM onset/tail checks for synthetic AAC conversion exports."""
import array
import json
import math
import subprocess


def check_audio_presentation(converted,source,ffmpeg,ffprobe,*,repetitions=1,gap=False):
    rate=48000
    def streams(path):
        probe=json.loads(subprocess.check_output([str(ffprobe),'-v','error','-show_streams','-of','json',str(path)],timeout=30))
        video=next((s for s in probe['streams'] if s['codec_type']=='video'),{})
        audio=next(s for s in probe['streams'] if s['codec_type']=='audio')
        return float(video.get('start_time',0)),float(audio.get('start_time',0))
    def pcm(path,fill=False):
        filters=['-af','aresample=async=1:first_pts=0'] if fill else []
        return array.array('h',subprocess.check_output([str(ffmpeg),'-v','error','-i',str(path),'-map','0:a:0',*filters,'-ar',str(rate),'-ac','2','-f','s16le','-'],timeout=30))
    def onset(samples):
        for i,value in enumerate(samples):
            if abs(value)>500:return (i//2)/rate
        raise AssertionError('Synthetic audio lost its onset')
    def rms(samples):
        if not samples:raise AssertionError('Missing synthetic PCM tail')
        return math.sqrt(sum(value*value for value in samples)/len(samples))/32768
    input_video,input_audio=streams(source);output_video,output_audio=streams(converted)
    original=pcm(source);result=pcm(converted)
    difference=(onset(result)+output_audio-output_video)-(onset(original)+input_audio-input_video)
    # Matroska/Vorbis timestamps and first decoded blocks have millisecond
    # granularity on JB. This still rejects the 43/46 ms AAC priming regression.
    if abs(difference)>.005:raise AssertionError(f'AAC presentation onset moved by {difference*1000:.3f} ms')
    expected=len(original)*repetitions
    if repetitions>1:expected=len(original)+(repetitions-1)*8*rate*2
    if gap:expected=max(0,len(pcm(source,True))-round(input_audio*rate)*2)
    frame_difference=(len(result)-expected)/2
    if abs(frame_difference)>repetitions*rate*.008:raise AssertionError('AAC export retained padding or dropped its tail: '+str(frame_difference)+' frames')
    source_tail=rms(original[-rate//50*2:]);output_tail=rms(result[-rate//50*2:])
    if source_tail<.02 or not .5<output_tail/source_tail<2:raise AssertionError('AAC source tail was truncated or replaced by padding')
    report={'onset_difference_ms':round(difference*1000,4),'decoded_duration_difference_frames':frame_difference,'tail_rms_ratio':round(output_tail/source_tail,4)}
    if repetitions>1:
        quiet=maximum=0
        window=result[round((8-output_audio-.15)*rate)*2:round((8-output_audio+.15)*rate)*2]
        for i in range(0,len(window),2):
            quiet=quiet+1 if max(abs(window[i]),abs(window[i+1]))<300 else 0;maximum=max(maximum,quiet)
        source_gap=max(0,8-(input_audio-input_video+len(original)/(2*rate)))
        if maximum/rate>source_gap+.01:raise AssertionError('AAC priming remained beyond the source period gap')
        report['period_boundary_silence_ms']=round(maximum*1000/rate,4)
        report['source_period_gap_ms']=round(source_gap*1000,4)
        report['extra_boundary_silence_ms']=round((maximum/rate-source_gap)*1000,4)
    if gap:
        middle=result[round((4.15-output_audio)*rate)*2:round((4.35-output_audio)*rate)*2]
        before=result[round((3.5-output_audio)*rate)*2:round((3.7-output_audio)*rate)*2]
        after=result[round((4.8-output_audio)*rate)*2:round((5-output_audio)*rate)*2]
        if rms(middle)>.005 or min(rms(before),rms(after))<.03:raise AssertionError('PCM timestamp gap was not preserved as silence')
        report['timestamp_gap']='half-second silent gap retained between tones'
    return report
