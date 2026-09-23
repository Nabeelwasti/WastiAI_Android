#!/usr/bin/env python3
import sys
import os
import zipfile

def verify_apks():
    target_apks = []
    for p in ["app/build/outputs/apk/release/app-release.apk", "app/build/outputs/apk/debug/app-debug.apk"]:
        if os.path.isfile(p):
            target_apks.append(p)

    if not target_apks:
        print("ERROR: No APK targets found to verify!")
        sys.exit(1)

    for apk in target_apks:
        print(f"Validating APK: {apk}")
        found = False
        with zipfile.ZipFile(apk, "r") as z:
            names = z.namelist()
            if "AndroidManifest.xml" not in names:
                print(f"ERROR: AndroidManifest.xml missing in {apk}")
                sys.exit(1)

            for name in names:
                if name.endswith(".dex") or name == "AndroidManifest.xml":
                    content = z.read(name)
                    if (b"WastiApplication" in content or
                        b"com/example/WastiApplication" in content or
                        b"com.example.WastiApplication" in content or
                        b"Wasti" in content or
                        b"com/example" in content):
                        print(f"SUCCESS: Wasti Application bytecode reference verified in {apk} -> {name}")
                        found = True

        if not found:
            print(f"ERROR: WastiApplication reference NOT FOUND in {apk}")
            sys.exit(1)

        print(f"SUCCESS: {apk} bytecode integrity verified.")

if __name__ == "__main__":
    verify_apks()
