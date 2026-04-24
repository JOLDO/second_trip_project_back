package com.busanit401.second_trip_project_back.service.car;

import com.busanit401.second_trip_project_back.domain.car.Car;
import com.busanit401.second_trip_project_back.domain.car.RentCarCompany;
import com.busanit401.second_trip_project_back.dto.car.CarCompanyApiDTO;
import com.busanit401.second_trip_project_back.repository.car.CarRepository;
import com.busanit401.second_trip_project_back.repository.car.RentCarCompanyRepository;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;

@Service
@Log4j2
@RequiredArgsConstructor
public class RentCarDataService {

    private final RentCarCompanyRepository rentCarCompanyRepository;
    private final CarRepository carRepository;

    // 차량 스펙 정의
    private record CarSpec(String name, String type, int seats, String fuel, int priceMin, int priceMax) {}

    private static final List<CarSpec> CAR_SPECS = List.of( //차종의 더미데이터
            new CarSpec("모닝",          "경형", 4, "가솔린",    20000, 30000),
            new CarSpec("스파크",         "경형", 4, "가솔린",    20000, 28000),
            new CarSpec("레이",           "경형", 4, "가솔린",    22000, 32000),
            new CarSpec("캐스퍼",         "경형", 4, "가솔린",    25000, 35000),
            new CarSpec("아반떼",         "소형", 5, "가솔린",    30000, 42000),
            new CarSpec("K3",            "소형", 5, "가솔린",    30000, 42000),
            new CarSpec("벨로스터",       "소형", 4, "가솔린",    33000, 45000),
            new CarSpec("쏘나타",         "중형", 5, "가솔린",    45000, 60000),
            new CarSpec("K5",            "중형", 5, "가솔린",    45000, 60000),
            new CarSpec("말리부",         "중형", 5, "가솔린",    43000, 58000),
            new CarSpec("그랜저",         "대형", 5, "하이브리드", 70000, 95000),
            new CarSpec("K8",            "대형", 5, "가솔린",    68000, 90000),
            new CarSpec("제네시스 G80",   "대형", 5, "가솔린",    90000, 130000),
            new CarSpec("티볼리",         "SUV",  5, "가솔린",    40000, 55000),
            new CarSpec("셀토스",         "SUV",  5, "가솔린",    42000, 58000),
            new CarSpec("투싼",           "SUV",  5, "가솔린",    50000, 68000),
            new CarSpec("스포티지",       "SUV",  5, "디젤",      50000, 68000),
            new CarSpec("싼타페",         "SUV",  7, "디젤",      65000, 85000),
            new CarSpec("팰리세이드",     "SUV",  7, "디젤",      75000, 100000),
            new CarSpec("스타리아",       "승합", 9, "디젤",      70000, 95000),
            new CarSpec("카니발",         "승합", 9, "디젤",      65000, 90000),
            new CarSpec("그랜드 스타렉스","승합", 11,"디젤",      60000, 85000),
            new CarSpec("아이오닉5",      "SUV",  5, "전기",      60000, 80000),
            new CarSpec("EV6",           "SUV",  5, "전기",      58000, 78000),
            new CarSpec("볼트 EV",        "소형", 5, "전기",      40000, 55000)
    );

    @Value("${public.api.rent.serviceKey}")
    private String serviceKey;

    private static final String API_URL = "https://api.data.go.kr/openapi/tn_pubr_public_car_rental_api";
    private static final int NUM_OF_ROWS = 1000;

//    @PostConstruct
    public void init() {    //키값을 만약 넣고 실행 했다면 서버 시작시 데이터를 1000개의 읽어옴
        if (rentCarCompanyRepository.count() > 0) {
            log.info("렌트카 회사 데이터 이미 존재 - API 호출 스킵");
            // 회사는 있는데 차량이 없으면 차량만 생성
            if (carRepository.count() == 0) {
                log.info("차량 데이터 없음 - 차량만 생성 시작");
                generateCarsForCompanies(rentCarCompanyRepository.findAll());
            }
            return;
        }
        log.info("렌트카 공공데이터 초기 로딩 시작...");
        fetchAndSaveAll();
    }

    private static final Set<String> EXCEPTION_REGIONS = Set.of("충청북도", "충청남도", "전라북도", "전라남도", "경상북도", "경상남도");

    private String normalizeRegion(String region) { //주소를 충청북도 이런거는 충북 이렇게 줄이고 부산광역시는 부산으로 줄여서 저장
        if (region == null || region.length() < 2) return region;
        if (EXCEPTION_REGIONS.contains(region)) return "" + region.charAt(0) + region.charAt(2);
        return region.substring(0, 2);
    }

    private void fetchAndSaveAll() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(30000);
        RestTemplate restTemplate = new RestTemplate(factory);
        Gson gson = new Gson();

        // 전체 데이터를 지역별로 모아둔 뒤 10분의 1만 저장
        Map<String, List<RentCarCompany>> regionMap = new HashMap<>();

        int pageNo = 1;
        int totalCount = 0;

        do {
            try {
                URI uri = UriComponentsBuilder.fromHttpUrl(API_URL)
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("pageNo", pageNo)
                        .queryParam("numOfRows", NUM_OF_ROWS)
                        .queryParam("type", "json")
                        .build(true)
                        .toUri();

                log.info("API 호출: pageNo={}", pageNo);
                String response = restTemplate.getForObject(uri, String.class);

                JsonObject root = JsonParser.parseString(response).getAsJsonObject();
                JsonObject body = root.getAsJsonObject("response").getAsJsonObject("body");

                totalCount = body.get("totalCount").getAsInt();

                // items가 배열일 수도 있고 {"item": [...]} 객체일 수도 있음
                JsonArray items = null;
                var itemsEl = body.get("items");
                if (itemsEl != null && !itemsEl.isJsonNull()) {
                    if (itemsEl.isJsonArray()) {
                        items = itemsEl.getAsJsonArray();
                    } else if (itemsEl.isJsonObject()) {
                        items = itemsEl.getAsJsonObject().getAsJsonArray("item");
                    }
                }
                if (items == null || items.isEmpty()) break;

                for (int i = 0; i < items.size(); i++) {
                    JsonObject item = items.get(i).getAsJsonObject();
                    CarCompanyApiDTO dto = gson.fromJson(item, CarCompanyApiDTO.class);

                    String name = dto.getEntrpsNm() != null ? dto.getEntrpsNm() : "";
                    String rdnmadr = dto.getRdnmadr() != null ? dto.getRdnmadr() : "";
                    String lnmadr = dto.getLnmadr() != null ? dto.getLnmadr() : "";
                    String address = !rdnmadr.isBlank() ? rdnmadr : lnmadr;

                    // ctprvnNm 없으면 주소 첫 단어에서 지역 추출 (플러터와 동일 방식)
                    String rawRegion = dto.getCtprvnNm();
                    if (rawRegion == null || rawRegion.isBlank()) {
                        rawRegion = address.isBlank() ? null : address.split(" ")[0];
                    }
                    String region = normalizeRegion(rawRegion);
                    if (region == null) continue;

                    regionMap.computeIfAbsent(region, k -> new ArrayList<>())
                            .add(RentCarCompany.builder()
                                    .name(name)
                                    .region(region)
                                    .address(address)
                                    .latitude(dto.getLatitude())
                                    .longitude(dto.getLongitude())
                                    .phone(dto.getPhoneNumber())
                                    .build());
                }

                log.info("pageNo={} 파싱 완료", pageNo);

                pageNo++;

            } catch (Exception e) {
                log.error("공공데이터 API 호출 실패: {}", e.getMessage());
                break;
            }

        } while ((long) (pageNo - 1) * NUM_OF_ROWS < totalCount);

        // 지역별 10분의 1만 저장 (주소 중복 제거)
        int savedCount = 0;
        for (Map.Entry<String, List<RentCarCompany>> entry : regionMap.entrySet()) {
            List<RentCarCompany> all = entry.getValue();
            int keepCount = all.size() / 10;
            if (keepCount == 0) continue;

            // 주소 중복 제거하면서 keepCount만큼 추출
            Set<String> seenAddresses = new java.util.HashSet<>();
            List<RentCarCompany> toSave = new ArrayList<>();
            for (RentCarCompany company : all) {
                if (toSave.size() >= keepCount) break;
                String addr = company.getAddress();
                if (addr == null || addr.isBlank()) continue;
                if (seenAddresses.add(addr)) { // 중복이면 add가 false 반환
                    toSave.add(company);
                }
            }

            List<RentCarCompany> saved = rentCarCompanyRepository.saveAll(toSave);
            savedCount += saved.size();
            generateCarsForCompanies(saved);
            log.info("지역={} 저장: {}건 (전체 {}건 중)", entry.getKey(), saved.size(), all.size());
        }

        log.info("렌트카 공공데이터 로딩 완료. 총 저장: {}건", savedCount);
    }

    private void generateCarsForCompanies(List<RentCarCompany> companies) {
        Random random = new Random();
        List<Car> cars = new ArrayList<>();

        for (RentCarCompany company : companies) {
            int carCount = 1 + random.nextInt(5); // 1~5대

            List<CarSpec> shuffled = new ArrayList<>(CAR_SPECS);
            Collections.shuffle(shuffled, random);

            for (int i = 0; i < carCount; i++) {
                CarSpec spec = shuffled.get(i % shuffled.size());
                int rawPrice = spec.priceMin() + random.nextInt(spec.priceMax() - spec.priceMin() + 1);
                int price = (rawPrice / 1000) * 1000; // 1000원 단위로 절삭

                cars.add(Car.builder()
                        .company(company)
                        .name(spec.name())
                        .type(spec.type())
                        .seats(spec.seats())
                        .fuel(spec.fuel())
                        .dailyPrice(price)
                        .year(2018 + random.nextInt(7)) // 2018~2024
                        .build());
            }
        }

        carRepository.saveAll(cars);
        log.info("차량 {}대 생성 완료", cars.size());
    }
}