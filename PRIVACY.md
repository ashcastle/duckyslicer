# DuckySlicer Privacy Policy

Effective date: August 10, 2026

DuckySlicer (`com.ashcastle.duckyslicer`) is an offline-first, open-source Android
slicer maintained through the
[DuckySlicer project](https://github.com/ashcastle/duckyslicer). This policy applies
to official DuckySlicer builds published by that project. Modified builds and forks
may handle data differently.

## Data DuckySlicer does not collect

The official app has no developer-operated account, advertising, analytics,
crash-reporting SDK, or DuckySlicer cloud service.
The DuckySlicer project does not collect, sell, or share personal data or app
activity from the app.

## Data stored on your device

Imported models, projects, printer and filament profiles, slicing settings, recent
choices, generated G-code, and printer connection details are stored in the app's
private storage. Printer access keys are protected with Android Keystore and are not
included in exported printer profiles. Android backup is disabled for the app.
A bounded history of recent problem categories and times is also kept locally so you
can choose to create support details without installing a tracking service. It does
not store error messages, stack traces, file names, printer addresses, or access keys.
On Android 11 and later, Android also keeps a bounded system history of prior app
process exits. DuckySlicer reads at most four entries only when you create support
details and reduces each entry to its time, a fixed app-or-slicer process kind, and a
fixed exit reason. It does not read the system description, trace, raw process name,
or memory sample.

Background slicing uses an Android notification to show progress and let you reopen
the app or cancel the current slice. It does not send models or G-code to the
DuckySlicer project.

## Actions that send data elsewhere

DuckySlicer sends data only when you choose an action that requires it:

- Exported DuckySlicer project files contain the model geometry, object placement,
  support, seam, and multi-color painting, variable layer-height ranges, and active printer, filament,
  and slicing settings needed to
  reopen the project. They do not contain G-code, saved printer addresses, or printer
  access keys. Project files and exported G-code are written only to the location you
  select. A storage provider you select may process that file under its own privacy
  terms.
- Optional OctoPrint and Klipper connections send status requests, the access key
  required by your server, and G-code uploads directly to the printer address you
  configured. This traffic does not pass through a DuckySlicer-operated service.
  HTTPS uses Android's certificate and hostname checks. If you choose an unencrypted
  local-network HTTP connection, that local traffic is not encrypted.
- The source-code page opens GitHub in your browser only when you tap it. Your browser
  and GitHub then handle the request under their own privacy terms.
- Support details are written only to a location you select. They contain the app and
  Android versions, device manufacturer and model, memory and storage capacity,
  non-sensitive display and connection preferences, and the fixed categories and
  times of recent problems. On Android 11 and later, they also contain up to four
  prior process-exit times, fixed process kinds, and fixed exit reasons. They do not
  contain models, G-code, file names, printer addresses, access keys, free-form error
  text, system exit descriptions, raw process names, memory samples, or stack traces.
  The DuckySlicer project receives this report only if you choose to share it.

Printer servers and selected storage providers may keep uploaded files or connection
logs. DuckySlicer does not control their retention practices.

## Retention and deletion

Local data remains until you delete it, clear DuckySlicer's app data, or uninstall
the app. Deleting a saved printer connection also removes its protected access key.
Files you exported and files or logs retained by a printer or storage provider must
be deleted from those locations separately. Because the DuckySlicer project does not
receive app data, it has no developer-held app data to delete on your behalf.

## Privacy questions and changes

For a privacy question that contains no personal or sensitive information, submit an
inquiry through the project's
[issue tracker](https://github.com/ashcastle/duckyslicer/issues/new). Do not post
personal data, printer credentials, private models, or G-code in a public issue. For
a sensitive privacy or security concern, follow the private-contact process in
[SECURITY.md](https://github.com/ashcastle/duckyslicer/blob/main/SECURITY.md).

Material changes will be published in this file with a new effective date before
they are included in an official release.

---

# DuckySlicer 개인정보처리방침

시행일: 2026년 8월 10일

DuckySlicer(`com.ashcastle.duckyslicer`)는
[DuckySlicer 프로젝트](https://github.com/ashcastle/duckyslicer)가 관리하는
오프라인 우선 오픈 소스 Android 슬라이서입니다. 이 방침은 해당 프로젝트가
배포하는 공식 빌드에 적용됩니다. 수정 빌드와 포크는 데이터를 다르게 처리할
수 있습니다.

## DuckySlicer가 수집하지 않는 데이터

공식 앱에는 개발자가 운영하는 계정, 광고, 이용 분석, 비정상 종료 보고 SDK 또는
DuckySlicer 클라우드 서비스가 없습니다. DuckySlicer 프로젝트는 앱의 개인정보나
앱 활동을 수집·판매·공유하지 않습니다.

## 기기에 저장되는 데이터

가져온 모델, 프로젝트, 프린터 및 필라멘트 프로필, 슬라이싱 설정, 최근 선택
내용, 생성된 G-code와 프린터 연결 정보는 앱 전용 저장 공간에 보관됩니다.
프린터 접속 키는 Android Keystore로 보호되며 내보낸 프린터 프로필에 포함되지
않습니다. 이 앱은 Android 백업을 사용하지 않습니다.
추적 서비스를 설치하지 않고도 지원 정보를 만들 수 있도록 최근 문제의 고정된
분류와 발생 시각을 제한된 개수만 기기에 저장합니다. 오류 메시지, 스택 트레이스,
파일 이름, 프린터 주소 또는 접속 키는 저장하지 않습니다.
Android 11 이상에서는 Android가 이전 앱 프로세스의 종료 이력을 제한된 개수로
기기에 보관합니다. DuckySlicer는 사용자가 지원 정보를 만들 때에만 최대 4건을
읽어 종료 시각, 앱 또는 슬라이서로 구분한 고정 프로세스 분류, 고정 종료 원인으로
축약합니다. 시스템 설명, 추적 정보, 원래 프로세스 이름 또는 메모리 표본은 읽지
않습니다.

백그라운드 슬라이싱은 진행 상태를 보여주고 앱을 다시 열거나 현재 슬라이싱을
취소할 수 있도록 Android 알림을 사용합니다. 모델이나 G-code를 DuckySlicer
프로젝트로 보내지 않습니다.

## 사용자가 선택할 때 외부로 전송되는 데이터

DuckySlicer는 사용자가 다음 동작을 선택한 경우에만 필요한 데이터를 전송합니다.

- 내보낸 DuckySlicer 프로젝트 파일에는 프로젝트를 다시 여는 데 필요한 모델
  형상, 오브젝트 배치, 서포트·심·다중 색상 채색, 가변 레이어 높이 구간, 현재
  프린터·필라멘트·슬라이싱 설정이 포함됩니다. G-code, 저장된 프린터 주소 또는
  프린터 접속 키는 포함되지 않습니다. 프로젝트 파일과 내보낸 G-code는
  사용자가 선택한 위치에만 저장됩니다. 선택한 저장 공간 제공자는 자체
  개인정보처리방침에 따라 파일을 처리할 수 있습니다.
- 선택 기능인 OctoPrint 및 Klipper 연결은 상태 요청, 서버에 필요한 접속 키와
  G-code를 사용자가 설정한 프린터 주소로 직접 보냅니다. 이 트래픽은
  DuckySlicer가 운영하는 서비스를 거치지 않습니다. HTTPS 연결에는 Android의
  인증서 및 호스트 이름 검증이 적용됩니다. 암호화되지 않은 로컬 네트워크 HTTP
  연결을 선택하면 해당 로컬 트래픽은 암호화되지 않습니다.
- 소스 코드 페이지는 사용자가 누른 경우에만 브라우저에서 GitHub를 엽니다.
  이후 요청은 브라우저와 GitHub의 개인정보처리방침에 따라 처리됩니다.
- 지원 정보는 사용자가 선택한 위치에만 저장됩니다. 앱 및 Android 버전, 기기
  제조사와 모델, 메모리 및 저장 공간 용량, 민감하지 않은 화면·연결 설정, 최근
  문제의 고정된 분류와 발생 시각이 포함됩니다. Android 11 이상에서는 이전
  프로세스 종료 시각, 고정 프로세스 분류 및 고정 종료 원인을 최대 4건 포함합니다.
  모델, G-code, 파일 이름, 프린터 주소, 접속 키, 자유 형식 오류 내용, 시스템 종료
  설명, 원래 프로세스 이름, 메모리 표본 또는 스택 트레이스는 포함되지 않습니다.
  사용자가 직접 공유한 경우에만 DuckySlicer 프로젝트가 이 정보를 받습니다.

프린터 서버와 사용자가 선택한 저장 공간 제공자는 업로드된 파일이나 연결 로그를
보관할 수 있습니다. DuckySlicer는 해당 서비스의 보관 방식을 통제하지 않습니다.

## 보관 및 삭제

로컬 데이터는 사용자가 삭제하거나 DuckySlicer의 앱 데이터를 지우거나 앱을
제거할 때까지 남습니다. 저장된 프린터 연결을 삭제하면 보호된 접속 키도 함께
제거됩니다. 내보낸 파일과 프린터 또는 저장 공간 제공자가 보관한 파일·로그는
각 위치에서 별도로 삭제해야 합니다. DuckySlicer 프로젝트는 앱 데이터를 받지
않으므로 사용자를 대신해 삭제할 개발자 보유 앱 데이터가 없습니다.

## 개인정보 문의 및 방침 변경

개인정보나 민감한 정보가 포함되지 않은 문의는 프로젝트
[이슈 트래커](https://github.com/ashcastle/duckyslicer/issues/new)에 남길 수
있습니다. 공개 이슈에 개인정보, 프린터 접속 정보, 비공개 모델 또는 G-code를
게시하지 마세요. 민감한 개인정보 또는 보안 문제는
[SECURITY.md](https://github.com/ashcastle/duckyslicer/blob/main/SECURITY.md)의
비공개 연락 절차를 따르세요.

중요한 변경 사항은 공식 릴리스에 포함되기 전에 새 시행일과 함께 이 파일에
게시됩니다.
