import importlib.util
from pathlib import Path
import pytest
from cryptography import x509
from cryptography.hazmat.primitives import serialization

script=Path(__file__).resolve().parents[3]/'scripts/init-command-proxy.py'
spec=importlib.util.spec_from_file_location('proxy_tls',script)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


def test_tls_generation_is_private_matches_certificate_and_refuses_replacement(tmp_path):
    module.generate(tmp_path)
    key_file=tmp_path/'proxy_tls_key.pem';cert_file=tmp_path/'proxy_tls_cert.pem'
    before=key_file.read_bytes()
    key=serialization.load_pem_private_key(before,password=None)
    cert=x509.load_pem_x509_certificate(cert_file.read_bytes())
    assert key.public_key().public_numbers()==cert.public_key().public_numbers()
    assert cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.DNSName)==['localhost']
    assert key_file.stat().st_mode & 0o777==0o600 and cert_file.stat().st_mode & 0o777==0o600
    with pytest.raises(ValueError):module.generate(tmp_path)
    assert key_file.read_bytes()==before
