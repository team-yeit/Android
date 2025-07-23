# 🍔 Yeet Application 사용 가이드

## 📱 1. 앱 설치 및 실행

### 빌드 및 설치
```bash
# 1. 프로젝트 디렉토리로 이동
cd /home/injun/project/team-yeit/Android

# 2. 앱 빌드 및 설치
./gradlew installDebug

# 또는 Android Studio에서 Run 버튼 클릭
```

### 최초 실행 시 권한 허용
앱을 처음 실행하면 다음 권한들을 요청합니다:
- ✅ **카메라**: 간판/메뉴판 촬영
- ✅ **마이크**: 음성 인식
- ✅ **오버레이**: 화면 위에 떠있는 UI
- ✅ **접근성**: 다른 앱에서 텍스트 입력
- ✅ **저장소**: 사진 임시 저장

## 🎯 2. 기본 사용법

### A. 메인 앱에서 사용하기

#### 1) 앱 실행
- 런처에서 "YeetApplication" 아이콘 터치
- 웹뷰 기반 하이브리드 UI가 나타남

#### 2) 권한 확인 및 설정
```javascript
// 개발자 콘솔에서 확인 가능
Android.checkPermissions()        // "true,true" (오디오,오버레이)
Android.hasCameraPermission()     // true/false
```

#### 3) 오버레이 서비스 시작
```javascript
Android.startOverlayService()     // 화면 위에 떠있는 주문 도우미 시작
```

### B. 오버레이 모드에서 사용하기 (주요 기능!)

#### 1) 오버레이 활성화
- 메인 앱에서 "오버레이 시작" 버튼 터치
- 또는 JavaScript: `Android.startOverlayService()`
- 화면 상단에 작은 정사각형 오버레이가 나타남

#### 2) 배달앱 실행
```javascript
// 배달의민족 실행 (또는 다른 배달앱)
Android.openBaemin()
// 또는
Android.openDeliveryApp("com.sampleapp")
```

#### 3) 음성으로 주문하기
1. **오버레이의 마이크 버튼 터치**
2. **"맥도날드"라고 말하기**
   - OCR API가 자동으로 정제
   - "어... 그... 맥도날드"도 "맥도날드"로 정제됨
3. **가게 찾기 성공 시 메뉴 선택 단계로 이동**
4. **"빅맥"이라고 말하기**
   - 메뉴 검색 및 표시

## 📸 3. OCR 기능 사용법

### A. 간판 스캔해서 가게 찾기

#### JavaScript에서:
```javascript
// 가게 간판 스캔
Android.scanStoreSign()

// 결과 처리
function onOcrResults(filterType, results) {
    if (filterType === "store") {
        console.log("찾은 가게들:", results);
        // results: [{text: "맥도날드", x: 100, y: 50}]
    }
}
```

#### 실제 사용:
1. 오버레이에서 "📸 간판 스캔" 버튼 터치
2. 카메라가 열리고 자동 촬영
3. OCR이 간판에서 가게이름 추출
4. 추출된 가게이름으로 자동 검색

### B. 메뉴판 스캔해서 메뉴 찾기

```javascript
// 메뉴판 스캔
Android.scanMenu()

// 결과 처리
function onOcrResults(filterType, results) {
    if (filterType === "food") {
        console.log("찾은 메뉴들:", results);
        // results: [{text: "빅맥세트", x: 150, y: 200}, {text: "치킨버거", x: 150, y: 250}]
    }
}
```

## 🗣️ 4. 음성 정제 기능

### 더듬거리는 음성을 정확하게 인식

#### 예시:
```javascript
// 사용자가 "어... 그... 맥도날드... 어디있지?" 라고 말함
Android.refineTextWithOcr("어... 그... 맥도날드... 어디있지?", "store")

// 결과: "맥도날드" - 깔끔하게 정제됨
function onTextRefined(extractType, result) {
    console.log("정제된 가게이름:", result); // "맥도날드"
}
```

#### 지원하는 정제 타입:
- `"store"`: 가게이름 추출
- `"food"`: 음식이름 추출  
- `"number"`: 숫자 추출

## 🎮 5. 실제 사용 시나리오

### 시나리오 1: 음성으로 주문하기
```
1. 사용자: 오버레이 서비스 시작
2. 사용자: 배달의민족 앱 실행
3. 사용자: 오버레이 마이크 버튼 터치
4. 사용자: "어... 맥도날드" (더듬거림)
   → OCR API가 "맥도날드"로 정제
   → 가게 검색 성공
5. 사용자: "빅맥" 
   → 메뉴 검색 성공
6. 완료! 주문 진행
```

### 시나리오 2: 간판 스캔으로 가게 찾기
```
1. 길을 걸으며 음식점 간판 발견
2. 오버레이에서 "📸 스캔" 버튼 터치
3. 카메라로 간판 촬영
4. OCR이 "교촌치킨" 추출
5. 배달앱에서 교촌치킨 자동 검색
```

### 시나리오 3: 메뉴판 스캔으로 메뉴 선택
```
1. 식당 앞 메뉴판 발견
2. "메뉴 스캔" 기능 사용
3. OCR이 모든 메뉴 추출
4. 원하는 메뉴 음성으로 선택
5. 주문 완료
```

## 🛠️ 6. 개발자 도구 및 디버깅

### JavaScript 콘솔에서 테스트
```javascript
// OCR 서비스 상태 확인
Android.checkOcrServiceHealth()

// 사용 가능한 모든 API 보기
Android.getApiList()

// 권한 상태 확인
Android.getAllPermissionStatus()

// 설치된 배달앱 목록
Android.getInstalledDeliveryApps()
```

### 로그 확인
```bash
# Android 로그캣에서 OCR 관련 로그 확인
adb logcat | grep -E "(OcrApiService|WebOverlay|CameraManager)"
```

## ⚠️ 7. 문제 해결

### 자주 발생하는 문제들

#### 1. 카메라가 안 켜져요
```javascript
// 권한 확인
if (!Android.hasCameraPermission()) {
    Android.requestPermission("camera");
}
```

#### 2. OCR이 작동하지 않아요
```javascript
// OCR 서비스 상태 확인
Android.checkOcrServiceHealth()

// 네트워크 연결 확인 필요 (https://yeit.ijw.app)
```

#### 3. 음성 인식이 부정확해요
- OCR API 정제 기능을 사용하세요
- 더듬거려도 정확하게 추출됩니다

#### 4. 오버레이가 터치되지 않아요
```javascript
// 터치 모드 활성화
Android.enableTouchMode()
```

## 🎯 8. 핵심 장점

### ✨ 기존 앱 대비 우수한 점
- **더듬거리는 음성도 정확하게 인식** (OCR API 정제)
- **간판/메뉴판 사진으로도 주문 가능** 
- **어떤 배달앱에서도 사용 가능** (오버레이)
- **실시간 음성 + 이미지 분석**
- **완전 자동화된 주문 프로세스**

### 🚀 활용 팁
- 시끄러운 곳에서는 간판 스캔 사용
- 복잡한 메뉴는 메뉴판 스캔 후 음성 선택
- 오버레이는 드래그해서 위치 조정 가능
- 여러 배달앱을 동시에 비교 가능

---

**🎉 이제 Yeet Application으로 더 쉽고 정확한 배달 주문을 경험하세요!**

*"더듬거려도, 사진으로도, 완벽한 주문이 가능합니다!"*