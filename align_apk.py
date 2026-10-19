#!/usr/bin/env python3
"""Align ZIP_STORED entries in a Java-only Android APK before signing.

This provides 4-byte ZIP entry alignment without depending on a native
zipalign executable. The project contains no native .so libraries.
"""
import os
import struct
import sys
import zipfile


def align_apk(source, destination):
    if os.path.abspath(source) == os.path.abspath(destination):
        raise ValueError('Source and output paths must differ')

    with zipfile.ZipFile(source, 'r') as original, zipfile.ZipFile(destination, 'w') as aligned:
        aligned.comment = original.comment
        for entry in original.infolist():
            info = zipfile.ZipInfo(entry.filename, entry.date_time)
            info.compress_type = entry.compress_type
            info.external_attr = entry.external_attr
            info.internal_attr = entry.internal_attr
            info.create_system = entry.create_system
            info.comment = entry.comment
            info.extra = entry.extra
            if info.compress_type == zipfile.ZIP_STORED:
                # All paths in this app are ASCII; UTF-8 is identical for them.
                name_bytes = info.filename.encode('utf-8')
                position = aligned.fp.tell()
                distance = (-(position + 30 + len(name_bytes) + len(info.extra))) % 4
                if distance:
                    size = distance + 4  # Reserve valid 4-byte ZIP extra-field header.
                    info.extra += struct.pack('<HH', 0xD935, size - 4) + bytes(size - 4)
            aligned.writestr(info, original.read(entry.filename), compress_type=entry.compress_type)

    with zipfile.ZipFile(destination, 'r') as checked:
        if checked.testzip() is not None:
            raise RuntimeError('ZIP CRC verification failed')
        for info in checked.infolist():
            if info.compress_type == zipfile.ZIP_STORED:
                with open(destination, 'rb') as src:
                    src.seek(info.header_offset + 26)
                    name_len, extra_len = struct.unpack('<HH', src.read(4))
                data_offset = info.header_offset + 30 + name_len + extra_len
                if data_offset % 4:
                    raise RuntimeError(f'Unaligned entry: {info.filename}')
    print('APK aligned: all uncompressed ZIP entries are 4-byte aligned.')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit('Usage: python align_apk.py INPUT.apk OUTPUT.apk')
    align_apk(sys.argv[1], sys.argv[2])
