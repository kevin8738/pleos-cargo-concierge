# VIP 조수석 — 합성 입력에 대한 안내 요청 예산 분석

**입력은 설계 가정(D), 결과는 고정 엔진의 계산(C)입니다. 이 분석의 실제 음향·청취 관측 0, 운전자·차량·짐 현장 관측 0입니다.** 120초씩 정한 네 수학 프로파일은 실제 도심의 대표 경로나 예상 발생률이 아닙니다.

별도 관측 APK의 G 추정·Guard·음향 정책을 바꾸지 않고 동결한 core를 직접 사용했습니다. APK SHA256은 `8971e207285f333c2c152f185e907ea1d38c01c04369473fc68c8f850f5e023c`입니다. 앱 재빌드·AVD·차량 명령·음향 재생을 이 분석에서 실행하지 않았습니다. 소스와 입력은 결과를 보기 전에 동결했습니다.

한 번 실행한 54개 케이스를 모두 포함합니다. 기준은 4프로파일×9품목=36개, 추가는 반복 프로파일 한 종류의 50/250ms×9품목=18개입니다. 기존 182개 앱 시험과 별도입니다. self-test의 7개 검증 범주나 독립 감사 JSON의 `checks` 내부 대조 횟수(1,047,473)를 기존 시험 수·독립 운행 수·실차 표본에 더하지 않습니다.

## 고정 조건과 읽는 법

- 기본 입력100ms, Tick250ms, 단일 sourceId=1, 모든 motion은 SYNTHETIC입니다. 같은 시각에 speed→steering→boolean brake→Tick 순서로 처리합니다.
- 축거는 앱 설정과 같은 `2.9f.toDouble()`, 조향비 `15f.toDouble()`, 종축 EMA0.5입니다. 공통 정책은 허용 음성3회·음성+효과음6회·허용 간격8초이며 변경하지 않았습니다.
- 0.12G: 뜨거운 음식/커피/깨지기 쉬운 물건; 0.15G: 케이크/장보기; 0.20G: 꽃/반려동물/일반짐; 0.25G: 전자기기. 같은 한계그룹의 수치 결과는 일치했습니다. 기준값의 실제 짐 타당성을 확인한 것은 아닙니다.
- `진입 W/O`는 speed 관측격자의 순간 WARN/OVER 진입입니다. OVER→WARN 하강도 WARN 진입에 포함하므로 6펄스에서 WARN12가 가능합니다. 화면 배지 전이는 summary의 별도 항목입니다.
- `후보 W/O`는 해당 영역의 엔진 gate 후보 총수입니다. 허용 요청+사유별 억제 증가량으로 복원하며, 전단 조건에서 생성되지 않은 후보는 포함하지 않습니다. 제거된 후보의 음성/효과음별 원목록은 공개 API로 알 수 없습니다.
- `허용 음성/효과음`은 reducer의 출력 요청입니다. 실제 소리, TTS 완료, 운전자 부담이나 방지한 방해 횟수를 뜻하지 않습니다. 억제 요청 수는 표본주기·이벤트 수에 민감합니다.
- 시간은 주행 시작 후 초입니다. `voice3`는 음성3회 도달, `전체6`은 합산6회 도달, `첫 TRIP_LIMIT`는 그 제한으로 실제 억제를 처음 기록한 시점입니다. 미도달은 원자료 그대로 null이며 종료시각이나0으로 채우지 않았습니다.

## 기본100ms: 네 프로파일과 한계그룹

프로파일은 완만한 가감속·작은 조향, 반복 조향 펄스, 지속 고조향, 의도적인 급감속입니다. 정확한 수식·속도 절점·신호 순서는 [동결 명세](analysis/spec.md)와 [입력 해시](analysis/inputs.sha256)에 있습니다.

| 프로파일 | 한계G | 최대계산G | 진입 W/O | 후보 W/O | 허용 음성/효과음 | voice3초 | 전체6초 | 첫 TRIP_LIMIT초 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 완만 | 0.12 | 0.070813626 | 0/0 | 0/0 | 0/0 | null(미도달) | null(미도달) | null(미도달) |
| 완만 | 0.15 | 0.070813626 | 0/0 | 0/0 | 0/0 | null(미도달) | null(미도달) | null(미도달) |
| 완만 | 0.20 | 0.070813626 | 0/0 | 0/0 | 0/0 | null(미도달) | null(미도달) | null(미도달) |
| 완만 | 0.25 | 0.070813626 | 0/0 | 0/0 | 0/0 | null(미도달) | null(미도달) | null(미도달) |
| 반복 경계 진입 | 0.12 | 0.227956404 | 12/6 | 6/882 | 0/6 | null(미도달) | 100.500 | 100.600 |
| 반복 경계 진입 | 0.15 | 0.227956404 | 12/6 | 6/846 | 0/6 | null(미도달) | 100.600 | 100.700 |
| 반복 경계 진입 | 0.20 | 0.227956404 | 12/6 | 6/774 | 0/6 | null(미도달) | 100.800 | 100.900 |
| 반복 경계 진입 | 0.25 | 0.227956404 | 6/0 | 6/0 | 0/6 | null(미도달) | 100.900 | null(미도달) |
| 지속 OVER | 0.12 | 0.285411225 | 2/1 | 1/1656 | 3/1 | 44.400 | null(미도달) | null(미도달) |
| 지속 OVER | 0.15 | 0.285411225 | 2/1 | 1/1650 | 3/1 | 44.500 | null(미도달) | null(미도달) |
| 지속 OVER | 0.20 | 0.285411225 | 2/1 | 1/1638 | 3/1 | 44.600 | null(미도달) | null(미도달) |
| 지속 OVER | 0.25 | 0.285411225 | 2/1 | 1/1632 | 3/1 | 44.800 | null(미도달) | null(미도달) |
| 급감속 | 0.12 | 1.273400495 | 0/1 | 0/35 | 1/0 | null(미도달) | null(미도달) | null(미도달) |
| 급감속 | 0.15 | 1.273400495 | 0/1 | 0/35 | 1/0 | null(미도달) | null(미도달) | null(미도달) |
| 급감속 | 0.20 | 1.273400495 | 0/1 | 0/35 | 1/0 | null(미도달) | null(미도달) | null(미도달) |
| 급감속 | 0.25 | 1.273400495 | 0/1 | 0/35 | 1/0 | null(미도달) | null(미도달) | null(미도달) |

급감속의 최대1.273400495G와 `HIGH_G_UNASSESSED`를 보존했습니다. 모든 그룹에서 프로파일 전체 음성1요청, `EMERGENCY`34요청 억제를 기록했습니다. 고G 원인·실제 긴급상황 판별·짐 안전성을 검증했다는 뜻이 아닙니다.

## 0.15G 그룹의 50/100/250ms 대조

| 입력간격ms | 최대계산G | 진입 W/O | 후보 W/O | 허용 음성/효과음 | voice3초 | 전체6초 | 첫 TRIP_LIMIT초 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 50 | 0.227956404 | 12/6 | 6/1674 | 0/6 | null(미도달) | 100.550 | 100.700 |
| 100 | 0.227956404 | 12/6 | 6/846 | 0/6 | null(미도달) | 100.600 | 100.700 |
| 250 | 0.227956404 | 0/6 | 0/180 | 3/3 | 52.750 | 100.750 | 100.750 |

같은 최대G라도 250ms에서는 WARN 구간을 관측하지 않고 OVER로 진입해 음성3/효과음3이 허용됐습니다. 50/100ms에서는 WARN 효과음 뒤 공통 cooldown이 OVER 음성을 억제해 음성0/효과음6이었습니다. 같은 timestamp의 다음 dispatch에서 억제가 시작할 수 있어 전체6 도달과 첫 TRIP_LIMIT 시각이 같을 수도 있습니다. 이 차이를 숨기거나 입력을 조정해 재실행하지 않았습니다. 실제 SDK 주기를 측정한 결과는 아닙니다.

지속 OVER에서는 음성3에44.4~44.8초에 도달했지만 전체6에는 도달하지 않았습니다. 모든 프로파일·품목·주기 결과와 사유별 억제량은 [54개 summary](analysis/out/reviewed-01/summary.jsonl), 더 넓은 주기 비교표는 [실행 수치표](analysis/validation-attempts/scenarios-01/numeric-summary.md)에 있습니다.

## 원자료와 독립 검토

- [전체 이벤트 ZIP](raw/events-reviewed-01.zip): 54개 ledger 모두 보존, 원문 합계 115,970,620바이트 → ZIP 3,665,674바이트. [회원별 해시](raw/events-member-manifest.json)로 압축 해제 바이트를 확인할 수 있습니다.
- [원결과 manifest](analysis/out/reviewed-01/result-manifest.tsv), [self-test](analysis/out/reviewed-01/selftest.json), [동결 manifest](analysis/frozen-files.tsv), 입력6개와 Kotlin runner/Gradle init을 포함합니다.
- [root 독립 감사](verification/alert-independent-reviewed-01.json)는54케이스의 raw 입력 순서·G 산술·허용effect/카운터·예산시점·파일해시를 대조해 오류0으로 판정했습니다. [감사 코드](verification/verify_alert_analysis.py)를 포함하며 runner 작성자의 자기검사와 구분합니다.
- [실행 원본 stdout](analysis/validation-attempts/scenarios-01/stdout.log), [stderr](analysis/validation-attempts/scenarios-01/stderr.log), [실행 receipt](analysis/validation-attempts/scenarios-01/process.json), 기존 보고서84파일의 before/after hash inventory와 앱 Git 상태 receipt를 포함합니다. 기존 시험을 다시182개 실행했다는 주장은 하지 않습니다.
- [공개 manifest](publication-manifest.json)는 원자료 SHA256과 공개본 SHA256을 별도로 기록합니다. 실행 receipt의 개인 작업경로는 `<PROJECT>`, JDK 설치경로는 `<JDK17_HOME>`으로 익명화했습니다. 숫자·판정·실패/미도달 값을 바꾸지 않았습니다.
- 캐시·컴파일 산출물·개인 호스트 경로·원래 환경값·인증정보는 포함하지 않습니다. 이 폴더는 공개를 위해 확정한 최종 증거 묶음입니다.

## 재현 배치와 오프라인 실행

기존 JDK17·Gradle wrapper 캐시·이미 선언된 Kotlin/라이브러리 캐시를 갖춘 환경이 필요합니다. 새 다운로드나 의존성 설치를 요구하지 않습니다. 앱 저장소를 `<PROJECT>/app`에 clone하고, 아래 파일 배치와 동결 SHA를 먼저 맞춥니다. source 설정/정책을 결과에 맞춰 수정하지 않습니다.

1. 이 묶음의 `analysis/`를 `<PROJECT>/.omx/qa/submission-v24/alert-analysis/`에 그대로 복사합니다. `raw/events-reviewed-01.zip`을 그 analysis 폴더에 해제하면54개 `out/reviewed-01/scenarios/.../events.jsonl`이 복원됩니다. archive는 내용을 변경하지 않는 무손실 ZIP입니다.
2. `verification/verify_alert_analysis.py`는 `<PROJECT>/.omx/qa/submission-v24/verify_alert_analysis.py`에 둡니다. 원 감사 JSON은 별도 보관합니다. 감사 코드를 실행하면 같은 디렉터리에 새 감사 JSON을 쓰므로 기존 결과를 덮지 않도록 원본을 유지합니다.
3. `analysis/frozen-files.tsv`의39개 경로는 `<PROJECT>` 기준입니다. clone의 동결한 core Kotlin 파일·설정 초기화 소스·Gradle 설정 SHA가 모두 일치해야 합니다. freeze 값은 `8f1618ac519fc8d07852fe7d4562e91a15e08b2f86315251732ff0a72c9207c4`입니다. 불일치 시 같은 엔진 재현이라고 주장하거나 manifest를 고쳐 통과시키지 않습니다.
4. 보존된 `reviewed-01`은 덮지 않습니다. 새로운 재현은 빈 run-id를 사용해 아래 self-test 후 분석 task를 실행합니다. 기존182시험 출력과 분리된 QA build/cache만 사용합니다. 출력 경로가 이미 있으면 runner는 거절합니다. 이것은 원실행의 반복 선택이 아니라 독립 재현이며 원결과와 구분해서 기록해야 합니다.

```sh
# 기존 JDK17의 JAVA_HOME을 설정한 환경에서, <PROJECT>/app 기준
./gradlew --offline --no-configuration-cache --no-daemon -PincludePleos=false \
  --project-cache-dir ../.omx/qa/submission-v24/alert-analysis/gradle-project-cache \
  -I ../.omx/qa/submission-v24/alert-analysis/analysis.init.gradle \
  -Pv24RunId=reproduction-01 :core:v24AlertSelftest
# 위 self-test가 같은 freeze로 통과한 뒤에만:
./gradlew --offline --no-configuration-cache --no-daemon -PincludePleos=false \
  --project-cache-dir ../.omx/qa/submission-v24/alert-analysis/gradle-project-cache \
  -I ../.omx/qa/submission-v24/alert-analysis/analysis.init.gradle \
  -Pv24RunId=reproduction-01 -Pv24AllowScenarios=true :core:v24AlertRun
```

`analysis/RUN.md`와 `spec.md`는 실행 전 준비 문서의 내용입니다. 제목의 작업 표기만 정리했으며, 동결 해시와 비교할 때는 포장 명세에 기록된 정리 전 원본 바이트를 사용합니다. 현재 완료 상태는 실제 selftest/summary/실행 receipt/독립 감사에서 확인합니다. 공개 README의 완료 주장과 동결 전 문서의 시점이 다릅니다.

## 제출 자료의 경로와 해시

원시 파일·재현 코드는 수집 당시 바이트를 보존했습니다. 원 명세의 경로·해시는 정리 전 자료 기준이며, 제목·링크를 정리한 Markdown과 현재 폴더 배치는 [포장 명세](../packaging-manifest.json)의 원본 경로·원본 해시·현재 해시로 대조합니다. 수집 식별자와 실행 명령의 고정 이름은 출처와 재현성을 위해 유지했습니다.
