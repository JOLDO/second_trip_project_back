# 🚗 Car (차량 렌트) 백엔드 문서 - Spring

## 🔐 인증 구조

```mermaid
flowchart TD
    A[APILoginFilter] -->|mid/mpw 파싱| B[AuthenticationManager]
    B --> C[APIUserDetailsService]
    C --> D[MemberRepository]
    D --> E[(trip_member DB)]
    B -->|인증 성공| F[APILoginSuccessHandler]
    F --> G[JWTUtil]
    G -->|accessToken, refreshToken 생성| F
    H[TokenCheckFilter] --> G
    I[RefreshTokenFilter] --> G
```

---

## 🗄️ Member 엔티티

```mermaid
erDiagram
    trip_member {
        Long id PK
        String mid UK "로그인 ID"
        String mpw "비밀번호"
        String mname "이름"
        String email UK
        String phone
        String role "USER/ADMIN"
        String profileImg
        LocalDateTime regDate
    }
```

---

## 🗄️ 차량 엔티티 관계

```mermaid
erDiagram
    trip_rent_company {
        Long id PK
        String name "업체명"
        String region "지역"
        String address
        String latitude
        String longitude
        String phone
    }
    trip_car {
        Long id PK
        Long company_id FK
        String name "차종명"
        String type
        int seats "좌석수"
        String fuel "연료"
        int dailyPrice "일일 가격"
        int year "연식"
    }
    trip_rental {
        Long id PK
        Long car_id FK
        String user_id FK "member.mid 참조"
        LocalDateTime startDate
        LocalDateTime endDate
        String status "PENDING/CONFIRMED"
        LocalDateTime createdAt
    }
    trip_rent_company ||--o{ trip_car : "1:N"
    trip_car ||--o{ trip_rental : "1:N"
    trip_member ||--o{ trip_rental : "1:N"
```

---

## 🏗️ 차량 검색 레이어 구조

```mermaid
flowchart TD
    A[RentCarController\nGET /car/search/all] --> B[RentCarServiceImpl]
    B -->|searchUnavailableCarIds| C[CarReservationRepository]
    B -->|searchCarTypesByRegionWithCursor\nGROUP BY 차종 + 커서 페이지네이션| D[CarRepository]
    B -->|searchOptionsByCarNamesIn\n차종 이름 IN절로 개별 차량 조회| D
    D --> E[(QueryDSL\nCarSearchImpl)]
    C --> F[(QueryDSL\nCarReservationSearchImpl)]
```

---

## 🔍 차량 검색 쿼리 흐름

```mermaid
sequenceDiagram
    RentCarServiceImpl->>CarReservationSearchImpl: searchUnavailableCarIds(startDate, endDate)
    Note over CarReservationSearchImpl: 날짜 겹치는 예약의 car_id 목록 반환
    CarReservationSearchImpl-->>RentCarServiceImpl: List<Long> unavailableIds

    RentCarServiceImpl->>CarSearchImpl: searchCarTypesByRegionWithCursor(region, unavailableIds, cursorPrice, cursorName, size+1)
    Note over CarSearchImpl: GROUP BY 차종(이름/타입/좌석/연료)<br/>HAVING minPrice > cursorPrice<br/>OR (minPrice = cursorPrice AND name > cursorName)
    CarSearchImpl-->>RentCarServiceImpl: List<CarTypeDTO>

    RentCarServiceImpl->>CarSearchImpl: searchOptionsByCarNamesIn(carNames, region, unavailableIds)
    Note over CarSearchImpl: 차종 이름 IN절로 개별 차량 + 업체 fetchJoin
    CarSearchImpl-->>RentCarServiceImpl: List<Car>
```

---

## 📝 예약 생성 및 취소

```mermaid
flowchart TD
    A[CarReservationController\nPOST /api/car/reservation] --> B[RentalServiceImpl]
    B -->|searchExistsOverlap| C[CarReservationSearchImpl]
    C -->|날짜 겹침 확인| D{겹침?}
    D -->|Yes| E[RuntimeException 400]
    D -->|No| F[CarReservationRepository.save]

    G[CarReservationController\nDELETE /api/car/reservation/id] --> H[RentalServiceImpl]
    H -->|startDate 이전인지 확인| I{취소 가능?}
    I -->|No| J[RuntimeException 400]
    I -->|Yes| K[상태 변경 or 삭제]
```

---

## 📋 내 예약 목록 커서 정렬

```mermaid
flowchart LR
    A["1순위: CONFIRMED 먼저\nstatusOrder = 0"] --> B["2순위: startDate DESC"]
    B --> C["3순위: id ASC"]
    D["커서 조건\nstartDate < cursorStartDate\nOR (startDate = cursor AND statusOrder > cursor)\nOR (startDate = cursor AND statusOrder = cursor AND id > cursorId)"]
```

---

## 📦 응답 DTO 구조

```mermaid
classDiagram
    class CarSearchCursorResponseDTO {
        List~CarSearchResultDTO~ content
        boolean hasNext
        Integer nextCursorPrice
        String nextCursorName
    }
    class CarSearchResultDTO {
        String carName
        String type
        int seats
        String fuel
        int lowestPrice
        List~CompanyCarDTO~ companyCarDTOs
    }
    class CompanyCarDTO {
        Long carId
        String companyName
        int year
        int dailyPrice
    }
    CarSearchCursorResponseDTO --> CarSearchResultDTO
    CarSearchResultDTO --> CompanyCarDTO
```
