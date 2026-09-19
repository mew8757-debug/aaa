#!/usr/bin/env python3
import json
import pathlib
import re
import struct
import sys
import urllib.request
import zipfile

SCHEMA_URL = (
    "https://raw.githubusercontent.com/Puluomiyuhun/"
    "ccz-SceneEditor/main/cczEditor2/cczEditor2View.h"
)

PLAYER = "player"
ALLY = "ally"
ENEMY = "enemy"

MAP_WIDTH = 23
MAP_HEIGHT = 16
TERRAIN_HEADER_SIZE = 2
JOB_GROWTH_BASE = 40372
JOB_GROWTH_STRIDE = 35
MOVE_COST_BASE = 43202
JOB_FAMILY_STRIDE = 60
TERRAIN_TYPE_COUNT = 30
JOB_FAMILY_COUNT = 40


def s16(buf, off):
    return int.from_bytes(buf[off:off + 2], "little", signed=True)


def u16(buf, off):
    return int.from_bytes(buf[off:off + 2], "little", signed=False)


def i32(buf, off):
    return int.from_bytes(buf[off:off + 4], "little", signed=True)


def be_desc(blob, zero_based_index):
    off = 0x110 + zero_based_index * 12
    if zero_based_index < 0 or off + 12 > len(blob):
        return None
    size, stored, data_off = struct.unpack_from(">III", blob, off)
    if size <= 0 or stored <= 0 or data_off < 0:
        return None
    if data_off + min(size, stored) > len(blob):
        return None
    return size, stored, data_off


def read_member_by_basename(zf, basename):
    wanted = basename.lower()
    for name in zf.namelist():
        if pathlib.PurePosixPath(name).name.lower() == wanted:
            return zf.read(name)
    return None


def extract_hexzmap_cells(blob, map_number, width, height):
    if len(blob) < 0x110 + (map_number + 1) * 12:
        raise ValueError("Hexzmap.e5 directory is truncated")

    magic = blob[:4]
    if magic not in (b"Ls10", b"Ls11", b"Ls12"):
        raise ValueError(f"unexpected Hexzmap magic {magic!r}")

    off = 0x110 + map_number * 12
    segment_length, decoded_length, file_offset = struct.unpack_from(">III", blob, off)
    expected = TERRAIN_HEADER_SIZE + width * height

    if decoded_length < expected:
        raise ValueError(
            f"Hexzmap map {map_number} decoded length {decoded_length} < {expected}"
        )
    if segment_length != decoded_length:
        raise ValueError(
            f"compressed Hexzmap map {map_number} is not supported: "
            f"{segment_length}/{decoded_length}"
        )
    if file_offset + segment_length > len(blob):
        raise ValueError("Hexzmap map segment exceeds file length")

    segment = blob[file_offset:file_offset + segment_length]
    cells = segment[TERRAIN_HEADER_SIZE:TERRAIN_HEADER_SIZE + width * height]
    if len(cells) != width * height:
        raise ValueError("Hexzmap terrain cell payload is truncated")
    return cells


def detailed_job_to_family(job_id):
    if job_id < 0 or job_id >= 80:
        raise ValueError(f"detailed job out of range: {job_id}")
    return job_id // 3 if job_id < 60 else 20 + (job_id - 60)


def decode_numeric_command(sec, off, types, records=1):
    if off + 2 > len(sec):
        raise ValueError("command header out of range")
    p = off + 2
    out = []
    for _ in range(records):
        row = []
        for typ in types:
            if p + 2 > len(sec):
                raise ValueError("parameter tag out of range")
            p += 2
            if typ == 0x04:
                if p + 4 > len(sec):
                    raise ValueError("int32 out of range")
                row.append(i32(sec, p))
                p += 4
            else:
                if p + 2 > len(sec):
                    raise ValueError("int16 out of range")
                row.append(s16(sec, p))
                p += 2
        out.append(row)
    return out, p - off


def load_command_schema():
    src = urllib.request.urlopen(SCHEMA_URL, timeout=30).read().decode(
        "utf-8", "replace"
    )

    m = re.search(r"int code_instruct\[124\]\[13\] = \{(.*?)\n\s*\};", src, re.S)
    if not m:
        raise RuntimeError("code_instruct metadata not found")
    vals = [int(x, 0) for x in re.findall(r"-1|0x[0-9A-Fa-f]+|\d+", m.group(1))]
    table = [vals[i * 13:(i + 1) * 13] for i in range(124)]

    mt = re.search(r"byte code_test\[124\] = \{([^}]*)\}", src, re.S)
    if not mt:
        raise RuntimeError("code_test metadata not found")
    test = [int(x, 0) for x in re.findall(r"0x[0-9A-Fa-f]+|\d+", mt.group(1))]
    test += [0] * (124 - len(test))
    return table, test


def parse_command(sec, k, table):
    cid = sec[k]
    if cid >= len(table):
        raise ValueError(f"unsupported command id 0x{cid:02x} at {k}")

    p = k + 2
    params = []
    count = 13
    width = 13
    if cid == 0x46:
        count = 11 * 20
        width = 11
    elif cid == 0x47:
        count = 12 * 80
        width = 12

    for i in range(count):
        typ = table[cid][i % width]
        if typ == -1:
            break
        if p + 2 > len(sec):
            raise ValueError((cid, k, "tag overflow"))
        p += 2

        if typ == 0x05:
            e = sec.find(b"\0", p)
            if e < 0:
                raise ValueError((cid, k, "string overflow"))
            params.append(sec[p:e].decode("cp949", "replace"))
            p = e + 1
        elif typ == 0x35:
            n = u16(sec, p)
            p += 2
            params.append([s16(sec, p + 2 * j) for j in range(n)])
            p += 2 * n
        elif typ == 0x04:
            params.append(i32(sec, p))
            p += 4
        else:
            params.append(s16(sec, p))
            p += 2

    return cid, p - k, params


def split_dialogue(raw_text):
    clean = raw_text.replace("\r", "")
    lines = clean.split("\n")
    if lines and lines[0].startswith("&"):
        return lines[0][1:].strip(), "\n".join(lines[1:]).strip()
    return "", clean.strip()


def extract_opening_events(sec):
    table, test = load_command_schema()
    events = []
    k = 0
    head = True
    zflag = False
    zsum = 0

    while k < len(sec):
        cmd_off = k
        cid, used, params = parse_command(sec, k, table)

        if cmd_off >= 5760:
            if cid == 0x09 and params:
                events.append({"type": "delay", "value": max(1, int(params[0]))})
            elif cid in (0x14, 0x15, 0x16, 0x69, 0x7A):
                strings = [p for p in params if isinstance(p, str)]
                if strings:
                    speaker, body = split_dialogue(strings[-1])
                    if body or speaker:
                        events.append({
                            "type": "dialogue",
                            "speaker": speaker,
                            "text": body or speaker,
                        })
            elif cid == 0x23 and params:
                events.append({"type": "sound", "value": int(params[0])})
            elif cid == 0x24 and params:
                events.append({"type": "music", "value": int(params[0])})
            elif cid == 0x30 and len(params) >= 5:
                char_id = int(params[0])
                events.append({"type": "reveal", "characterId": char_id})
                events.append({
                    "type": "move",
                    "characterId": char_id,
                    "x": int(params[1]),
                    "y": int(params[2]),
                    "direction": int(params[3]),
                })
                if int(params[4]) >= 0:
                    events.append({
                        "type": "action",
                        "characterId": char_id,
                        "value": int(params[4]),
                    })
            elif cid == 0x31 and len(params) >= 2 and int(params[0]) == 0:
                events.append({"type": "hide", "characterId": int(params[1])})
            elif cid == 0x32 and len(params) >= 6 and int(params[0]) != 1:
                events.append({
                    "type": "move",
                    "characterId": int(params[1]),
                    "x": int(params[3]),
                    "y": int(params[4]),
                    "direction": int(params[5]),
                })
            elif cid == 0x33 and len(params) >= 3:
                char_id = int(params[0])
                if int(params[1]) >= 0:
                    events.append({
                        "type": "action",
                        "characterId": char_id,
                        "value": int(params[1]),
                    })
                events.append({
                    "type": "turn",
                    "characterId": char_id,
                    "direction": int(params[2]),
                })
            elif cid == 0x34 and len(params) >= 2:
                events.append({
                    "type": "action",
                    "characterId": int(params[0]),
                    "value": int(params[1]),
                })
            elif cid == 0x4C and len(params) >= 3:
                char_id = int(params[1]) if int(params[0]) == 0 else -1
                if char_id >= 0:
                    events.append({"type": "reveal", "characterId": char_id})
            elif cid == 0x4F and len(params) >= 6:
                events.append({
                    "type": "turn",
                    "characterId": int(params[0]),
                    "targetId": int(params[1]),
                    "direction": int(params[2]),
                })
            elif cid == 0x50 and len(params) >= 2:
                events.append({
                    "type": "action",
                    "characterId": int(params[0]),
                    "value": int(params[1]),
                })
            elif cid == 0x53 and len(params) >= 2 and int(params[0]) != 1:
                events.append({"type": "retreat", "characterId": int(params[1])})
            elif cid == 0x5A:
                events.append({"type": "end"})
                break

        k += used

        if cid == 0 and head:
            head = False
            k += 2

        if cid == 1:
            zflag = True
        elif zflag:
            if cid < len(test) and test[cid] != 0:
                zsum += 1
                k += 2
            zflag = False

        if cid == 0 and zsum > 0:
            zsum -= 1

    return events


def main(argv):
    if len(argv) != 4:
        print("usage: build_s00_assets.py game1.Zip game2.Zip output-assets-dir")
        return 2

    game1_path, game2_path, output_path = argv[1:]
    root = pathlib.Path(output_path)
    map_dir = root / "map"
    sprite_dir = root / "sprites"
    battle_dir = root / "battle"

    if root.exists():
        import shutil
        shutil.rmtree(root)
    map_dir.mkdir(parents=True)
    sprite_dir.mkdir(parents=True)
    battle_dir.mkdir(parents=True)

    with zipfile.ZipFile(game1_path) as game1, zipfile.ZipFile(game2_path) as game2:
        map_bytes = game2.read("map/m000.jpg")
        (map_dir / "m000.jpg").write_bytes(map_bytes)

        data = game1.read("Data.e5")
        exe = game1.read("Ekd5.exe")
        mov = game1.read("Unit_mov.e5")
        spc = game1.read("Unit_spc.e5")
        pal = game1.read("Spalet.e5")
        s00 = game1.read("RS/S_00.eex")

        hexz = read_member_by_basename(game2, "Hexzmap.e5")
        if hexz is None:
            hexz = read_member_by_basename(game1, "Hexzmap.e5")

    if hexz is None:
        raise SystemExit("Hexzmap.e5 not found in game1/game2")

    if len(s00) != 31318 or not s00.startswith(b"EEX"):
        raise SystemExit("Unexpected S_00.eex revision")

    terrain_cells = extract_hexzmap_cells(hexz, 0, MAP_WIDTH, MAP_HEIGHT)
    (battle_dir / "terrain0.bin").write_bytes(terrain_cells)

    move_cost_blob = bytearray()
    for family in range(JOB_FAMILY_COUNT):
        start = MOVE_COST_BASE + family * JOB_FAMILY_STRIDE
        row = data[start:start + TERRAIN_TYPE_COUNT]
        if len(row) != TERRAIN_TYPE_COUNT:
            raise SystemExit(f"movement cost row truncated: family={family}")
        move_cost_blob.extend(row)
    (battle_dir / "movement_costs.bin").write_bytes(move_cost_blob)

    def character_row(cid):
        off = 0x18C + cid * 0x20
        if off < 0 or off + 0x20 > len(data):
            raise ValueError(f"character out of Data.e5 range: {cid}")
        return off

    def name_of(cid):
        row = character_row(cid)
        raw = data[row:row + 12].split(b"\0", 1)[0]
        return raw.decode("cp949", "replace").strip() or f"인물{cid}"

    def job_profile_of(cid):
        row = character_row(cid)
        job_id = data[row + 26]
        family = detailed_job_to_family(job_id)
        growth_off = JOB_GROWTH_BASE + job_id * JOB_GROWTH_STRIDE
        if growth_off + JOB_GROWTH_STRIDE > len(data):
            raise ValueError(f"job growth row out of range: {job_id}")
        move_points = data[growth_off]
        attack_range_id = data[growth_off + 1]
        return job_id, family, move_points, attack_range_id

    def sprite_of(cid):
        off = 0xD2800 + cid * 2
        return int.from_bytes(exe[off:off + 2], "little")

    def sprite_record_valid(sid):
        expected_mov = 48 * 48 * 11
        expected_spc = 48 * 48 * 5
        md = be_desc(mov, sid)
        sd = be_desc(spc, sid)
        return (
            md is not None and sd is not None
            and md[0] == expected_mov and md[1] >= expected_mov
            and sd[0] == expected_spc and sd[1] >= expected_spc
        )

    # Verified non-empty deployment records from RS/S_00.eex.
    # flag=1 means the actor starts under scenario control/hidden.
    player = [
        (0, 0, 3, 2),
        (1, 0, 4, 2),
        (2, 1, 4, 2),
    ]
    allies = [
        (650, 1, 3, 10, 3),
        (651, 1, 17, 8, 3),
        (310, 1, 13, 10, 1),
        (309, 1, 12, 11, 1),
        (654, 1, 7, 13, 3),
        (655, 1, 13, 12, 0),
        (181, 1, 1, 3, 2),
    ]
    enemies = [
        (596, 1, 20, 5, 2),
        (594, 1, 19, 5, 2),
        (595, 1, 20, 4, 2),
        (226, 1, 19, 4, 2),
        (597, 0, 18, 0, 2),
        (598, 0, 18, 1, 2),
        (599, 0, 21, 0, 2),
        (600, 0, 21, 1, 2),
        (662, 0, 20, 0, 2),
        (663, 0, 19, 1, 2),
        (664, 0, 20, 1, 2),
        (601, 0, 19, 2, 2),
        (602, 0, 20, 2, 2),
        (225, 0, 19, 0, 2),
        (603, 0, 19, 4, 0),
    ]

    units = []

    def append_unit(cid, faction, scripted, x, y, direction, source):
        sid = sprite_of(cid)
        if not sprite_record_valid(sid):
            print(f"skip actor {cid}: invalid sprite {sid}")
            return False
        job_id, family, move_points, attack_range_id = job_profile_of(cid)
        units.append({
            "characterId": cid,
            "name": name_of(cid),
            "spriteId": sid,
            "jobId": job_id,
            "jobFamily": family,
            "movePoints": move_points,
            "attackRangeId": attack_range_id,
            "faction": faction,
            "scripted": bool(scripted),
            "visible": not bool(scripted),
            "x": x,
            "y": y,
            "direction": direction,
            "source": source,
        })
        return True

    for cid, x, y, direction in player:
        append_unit(cid, PLAYER, False, x, y, direction, "S_00:0x4B")
    for index, (cid, flag, x, y, direction) in enumerate(allies):
        append_unit(cid, ALLY, flag != 0, x, y, direction, f"S_00:0x46:{index}")
    for index, (cid, flag, x, y, direction) in enumerate(enemies):
        append_unit(cid, ENEMY, flag != 0, x, y, direction, f"S_00:0x47:{index}")

    scene0 = int.from_bytes(s00[10:14], "little")
    section_count = u16(s00, scene0)
    if section_count < 1:
        raise SystemExit("S_00 scene0 has no sections")
    section_len = u16(s00, scene0 + 2)
    sec = s00[scene0 + 4:scene0 + 4 + section_len]
    events = extract_opening_events(sec)

    referenced_ids = sorted({
        e["characterId"]
        for e in events
        if isinstance(e.get("characterId"), int) and e["characterId"] >= 0
    })
    known_ids = {u["characterId"] for u in units}
    for cid in referenced_ids:
        if cid in known_ids:
            continue
        if append_unit(cid, ALLY, True, 0, 0, 2, "S_00:event-reference"):
            known_ids.add(cid)

    terrain_ids = sorted(set(terrain_cells))
    unsupported_terrain = [tid for tid in terrain_ids if tid >= TERRAIN_TYPE_COUNT]
    if unsupported_terrain:
        print("warning: terrain ids outside movement table:", unsupported_terrain)

    battle = {
        "version": 7,
        "source": "RS/S_00.eex",
        "mapId": 0,
        "map": "m000.jpg",
        "widthTiles": MAP_WIDTH,
        "heightTiles": MAP_HEIGHT,
        "terrainFile": "terrain0.bin",
        "terrainTypeCount": TERRAIN_TYPE_COUNT,
        "movementCostFile": "movement_costs.bin",
        "movementCostFamilyCount": JOB_FAMILY_COUNT,
        "terrainIds": terrain_ids,
        "units": units,
        "openingEvents": events,
    }
    (battle_dir / "battle0.json").write_text(
        json.dumps(battle, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    sprite_ids = sorted({u["spriteId"] for u in units})
    for sid in sprite_ids:
        for label, blob, frames in (("unit_mov", mov, 11), ("unit_spc", spc, 5)):
            expected = 48 * 48 * frames
            desc = be_desc(blob, sid)
            if desc is None:
                raise SystemExit(f"Missing {label} sprite {sid}")
            unpacked, stored, offset = desc
            if unpacked != expected or stored < expected:
                raise SystemExit(
                    f"Unexpected {label} sid={sid}: {unpacked}/{stored}/{offset}"
                )
            payload = blob[offset:offset + expected]
            if len(payload) != expected:
                raise SystemExit(f"Truncated {label} sid={sid}")
            (sprite_dir / f"{label}_{sid:03d}.bin").write_bytes(payload)

    pdesc = be_desc(pal, 0)
    if pdesc is None or pdesc[0] != 768 or pdesc[1] < 768:
        raise SystemExit("Unexpected Spalet record 0")
    (sprite_dir / "spalet_000.bin").write_bytes(
        pal[pdesc[2]:pdesc[2] + 768]
    )

    print("map bytes=", len(map_bytes))
    print("terrain cells=", len(terrain_cells), "ids=", terrain_ids)
    print(
        "units=", len(units),
        "visible=", sum(1 for u in units if u["visible"]),
    )
    print(
        "players=",
        [
            {
                "id": u["characterId"],
                "job": u["jobId"],
                "family": u["jobFamily"],
                "move": u["movePoints"],
            }
            for u in units
            if u["faction"] == PLAYER
        ],
    )
    print("sprite ids=", sprite_ids)
    print(
        "opening events=", len(events),
        "dialogues=", sum(1 for e in events if e["type"] == "dialogue"),
        "moves=", sum(1 for e in events if e["type"] == "move"),
    )
    print("event character ids=", referenced_ids)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
