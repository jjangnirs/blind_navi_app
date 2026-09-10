def detect_encoding(file_path: str) -> str:
    """
    파일의 인코딩을 안전하게 감지합니다.
    UTF-8-SIG (BOM), UTF-8, CP949 (EUC-KR) 순서로 검사합니다.
    """
    with open(file_path, "rb") as f:
        raw_prefix = f.read(4)

    # UTF-8 BOM check (0xEF, 0xBB, 0xBF)
    if raw_prefix.startswith(b"\xef\xbb\xbf"):
        return "utf-8-sig"

    # Try full read with UTF-8
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            f.read()
        return "utf-8"
    except UnicodeDecodeError:
        pass

    # Try CP949
    try:
        with open(file_path, "r", encoding="cp949") as f:
            f.read()
        return "cp949"
    except UnicodeDecodeError as e:
        raise ValueError(
            f"지원하지 않는 파일 인코딩입니다 (UTF-8 또는 CP949 필요): {e}"
        ) from e
