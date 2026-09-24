#!/usr/bin/env python3
import sys
import os
import zipfile
import struct

def parse_axml(data):
    """
    Parses Android Binary XML (AXML) to extract the manifest package name
    and the application android:name class.
    Robust StringPool UTF-8 & UTF-16 decoder.
    """
    if len(data) < 8:
        raise ValueError("AXML data too short")
    
    magic, size = struct.unpack("<II", data[:8])
    if magic != 0x00080003:
        raise ValueError(f"Invalid AXML magic: {hex(magic)}")
    
    pos = 8
    strings = []
    
    # 1. Parse String Pool
    while pos < len(data):
        if pos + 8 > len(data):
            break
        chunk_type, header_size, chunk_size = struct.unpack("<HHI", data[pos:pos+8])
        if chunk_size < 8:
            break
            
        if chunk_type == 0x0001: # String Pool Chunk
            str_count, style_count, flags, str_start, styles_start = struct.unpack("<IIIII", data[pos+8:pos+28])
            offsets = struct.unpack(f"<{str_count}I", data[pos+28:pos+28+str_count*4])
            is_utf8 = bool(flags & 0x0100)
            base = pos + str_start
            
            for off in offsets:
                p = base + off
                if p >= len(data):
                    strings.append("")
                    continue
                if is_utf8:
                    # UTF-8 length decoding (ULEB128 for char count and byte count)
                    if p < len(data) and (data[p] & 0x80): p += 2
                    else: p += 1
                    if p < len(data) and (data[p] & 0x80):
                        if p + 1 < len(data):
                            length = ((data[p] & 0x7f) << 8) | data[p+1]
                            p += 2
                        else:
                            length = 0
                    else:
                        length = data[p] if p < len(data) else 0
                        p += 1
                    s = data[p:p+length].decode("utf-8", errors="replace") if p + length <= len(data) else ""
                else:
                    if p + 2 <= len(data):
                        length = struct.unpack("<H", data[p:p+2])[0]
                        p += 2
                        s = data[p:p+length*2].decode("utf-16le", errors="replace") if p + length*2 <= len(data) else ""
                    else:
                        s = ""
                strings.append(s)
            break
        pos += chunk_size

    # 2. Parse XML Element Nodes for <manifest package="..."> and <application android:name="...">
    package_name = ""
    app_class_name = ""
    
    pos = 8
    while pos < len(data):
        if pos + 8 > len(data):
            break
        chunk_type, header_size, chunk_size = struct.unpack("<HHI", data[pos:pos+8])
        if chunk_size < 8:
            break
            
        if chunk_type == 0x0102: # START_TAG Chunk
            if pos + 30 <= len(data):
                ns_idx, name_idx = struct.unpack("<II", data[pos+16:pos+24])
                attr_count = struct.unpack("<H", data[pos+28:pos+30])[0]
                tag_name = strings[name_idx] if name_idx < len(strings) else ""
                
                attrs_base = pos + 36
                for i in range(attr_count):
                    ab = attrs_base + i * 20
                    if ab + 20 <= len(data):
                        a_ns, a_name, a_val_str, a_type, a_data = struct.unpack("<IIIII", data[ab:ab+20])
                        attr_name = strings[a_name] if a_name < len(strings) else ""
                        val_str = strings[a_val_str] if (a_val_str < len(strings) and a_val_str != 0xffffffff) else ""
                        
                        if tag_name == "manifest" and attr_name == "package":
                            package_name = val_str
                        elif tag_name == "application" and attr_name == "name":
                            app_class_name = val_str
        pos += chunk_size

    return package_name, app_class_name


def parse_uleb128(data, offset):
    """Parses an unsigned LEB128 integer from data at offset, returning (value, bytes_read)."""
    result = 0
    shift = 0
    count = 0
    while True:
        if offset + count >= len(data):
            break
        byte = data[offset + count]
        count += 1
        result |= (byte & 0x7f) << shift
        if (byte & 0x80) == 0:
            break
        shift += 7
    return result, count


def is_class_defined_in_dex(dex_bytes, target_descriptor):
    """
    Parses DEX structure (header, string_ids, type_ids, class_defs)
    and verifies whether target_descriptor exists as an actual class definition.
    """
    if len(dex_bytes) < 112:
        return False
    
    magic = dex_bytes[:8]
    if not (magic.startswith(b"dex\n") and magic.endswith(b"\x00")):
        return False

    header = struct.unpack("<8sI20sIIIIIIIIIIIIIIIIIIII", dex_bytes[:112])
    string_ids_size, string_ids_off = header[9], header[10]
    type_ids_size, type_ids_off = header[11], header[12]
    class_defs_size, class_defs_off = header[19], header[20]

    # 1. Find string index for target_descriptor
    target_str_idx = None
    target_bytes = target_descriptor.encode("utf-8")
    for i in range(string_ids_size):
        off_ptr = string_ids_off + i * 4
        if off_ptr + 4 > len(dex_bytes):
            break
        str_off = struct.unpack("<I", dex_bytes[off_ptr:off_ptr+4])[0]
        if str_off >= len(dex_bytes):
            continue
        _, uleb_len = parse_uleb128(dex_bytes, str_off)
        str_start = str_off + uleb_len
        if str_start + len(target_bytes) <= len(dex_bytes):
            candidate = dex_bytes[str_start:str_start + len(target_bytes)]
            if candidate == target_bytes and (str_start + len(target_bytes) >= len(dex_bytes) or dex_bytes[str_start + len(target_bytes)] == 0):
                target_str_idx = i
                break

    if target_str_idx is None:
        return False

    # 2. Find type index for target_str_idx
    target_type_idx = None
    for i in range(type_ids_size):
        off_ptr = type_ids_off + i * 4
        if off_ptr + 4 > len(dex_bytes):
            break
        descriptor_idx = struct.unpack("<I", dex_bytes[off_ptr:off_ptr+4])[0]
        if descriptor_idx == target_str_idx:
            target_type_idx = i
            break

    if target_type_idx is None:
        return False

    # 3. Check class_defs for target_type_idx
    for i in range(class_defs_size):
        off_ptr = class_defs_off + i * 32
        if off_ptr + 32 > len(dex_bytes):
            break
        class_idx = struct.unpack("<I", dex_bytes[off_ptr:off_ptr+4])[0]
        if class_idx == target_type_idx:
            return True

    return False


def verify_apks():
    target_apks = []
    for p in ["app/build/outputs/apk/release/app-release.apk", "app/build/outputs/apk/debug/app-debug.apk"]:
        if os.path.isfile(p):
            target_apks.append(p)

    if not target_apks:
        print("ERROR: No APK targets found to verify!")
        sys.exit(1)

    for apk in target_apks:
        print(f"Validating APK bytecode and manifest integrity: {apk}")
        with zipfile.ZipFile(apk, "r") as z:
            names = z.namelist()
            if "AndroidManifest.xml" not in names:
                print(f"ERROR: AndroidManifest.xml missing in {apk}")
                sys.exit(1)

            axml_data = z.read("AndroidManifest.xml")
            try:
                pkg_name, app_cls = parse_axml(axml_data)
            except Exception as e:
                print(f"ERROR: Failed to parse AndroidManifest.xml in {apk}: {e}")
                sys.exit(1)

            if not pkg_name:
                print(f"ERROR: Manifest package name empty in {apk}")
                sys.exit(1)

            if not app_cls:
                print(f"ERROR: Manifest application class android:name empty in {apk}")
                sys.exit(1)

            # Resolve fully-qualified application class name
            if app_cls.startswith("."):
                full_app_class = pkg_name + app_cls
            elif "." in app_cls:
                full_app_class = app_cls
            else:
                full_app_class = pkg_name + "." + app_cls

            slash_class = full_app_class.replace(".", "/")
            dex_descriptor = f"L{slash_class};"

            print(f"Resolved Application Class: '{full_app_class}'")
            print(f"Target DEX descriptor: {dex_descriptor}")

            found_dex = None
            for name in names:
                if name.endswith(".dex"):
                    dex_content = z.read(name)
                    if is_class_defined_in_dex(dex_content, dex_descriptor):
                        found_dex = name
                        print(f"SUCCESS: Application class '{full_app_class}' verified in {apk} -> {name} class_defs")
                        break

            if not found_dex:
                print(f"ERROR: Application class '{full_app_class}' (descriptor '{dex_descriptor}') NOT DEFINED in class_defs of any DEX file in {apk}")
                sys.exit(1)

            # Strict Package Integrity Verification
            if not pkg_name.startswith("com."):
                print(f"ERROR: Suspicious manifest package identifier '{pkg_name}' in {apk}")
                sys.exit(1)

            print(f"SUCCESS: {apk} manifest and bytecode class_def integrity verified fail-closed.")

if __name__ == "__main__":
    verify_apks()

