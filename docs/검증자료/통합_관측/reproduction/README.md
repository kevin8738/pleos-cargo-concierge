# 재현 코드 선택본과 안전한 오프라인 확인

아래 읽기 전용 명령은 **정리 전 원본 묶음**의 Markdown·원 명세까지 같은 바이트로 배치했을 때 사용하는 원 검산 코드다. 현재 제출 묶음의 문서는 제목·링크가 정리되어 원 해시와 다를 수 있으므로, 현재 배치는 상위 [포장 명세](../../packaging-manifest.json)로 확인한다. 원 검산 코드를 현재 문서의 해시에 맞춰 수정하지 않았다. Python 표준 라이브러리만 사용하며 ADB·네트워크·앱·수집기·기존 하네스를 실행하거나 파일을 덮어쓰지 않는다.

```sh
python3 reproduction/verify_bundle.py
```

공개 바이트 해시, 원 false4개, 원 cleanup=true, 독립57개 결과, PCM/WAV의 동일 프레임과 희소 길이, 실제 로컬 링크를 검산한다. 이 검산의 성공은 통합 실행 PASS나 미디어 공개 승인과 다르다.

## 보존된 실행·분석 코드

- [실제 실행한 동결 하네스](../integration-01/harness-source.py), [본인 Console helper](../integration-01/console-lease-source.py)
- [독립 검산 원 코드](../integration-verification/verify_integration.py)
- [실행 의존 해시](../integration-01/dependency-sha256.json), [사전 계약](../preparation/integration-contract.json), [정적 검수](../preparation/integration-static-review.json)
- [기존 driving helper](source-dependencies/driving_audio_probe.py), [boundary oracle](source-dependencies/known_boundary_probe.py), [runtime helper](source-dependencies/runtime_avd_probe.py), [known-answer helper](source-dependencies/known_answer_probe.py), [설정 matrix helper](source-dependencies/run_matrix.py), [streamAudio 수집기](source-dependencies/capture_emulator_audio.py)

이 코드들은 검토 가능한 실행 출처 선택본이다. 원 실행 하네스/독립 검산 코드는 원 저장소 디렉터리 구조와 core source 파일을 전제로 하므로 이 bundle에서 임의 실행하지 않는다. 경로 구조를 재구성하거나 코드를 바꿔 원 판정을 다시 쓰지 않는다. 특히 독립 검산 원 코드는 결과 파일을 작성하는 top-level 프로그램이며 import 대상으로 쓰지 않는다.

AVD 실행에는 승인된 같은 APK·공식 AVD·SDK·기존 Python grpc/Playwright 환경 및 비공개 연결 인증이 별도로 필요하다. 인증·로컬 연결 INI는 공개하지 않는다. 이 자료는 추가 실행 승인이 아니며 `integration-01`은 한 회차로 종료됐다. 코드/임계/정책 변경·빈 행 생성·시계 보정 최적화·재실행으로 최초 false를 PASS로 바꾸지 않는다.
