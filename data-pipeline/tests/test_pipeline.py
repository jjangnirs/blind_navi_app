from safecross_pipeline import __version__, get_pipeline_version


def test_pipeline_package_import_and_version():
    assert get_pipeline_version() == "0.1.0"
    assert __version__ == "0.1.0"
