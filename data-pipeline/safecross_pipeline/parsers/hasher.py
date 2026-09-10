import hashlib


def compute_file_sha256(file_path: str) -> str:
    """파일의 SHA-256 해시를 스트리밍 방식으로 계산하여 반환합니다."""
    sha256_hash = hashlib.sha256()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            sha256_hash.update(chunk)
    return sha256_hash.hexdigest()
