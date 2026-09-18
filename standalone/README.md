# Standalone build notes

이 빌드는 Winlator 11.2 소스를 기반으로 성삼국지 촉한영걸전용 단일 앱 UX를 만드는 실험 빌드입니다.

- Winlator 원본: https://github.com/brunodev85/winlator-app
- Winlator 라이선스: GNU LGPL v2.1
- 이 저장소의 `standalone/SeongSamgukjiActivity.java`와 빌드 워크플로가 변경사항입니다.
- 게임 원본 데이터는 저장소와 APK에 포함하지 않습니다.
- 사용자가 처음 실행할 때 자신의 `game1.zip`, `game2.zip`을 선택하면 앱 전용 저장공간에 합쳐 설치합니다.
- 알려진 조조전 계열 시작 영상 문제를 피하기 위해 logo/open/opening/intro/start 이름의 AVI는 자동 비활성화합니다.
- 게임 실행은 Winlator 내부 XServer/Wine/Box86·Box64 환경을 직접 호출하며 Winlator 메인 UI는 런처로 노출하지 않습니다.
