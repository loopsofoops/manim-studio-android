#!/usr/bin/env python3
import base64
import os
import subprocess

raw = os.environ.get("KEYSTORE_BASE64", "").strip().replace("\n", "").replace("\r", "").replace(" ", "")
env_file = os.environ["GITHUB_ENV"]

if raw:
    missing_padding = len(raw) % 4
    if missing_padding:
        raw += "=" * (4 - missing_padding)
    with open("app/release-key.jks", "wb") as f:
        f.write(base64.b64decode(raw))
    print("Decoded release keystore from KEYSTORE_BASE64 secret.")
else:
    print("Generating dedicated PKCS12 signing key for release APK.")
    subprocess.run([
        "keytool", "-genkeypair", "-v",
        "-keystore", "app/release-key.jks",
        "-storetype", "PKCS12",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-alias", "atelier",
        "-storepass", "atelier_release_key",
        "-keypass", "atelier_release_key",
        "-dname", "CN=Atelier, O=Atelier Studio, C=US",
    ], check=True)
    with open(env_file, "a", encoding="utf-8") as f:
        f.write("KEYSTORE_PASSWORD=atelier_release_key\n")
        f.write("KEY_ALIAS=atelier\n")
        f.write("KEY_PASSWORD=atelier_release_key\n")
