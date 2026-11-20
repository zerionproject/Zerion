#!/usr/bin/env python3
"""Regenerates the KeePass test databases used by the KDBX reader tests.

Written against pykeepass 4.2.0 (pip install pykeepass==4.2.0) in a throwaway
virtual environment. pykeepass is an independent KDBX implementation, so the
fixtures do not depend on the reader under test. Every database carries the
same content and the master password "correct horse"; the KDF, cipher,
compression and key file differ per file. Seeds and IVs are random, so the
bytes differ on every run while the content stays the same.

Usage: python generate_fixtures.py [output-directory]
"""
import base64
import hashlib
import os
import sys
import tempfile

from construct import Container
from lxml.etree import Element
from pykeepass import PyKeePass, create_database
from pykeepass.kdbx_parsing.kdbx4 import kdf_uuids

PASSWORD = "correct horse"
ARGON2_MEMORY = 1024 * 1024
ARGON2_ITERATIONS = 2
ARGON2_PARALLELISM = 2
AES_ROUNDS = 6000

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))


def fill(kp):
    """Adds the shared content. Expected import: 9 passwords, 6 skipped."""
    root = kp.root_group
    kp.add_entry(root, "Email", "alice", "pw-email-1",
                 url="https://mail.example.org", notes="Main mailbox")
    work = kp.add_group(root, "Work")
    kp.add_entry(work, "Jira", "bob", "pw-jira", url="https://jira.example.com/")
    servers = kp.add_group(work, "Servers")
    db = kp.add_entry(servers, "db01", "root", "pw-db01", notes="Postgres")
    db.set_custom_property("Port", "5432")
    db.set_custom_property("API Key", "secret-api-key", protect=True)
    db.otp = "otpauth://totp/db01?secret=JBSWY3DPEHPK3PXP"
    bank = kp.add_entry(root, "Bank", "alice", "old-bank-pw-1",
                        url="https://bank.example.org")
    bank.save_history()
    bank.password = "old-bank-pw-2"
    bank.save_history()
    bank.password = "pw-bank-3"
    kp.add_entry(root, "After History", "carol", "pw-after")
    deleted = kp.add_entry(root, "Deleted thing", "zed", "pw-deleted")
    kp.trash_entry(deleted)
    kp._xpath('/KeePassFile/Meta/RecycleBinEnabled', first=True).text = "True"
    only_file = kp.add_entry(root, "", "", "", force_creation=True)
    only_file.add_attachment(kp.add_binary(b"just a note"), "note.txt")
    with_file = kp.add_entry(root, "With file", "frank", "pw-file")
    with_file.add_attachment(kp.add_binary(b"\x00\x01\x02binary"), "a.bin")
    kp.add_entry(root, "", "dave", "pw-notitle",
                 url="https://login.example.net/path?x=1", force_creation=True)
    kp.add_entry(root, "", "eve", "pw-untitled", force_creation=True)
    kp.add_entry(work, "Dup", "dup", "pw-dup", url="https://dup.example.com",
                 notes="same")
    kp.add_entry(work, "Dup", "dup", "pw-dup", url="https://dup.example.com",
                 notes="same", force_creation=True)
    kp.add_entry(root, "", "", "", force_creation=True)


def kdf_item(type_id, key, value, last):
    return Container(type=type_id, key=key, value=value,
                     next_byte=0 if last else 1)


def set_argon2(kp, uuid_name):
    d = kp.kdbx.header.value.dynamic_header.kdf_parameters.data.dict
    d["$UUID"].value = kdf_uuids[uuid_name]
    d["M"].value = ARGON2_MEMORY
    d["I"].value = ARGON2_ITERATIONS
    d["P"].value = ARGON2_PARALLELISM


def set_aeskdf(kp):
    d = kp.kdbx.header.value.dynamic_header.kdf_parameters.data.dict
    for key in list(d.keys()):
        del d[key]
    d["$UUID"] = kdf_item(0x42, "$UUID", kdf_uuids["aeskdf"], False)
    d["S"] = kdf_item(0x42, "S", os.urandom(32), False)
    d["R"] = kdf_item(0x05, "R", AES_ROUNDS, True)


def set_cipher(kp, name):
    kp.kdbx.header.value.dynamic_header.cipher_id.data = name


def set_compression(kp, enabled):
    kp.kdbx.header.value.dynamic_header.compression_flags.data.compression = enabled


def make_version3(kp, cipher="aes256"):
    header = kp.kdbx.header.value
    header.major_version = 3
    header.minor_version = 1
    old = header.dynamic_header
    dynamic = Container()
    dynamic["cipher_id"] = Container(id="cipher_id", data=cipher)
    dynamic["compression_flags"] = Container(id="compression_flags",
                                             data=old.compression_flags.data)
    dynamic["master_seed"] = Container(id="master_seed", data=os.urandom(32))
    dynamic["transform_seed"] = Container(id="transform_seed", data=os.urandom(32))
    dynamic["transform_rounds"] = Container(id="transform_rounds", data=AES_ROUNDS)
    dynamic["encryption_iv"] = Container(id="encryption_iv", data=os.urandom(16))
    dynamic["protected_stream_key"] = Container(id="protected_stream_key",
                                                data=os.urandom(32))
    dynamic["stream_start_bytes"] = Container(id="stream_start_bytes",
                                              data=os.urandom(32))
    dynamic["protected_stream_id"] = Container(id="protected_stream_id",
                                               data="salsa20")
    dynamic["end"] = Container(id="end", data=b"\r\n\r\n")
    header.dynamic_header = dynamic
    tree = kp.kdbx.body.payload.xml
    meta = tree.find("Meta")
    if meta.find("Binaries") is None:
        meta.append(Element("Binaries"))
    kp.kdbx.body = Container(payload=Container(xml=tree))


def new_database(keyfile=None):
    handle, path = tempfile.mkstemp(suffix=".kdbx")
    os.close(handle)
    kp = create_database(path, password=PASSWORD, keyfile=keyfile)
    return kp, path


def write(kp, name, keyfile=None):
    target = os.path.join(OUT, name)
    kp.save(target)
    check = PyKeePass(target, password=PASSWORD, keyfile=keyfile)
    count = len(check.entries)
    size = os.path.getsize(target)
    print("%-36s %6d bytes, %2d entries, %s, kdf %s" % (
        name, size, count, check.version, check.kdf_algorithm))


def build(name, configure, keyfile=None, version3=False):
    kp, tmp = new_database(keyfile)
    try:
        if version3:
            make_version3(kp)
        configure(kp)
        fill(kp)
        write(kp, name, keyfile)
    finally:
        os.remove(tmp)


def write_keyfiles():
    raw = os.path.join(OUT, "keyfile_raw.key")
    with open(raw, "wb") as f:
        f.write(bytes(range(7, 39)))
    key = bytes(range(100, 132))
    digest = hashlib.sha256(key).digest()[:4].hex().upper()
    hex_key = key.hex().upper()
    groups = " ".join(hex_key[i:i + 8] for i in range(0, 64, 8))
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<KeyFile>\n"
        "\t<Meta>\n\t\t<Version>2.0</Version>\n\t</Meta>\n"
        "\t<Key>\n"
        '\t\t<Data Hash="%s">\n\t\t\t%s\n\t\t\t%s\n\t\t</Data>\n'
        "\t</Key>\n"
        "</KeyFile>\n" % (digest, groups[:35], groups[36:])
    )
    xml_path = os.path.join(OUT, "keyfile_v2.keyx")
    with open(xml_path, "w", newline="\n") as f:
        f.write(xml)
    return raw, xml_path


def main():
    os.makedirs(OUT, exist_ok=True)
    raw_key, xml_key = write_keyfiles()
    build("kdbx4_argon2d_aes_chacha20.kdbx", lambda kp: set_argon2(kp, "argon2"))
    build("kdbx4_argon2id_chacha20.kdbx",
          lambda kp: (set_argon2(kp, "argon2id"), set_cipher(kp, "chacha20")))
    build("kdbx4_aeskdf.kdbx", set_aeskdf)
    build("kdbx4_twofish.kdbx",
          lambda kp: (set_argon2(kp, "argon2"), set_cipher(kp, "twofish")))
    build("kdbx4_nocompress.kdbx",
          lambda kp: (set_argon2(kp, "argon2"), set_compression(kp, False)))
    build("kdbx4_keyfile_raw.kdbx", lambda kp: set_argon2(kp, "argon2"),
          keyfile=raw_key)
    build("kdbx4_keyfile_xml2.kdbx", lambda kp: set_argon2(kp, "argon2"),
          keyfile=xml_key)
    build("kdbx3_aeskdf_salsa20.kdbx", lambda kp: None, version3=True)


if __name__ == "__main__":
    main()
