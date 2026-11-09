#!/usr/bin/env python3
"""
Android 15+ 16KB Page Size Alignment & ELF Compliance Checker (P4).
Inspects compiled or packaged ELF binaries for 16KB segment alignment.
"""

import struct
import sys
from pathlib import Path

ELF_MAGIC = b"\x7fELF"
PT_LOAD = 1
ALIGN_16KB = 16384  # 0x4000

def check_elf_alignment(file_path: Path) -> tuple[bool, str]:
    if not file_path.exists():
        return False, f"File not found: {file_path}"
    
    with open(file_path, "rb") as f:
        magic = f.read(4)
        if magic != ELF_MAGIC:
            return False, f"Not an ELF binary: {file_path}"
        
        ei_class = ord(f.read(1))
        if ei_class != 2:  # ELFCLASS64
            return False, f"Not a 64-bit ELF: {file_path}"
        
        # Seek to e_phoff (Program header table file offset)
        f.seek(32)
        e_phoff = struct.unpack("<Q", f.read(8))[0]
        
        # Seek to e_phentsize and e_phnum
        f.seek(54)
        e_phentsize = struct.unpack("<H", f.read(2))[0]
        e_phnum = struct.unpack("<H", f.read(2))[0]
        
        all_aligned = True
        details = []
        
        for i in range(e_phnum):
            f.seek(e_phoff + (i * e_phentsize))
            p_type = struct.unpack("<I", f.read(4))[0]
            if p_type == PT_LOAD:
                f.seek(e_phoff + (i * e_phentsize) + 48)  # offset of p_align in 64-bit program header
                p_align = struct.unpack("<Q", f.read(8))[0]
                if p_align < ALIGN_16KB:
                    all_aligned = False
                    details.append(f"Segment {i} PT_LOAD has p_align={p_align} (expected >= {ALIGN_16KB})")
        
        if all_aligned:
            return True, f"PASS: All PT_LOAD segments aligned to >= 16KB ({file_path.name})"
        else:
            return False, f"FAIL: {file_path.name} is not 16KB aligned:\n  " + "\n  ".join(details)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: check-16kb-alignment.py <elf_or_apk_path>")
        sys.exit(0)
    
    target = Path(sys.argv[1])
    ok, msg = check_elf_alignment(target)
    print(msg)
    sys.exit(0 if ok else 1)
