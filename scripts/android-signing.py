#!/usr/bin/env python3
"""Create a private release identity once, or build/verify an APK with that identity."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init', 'build'])
    parser.add_argument('--directory', type=Path, default=Path.home()/'.config/teslatempo/android-signing')
    args = parser.parse_args()
    directory = args.directory.expanduser().resolve()
    if args.action == 'init':
        # Never overwrite an identity: future updates depend on exactly this key.
        directory.mkdir(mode=0o700, parents=True, exist_ok=False)
        os.chmod(directory, 0o700)
        password = secrets.token_urlsafe(48)
        password_file = directory/'password'
        fd = os.open(password_file, os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600)
        with os.fdopen(fd,'w') as f: f.write(password)
        keytool = str(Path(os.environ['JAVA_HOME'])/'bin/keytool') if os.environ.get('JAVA_HOME') else 'keytool'
        subprocess.run([keytool,'-genkeypair','-keystore',str(directory/'release.p12'),'-storetype','PKCS12',
            '-storepass:file',str(password_file),'-keypass:file',str(password_file),'-alias','teslatempo',
            '-keyalg','RSA','-keysize','3072','-validity','10950','-dname','CN=TeslaTempo','-noprompt'],check=True)
        os.chmod(directory/'release.p12',0o600)
        config = {'storeFile':str(directory/'release.p12'), 'storePassword':password, 'keyAlias':'teslatempo','keyPassword':password}
        fd=os.open(directory/'signing.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w') as f: json.dump(config,f)
        password_file.unlink()
        print('Identité créée. Sauvegardez ce dossier privé hors du poste avant toute distribution.')
    else:
        config = directory/'signing.json'
        if not config.is_file() or config.stat().st_mode & 0o077: raise SystemExit('Configuration absente ou permissions trop ouvertes (600 requis).')
        env = dict(os.environ, TESLATEMPO_SIGNING_FILE=str(config))
        subprocess.run(['./gradlew',':app:assembleRelease',':app:lintRelease','--console=plain'],cwd=ROOT/'apps/android',env=env,check=True)
        sdk = Path(os.environ.get('ANDROID_HOME',Path.home()/'Android/Sdk'))
        signer = sdk/'build-tools/36.0.0/apksigner'
        apk = ROOT/'apps/android/app/build/outputs/apk/release/app-release.apk'
        result = subprocess.run([str(signer),'verify','--verbose','--print-certs',str(apk)],capture_output=True,text=True,check=True)
        output = ROOT/'apps/android/app/build/distribution';output.mkdir(exist_ok=True)
        target=output/'teslatempo-release.apk';shutil.copyfile(apk,target)
        (output/'SHA256SUMS').write_text(hashlib.sha256(target.read_bytes()).hexdigest()+'  '+target.name+'\n')
        # Print only the public certificate digest, not environment paths/identity secrets.
        certificate = [line for line in result.stdout.splitlines() if 'certificate SHA-256 digest:' in line]
        if len(certificate)!=1: raise SystemExit('Signature inattendue : ne pas distribuer.')
        (output/'SIGNATURE.txt').write_text(certificate[0]+'\n')
        print('APK signé et vérifié :',target)
        print(certificate[0])


if __name__ == '__main__': main()
