# OCR API 통합 완료 가이드

## 📋 구현된 기능들

### 1. OCR API 서비스 클래스 (`OcrApiService.kt`)
- **이미지 텍스트 추출**: `extractTextFromImage(bitmap, filterType)`
- **텍스트 정제**: `extractFromText(text, extractType)`
- **서비스 상태 확인**: `checkHealth()`
- **편의 메서드들**: `refineStoreName()`, `refineFoodName()`, `extractNumber()`

### 2. 카메라 관리자 (`CameraManager.kt`)
- **권한 확인**: `hasCameraPermission()`
- **카메라 초기화**: `initializeCamera()`
- **사진 촬영**: `takePhotoToBitmap()`, `takePhotoToFile()`
- **이미지 최적화**: `resizeBitmapForOcr()`
- **회전 보정**: EXIF 데이터 기반 자동 회전

### 3. WebView Bridge API 확장
#### WebActivity 및 WebOverlayService에 추가된 JavaScript 인터페이스:

```javascript
// 권한 확인
Android.hasCameraPermission()

// 사진 촬영 및 OCR 분석
Android.takePhotoAndAnalyze("store")    // 가게이름 추출
Android.takePhotoAndAnalyze("food")     // 음식이름 추출  
Android.takePhotoAndAnalyze("all")      // 모든 텍스트 추출

// 편의 메서드
Android.scanStoreSign()  // 가게 간판 스캔
Android.scanMenu()       // 메뉴판 스캔
Android.scanAll()        // 전체 스캔

// 텍스트 정제
Android.refineTextWithOcr("더듬거리는 음성", "store")
Android.refineTextWithOcr("더듬거리는 음성", "food")
Android.refineTextWithOcr("더듬거리는 음성", "number")

// 서비스 상태 확인
Android.checkOcrServiceHealth()
```

### 4. JavaScript 콜백 함수들
웹페이지에서 구현해야 할 콜백 함수들:

```javascript
// OCR 결과 처리
function onOcrResults(filterType, results) {
    // results: [{text: "텍스트", x: 100, y: 200}, ...]
    console.log("OCR 결과:", filterType, results);
}

function onOcrError(errorMessage) {
    console.error("OCR 오류:", errorMessage);
}

// 카메라 오류 처리
function onCameraError(errorMessage) {
    console.error("카메라 오류:", errorMessage);
}

// 텍스트 정제 결과
function onTextRefined(extractType, result) {
    console.log("정제된 텍스트:", extractType, result);
}

function onTextRefineError(errorMessage) {
    console.error("텍스트 정제 오류:", errorMessage);
}

// 서비스 상태 확인
function onOcrHealthCheck(isHealthy, status) {
    console.log("OCR 서비스 상태:", isHealthy, status);
}
```

### 5. 음성 인식 개선
- **OverlayService**: 음성 인식 결과를 OCR API로 자동 정제
- **WebOverlayService**: JavaScript에서 `refineTextWithOcr()` 호출 가능

## 🚀 사용 시나리오

### 시나리오 1: 가게 간판 스캔
```javascript
// 카메라 권한 확인
if (Android.hasCameraPermission()) {
    // 가게 간판 스캔
    Android.scanStoreSign();
} else {
    // 권한 요청
    Android.requestPermission("camera");
}
```

### 시나리오 2: 메뉴판 스캔
```javascript
Android.scanMenu(); // 메뉴판에서 음식이름만 추출
```

### 시나리오 3: 음성 인식 결과 정제
```javascript
function onSpeechResult(recognizedText) {
    // 더듬거리는 음성을 정제하여 가게이름 추출
    Android.refineTextWithOcr(recognizedText, "store");
}

function onTextRefined(extractType, result) {
    if (extractType === "store") {
        console.log("정제된 가게이름:", result);
        // 정제된 결과로 가게 검색
        searchStore(result);
    }
}
```

### 시나리오 4: OCR 서비스 상태 모니터링
```javascript
// 앱 시작 시 OCR 서비스 상태 확인
Android.checkOcrServiceHealth();

function onOcrHealthCheck(isHealthy, status) {
    if (isHealthy) {
        console.log("OCR 서비스 정상:", status);
        // OCR 기능 활성화
        enableOcrFeatures();
    } else {
        console.warn("OCR 서비스 비정상:", status);
        // OCR 기능 비활성화 또는 대안 제시
        disableOcrFeatures();
    }
}
```

## 🔧 기술적 세부사항

### HTTP 클라이언트
- **OkHttp 4.12.0** 사용
- **로깅 인터셉터** 포함 (디버그 용도)
- **Gson** JSON 파싱

### 카메라
- **CameraX 1.3.1** 사용
- **EXIF 기반 회전 보정**
- **비트맵 크기 최적화** (OCR 성능 향상)

### 네트워크 보안
- **Network Security Config** 적용
- **HTTPS 강제** (yeit.ijw.app)
- **로컬 개발 환경** cleartext 허용

### 권한
- `CAMERA`: 사진 촬영
- `INTERNET`: OCR API 통신
- `WRITE_EXTERNAL_STORAGE`: 임시 파일 저장

## 📱 API 엔드포인트

### 베이스 URL
```
https://yeit.ijw.app
```

### 주요 엔드포인트
- `POST /image/extract?type={store|food}`: 이미지 OCR
- `POST /text/extract?type={store|food|number}`: 텍스트 정제
- `GET /health`: 서비스 상태 확인

## 🐛 오류 처리

모든 OCR 관련 기능은 **Fallback 메커니즘**을 포함:
1. OCR API 실패 시 원본 텍스트 사용
2. 네트워크 오류 시 로컬 검색 알고리즘 사용
3. 권한 부족 시 사용자에게 안내 메시지

## ✅ 완성도

### 구현 완료 ✓
- OCR API 서비스 클래스
- 카메라 관리자
- WebView Bridge 확장
- 음성 인식 개선
- 권한 및 보안 설정
- 오류 처리 및 Fallback

### 추가 구현 권장사항
- 오프라인 OCR (ML Kit 등)
- 이미지 전처리 (대비/밝기 조정)
- OCR 결과 캐싱
- 배치 처리 (다중 이미지)

---

**🎉 OCR API 통합이 완료되었습니다!**
이제 Yeet Application은 음성 인식과 이미지 인식을 모두 지원하는 
완전한 배달 주문 도우미가 되었습니다.