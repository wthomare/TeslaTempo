#!/usr/bin/env python3
"""Create separate localhost TLS material; never replace an existing key or contact Tesla."""
import argparse
import datetime
import ipaddress
import os
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID


def generate(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    key_path, cert_path = directory/'proxy_tls_key.pem', directory/'proxy_tls_cert.pem'
    if key_path.exists() or cert_path.exists():
        raise ValueError('TLS files already exist; no replacement performed')
    key = ec.generate_private_key(ec.SECP256R1())
    now = datetime.datetime.now(datetime.timezone.utc)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'TeslaTempo local command proxy')])
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
        .public_key(key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now-datetime.timedelta(minutes=5)).not_valid_after(now+datetime.timedelta(days=365))
        .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost'),x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]),critical=False)
        .add_extension(x509.BasicConstraints(ca=False,path_length=None),critical=True)
        .sign(key,hashes.SHA256()))
    material=[(key_path,key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())),
              (cert_path,cert.public_bytes(serialization.Encoding.PEM))]
    # Exclusive writes; on failure remove only the files created by this invocation.
    created=[]
    try:
        for path, raw in material:
            fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600); created.append(path)
            with os.fdopen(fd,'wb') as f: f.write(raw)
    except Exception:
        for path in created:path.unlink(missing_ok=True)
        raise


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory',type=Path,default=Path('secrets'))
    args=parser.parse_args()
    try:generate(args.directory)
    except (OSError,ValueError):raise SystemExit('TLS material not created: existing files or inaccessible directory.') from None
    print('Dedicated local TLS files created with private permissions. No Tesla signing key modified.')
