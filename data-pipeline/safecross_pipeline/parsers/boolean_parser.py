class InvalidBooleanError(ValueError):
    """엄격한 삼항 논리(True, False, None)로 해석할 수 없는 알 수 없는 값인 경우 발생합니다."""


TRUE_VALUES = {
    "Y",
    "YES",
    "1",
    "T",
    "TRUE",
    "유",
    "O",
    "설치",
    "있음",
    "동작",
}
FALSE_VALUES = {
    "N",
    "NO",
    "0",
    "F",
    "FALSE",
    "무",
    "X",
    "미설치",
    "없음",
    "미동작",
}
NULL_VALUES = {
    "",
    "NULL",
    "NONE",
    "-",
    ".",
    "미기재",
    "미상",
    "해당없음",
}


def parse_tristate_boolean(val: str | None) -> bool | None:
    """
    공공데이터의 Y/N/한글 표기/공란을 엄격한 삼항 논리(True, False, None)로 변환합니다.
    - True: Y, YES, 1, T, TRUE, 유, O, 설치, 있음, 동작
    - False: N, NO, 0, F, FALSE, 무, X, 미설치, 없음, 미동작
    - None: 빈 문자열, null, None, -, ., 미기재, 미상, 해당없음 (공공데이터 미기재)
    - 그 외: InvalidBooleanError 발생 (격리 대상)
    """
    if val is None:
        return None

    cleaned = str(val).strip().upper()
    if cleaned in NULL_VALUES:
        return None
    if cleaned in TRUE_VALUES:
        return True
    if cleaned in FALSE_VALUES:
        return False

    raise InvalidBooleanError(
        f"알 수 없는 불리언 플래그 값: '{val}' (True/False/Null 해석 불가)"
    )
