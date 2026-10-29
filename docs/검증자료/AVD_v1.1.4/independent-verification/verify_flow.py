"""Read-only saved flow validation and ffprobe metadata; writes review output only."""
from pathlib import Path
import ast
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

OUT=Path(__file__).resolve().parent;ROOT=OUT.parents[3];BASE=ROOT/'.omx/qa/submission-v22'
SHA='8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c'
tree=ast.parse((OUT/'verify_boundary_audio.py').read_text())
exec(compile(ast.Module(body=[n for n in tree.body if isinstance(n,(ast.FunctionDef,ast.ClassDef)) and n.name in {'read','digest','texts','Review'}],type_ignores=[]),'<pure-helpers>','exec'))
v=Review();p=BASE/'flow-01';video=p/'전체동선_무음.mp4'
hashes=v.hashes(p,'artifact-sha256.json');manifest=read(p/'artifact-sha256.json')
unmanifested=sorted(x.name for x in p.iterdir() if x.is_file() and x.name not in manifest and x.name!='artifact-sha256.json')
v.check('before/after installed same runtime06',read(p/'environment.json')==read(p/'environment-after.json') and read(p/'environment.json')['apk_sha256']==SHA and digest(BASE/'candidates/runtime-06/app-pleos-release.apk')==SHA)
v.check('nine reported assertions true',len(read(p/'results.json'))==9 and all(read(p/'results.json').values()))
states={n:read(p/(n+'.json')) for n in ['A1-home','A2-loaded','A3-driving','A4-arrival','A5-power-off','A6-occupied-blocked']}
ui={n:texts(p/(n+'.xml')) for n in states}
v.check('initial idle22',states['A1-home']['hvac']['passenger']['temp_c']==22 and '프리셋 고르기' in ui['A1-home'])
v.check('loaded18 setting and limits visible',states['A2-loaded']['hvac']['passenger']['temp_c']==18 and '설정 온도 · 18℃ 설정값 확인' in ui['A2-loaded'] and '냉방 효과와 짐 온도는 측정하지 않아요.' in ui['A2-loaded'])
v.check('driving Console state and short UI',states['A3-driving']['gear']['actual']['value']=='Drive' and states['A3-driving']['controls']['speed_kph']>0 and '0.15G 기준' in ui['A3-driving'] and '조금만 부드럽게' in ui['A3-driving'])
arrival=states['A4-arrival']
v.check('park driver door arrival and restored22',arrival['gear']['actual']['value']=='Park' and arrival['controls']['speed_kph']==0 and arrival['body']['doors']['front_left'] is True and arrival['hvac']['passenger']['temp_c']==22 and '조수석 케이크 챙기세요' in ui['A4-arrival'])
v.check('synthetic highG report explicit',all(t in ui['A4-arrival'] for t in ['4.49G','모의 입력 최대 추정 G','실주행 평가 불가','모의 입력 포함 · 고G 구간 · 원인 미확인','평균 설정 온도','18.0℃']))
v.check('conditional restoration disclosure',any('조건부 복원을 마쳤어요' in t for t in ui['A4-arrival']) and any('실제 실내·짐 온도와 냉방 효과는 확인하지 않아요' in t for t in ui['A4-arrival']))
v.check('power-off setting18 and UI',states['A5-power-off']['hvac']['power_on'] is False and states['A5-power-off']['hvac']['passenger']['temp_c']==18 and '설정 후 조회 · 공조 전원 꺼짐' in ui['A5-power-off'])
v.check('occupied blocked leaves22 with UI',states['A6-occupied-blocked']['seat']['occupied']['passenger'] is True and states['A6-occupied-blocked']['hvac']['passenger']['temp_c']==22 and '조수석 비착좌 확인 불가 · 변경하지 않음' in ui['A6-occupied-blocked'])
timeline=read(p/'timeline.json')['events']
v.check('one initial cake tap marker and ordered observations',sum(e['event']=='tap_recent_cake_once' for e in timeline)==1 and all(x['seconds']<=y['seconds'] for x,y in zip(timeline,timeline[1:])) and all(any(e['event']==n for e in timeline) for n in states))
source=(p/'harness-source.py').read_text()
v.check('single screenrecord session and observed cake tap path',source.count("'screenrecord','--bit-rate'")==1 and 'shot(\'A1-home\');tap_cake();mark(\'tap_recent_cake_once\')' in source)
probe=json.loads(subprocess.run(['ffprobe','-v','error','-show_entries','format=duration,size:stream=codec_type,width,height','-of','json',str(video)],check=True,capture_output=True,text=True).stdout)
audit=read(p/'video-audit.json');streams=probe['streams']
v.check('video bytes and SHA',video.stat().st_size==int(probe['format']['size'])==audit['bytes']==34623201 and digest(video)==audit['sha256']=='74483ce733bb5a47bd3271eac06968baeffafb676e3031c091217967fdd1eda8')
v.check('video duration',float(probe['format']['duration'])==audit['duration_seconds']==82.287311)
v.check('video2560x1440 no audio and two data streams',len([s for s in streams if s['codec_type']=='video'])==1 and next(s for s in streams if s['codec_type']=='video')['width']==2560 and next(s for s in streams if s['codec_type']=='video')['height']==1440 and sum(s['codec_type']=='audio' for s in streams)==0 and sum(s['codec_type']=='data' for s in streams)==2)
decode=read(BASE/'flow-decoder-verification.json')
v.check('root decode evidence original timebase zero errors',decode['passed'] and decode['exit_code']==0 and decode['stderr_bytes']==0 and decode['source_video_unchanged'] and (p/'decode-original-timebase.log').stat().st_size==0)
before,after=read(p/'tester-before.json'),read(p/'tester-after.json')
v.check('tester fixture restored values',read(p/'tester-cleanup.json')['verified'] and all(before['hvac'][k]==after['hvac'][k] for k in ['power_on','dual_on','driver','passenger']) and before['seat']['occupied']['passenger']==after['seat']['occupied']['passenger'] and before['body']['doors']['front_left']==after['body']['doors']['front_left'] and after['gear']['actual']['value']=='Park' and all(after['controls'][k]==0 for k in ['speed_kph','accelerator_pct','brake_pct','steering_deg']))
log=(p/'sdk-flow.log').read_text()
v.check('flow SDK readback18/22 and applied/restore observed',all(s in log for s in ['eventPropKey HVAC_TEMPERATURE_SET eventAreaId 4 eventValue 18.0','eventPropKey HVAC_TEMPERATURE_SET eventAreaId 4 eventValue 22.0','apply TEMPERATURE: APPLIED','restore TEMPERATURE: APPLIED','apply TEMPERATURE: BLOCKED_BY_OCCUPANCY']))
v.save('flow-verification.json',{'candidate_sha256':SHA,'artifact_hashes':hashes,'files_outside_existing_manifest':unmanifested,'ffprobe':probe,'video_sha256':audit['sha256'],'raw_display_max_g':4.49,'visual_inspection':'A4-arrival.png directly inspected: 4.49G, synthetic input, highG unassessed, real-driving assessment unavailable, 18C average SETTING,22C passenger restored.','limits':['Flow demonstrates emulator interaction/readback, not physical G accuracy/cargo outcome; do not compare4.49G against earlier fixture runs3.69G/4.53G.','Silent screenrecord has no audio stream; audio-output evidence is separate matrix03.','Initial one-tap is supported by retained harness/timeline plus loaded UI; no independent input-event recording.','Root decoder evidence inspected, decode not rerun by reviewer; ffprobe metadata and source SHA independently recomputed.','Video end duration differs from host flow_complete timestamp by about83ms; different capture/host clocks, not evidence of a cut.','Tester cleanup is external fixture restore, distinct from app arrival restore.']})
