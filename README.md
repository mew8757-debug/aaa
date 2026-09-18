# 성삼국지 촉한영걸전 Android Launcher

Windows용 조조전 MOD인 **성삼국지 촉한영걸전**을 Android에서 Winlator 계열 실행환경으로 빠르게 실행하기 위한 전용 런처 프로젝트입니다.

## v0.1 목표
- 설치된 Winlator 계열 앱 자동 감지
- Winlator가 내보낸 `.desktop` 바로가기 경로 저장
- 저장된 바로가기를 `XServerDisplayActivity` + `shortcut_path` 방식으로 직접 실행
- 직접 실행이 실패하면 Winlator 기본 실행 화면으로 자동 폴백
- 게임 데이터는 APK와 분리하여 유지
- GitHub Actions로 Debug APK 자동 빌드

## 사용 흐름
1. Android에 Winlator 또는 호환 포크 설치
2. 성삼국지 촉한영걸전 게임 폴더를 Android 저장소에 복사
3. Winlator에서 컨테이너를 만들고 게임 EXE 바로가기를 생성
4. 가능하면 Winlator의 Frontend/Export 기능으로 `.desktop` 바로가기를 공유 저장소에 내보냄
5. 이 런처 앱에서 Winlator 종류와 `.desktop` 파일을 지정
6. 다음부터는 런처의 **게임 실행** 버튼으로 바로 시작

> 이 저장소에는 게임 원본 데이터나 저작권이 있는 리소스를 포함하지 않습니다.
