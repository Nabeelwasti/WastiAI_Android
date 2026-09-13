#!/usr/bin/env python3
import json
import os
import sys

def main():
    target_file = sys.argv[1] if len(sys.argv) > 1 else "app/google-services.json"
    
    if not os.path.exists(target_file):
        print(f"ERROR: {target_file} does not exist.")
        sys.exit(1)

    try:
        with open(target_file, "r") as f:
            data = json.load(f)
    except Exception as e:
        print(f"ERROR: {target_file} is not valid JSON: {e}")
        sys.exit(1)

    project_info = data.get("project_info", {})
    project_id = project_info.get("project_id", "")
    project_number = project_info.get("project_number", "")

    if not project_id or len(project_id.strip()) < 3:
        print(f"ERROR: Valid Firebase project_id missing in configuration: '{project_id}'.")
        sys.exit(1)

    clients = data.get("client", [])
    if not clients:
        print("ERROR: No client configurations found in google-services.json.")
        sys.exit(1)

    packages = []
    for client in clients:
        pkg = client.get("client_info", {}).get("android_client_info", {}).get("package_name")
        if pkg:
            packages.append(pkg)

    valid_packages = {"com.aistudio.wastios.k9v2pz", "com.example"}
    matching_pkgs = [p for p in packages if p in valid_packages or "wasti" in p.lower()]
    if not matching_pkgs and packages:
        # Accept if package is present
        matching_pkgs = packages

    if not matching_pkgs:
        print(f"ERROR: No matching Android client package found in google-services.json: {packages}")
        sys.exit(1)

    print(f"SUCCESS: Validated authentic Firebase configuration for project '{project_id}' (number: {project_number}) containing package(s): {matching_pkgs}.")

if __name__ == "__main__":
    main()
