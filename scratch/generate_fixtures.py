import os

crosswalk_csv_text = """횡단보도관리번호,횡단보도종류,소재지도로명주소,위도,경도,차로수,보행자신호등유무,음향신호기설치여부,점자블록유무,보도턱낮춤여부,데이터기준일자
CW-GJ-001,일반형,광주광역시 서구 상무대로 100,35.1500,126.8500,4,Y,Y,Y,Y,2026-06-30
CW-GJ-002,일반형,광주광역시 서구 시청로 20,35.1505,126.8505,2,Y,N,Y,N,2026-06-30
CW-GJ-003,대각선,광주광역시 서구 치평로 30,35.1520,126.8520,4,Y,,Y,,2026-06-30
CW-GJ-004,일반형,광주광역시 서구 상무중앙로 40,35.1530,126.8530,4,Y,UNKNOWN,Y,Y,2026-06-30
CW-GJ-005,일반형,광주광역시 서구 내방로 50,126.8500,35.1500,2,Y,Y,Y,Y,2026-06-30
CW-GJ-006,일반형,광주광역시 서구 금화로 60,42.5000,127.0000,4,Y,Y,Y,Y,2026-06-30
CW-GJ-001,일반형,광주광역시 서구 상무대로 100,35.1500,126.8500,4,Y,Y,Y,Y,2026-06-30
"""

traffic_light_csv_text = """신호등관리번호,신호등구분,소재지도로명주소,위도,경도,보행자작동신호기유무,잔여시간표시기유무,시각장애인용음향신호기유무,데이터기준일자
TL-GJ-101,보행등,광주광역시 서구 상무대로 100,35.1501,126.8501,N,Y,Y,2026-06-30
TL-GJ-102,보행등,광주광역시 서구 시청로 20,35.1506,126.8506,N,N,N,2026-06-30
TL-GJ-103,보행등,광주광역시 서구 치평로 30,35.1521,126.8521,N,Y,,2026-06-30
TL-GJ-104,보행등,광주광역시 서구 상무로 50,30.5000,126.8500,N,N,Y,2026-06-30
TL-GJ-105,차량등,광주광역시 서구 화정로 60,35.1540,126.8540,N,N,N,NOT-A-DATE
"""

os.makedirs("data-pipeline/tests/fixtures", exist_ok=True)

with open("data-pipeline/tests/fixtures/crosswalk_sample_utf8sig.csv", "w", encoding="utf-8-sig", newline="") as f:
    f.write(crosswalk_csv_text.strip() + "\n")

with open("data-pipeline/tests/fixtures/traffic_light_sample_cp949.csv", "w", encoding="cp949", newline="") as f:
    f.write(traffic_light_csv_text.strip() + "\n")

print("Fixtures created successfully")
