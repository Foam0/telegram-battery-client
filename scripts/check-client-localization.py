#!/usr/bin/env python3
"""Check Battery Client strings in the binary assets used by LocaleController."""
import argparse
import struct
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


def java_hash(text):
    value = 0
    for byte_pair in struct.iter_unpack('>H', text.encode('utf-16-be')):
        value = (31 * value + byte_pair[0]) & 0xffffffff
    return value


def strings(data):
    count, = struct.unpack_from('<I', data)
    offset = 4
    result = {}
    for _ in range(count):
        key, = struct.unpack_from('<I', data, offset)
        offset += 4
        length = data[offset]
        header = 1
        if length == 254:
            length = int.from_bytes(data[offset + 1:offset + 4], 'little')
            header = 4
        offset += header
        result[key] = data[offset:offset + length].decode('utf-8')
        offset += length + (-(header + length) % 4)
    if offset != len(data):
        raise ValueError('Invalid localization asset length')
    return result


def expected(path):
    return {element.attrib['name']: ''.join(element.itertext()).replace('\\n', '\n').replace('\\', '').replace('&lt;', '<')
            for element in ET.parse(path).getroot().findall('string')}


def verify(read_asset):
    root = Path(__file__).resolve().parent.parent
    default = expected(root / 'TMessagesProj/src/main/res/values/battery_client_strings.xml')
    russian = expected(root / 'TMessagesProj/src/main/res/values-ru/battery_client_strings.xml')
    if default.keys() != russian.keys():
        raise ValueError('Russian client translations do not cover the default namespace')
    for language, values in [('en', default), ('ru', russian)]:
        actual = strings(read_asset(f'localization_{language}.bin'))
        missing = [key for key, value in values.items() if actual.get(java_hash(key)) != value]
        if missing:
            raise ValueError(f'{language} binary localization missing or mismatched: {", ".join(missing)}')
    bindings = read_asset('string_resource_ids.bin')
    count, = struct.unpack_from('<I', bindings)
    if len(bindings) != 4 + count * 8:
        raise ValueError('Invalid string resource bindings length')
    bound_hashes = {name_hash for _, name_hash in struct.iter_unpack('<II', bindings[4:])}
    missing = [key for key in default if java_hash(key) not in bound_hashes]
    if missing:
        raise ValueError(f'Client resource ID bindings missing: {", ".join(missing)}')
    print(f'client_localization=ok ({len(default)} keys, English/Russian binary values and ID bindings)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact', type=Path, help='APK or directory with generated binary assets')
    args = parser.parse_args()
    if args.artifact.is_dir():
        verify(lambda name: (args.artifact / name).read_bytes())
    else:
        with zipfile.ZipFile(args.artifact) as apk:
            verify(lambda name: apk.read('assets/' + name))


if __name__ == '__main__':
    main()
