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
TERRAIN_POWER_BASE = 43172
MOVE_COST_BASE = 43202
JOB_FAMILY_STRIDE = 60
TERRAIN_TYPE_COUNT = 30
JOB_FAMILY_COUNT = 40
JOB_RESTRAINT_BASE = 668288
JOB_RESTRAINT_STRIDE = 40

# v0.8 panel bridge. The source values are also emitted into battle0.json so
# this deterministic prototype formula can be replaced without re-reversing data.
COMBAT_MODEL = "ccz65-panel-bridge-v0.8"
DAMAGE_MODEL = "physical-atk-minus-def-counter-v0.9"


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


def scan_commands(sec):
    table, test = load_command_schema()
    commands = []
    k = 0
    head = True
    zflag = False
    zsum = 0

    while k < len(sec):
        cmd_off = k
        cid, used, params = parse_command(sec, k, table)
        commands.append((cmd_off, cid, params))
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

    return commands



def parse_scenario_tree(blob):
    table, test = load_command_schema()

    first_scene_offset = i32(blob, 10)
    if (
        first_scene_offset < 14
        or first_scene_offset > len(blob)
        or (first_scene_offset - 10) % 4 != 0
    ):
        raise ValueError("invalid EEX scene offset table")

    scene_offsets = [
        i32(blob, off)
        for off in range(10, first_scene_offset, 4)
    ]

    def parse_block(start, length, scene_index, section_index, kind, is_section_root):
        end = start + length
        if start < 0 or end > len(blob):
            raise ValueError(
                f"scenario block out of range scene={scene_index} "
                f"section={section_index} kind={kind}"
            )

        nodes = []
        cursor = start
        head = is_section_root
        pending_sub_event = False

        while cursor < end:
            command_offset = cursor
            cid, used, params = parse_command(blob, cursor, table)
            cursor += used

            node = {
                "scene": scene_index,
                "section": section_index,
                "kind": kind,
                "offset": command_offset,
                "commandId": cid,
                "params": params,
                "children": [],
            }

            if head and cid == 0:
                if cursor + 2 > end:
                    raise ValueError("body block length prefix out of range")
                child_len = u16(blob, cursor)
                child_start = cursor + 2
                node["children"] = parse_block(
                    child_start,
                    child_len,
                    scene_index,
                    section_index,
                    "Body",
                    False,
                )
                cursor = child_start + child_len
                head = False
                pending_sub_event = False
            elif (
                pending_sub_event
                and cid < len(test)
                and test[cid] != 0
            ):
                if cursor + 2 > end:
                    raise ValueError("sub-event length prefix out of range")
                child_len = u16(blob, cursor)
                child_start = cursor + 2
                node["children"] = parse_block(
                    child_start,
                    child_len,
                    scene_index,
                    section_index,
                    "SubEvent",
                    False,
                )
                cursor = child_start + child_len
                pending_sub_event = False
            elif cid != 1:
                pending_sub_event = False

            if cid == 1:
                pending_sub_event = True

            nodes.append(node)

        if cursor != end:
            raise ValueError(
                f"scenario block length mismatch scene={scene_index} "
                f"section={section_index} kind={kind}"
            )
        return nodes

    scenes = []
    for scene_index, scene_offset in enumerate(scene_offsets, start=1):
        if scene_offset + 2 > len(blob):
            raise ValueError(f"scene {scene_index} offset out of range")

        section_count = u16(blob, scene_offset)
        cursor = scene_offset + 2
        sections = []

        for section_index in range(1, section_count + 1):
            if cursor + 2 > len(blob):
                raise ValueError(
                    f"scene {scene_index} section {section_index} prefix out of range"
                )
            section_len = u16(blob, cursor)
            section_start = cursor + 2
            nodes = parse_block(
                section_start,
                section_len,
                scene_index,
                section_index,
                "Section",
                True,
            )
            sections.append({
                "section": section_index,
                "offset": section_start,
                "length": section_len,
                "commands": nodes,
            })
            cursor = section_start + section_len

        scenes.append({
            "scene": scene_index,
            "offset": scene_offset,
            "sections": sections,
        })

    return scenes


def flatten_scenario_nodes(scenes):
    flat = []

    def visit(node, depth):
        row = {
            "scene": node["scene"],
            "section": node["section"],
            "kind": node["kind"],
            "depth": depth,
            "offset": node["offset"],
            "commandId": node["commandId"],
            "params": node["params"],
            "childCommandIds": [
                child["commandId"]
                for child in node["children"]
            ],
        }
        flat.append(row)
        for child in node["children"]:
            visit(child, depth + 1)

    for scene in scenes:
        for section in scene["sections"]:
            for node in section["commands"]:
                visit(node, 0)

    return flat


def build_scenario_diagnostics(scenes):
    flat = flatten_scenario_nodes(scenes)
    command_counts = {}
    for row in flat:
        key = f"0x{row['commandId']:02X}"
        command_counts[key] = command_counts.get(key, 0) + 1

    relevant_ids = {
        0x19, 0x1A,
        0x25, 0x26,
        0x36,
        0x3F, 0x40, 0x41,
        0x42, 0x43,
        0x49,
        0x53, 0x54,
        0x5D,
        0x0D, 0x0E,
    }
    relevant = [
        {
            "scene": row["scene"],
            "section": row["section"],
            "kind": row["kind"],
            "depth": row["depth"],
            "offset": row["offset"],
            "commandId": row["commandId"],
            "commandHex": f"0x{row['commandId']:02X}",
            "params": row["params"],
            "childCommandIds": row["childCommandIds"],
        }
        for row in flat
        if row["commandId"] in relevant_ids
    ]

    selected_section_keys = {
        (2, 1),
        (2, 9),
        (2, 10),
        (2, 2),
        (2, 11),
        (2, 12),
        (2, 15),
        (2, 30),
        (2, 20),
        (2, 21),
        (2, 31),
        (2, 33),
        (2, 34),
        (3, 1),
    }
    selected_sections = {}
    for scene_index, section_index in sorted(selected_section_keys):
        key = f"S{scene_index:02d}-SEC{section_index:02d}"
        selected_sections[key] = [
            {
                "depth": row["depth"],
                "kind": row["kind"],
                "offset": row["offset"],
                "commandId": row["commandId"],
                "commandHex": f"0x{row['commandId']:02X}",
                "params": row["params"],
                "childCommandIds": row["childCommandIds"],
            }
            for row in flat
            if row["scene"] == scene_index
            and row["section"] == section_index
        ]

    return {
        "sceneCount": len(scenes),
        "sectionCounts": [
            len(scene["sections"])
            for scene in scenes
        ],
        "commandCount": len(flat),
        "commandCounts": command_counts,
        "relevantCommands": relevant,
        "selectedSections": selected_sections,
    }




def native_action_from_node(node):
    cid = node["commandId"]
    params = node["params"]

    if cid == 0x09 and params:
        return {"type": "delay", "value": max(1, int(params[0]))}

    if cid in (0x14, 0x15, 0x16, 0x69, 0x7A):
        strings = [p for p in params if isinstance(p, str)]
        if strings:
            speaker, body = split_dialogue(strings[-1])
            if body or speaker:
                return {
                    "type": "dialogue",
                    "speaker": speaker,
                    "text": body or speaker,
                }
        return {"type": "noop", "commandId": cid}

    if cid == 0x23 and params:
        return {"type": "sound", "value": int(params[0])}

    if cid == 0x24 and params:
        return {"type": "music", "value": int(params[0])}

    # 0x6B is a scripted spell visual at an absolute battlefield tile.
    if cid == 0x6B and len(params) >= 4:
        return {
            "type": "spellEffect",
            "x": int(params[0]),
            "y": int(params[1]),
            "effectId": int(params[2]),
            "focus": int(params[3]) != 0,
        }


    if cid == 0x31 and len(params) >= 2 and int(params[0]) == 0:
        return {"type": "hide", "characterId": int(params[1])}

    if cid == 0x31 and len(params) >= 7 and int(params[0]) == 1:
        return {
            "type": "hideArea",
            "x1": int(params[2]),
            "y1": int(params[3]),
            "x2": int(params[4]),
            "y2": int(params[5]),
            "camp": int(params[6]),
        }


    if cid == 0x32 and len(params) >= 6 and int(params[0]) != 1:
        return {
            "type": "move",
            "characterId": int(params[1]),
            "x": int(params[3]),
            "y": int(params[4]),
            "direction": int(params[5]),
        }

    if cid == 0x4C and len(params) >= 3 and int(params[0]) == 0:
        return {"type": "reveal", "characterId": int(params[1])}

    if cid == 0x4E and len(params) >= 11:
        return {
            "type": "aiPolicy",
            "targetMode": int(params[0]),
            "characterId": int(params[1]),
            "x1": int(params[2]),
            "y1": int(params[3]),
            "x2": int(params[4]),
            "y2": int(params[5]),
            "camp": int(params[6]),
            "policy": int(params[7]),
            "targetCharacterId": int(params[8]),
            "targetX": int(params[9]),
            "targetY": int(params[10]),
        }

    if (
        cid == 0x6D
        and len(params) >= 8
        and int(params[0]) == 0
    ):
        return {
            "type": "relativeMove",
            "characterId": int(params[1]),
            "anchorCharacterId": int(params[3]),
            "offsetX": int(params[4]),
            "offsetY": int(params[5]),
            "direction": int(params[6]),
            "revive": int(params[7]) != 0,
        }

    if cid == 0x6F and params:
        return {
            "type": "discardItem",
            "itemId": int(params[0]),
            "count": 1,
        }

    if cid == 0x4F and len(params) >= 6:
        return {
            "type": "turn",
            "characterId": int(params[0]),
            "targetId": int(params[1]),
            "direction": int(params[2]),
        }

    if cid == 0x50 and len(params) >= 2:
        return {
            "type": "action",
            "characterId": int(params[0]),
            "value": int(params[1]),
        }

    if cid == 0x53 and len(params) >= 8 and int(params[0]) == 0:
        return {
            "type": "kill" if int(params[7]) != 0 else "retreat",
            "characterId": int(params[1]),
        }

    if cid == 0x53 and len(params) >= 8 and int(params[0]) == 1:
        return {
            "type": "retreatArea",
            "x1": int(params[2]),
            "y1": int(params[3]),
            "x2": int(params[4]),
            "y2": int(params[5]),
            "camp": int(params[6]),
            "kill": int(params[7]) != 0,
        }


    if cid == 0x55 and len(params) >= 6 and int(params[0]) == 0:
        return {
            "type": "revive",
            "characterId": int(params[1]),
            "x": int(params[3]),
            "y": int(params[4]),
            "direction": int(params[5]),
        }

    if cid == 0x3D and len(params) >= 4:
        return {
            "type": "reward",
            "value": int(params[0]),
            "targetId": int(params[3]),
        }

    if cid == 0x3A and len(params) >= 3:
        return {
            "type": "globalValueOp",
            "globalId": int(params[0]),
            "operation": int(params[1]),
            "value": int(params[2]),
        }

    if (
        cid == 0x38
        and len(params) >= 4
        and int(params[1]) == 7
    ):
        return {
            "type": "unitHpChange",
            "characterId": int(params[0]),
            "operation": int(params[2]),
            "value": int(params[3]),
        }

    if (
        cid == 0x38
        and len(params) >= 4
        and int(params[1]) in (0, 1, 2, 3, 4)
    ):
        return {
            "type": "unitPanelChange",
            "characterId": int(params[0]),
            "panel": int(params[1]),
            "operation": int(params[2]),
            "value": int(params[3]),
        }



    # 0x72 / extended command 11:
    # line 1 = movement rectangle, following line(s) = DATA character ids.
    if (
        cid == 0x72
        and len(params) >= 2
        and int(params[0]) == 11
        and isinstance(params[1], str)
    ):
        lines = [
            line.strip()
            for line in params[1].replace("\r", "").split("\n")
            if line.strip()
        ]
        if lines:
            area_match = re.fullmatch(
                r"\s*(-?\d+)\s*,\s*(-?\d+)\s*\|"
                r"\s*(-?\d+)\s*,\s*(-?\d+)\s*",
                lines[0],
            )
            if area_match:
                x1, y1, x2, y2 = (
                    int(area_match.group(i))
                    for i in range(1, 5)
                )
                ids = []
                for line in lines[1:]:
                    for token in re.split(r"[,|\s]+", line):
                        if not token:
                            continue
                        try:
                            ids.append(int(token))
                        except ValueError:
                            pass
                return {
                    "type": "aiAreaLimit",
                    "x1": x1,
                    "y1": y1,
                    "x2": x2,
                    "y2": y2,
                    "enabled": not (
                        x1 == 0
                        and y1 == 0
                        and x2 == 255
                        and y2 == 255
                    ),
                    "characterIds": ids,
                }

    # 0x58: object/display/terrain/x/y/viewpoint/sound.
    if cid == 0x58 and len(params) >= 7:
        return {
            "type": "battlefieldObject",
            "objectId": int(params[0]),
            "visible": int(params[1]) != 0,
            "terrainId": int(params[2]),
            "x": int(params[3]),
            "y": int(params[4]),
            "focus": int(params[5]) != 0,
            "sound": int(params[6]) != 0,
        }

    # 0x78 in S01 transfers an integer variable to/from a unit attribute.
    # Verified attributes used here: 7=HP(max), 33=HpCur.
    if (
        cid == 0x78
        and len(params) >= 4
        and int(params[3]) in (0, 1, 7, 32, 33)
    ):
        return {
            "type": "unitAttributeTransfer",
            "variableId": int(params[0]),
            "direction": int(params[1]),
            "characterId": int(params[2]),
            "attribute": int(params[3]),
        }


    if cid == 0x0B and len(params) >= 2:
        return {
            "type": "setVariable",
            "variableId": int(params[0]),
            "value": int(params[1]),
        }

    if cid == 0x11 and params:
        return {
            "type": "scenarioJump",
            "target": int(params[0]),
        }


    if cid == 0x5C and params:
        return {
            "type": "highlightUnit",
            "characterId": int(params[0]),
            "mode": int(params[1]) if len(params) >= 2 else 0,
        }

    if cid == 0x5D and len(params) >= 2:
        return {"type": "turnLimit", "value": int(params[1])}

    if cid == 0x19 and params and isinstance(params[0], str):
        return {"type": "objective", "text": params[0]}

    if cid == 0x1A and params and isinstance(params[0], str):
        return {"type": "objectivePopup", "text": params[0]}

    # 0x77: this S00 uses integer-variable = constant around reward UI.
    if (
        cid == 0x77
        and len(params) >= 5
        and int(params[0]) == 2
        and int(params[3]) == 0
    ):
        return {
            "type": "intVariableOp",
            "variableId": int(params[1]),
            "operation": int(params[2]),
            "value": int(params[4]),
        }

    # 0x4D: preserve the full verified battlefield-status target shape.
    if cid == 0x4D and len(params) >= 13:
        return {
            "type": "statusChange",
            "targetMode": int(params[0]),
            "characterId": int(params[1]),
            "battleNumber": int(params[2]),
            "x1": int(params[3]),
            "y1": int(params[4]),
            "x2": int(params[5]),
            "y2": int(params[6]),
            "camp": int(params[7]),
            "condition": int(params[8]),
            "change": int(params[9]),
            "debuffMask": int(params[10]),
            "value1": int(params[11]),
            "value2": int(params[12]),
        }

    # Scripted duel command family used by S00 Sections 11/12.
    if cid == 0x68 and len(params) >= 3:
        return {
            "type": "duelStart",
            "firstCharacterId": int(params[0]),
            "secondCharacterId": int(params[1]),
            "logo": int(params[2]),
        }
    if cid == 0x60 and len(params) >= 3:
        strings = [p for p in params if isinstance(p, str)]
        return {
            "type": "duelIntro",
            "side": int(params[0]),
            "text": strings[-1] if strings else "",
            "gesture": int(params[-1]) if isinstance(params[-1], int) else -1,
        }
    if cid == 0x61:
        return {"type": "duelClash"}
    if cid == 0x62 and params:
        return {"type": "duelDefeat", "side": int(params[0])}
    if cid == 0x63 and len(params) >= 3:
        strings = [p for p in params if isinstance(p, str)]
        return {
            "type": "duelDialogue",
            "side": int(params[0]),
            "text": strings[-1] if strings else "",
            "delay": int(params[-1]) if isinstance(params[-1], int) else 0,
        }
    if cid == 0x64 and len(params) >= 2:
        return {
            "type": "duelGesture",
            "side": int(params[0]),
            "gesture": int(params[1]),
        }
    if cid == 0x65 and len(params) >= 3:
        return {
            "type": "duelAttack",
            "side": int(params[0]),
            "result": int(params[1]),
            "critical": int(params[2]) != 0,
        }
    if cid == 0x66 and len(params) >= 3:
        return {
            "type": "duelCharge",
            "side": int(params[0]),
            "mode": int(params[1]),
            "result": int(params[2]),
        }
    if cid == 0x5F:
        return {"type": "duelEnd"}

    if cid == 0x59 and len(params) >= 8:
        return {
            "type": "loot",
            "category": int(params[0]),
            "slots": [
                {
                    "itemId": int(params[1]),
                    "level": int(params[2]),
                },
                {
                    "itemId": int(params[3]),
                    "level": int(params[4]),
                },
                {
                    "itemId": int(params[5]),
                    "level": int(params[6]),
                },
            ],
            "ending": int(params[7]),
        }

    if cid == 0x49:
        return {"type": "battleEndMarker"}

    if cid == 0x0D:
        return {"type": "sceneEnd"}

    if cid == 0x0E:
        return {"type": "battleFailureMarker"}

    if cid == 0x08 and params:
        return {
            "type": "menu",
            "enabled": int(params[0]) != 0,
        }

    if cid == 0x1D:
        return {"type": "paletteReset"}

    if cid in (0x00, 0x01, 0x02, 0x1B, 0x51):
        return {"type": "noop", "commandId": cid}

    return None


def native_trigger_from_node(node):
    cid = node["commandId"]
    params = node["params"]

    if cid == 0x25 and len(params) >= 3:
        return {
            "type": "position",
            "personCode": int(params[0]),
            "x": int(params[1]),
            "y": int(params[2]),
        }

    if cid == 0x26 and len(params) >= 5:
        return {
            "type": "area",
            "personCode": int(params[0]),
            "x1": int(params[1]),
            "y1": int(params[2]),
            "x2": int(params[3]),
            "y2": int(params[4]),
        }

    if cid == 0x2E and len(params) >= 3:
        return {
            "type": "adjacent",
            "firstCharacterId": int(params[0]),
            "secondCharacterId": int(params[1]),
            "requireAttackable": int(params[2]) == 0,
        }

    if cid == 0x36 and len(params) >= 4:
        if int(params[1]) == 7:
            return {
                "type": "unitHpCompare",
                "characterId": int(params[0]),
                "value": int(params[2]),
                "compare": int(params[3]),
            }
        return None

    if cid == 0x3F and len(params) >= 2:
        return {
            "type": "roundCompare",
            "value": int(params[0]),
            "compare": int(params[1]),
        }

    if cid == 0x40 and params:
        return {
            "type": "side",
            "side": int(params[0]),
        }

    if cid == 0x41 and len(params) >= 8:
        return {
            "type": "campCount",
            "camp": int(params[0]),
            "value": int(params[1]),
            "compare": int(params[2]),
            "area": int(params[3]) != 0,
            "x1": int(params[4]),
            "y1": int(params[5]),
            "x2": int(params[6]),
            "y2": int(params[7]),
        }

    return None



def compile_native_action_tree(node):
    nested_count = 1 if node["children"] else 0
    cid = node["commandId"]
    params = node["params"]

    if node["children"] and cid == 0x12:
        raw = ""
        if params and isinstance(params[0], str):
            raw = params[0].replace("\r", "")
        options = [
            line.strip()
            for line in raw.split("\n")
            if line.strip()
        ]
        cases = []
        unsupported_ids = []
        unsupported_actions = []
        total_nested = nested_count

        for child in node["children"]:
            if child["commandId"] == 0x01:
                continue

            if child["commandId"] == 0x13:
                case_actions = []
                case_value = (
                    int(child["params"][0])
                    if child["params"]
                    else len(cases) + 1
                )
                total_nested += 1 if child["children"] else 0

                for grandchild in child["children"]:
                    (
                        action,
                        child_unsupported_ids,
                        child_unsupported_actions,
                        child_nested,
                    ) = compile_native_action_tree(grandchild)
                    total_nested += child_nested
                    unsupported_ids.extend(child_unsupported_ids)
                    unsupported_actions.extend(
                        child_unsupported_actions
                    )
                    if action is not None:
                        case_actions.append(action)

                cases.append({
                    "value": case_value,
                    "actions": case_actions,
                })
                continue

            (
                action,
                child_unsupported_ids,
                child_unsupported_actions,
                child_nested,
            ) = compile_native_action_tree(child)
            total_nested += child_nested
            unsupported_ids.extend(child_unsupported_ids)
            unsupported_actions.extend(child_unsupported_actions)
            if action is not None:
                cases.append({
                    "value": len(cases) + 1,
                    "actions": [action],
                })

        return (
            {
                "type": "choice",
                "options": options,
                "cases": cases,
            },
            unsupported_ids,
            unsupported_actions,
            total_nested,
        )

    if node["children"]:
        child_actions = []
        unsupported_ids = []
        unsupported_actions = []
        total_nested = nested_count

        for child in node["children"]:
            (
                child_action,
                child_unsupported_ids,
                child_unsupported_actions,
                child_nested,
            ) = compile_native_action_tree(child)
            total_nested += child_nested
            unsupported_ids.extend(child_unsupported_ids)
            unsupported_actions.extend(child_unsupported_actions)
            if child_action is not None:
                child_actions.append(child_action)

        cid = node["commandId"]
        params = node["params"]

        if cid == 0x02:
            return (
                {
                    "type": "sequence",
                    "actions": child_actions,
                },
                unsupported_ids,
                unsupported_actions,
                total_nested,
            )

        if cid == 0x05 and len(params) >= 2:
            first = params[0] if isinstance(params[0], list) else []
            second = params[1] if isinstance(params[1], list) else []
            return (
                {
                    "type": "conditionalVariables",
                    "requireTrueVariables": [int(v) for v in first],
                    "requireFalseVariables": [int(v) for v in second],
                    "actions": child_actions,
                },
                unsupported_ids,
                unsupported_actions,
                total_nested,
            )

        if cid in (0x36, 0x41):
            trigger = native_trigger_from_node(node)
            if trigger is not None:
                return (
                    {
                        "type": "conditionalTrigger",
                        "trigger": trigger,
                        "actions": child_actions,
                    },
                    unsupported_ids,
                    unsupported_actions,
                    total_nested,
                )

        if cid == 0x03:
            return (
                {
                    "type": "elseBranch",
                    "actions": child_actions,
                },
                unsupported_ids,
                unsupported_actions,
                total_nested,
            )

        unsupported_ids.append(cid)
        unsupported_actions.append({
            "commandId": cid,
            "params": params,
            "nested": True,
        })
        return (
            None,
            unsupported_ids,
            unsupported_actions,
            total_nested,
        )

    action = native_action_from_node(node)
    if action is None:
        return (
            None,
            [node["commandId"]],
            [{
                "commandId": node["commandId"],
                "params": node["params"],
                "nested": False,
            }],
            0,
        )
    if action["type"] == "noop":
        return None, [], [], 0
    return action, [], [], 0



def compile_native_action_sequence(nodes):
    actions = []
    unsupported_ids = []
    unsupported_actions = []
    nested_count = 0
    i = 0

    while i < len(nodes):
        node = nodes[i]

        # Some 6.5 files store 0x12 as a leaf followed by sibling
        # 0x13 case nodes rather than making the cases children of 0x12.
        if node["commandId"] == 0x12 and not node["children"]:
            params = node["params"]
            raw = (
                params[0].replace("\r", "")
                if params and isinstance(params[0], str)
                else ""
            )
            options = [
                line.strip()
                for line in raw.split("\n")
                if line.strip()
            ]
            cases = []
            j = i + 1

            while j < len(nodes):
                sibling = nodes[j]
                if sibling["commandId"] == 0x01:
                    j += 1
                    continue
                if sibling["commandId"] != 0x13:
                    break

                case_actions = []
                case_value = (
                    int(sibling["params"][0])
                    if sibling["params"]
                    else len(cases) + 1
                )
                if sibling["children"]:
                    nested_count += 1

                for child in sibling["children"]:
                    (
                        child_action,
                        child_unsupported_ids,
                        child_unsupported_actions,
                        child_nested,
                    ) = compile_native_action_tree(child)
                    nested_count += child_nested
                    unsupported_ids.extend(child_unsupported_ids)
                    unsupported_actions.extend(child_unsupported_actions)
                    if child_action is not None:
                        case_actions.append(child_action)

                cases.append({
                    "value": case_value,
                    "actions": case_actions,
                })
                j += 1

            if cases:
                actions.append({
                    "type": "choice",
                    "options": options,
                    "cases": cases,
                })
                i = j
                continue

        (
            action,
            node_unsupported_ids,
            node_unsupported_actions,
            node_nested_count,
        ) = compile_native_action_tree(node)
        nested_count += node_nested_count
        unsupported_ids.extend(node_unsupported_ids)
        unsupported_actions.extend(node_unsupported_actions)
        if action is not None:
            actions.append(action)
        i += 1

    return (
        actions,
        unsupported_ids,
        unsupported_actions,
        nested_count,
    )


def extract_scene2_native_events(scenes, excluded_sections=None):
    if len(scenes) < 2:
        return []

    if excluded_sections is None:
        excluded_sections = {1, 20, 21, 31, 33, 34}
    else:
        excluded_sections = set(excluded_sections)
    trigger_ids = {0x25, 0x26, 0x2E, 0x36, 0x3F, 0x40, 0x41}
    events = []

    for section in scenes[1]["sections"]:
        section_id = section["section"]
        if section_id in excluded_sections:
            continue

        root_nodes = section["commands"]
        body_node = next(
            (
                node for node in root_nodes
                if node["commandId"] == 0 and node["children"]
            ),
            None,
        )
        if body_node is None:
            continue

        require_true = []
        require_false = []
        triggers = []
        unsupported_trigger_ids = []

        for node in root_nodes:
            cid = node["commandId"]
            if cid == 0x05 and len(node["params"]) >= 2:
                first = node["params"][0]
                second = node["params"][1]
                if isinstance(first, list):
                    require_true.extend(int(v) for v in first)
                if isinstance(second, list):
                    require_false.extend(int(v) for v in second)
            elif cid in trigger_ids:
                trigger = native_trigger_from_node(node)
                if trigger is None:
                    unsupported_trigger_ids.append(cid)
                else:
                    triggers.append(trigger)

        if not triggers:
            continue

        (
            actions,
            unsupported_action_ids,
            unsupported_actions,
            nested_branch_count,
        ) = compile_native_action_sequence(body_node["children"])

        core_supported = (
            not unsupported_trigger_ids
            and not unsupported_action_ids
            and bool(actions)
        )

        events.append({
            "scene": 2,
            "section": section_id,
            "requireTrueVariables": sorted(set(require_true)),
            "requireFalseVariables": sorted(set(require_false)),
            "triggers": triggers,
            "actions": actions,
            "coreSupported": core_supported,
            "unsupportedTriggerIds": sorted(set(unsupported_trigger_ids)),
            "unsupportedActionIds": sorted(set(unsupported_action_ids)),
            "unsupportedActions": unsupported_actions,
            "nestedBranchCount": nested_branch_count,
            "nestedSupported": nested_branch_count == 0
            or not unsupported_action_ids,
        })

    return events




def compile_scenario_section_actions(scenes, scene_number, section_number):
    if scene_number < 1 or scene_number > len(scenes):
        return {
            "actions": [],
            "unsupportedActionIds": [],
            "unsupportedActions": [],
            "nestedBranchCount": 0,
            "supported": False,
        }

    scene = scenes[scene_number - 1]
    section = next(
        (
            row for row in scene["sections"]
            if row["section"] == section_number
        ),
        None,
    )
    if section is None:
        return {
            "actions": [],
            "unsupportedActionIds": [],
            "unsupportedActions": [],
            "nestedBranchCount": 0,
            "supported": False,
        }

    body_node = next(
        (
            node for node in section["commands"]
            if node["commandId"] == 0 and node["children"]
        ),
        None,
    )
    if body_node is None:
        return {
            "actions": [],
            "unsupportedActionIds": [],
            "unsupportedActions": [],
            "nestedBranchCount": 0,
            "supported": False,
        }

    (
        actions,
        unsupported_ids,
        unsupported_actions,
        nested_count,
    ) = compile_native_action_sequence(body_node["children"])

    return {
        "scene": scene_number,
        "section": section_number,
        "actions": actions,
        "unsupportedActionIds": sorted(set(unsupported_ids)),
        "unsupportedActions": unsupported_actions,
        "nestedBranchCount": nested_count,
        "supported": bool(actions) and not unsupported_ids,
    }


def extract_s00_outcome_events(scenes):
    return {
        "victory": compile_scenario_section_actions(
            scenes,
            2,
            33,
        ),
        "defeat": compile_scenario_section_actions(
            scenes,
            2,
            34,
        ),
        "postBattle": compile_scenario_section_actions(
            scenes,
            3,
            1,
        ),
    }


def extract_s01_outcome_events(scenes):
    return {
        "victory": compile_scenario_section_actions(
            scenes,
            2,
            53,
        ),
        "defeatByCharacter": {
            "118": compile_scenario_section_actions(
                scenes,
                2,
                37,
            ),
            "0": compile_scenario_section_actions(
                scenes,
                2,
                38,
            ),
            "36": compile_scenario_section_actions(
                scenes,
                2,
                52,
            ),
        },
        "genericDefeat": compile_scenario_section_actions(
            scenes,
            2,
            54,
        ),
        "postBattle": compile_scenario_section_actions(
            scenes,
            3,
            1,
        ),
    }


def extract_s02_outcome_events(scenes):
    return {
        "victory": compile_scenario_section_actions(
            scenes,
            2,
            37,
        ),
        "defeatByCharacter": {
            "26": compile_scenario_section_actions(
                scenes,
                2,
                20,
            ),
        },
        "genericDefeat": compile_scenario_section_actions(
            scenes,
            2,
            38,
        ),
        "postBattle": compile_scenario_section_actions(
            scenes,
            3,
            1,
        ),
    }


def extract_s03_outcome_events(scenes):
    return {
        "victory": compile_scenario_section_actions(
            scenes,
            2,
            30,
        ),
        "defeatByCharacter": {
            "36": compile_scenario_section_actions(
                scenes,
                2,
                24,
            ),
            "0": compile_scenario_section_actions(
                scenes,
                2,
                25,
            ),
        },
        "genericDefeat": compile_scenario_section_actions(
            scenes,
            2,
            31,
        ),
        "postBattle": compile_scenario_section_actions(
            scenes,
            3,
            1,
        ),
    }


def extract_s04_outcome_events(scenes):
    return {
        "victory": compile_scenario_section_actions(
            scenes,
            2,
            39,
        ),
        "defeatByCharacter": {
            "0": compile_scenario_section_actions(
                scenes,
                2,
                11,
            ),
            "1": compile_scenario_section_actions(
                scenes,
                2,
                18,
            ),
            "2": compile_scenario_section_actions(
                scenes,
                2,
                19,
            ),
        },
        "genericDefeat": compile_scenario_section_actions(
            scenes,
            2,
            40,
        ),
        "postBattle": compile_scenario_section_actions(
            scenes,
            3,
            1,
        ),
    }


def extract_s05_outcome_events(scenes):
    return {
        "victory": compile_scenario_section_actions(
            scenes,
            2,
            37,
        ),
        "alternateVictory": compile_scenario_section_actions(
            scenes,
            2,
            43,
        ),
        "defeatByCharacter": {
            "2": compile_scenario_section_actions(
                scenes,
                2,
                17,
            ),
            "1": compile_scenario_section_actions(
                scenes,
                2,
                18,
            ),
            "0": compile_scenario_section_actions(
                scenes,
                2,
                19,
            ),
            "101": compile_scenario_section_actions(
                scenes,
                2,
                22,
            ),
            "148": compile_scenario_section_actions(
                scenes,
                2,
                23,
            ),
        },
        "genericDefeat": compile_scenario_section_actions(
            scenes,
            2,
            44,
        ),
        "postBattle": compile_scenario_section_actions(
            scenes,
            3,
            1,
        ),
    }


def extract_s06_outcome_events(scenes):
    return {
        "victory": compile_scenario_section_actions(
            scenes,
            2,
            13,
        ),
        "alternateVictory": compile_scenario_section_actions(
            scenes,
            2,
            25,
        ),
        "defeatByCharacter": {
            "4": compile_scenario_section_actions(
                scenes,
                2,
                22,
            ),
            "19": compile_scenario_section_actions(
                scenes,
                2,
                23,
            ),
            "148": compile_scenario_section_actions(
                scenes,
                2,
                24,
            ),
        },
        "genericDefeat": compile_scenario_section_actions(
            scenes,
            2,
            26,
        ),
        "postBattle": compile_scenario_section_actions(
            scenes,
            3,
            1,
        ),
    }


def extract_s07_outcome_events(scenes):
    return {
        "victory": compile_scenario_section_actions(
            scenes,
            2,
            55,
        ),
        "defeatByCharacter": {
            "0": compile_scenario_section_actions(
                scenes,
                2,
                35,
            ),
        },
        "genericDefeat": compile_scenario_section_actions(
            scenes,
            2,
            56,
        ),
        "postBattle": compile_scenario_section_actions(
            scenes,
            3,
            1,
        ),
    }


def extract_s08_outcome_events(scenes):
    return {
        "victory": compile_scenario_section_actions(
            scenes,
            2,
            52,
        ),
        "defeatByCharacter": {
            "0": compile_scenario_section_actions(
                scenes,
                2,
                36,
            ),
            "149": compile_scenario_section_actions(
                scenes,
                2,
                37,
            ),
        },
        "genericDefeat": compile_scenario_section_actions(
            scenes,
            2,
            53,
        ),
        "postBattle": compile_scenario_section_actions(
            scenes,
            3,
            1,
        ),
    }


def extract_s09_outcome_events(scenes):
    return {
        "victory": compile_scenario_section_actions(
            scenes,
            2,
            74,
        ),
        "defeatByCharacter": {
            "0": compile_scenario_section_actions(
                scenes,
                2,
                56,
            ),
            "145": compile_scenario_section_actions(
                scenes,
                2,
                57,
            ),
        },
        "genericDefeat": compile_scenario_section_actions(
            scenes,
            2,
            75,
        ),
        "postBattle": compile_scenario_section_actions(
            scenes,
            3,
            1,
        ),
    }



def extract_s00_objective_model(scenes):
    flat = flatten_scenario_nodes(scenes)

    def rows(scene, section, command_id=None, depth=None):
        out = [
            row for row in flat
            if row["scene"] == scene
            and row["section"] == section
            and (command_id is None or row["commandId"] == command_id)
            and (depth is None or row["depth"] == depth)
        ]
        return out

    objective_text_rows = [
        row for row in flat
        if row["commandId"] == 0x19
        and row["params"]
        and isinstance(row["params"][0], str)
    ]
    popup_rows = [
        row for row in flat
        if row["commandId"] == 0x1A
        and row["params"]
        and isinstance(row["params"][0], str)
    ]

    phase1_text = objective_text_rows[0]["params"][0] if objective_text_rows else ""
    phase2_text = objective_text_rows[1]["params"][0] if len(objective_text_rows) > 1 else ""
    phase1_popup = popup_rows[0]["params"][0] if popup_rows else ""
    phase2_popup = popup_rows[1]["params"][0] if len(popup_rows) > 1 else ""

    def turn_limit_from_text(text):
        m = re.search(r"(\d+)턴", text or "")
        return int(m.group(1)) if m else None

    phase1_goal = None
    for row in rows(2, 1, 0x25, 0):
        params = row["params"]
        if len(params) >= 3 and int(params[0]) == 1025:
            phase1_goal = {
                "scopeCode": 1025,
                "scope": "player-or-ally",
                "x": int(params[1]),
                "y": int(params[2]),
            }
            break

    phase2_turn_limit = None
    for row in rows(2, 1, 0x5D, 1):
        params = row["params"]
        if len(params) >= 2:
            phase2_turn_limit = int(params[1])
            break

    protected_character_ids = []
    for section in (20, 21):
        section_rows = rows(2, section, 0x36, 0)
        if section_rows and section_rows[0]["params"]:
            cid = int(section_rows[0]["params"][0])
            if cid not in protected_character_ids:
                protected_character_ids.append(cid)

    village_enemy_goal = None
    for row in rows(2, 31, 0x25, 0):
        params = row["params"]
        if len(params) >= 3 and int(params[0]) == 1026:
            village_enemy_goal = {
                "scopeCode": 1026,
                "scope": "enemy",
                "x": int(params[1]),
                "y": int(params[2]),
            }
            break

    transition_events = []
    section1_body = rows(2, 1, depth=1)
    for row in section1_body:
        cid = row["commandId"]
        params = row["params"]

        if cid == 0x09 and params:
            transition_events.append({
                "type": "delay",
                "value": max(1, int(params[0])),
            })
        elif cid in (0x14, 0x15, 0x16, 0x69, 0x7A):
            strings = [p for p in params if isinstance(p, str)]
            if strings:
                speaker, body = split_dialogue(strings[-1])
                if body or speaker:
                    transition_events.append({
                        "type": "dialogue",
                        "speaker": speaker,
                        "text": body or speaker,
                    })
        elif cid == 0x23 and params:
            transition_events.append({
                "type": "sound",
                "value": int(params[0]),
            })
        elif cid == 0x24 and params:
            transition_events.append({
                "type": "music",
                "value": int(params[0]),
            })
        elif cid == 0x31 and len(params) >= 2 and int(params[0]) == 0:
            transition_events.append({
                "type": "hide",
                "characterId": int(params[1]),
            })
        elif cid == 0x32 and len(params) >= 6 and int(params[0]) != 1:
            transition_events.append({
                "type": "move",
                "characterId": int(params[1]),
                "x": int(params[3]),
                "y": int(params[4]),
                "direction": int(params[5]),
            })
        elif cid == 0x4C and len(params) >= 3 and int(params[0]) == 0:
            transition_events.append({
                "type": "reveal",
                "characterId": int(params[1]),
            })
        elif cid == 0x4F and len(params) >= 6:
            transition_events.append({
                "type": "turn",
                "characterId": int(params[0]),
                "targetId": int(params[1]),
                "direction": int(params[2]),
            })
        elif cid == 0x50 and len(params) >= 2:
            transition_events.append({
                "type": "action",
                "characterId": int(params[0]),
                "value": int(params[1]),
            })
        elif cid == 0x53 and len(params) >= 2 and int(params[0]) != 1:
            transition_events.append({
                "type": "retreat",
                "characterId": int(params[1]),
            })
        elif cid == 0x5D and len(params) >= 2:
            transition_events.append({
                "type": "turnLimit",
                "value": int(params[1]),
            })
        elif cid == 0x19 and params and isinstance(params[0], str):
            transition_events.append({
                "type": "objective",
                "text": params[0],
            })
        elif cid == 0x1A and params and isinstance(params[0], str):
            transition_events.append({
                "type": "objectivePopup",
                "text": params[0],
            })
        elif cid == 0x3D and len(params) >= 4:
            transition_events.append({
                "type": "reward",
                "value": int(params[0]),
                "targetId": int(params[3]),
            })
        elif cid == 0x0B and len(params) >= 2:
            transition_events.append({
                "type": "setVariable",
                "variableId": int(params[0]),
                "value": int(params[1]),
            })

    transition_events.append({"type": "phaseComplete", "value": 2})

    return {
        "source": "S_00.eex",
        "phase1": {
            "objectiveText": phase1_text,
            "popupText": phase1_popup,
            "turnLimit": turn_limit_from_text(phase1_text),
            "goal": phase1_goal,
            "villageFailure": village_enemy_goal,
        },
        "phase2": {
            "objectiveText": phase2_text,
            "popupText": phase2_popup,
            "turnLimit": (
                phase2_turn_limit
                if phase2_turn_limit is not None
                else turn_limit_from_text(phase2_text)
            ),
            "victory": "enemy-annihilation",
        },
        "protectedCharacterIds": protected_character_ids,
        "phase1TransitionEvents": transition_events,
    }



def split_dialogue(raw_text):
    clean = raw_text.replace("\r", "")
    lines = clean.split("\n")
    if lines and lines[0].startswith("&"):
        return lines[0][1:].strip(), "\n".join(lines[1:]).strip()
    return "", clean.strip()


def extract_opening_events(commands):
    events = []
    for cmd_off, cid, params in commands:
        if cmd_off < 5760:
            continue

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

    return events


def extract_deployment_hints(commands):
    """
    Read the first 0x46/0x47 deployment blocks and retain their level/job-level/AI
    fields. v0.8 keeps the already-verified roster/coordinates from v0.7 but uses
    these records to enrich combat panels without guessing deployment levels.
    """
    hints = {}
    seen_friend = False
    seen_enemy = False

    for _off, cid, params in commands:
        if cid == 0x46 and not seen_friend:
            seen_friend = True
            for i in range(20):
                row = params[i * 11:(i + 1) * 11]
                if len(row) != 11:
                    continue
                person, hidden, x, y, direction, level, job_level, ai, target, tx, ty = row
                if not all(isinstance(v, int) for v in row):
                    continue
                if 0 <= person < 1024 and 0 <= x < MAP_WIDTH and 0 <= y < MAP_HEIGHT:
                    hints[(ALLY, person, x, y)] = {
                        "level": int(level),
                        "jobLevel": int(job_level),
                        "ai": int(ai),
                        "hidden": int(hidden),
                        "direction": int(direction),
                    }

        elif cid == 0x47 and not seen_enemy:
            seen_enemy = True
            for i in range(80):
                row = params[i * 12:(i + 1) * 12]
                if len(row) != 12:
                    continue
                (
                    person,
                    reinforcement,
                    hidden,
                    x,
                    y,
                    direction,
                    level,
                    job_level,
                    ai,
                    target,
                    tx,
                    ty,
                ) = row
                if not all(isinstance(v, int) for v in row):
                    continue
                if 0 <= person < 1024 and 0 <= x < MAP_WIDTH and 0 <= y < MAP_HEIGHT:
                    hints[(ENEMY, person, x, y)] = {
                        "level": int(level),
                        "jobLevel": int(job_level),
                        "ai": int(ai),
                        "hidden": int(hidden),
                        "reinforcement": int(reinforcement),
                        "direction": int(direction),
                    }

        if seen_friend and seen_enemy:
            break

    return hints




def compile_r_story_leaf(node):
    cid = node["commandId"]
    params = node["params"]

    if cid == 0x09 and params:
        return {"type": "delay", "value": max(1, int(params[0]))}

    if cid == 0x14 and params and isinstance(params[-1], str):
        speaker, body = split_dialogue(params[-1])
        return {
            "type": "dialogue",
            "speaker": speaker,
            "text": body or speaker,
        }

    if cid in (0x15, 0x16, 0x69, 0x7A):
        strings = [p for p in params if isinstance(p, str)]
        if strings:
            speaker, body = split_dialogue(strings[-1])
            return {
                "type": "dialogue",
                "speaker": speaker,
                "text": body or speaker,
            }

    if cid == 0x17 and params and isinstance(params[0], str):
        return {"type": "storyLocation", "text": params[0]}

    if cid == 0x18 and params and isinstance(params[0], str):
        return {"type": "storyTitle", "text": params[0]}

    if cid == 0x23 and params:
        return {"type": "sound", "value": int(params[0])}

    if cid == 0x24 and params:
        return {"type": "music", "value": int(params[0])}

    if cid == 0x08 and params:
        return {"type": "menu", "enabled": int(params[0]) != 0}

    if cid == 0x1D:
        return {"type": "paletteReset"}

    if cid == 0x3A and len(params) >= 3:
        return {
            "type": "globalValueOp",
            "globalId": int(params[0]),
            "operation": int(params[1]),
            "value": int(params[2]),
        }

    if cid == 0x3D and len(params) >= 4:
        return {
            "type": "reward",
            "value": int(params[0]),
            "targetId": int(params[3]),
        }

    if cid == 0x3B and len(params) >= 3:
        return {
            "type": "joinCharacter",
            "characterId": int(params[0]),
            "joinMode": int(params[1]),
            "levelAdjust": int(params[2]),
        }

    if cid == 0x67:
        strings = [p for p in params if isinstance(p, str)]
        return {
            "type": "storyChapter",
            "value": (
                int(params[0])
                if params and isinstance(params[0], int)
                else 0
            ),
            "text": strings[-1] if strings else "",
        }

    if cid == 0x77 and len(params) >= 5:
        if int(params[0]) == 2 and int(params[3]) == 0:
            return {
                "type": "intVariableOp",
                "variableId": int(params[1]),
                "operation": int(params[2]),
                "value": int(params[4]),
            }

    if cid == 0x0B and len(params) >= 2:
        return {
            "type": "setVariable",
            "variableId": int(params[0]),
            "value": int(params[1]),
        }

    if cid == 0x06:
        return {
            "type": "deploymentLimit",
            "params": [int(v) for v in params if isinstance(v, int)],
        }

    if cid == 0x07:
        return {"type": "deploymentTest"}

    if cid == 0x0D:
        return {"type": "sceneEnd"}

    if cid == 0x27:
        return {
            "type": "storyBackground",
            "params": params,
        }

    if cid == 0x2C:
        strings = [p for p in params if isinstance(p, str)]
        return {
            "type": "storyMapText",
            "text": strings[-1] if strings else "",
            "params": params,
        }

    if cid in (
        0x0A, 0x1C, 0x1E, 0x28, 0x2F,
        0x30, 0x31, 0x32, 0x33, 0x34,
    ):
        return {
            "type": "storyVisual",
            "commandId": cid,
            "params": params,
        }

    if cid == 0x78:
        action = native_action_from_node(node)
        if action is not None:
            return action

    if cid in (0x00, 0x01, 0x02, 0x51):
        return None

    return {
        "type": "storyUnsupported",
        "commandId": cid,
        "params": params,
    }


def compile_r_story_node(node):
    cid = node["commandId"]
    params = node["params"]

    if node["children"]:
        if cid == 0x12:
            raw = ""
            if params and isinstance(params[0], str):
                raw = params[0].replace("\r", "")
            options = [
                line.strip()
                for line in raw.split("\n")
                if line.strip()
            ]
            cases = []
            unsupported = []
            for child in node["children"]:
                if child["commandId"] == 0x01:
                    continue
                if child["commandId"] != 0x13:
                    action, child_unsupported = compile_r_story_node(child)
                    unsupported.extend(child_unsupported)
                    if action is not None:
                        cases.append({
                            "value": len(cases) + 1,
                            "actions": [action],
                        })
                    continue

                case_actions = []
                case_unsupported = []
                for grandchild in child["children"]:
                    action, child_unsupported = compile_r_story_node(grandchild)
                    case_unsupported.extend(child_unsupported)
                    if action is not None:
                        case_actions.append(action)
                unsupported.extend(case_unsupported)
                value = int(child["params"][0]) if child["params"] else len(cases) + 1
                cases.append({
                    "value": value,
                    "actions": case_actions,
                })

            return ({
                "type": "choice",
                "options": options,
                "cases": cases,
            }, unsupported)

        if cid == 0x05 and len(params) >= 2:
            actions = []
            unsupported = []
            for child in node["children"]:
                action, child_unsupported = compile_r_story_node(child)
                unsupported.extend(child_unsupported)
                if action is not None:
                    actions.append(action)
            first = params[0] if isinstance(params[0], list) else []
            second = params[1] if isinstance(params[1], list) else []
            return ({
                "type": "conditionalVariables",
                "requireTrueVariables": [int(v) for v in first],
                "requireFalseVariables": [int(v) for v in second],
                "actions": actions,
            }, unsupported)

        if cid == 0x03:
            actions = []
            unsupported = []
            for child in node["children"]:
                action, child_unsupported = compile_r_story_node(child)
                unsupported.extend(child_unsupported)
                if action is not None:
                    actions.append(action)
            return ({
                "type": "elseBranch",
                "actions": actions,
            }, unsupported)

        if cid == 0x02:
            actions = []
            unsupported = []
            for child in node["children"]:
                action, child_unsupported = compile_r_story_node(child)
                unsupported.extend(child_unsupported)
                if action is not None:
                    actions.append(action)
            return ({
                "type": "sequence",
                "actions": actions,
            }, unsupported)

        # Structural wrappers: preserve child order rather than dropping them.
        actions = []
        unsupported = []
        for child in node["children"]:
            action, child_unsupported = compile_r_story_node(child)
            unsupported.extend(child_unsupported)
            if action is not None:
                actions.append(action)
        leaf = compile_r_story_leaf(node)
        if leaf is not None and leaf.get("type") == "storyUnsupported":
            unsupported.append(cid)
        return ({
            "type": "sequence",
            "actions": actions,
        } if actions else leaf, unsupported)

    leaf = compile_r_story_leaf(node)
    if leaf is None:
        return None, []
    if leaf.get("type") == "storyUnsupported":
        return leaf, [cid]
    return leaf, []


def compile_r_story(
    blob,
    source_name,
    story_scene_count,
    departure_scene_number,
    next_battle,
):
    if blob is None or not blob.startswith(b"EEX"):
        return {
            "source": source_name,
            "supported": False,
            "scenes": [],
            "unsupportedActionIds": [],
            "nextBattle": next_battle,
        }

    scenes = parse_scenario_tree(blob)
    compiled_scenes = []
    unsupported_ids = []

    for scene_number in range(
        1,
        min(story_scene_count, len(scenes)) + 1,
    ):
        scene = scenes[scene_number - 1]
        if not scene["sections"]:
            continue
        section = scene["sections"][0]
        body_node = next(
            (
                node for node in section["commands"]
                if node["commandId"] == 0 and node["children"]
            ),
            None,
        )
        if body_node is None:
            continue

        actions = []
        scene_unsupported = []
        for node in body_node["children"]:
            action, node_unsupported = compile_r_story_node(node)
            scene_unsupported.extend(node_unsupported)
            if action is not None:
                actions.append(action)

        unsupported_ids.extend(scene_unsupported)
        compiled_scenes.append({
            "scene": scene_number,
            "section": 1,
            "kind": "story",
            "actions": actions,
            "unsupportedActionIds": sorted(set(scene_unsupported)),
        })

    if (
        departure_scene_number >= 1
        and departure_scene_number <= len(scenes)
    ):
        scene = scenes[departure_scene_number - 1]
        section = next(
            (s for s in scene["sections"] if s["section"] == 1),
            None,
        )
        if section is not None:
            actions = []
            scene_unsupported = []
            for node in section["commands"]:
                if node["commandId"] == 7:
                    actions.append({"type": "deploymentTest"})
                if node["commandId"] == 0 and node["children"]:
                    for child in node["children"]:
                        action, child_unsupported = compile_r_story_node(
                            child
                        )
                        scene_unsupported.extend(child_unsupported)
                        if action is not None:
                            actions.append(action)

            unsupported_ids.extend(scene_unsupported)
            compiled_scenes.append({
                "scene": departure_scene_number,
                "section": 1,
                "kind": "departure",
                "actions": actions,
                "unsupportedActionIds": sorted(set(scene_unsupported)),
            })

    unsupported_ids = sorted(set(unsupported_ids))
    return {
        "source": source_name,
        "supported": not unsupported_ids and bool(compiled_scenes),
        "unsupportedActionIds": unsupported_ids,
        "sceneCount": len(compiled_scenes),
        "scenes": compiled_scenes,
        "nextBattle": next_battle,
    }


def compile_r01_story(blob):
    return compile_r_story(
        blob,
        "R_01.eex",
        4,
        5,
        "S_01.eex",
    )


def compile_r02_story(blob):
    return compile_r_story(
        blob,
        "R_02.eex",
        10,
        11,
        "S_02.eex",
    )


def compile_r03_story(blob):
    return compile_r_story(
        blob,
        "R_03.eex",
        11,
        12,
        "S_03.eex",
    )


def compile_r05_story(blob):
    return compile_r_story(
        blob,
        "R_05.eex",
        8,
        9,
        "S_05.eex",
    )


def compile_r06_story(blob):
    return compile_r_story(
        blob,
        "R_06.eex",
        13,
        14,
        "S_06.eex",
    )

def compile_r07_story(blob):
    return compile_r_story(
        blob,
        "R_07.eex",
        9,
        10,
        "S_07.eex",
    )

def compile_r08_story(blob):
    return compile_r_story(
        blob,
        "R_08.eex",
        22,
        23,
        "S_08.eex",
    )


def compile_r09_story(blob):
    return compile_r_story(
        blob,
        "R_09.eex",
        17,
        18,
        "S_09.eex",
    )


def extract_r09_departure_players(blob):
    if blob is None or not blob.startswith(b"EEX"):
        return []
    scenes = parse_scenario_tree(blob)
    if len(scenes) < 18:
        return []
    ids = [0]
    seen = {0}
    for section in scenes[17]["sections"]:
        stack = list(section["commands"])
        while stack:
            node = stack.pop()
            if node["commandId"] == 0x06:
                params = node["params"]
                if len(params) >= 3 and int(params[0]) == 1:
                    for value in params[2:]:
                        if (
                            isinstance(value, int)
                            and 0 <= value < 1024
                            and value not in seen
                        ):
                            ids.append(int(value))
                            seen.add(int(value))
            stack.extend(node["children"])
    return ids[:8]




def build_next_scenario_probe(filename, blob):
    if blob is None:
        return {
            "filename": filename,
            "found": False,
        }
    if not blob.startswith(b"EEX"):
        return {
            "filename": filename,
            "found": True,
            "size": len(blob),
            "validEex": False,
        }

    scenes = parse_scenario_tree(blob)
    flat = flatten_scenario_nodes(scenes)

    flow_ids = {
        0x05, 0x06, 0x07, 0x08,
        0x0B, 0x0D, 0x11, 0x12, 0x13,
        0x19, 0x1A,
        0x3A, 0x3D,
        0x44, 0x4A, 0x4B, 0x5A,
        0x77, 0x78,
    }
    text_ids = {
        0x14, 0x15, 0x16, 0x17, 0x18,
        0x19, 0x1A, 0x69, 0x7A,
    }

    flow_commands = []
    text_samples = []

    for row in flat:
        cid = row["commandId"]
        if cid in flow_ids and len(flow_commands) < 80:
            flow_commands.append({
                "scene": row["scene"],
                "section": row["section"],
                "depth": row["depth"],
                "commandId": cid,
                "commandHex": f"0x{cid:02X}",
                "params": row["params"],
            })

        if cid in text_ids and len(text_samples) < 30:
            strings = [
                value for value in row["params"]
                if isinstance(value, str) and value.strip()
            ]
            if strings:
                text_samples.append({
                    "scene": row["scene"],
                    "section": row["section"],
                    "commandId": cid,
                    "commandHex": f"0x{cid:02X}",
                    "text": strings[-1],
                })

    counts = {}
    for row in flat:
        key = f"0x{row['commandId']:02X}"
        counts[key] = counts.get(key, 0) + 1


    route_sections = {}
    if filename.lower() == "r_01.eex":
        for section_number in range(1, 8):
            rows = [
                row for row in flat
                if row["scene"] == 5
                and row["section"] == section_number
            ]
            if not rows:
                continue
            route_sections[f"S05-SEC{section_number:02d}"] = [
                {
                    "depth": row["depth"],
                    "kind": row["kind"],
                    "commandId": row["commandId"],
                    "commandHex": f"0x{row['commandId']:02X}",
                    "params": row["params"],
                    "childCommandIds": row["childCommandIds"],
                }
                for row in rows
            ]

    if filename.lower() == "r_08.eex":
        for section_number in range(1, 7):
            rows = [
                row for row in flat
                if row["scene"] == 23
                and row["section"] == section_number
            ]
            if not rows:
                continue
            route_sections[f"S23-SEC{section_number:02d}"] = [
                {
                    "depth": row["depth"],
                    "kind": row["kind"],
                    "commandId": row["commandId"],
                    "commandHex": f"0x{row['commandId']:02X}",
                    "params": row["params"],
                    "childCommandIds": row["childCommandIds"],
                }
                for row in rows
            ]

    return {
        "filename": filename,
        "found": True,
        "validEex": True,
        "size": len(blob),
        "sceneCount": len(scenes),
        "sectionCounts": [
            len(scene["sections"])
            for scene in scenes
        ],
        "commandCount": len(flat),
        "commandCounts": counts,
        "flowCommands": flow_commands,
        "textSamples": text_samples,
        "routeSections": route_sections,
    }



def jpeg_dimensions(blob):
    if blob is None or len(blob) < 4 or blob[:2] != b"\xff\xd8":
        raise ValueError("invalid JPEG")
    p = 2
    while p + 4 <= len(blob):
        if blob[p] != 0xFF:
            p += 1
            continue
        while p < len(blob) and blob[p] == 0xFF:
            p += 1
        if p >= len(blob):
            break
        marker = blob[p]
        p += 1
        if marker in (0xD8, 0xD9):
            continue
        if p + 2 > len(blob):
            break
        seg_len = int.from_bytes(blob[p:p + 2], "big")
        if seg_len < 2 or p + seg_len > len(blob):
            break
        if marker in (
            0xC0, 0xC1, 0xC2, 0xC3,
            0xC5, 0xC6, 0xC7,
            0xC9, 0xCA, 0xCB,
            0xCD, 0xCE, 0xCF,
        ):
            if seg_len < 7:
                break
            height = int.from_bytes(blob[p + 3:p + 5], "big")
            width = int.from_bytes(blob[p + 5:p + 7], "big")
            return width, height
        p += seg_len
    raise ValueError("JPEG dimensions not found")


def probe_s01_initialization(blob):
    if blob is None or not blob.startswith(b"EEX"):
        return {"found": blob is not None, "validEex": False}

    scenes = parse_scenario_tree(blob)
    if not scenes or not scenes[0]["sections"]:
        return {
            "found": True,
            "validEex": True,
            "sceneCount": len(scenes),
            "error": "scene1/section1 missing",
        }

    section = scenes[0]["sections"][0]
    rows = []
    commands_by_id = {}
    for node in section["commands"]:
        stack = [(node, 0)]
        while stack:
            current, depth = stack.pop()
            cid = current["commandId"]
            if cid in {
                0x19, 0x1A, 0x24, 0x27,
                0x44, 0x45, 0x46, 0x47, 0x48,
                0x4A, 0x4B, 0x5A, 0x5D,
            }:
                row = {
                    "depth": depth,
                    "commandId": cid,
                    "commandHex": f"0x{cid:02X}",
                    "params": current["params"],
                }
                rows.append(row)
                commands_by_id.setdefault(f"0x{cid:02X}", []).append(
                    current["params"]
                )
            for child in reversed(current["children"]):
                stack.append((child, depth + 1))

    friend_records = []
    enemy_records = []
    player_slots = []
    forced_players = []

    for params in commands_by_id.get("0x46", []):
        for i in range(20):
            row = params[i * 11:(i + 1) * 11]
            if len(row) != 11 or not all(isinstance(v, int) for v in row):
                continue
            person, hidden, x, y, direction, level, job_level, ai, target, tx, ty = row
            if 0 <= person < 1024:
                friend_records.append({
                    "person": person,
                    "hidden": hidden,
                    "x": x,
                    "y": y,
                    "direction": direction,
                    "level": level,
                    "jobLevel": job_level,
                    "ai": ai,
                    "target": target,
                    "targetX": tx,
                    "targetY": ty,
                })

    for params in commands_by_id.get("0x47", []):
        for i in range(80):
            row = params[i * 12:(i + 1) * 12]
            if len(row) != 12 or not all(isinstance(v, int) for v in row):
                continue
            (
                person, reinforcement, hidden, x, y, direction,
                level, job_level, ai, target, tx, ty,
            ) = row
            if 0 <= person < 1024:
                enemy_records.append({
                    "person": person,
                    "reinforcement": reinforcement,
                    "hidden": hidden,
                    "x": x,
                    "y": y,
                    "direction": direction,
                    "level": level,
                    "jobLevel": job_level,
                    "ai": ai,
                    "target": target,
                    "targetX": tx,
                    "targetY": ty,
                })

    for params in commands_by_id.get("0x4B", []):
        if len(params) >= 5 and all(isinstance(v, int) for v in params[:5]):
            player_slots.append({
                "slot": int(params[0]),
                "x": int(params[1]),
                "y": int(params[2]),
                "direction": int(params[3]),
                "flag": int(params[4]),
            })

    for params in commands_by_id.get("0x4A", []):
        forced_players.extend(
            int(v) for v in params
            if isinstance(v, int) and v >= 0
        )

    objective_texts = []
    popup_texts = []
    for params in commands_by_id.get("0x19", []):
        if params and isinstance(params[0], str):
            objective_texts.append(params[0])
    for params in commands_by_id.get("0x1A", []):
        if params and isinstance(params[0], str):
            popup_texts.append(params[0])

    return {
        "found": True,
        "validEex": True,
        "sceneCount": len(scenes),
        "sectionCounts": [len(scene["sections"]) for scene in scenes],
        "scene1Section1InitCommands": rows,
        "forcedPlayers": forced_players,
        "playerSlots": player_slots,
        "friendRecords": friend_records,
        "enemyRecords": enemy_records,
        "objectiveTexts": objective_texts,
        "objectivePopups": popup_texts,
        "music": commands_by_id.get("0x24", []),
        "turnLimit": commands_by_id.get("0x5D", []),
        "operationStart": commands_by_id.get("0x5A", []),
    }



def probe_selected_scenario_sections(scenes, selections):
    flat = flatten_scenario_nodes(scenes)
    result = {}
    for scene_number, section_number in selections:
        key = f"S{scene_number:02d}-SEC{section_number:02d}"
        result[key] = [
            {
                "depth": row["depth"],
                "kind": row["kind"],
                "commandId": row["commandId"],
                "commandHex": f"0x{row['commandId']:02X}",
                "params": row["params"],
                "childCommandIds": row["childCommandIds"],
            }
            for row in flat
            if row["scene"] == scene_number
            and row["section"] == section_number
        ]
    return result



def probe_battle_outcome_candidates(scenes):
    flat = flatten_scenario_nodes(scenes)
    section_keys = set()

    for scene in scenes:
        if scene["scene"] != 2:
            continue
        for section in scene["sections"]:
            rows = [
                row for row in flat
                if row["scene"] == 2
                and row["section"] == section["section"]
            ]
            root_ids = {
                row["commandId"]
                for row in rows
                if row["depth"] == 0
            }
            all_ids = {row["commandId"] for row in rows}
            if 0x42 in root_ids or 0x43 in root_ids:
                section_keys.add((2, section["section"]))
                continue
            if 0x36 in root_ids and 0x49 in all_ids:
                section_keys.add((2, section["section"]))

    if len(scenes) >= 3:
        scene3 = scenes[2]
        if scene3["sections"]:
            section_keys.add((3, scene3["sections"][0]["section"]))

    return probe_selected_scenario_sections(
        scenes,
        sorted(section_keys),
    )


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
        atk = game1.read("Unit_atk.e5")
        spc = game1.read("Unit_spc.e5")
        pal = game1.read("Spalet.e5")
        s00 = game1.read("RS/S_00.eex")
        r01 = read_member_by_basename(game1, "R_01.eex")
        s01 = read_member_by_basename(game1, "S_01.eex")
        r02 = read_member_by_basename(game1, "R_02.eex")
        s02 = read_member_by_basename(game1, "S_02.eex")
        r03 = read_member_by_basename(game1, "R_03.eex")
        s03 = read_member_by_basename(game1, "S_03.eex")
        r04 = read_member_by_basename(game1, "R_04.eex")
        s04 = read_member_by_basename(game1, "S_04.eex")
        r05 = read_member_by_basename(game1, "R_05.eex")
        s05 = read_member_by_basename(game1, "S_05.eex")
        r06 = read_member_by_basename(game1, "R_06.eex")
        s06 = read_member_by_basename(game1, "S_06.eex")
        r07 = read_member_by_basename(game1, "R_07.eex")
        s07 = read_member_by_basename(game1, "S_07.eex")
        r08 = read_member_by_basename(game1, "R_08.eex")
        s08 = read_member_by_basename(game1, "S_08.eex")
        r09 = read_member_by_basename(game1, "R_09.eex")
        s09 = read_member_by_basename(game1, "S_09.eex")
        map1_bytes = read_member_by_basename(game2, "m001.jpg")
        map2_bytes = read_member_by_basename(game2, "m002.jpg")
        map3_bytes = read_member_by_basename(game2, "m003.jpg")
        map4_bytes = read_member_by_basename(game2, "m004.jpg")
        map5_bytes = read_member_by_basename(game2, "m005.jpg")
        map6_bytes = read_member_by_basename(game2, "m006.jpg")
        map7_bytes = read_member_by_basename(game2, "m007.jpg")
        map8_bytes = read_member_by_basename(game2, "m008.jpg")
        map9_bytes = read_member_by_basename(game2, "m009.jpg")
        if map1_bytes is None:
            map1_bytes = read_member_by_basename(game1, "m001.jpg")
        if map2_bytes is None:
            map2_bytes = read_member_by_basename(game1, "m002.jpg")
        if map3_bytes is None:
            map3_bytes = read_member_by_basename(game1, "m003.jpg")
        if map4_bytes is None:
            map4_bytes = read_member_by_basename(game1, "m004.jpg")
        if map5_bytes is None:
            map5_bytes = read_member_by_basename(game1, "m005.jpg")
        if map6_bytes is None:
            map6_bytes = read_member_by_basename(game1, "m006.jpg")
        if map7_bytes is None:
            map7_bytes = read_member_by_basename(game1, "m007.jpg")
        if map8_bytes is None:
            map8_bytes = read_member_by_basename(game1, "m008.jpg")
        if map9_bytes is None:
            map9_bytes = read_member_by_basename(game1, "m009.jpg")

        hexz = read_member_by_basename(game2, "Hexzmap.e5")
        if hexz is None:
            hexz = read_member_by_basename(game1, "Hexzmap.e5")

    if hexz is None:
        raise SystemExit("Hexzmap.e5 not found in game1/game2")
    if map1_bytes is None:
        raise SystemExit("m001.jpg not found in game1/game2")
    if map2_bytes is None:
        raise SystemExit("m002.jpg not found in game1/game2")
    if map3_bytes is None:
        raise SystemExit("m003.jpg not found in game1/game2")
    if map4_bytes is None:
        raise SystemExit("m004.jpg not found in game1/game2")
    if map5_bytes is None:
        raise SystemExit("m005.jpg not found in game1/game2")
    if map6_bytes is None:
        raise SystemExit("m006.jpg not found in game1/game2")
    if map7_bytes is None:
        raise SystemExit("m007.jpg not found in game1/game2")
    if map8_bytes is None:
        raise SystemExit("m008.jpg not found in game1/game2")
    if map9_bytes is None:
        raise SystemExit("m009.jpg not found in game1/game2")

    if len(s00) != 31318 or not s00.startswith(b"EEX"):
        raise SystemExit("Unexpected S_00.eex revision")

    terrain_cells = extract_hexzmap_cells(hexz, 0, MAP_WIDTH, MAP_HEIGHT)
    (battle_dir / "terrain0.bin").write_bytes(terrain_cells)

    map1_width, map1_height = jpeg_dimensions(map1_bytes)
    if map1_width % 48 != 0 or map1_height % 48 != 0:
        raise SystemExit(
            f"m001 dimensions not divisible by 48: "
            f"{map1_width}x{map1_height}"
        )
    map1_cols = map1_width // 48
    map1_rows = map1_height // 48
    terrain1_cells = extract_hexzmap_cells(
        hexz,
        1,
        map1_cols,
        map1_rows,
    )
    (map_dir / "m001.jpg").write_bytes(map1_bytes)
    (battle_dir / "terrain1.bin").write_bytes(terrain1_cells)

    map2_width, map2_height = jpeg_dimensions(map2_bytes)
    if map2_width % 48 != 0 or map2_height % 48 != 0:
        raise SystemExit(
            f"m002 dimensions not divisible by 48: "
            f"{map2_width}x{map2_height}"
        )
    map2_cols = map2_width // 48
    map2_rows = map2_height // 48
    terrain2_cells = extract_hexzmap_cells(
        hexz,
        2,
        map2_cols,
        map2_rows,
    )
    (map_dir / "m002.jpg").write_bytes(map2_bytes)
    (battle_dir / "terrain2.bin").write_bytes(terrain2_cells)

    map3_width, map3_height = jpeg_dimensions(map3_bytes)
    if map3_width % 48 != 0 or map3_height % 48 != 0:
        raise SystemExit(
            f"m003 dimensions not divisible by 48: "
            f"{map3_width}x{map3_height}"
        )
    map3_cols = map3_width // 48
    map3_rows = map3_height // 48
    terrain3_cells = extract_hexzmap_cells(
        hexz,
        3,
        map3_cols,
        map3_rows,
    )
    (map_dir / "m003.jpg").write_bytes(map3_bytes)
    (battle_dir / "terrain3.bin").write_bytes(terrain3_cells)

    map4_width, map4_height = jpeg_dimensions(map4_bytes)
    if map4_width % 48 != 0 or map4_height % 48 != 0:
        raise SystemExit(
            f"m004 dimensions not divisible by 48: "
            f"{map4_width}x{map4_height}"
        )
    map4_cols = map4_width // 48
    map4_rows = map4_height // 48
    terrain4_cells = extract_hexzmap_cells(
        hexz,
        4,
        map4_cols,
        map4_rows,
    )
    (map_dir / "m004.jpg").write_bytes(map4_bytes)
    (battle_dir / "terrain4.bin").write_bytes(terrain4_cells)

    map5_width, map5_height = jpeg_dimensions(map5_bytes)
    if map5_width % 48 != 0 or map5_height % 48 != 0:
        raise SystemExit(
            f"m005 dimensions not divisible by 48: "
            f"{map5_width}x{map5_height}"
        )
    map5_cols = map5_width // 48
    map5_rows = map5_height // 48
    terrain5_cells = extract_hexzmap_cells(
        hexz,
        5,
        map5_cols,
        map5_rows,
    )
    (map_dir / "m005.jpg").write_bytes(map5_bytes)
    (battle_dir / "terrain5.bin").write_bytes(terrain5_cells)

    map6_width, map6_height = jpeg_dimensions(map6_bytes)
    if map6_width % 48 != 0 or map6_height % 48 != 0:
        raise SystemExit(
            f"m006 dimensions not divisible by 48: "
            f"{map6_width}x{map6_height}"
        )
    map6_cols = map6_width // 48
    map6_rows = map6_height // 48
    terrain6_cells = extract_hexzmap_cells(
        hexz,
        6,
        map6_cols,
        map6_rows,
    )
    (map_dir / "m006.jpg").write_bytes(map6_bytes)
    (battle_dir / "terrain6.bin").write_bytes(terrain6_cells)

    map7_width, map7_height = jpeg_dimensions(map7_bytes)
    if map7_width % 48 != 0 or map7_height % 48 != 0:
        raise SystemExit(
            f"m007 dimensions not divisible by 48: "
            f"{map7_width}x{map7_height}"
        )
    map7_cols = map7_width // 48
    map7_rows = map7_height // 48
    terrain7_cells = extract_hexzmap_cells(
        hexz,
        7,
        map7_cols,
        map7_rows,
    )
    (map_dir / "m007.jpg").write_bytes(map7_bytes)
    (battle_dir / "terrain7.bin").write_bytes(terrain7_cells)

    map8_width, map8_height = jpeg_dimensions(map8_bytes)
    if map8_width % 48 != 0 or map8_height % 48 != 0:
        raise SystemExit(
            f"m008 dimensions not divisible by 48: "
            f"{map8_width}x{map8_height}"
        )
    map8_cols = map8_width // 48
    map8_rows = map8_height // 48
    terrain8_cells = extract_hexzmap_cells(
        hexz,
        8,
        map8_cols,
        map8_rows,
    )
    (map_dir / "m008.jpg").write_bytes(map8_bytes)
    (battle_dir / "terrain8.bin").write_bytes(terrain8_cells)

    map9_width, map9_height = jpeg_dimensions(map9_bytes)
    if map9_width % 48 != 0 or map9_height % 48 != 0:
        raise SystemExit(
            f"m009 dimensions not divisible by 48: "
            f"{map9_width}x{map9_height}"
        )
    map9_cols = map9_width // 48
    map9_rows = map9_height // 48
    terrain9_cells = extract_hexzmap_cells(
        hexz,
        9,
        map9_cols,
        map9_rows,
    )
    (map_dir / "m009.jpg").write_bytes(map9_bytes)
    (battle_dir / "terrain9.bin").write_bytes(terrain9_cells)

    terrain_power_blob = bytearray()
    move_cost_blob = bytearray()
    for family in range(JOB_FAMILY_COUNT):
        power_start = TERRAIN_POWER_BASE + family * JOB_FAMILY_STRIDE
        move_start = MOVE_COST_BASE + family * JOB_FAMILY_STRIDE
        power_row = data[power_start:power_start + TERRAIN_TYPE_COUNT]
        move_row = data[move_start:move_start + TERRAIN_TYPE_COUNT]
        if len(power_row) != TERRAIN_TYPE_COUNT:
            raise SystemExit(f"terrain power row truncated: family={family}")
        if len(move_row) != TERRAIN_TYPE_COUNT:
            raise SystemExit(f"movement cost row truncated: family={family}")
        terrain_power_blob.extend(power_row)
        move_cost_blob.extend(move_row)
    (battle_dir / "terrain_power.bin").write_bytes(terrain_power_blob)
    (battle_dir / "movement_costs.bin").write_bytes(move_cost_blob)

    restraint_blob = exe[
        JOB_RESTRAINT_BASE:
        JOB_RESTRAINT_BASE + JOB_FAMILY_COUNT * JOB_RESTRAINT_STRIDE
    ]
    if len(restraint_blob) != JOB_FAMILY_COUNT * JOB_RESTRAINT_STRIDE:
        raise SystemExit("job restraint matrix is truncated")
    (battle_dir / "job_restraint.bin").write_bytes(restraint_blob)

    scenario_scenes = parse_scenario_tree(s00)
    scenario_diagnostics = build_scenario_diagnostics(scenario_scenes)
    objective_model = extract_s00_objective_model(scenario_scenes)
    native_scene2_events = extract_scene2_native_events(scenario_scenes)
    outcome_events = extract_s00_outcome_events(scenario_scenes)
    next_scenario_probe = {
        "R_01.eex": build_next_scenario_probe("R_01.eex", r01),
        "S_01.eex": build_next_scenario_probe("S_01.eex", s01),
    }
    r01_story = compile_r01_story(r01)
    s01_scenes = parse_scenario_tree(s01)
    s01_native_events = extract_scene2_native_events(
        s01_scenes,
        excluded_sections={37, 38, 52, 53, 54},
    )
    s01_outcome_probe = probe_selected_scenario_sections(
        s01_scenes,
        [
            (2, 37),
            (2, 38),
            (2, 52),
            (2, 53),
            (2, 54),
            (3, 1),
        ],
    )
    r02_probe = build_next_scenario_probe("R_02.eex", r02)
    s02_probe = build_next_scenario_probe("S_02.eex", s02)
    r02_story = compile_r02_story(r02)
    s01_outcome_events = extract_s01_outcome_events(s01_scenes)
    s01_init_probe = probe_s01_initialization(s01)
    s01_init_probe["map"] = {
        "filename": "m001.jpg",
        "width": map1_width,
        "height": map1_height,
        "cols": map1_cols,
        "rows": map1_rows,
        "terrainCellCount": len(terrain1_cells),
        "terrainIds": sorted(set(terrain1_cells)),
        "hexzmapEntry": 1,
    }
    s02_scenes = parse_scenario_tree(s02)
    s02_native_events = extract_scene2_native_events(s02_scenes)
    s02_outcome_probe = probe_selected_scenario_sections(
        s02_scenes,
        [
            (2, 20),
            (2, 21),
            (2, 31),
            (2, 33),
            (2, 34),
            (2, 37),
            (2, 38),
            (3, 1),
        ],
    )
    r03_probe = build_next_scenario_probe("R_03.eex", r03)
    s03_probe = build_next_scenario_probe("S_03.eex", s03)
    r03_story = compile_r03_story(r03)
    s02_outcome_events = extract_s02_outcome_events(s02_scenes)
    s02_init_probe = probe_s01_initialization(s02)
    s02_init_probe["map"] = {
        "filename": "m002.jpg",
        "width": map2_width,
        "height": map2_height,
        "cols": map2_cols,
        "rows": map2_rows,
        "terrainCellCount": len(terrain2_cells),
        "terrainIds": sorted(set(terrain2_cells)),
        "hexzmapEntry": 2,
    }

    s03_scenes = parse_scenario_tree(s03)
    s03_init_probe = probe_s01_initialization(s03)
    s03_init_probe["map"] = {
        "filename": "m003.jpg",
        "width": map3_width,
        "height": map3_height,
        "cols": map3_cols,
        "rows": map3_rows,
        "terrainCellCount": len(terrain3_cells),
        "terrainIds": sorted(set(terrain3_cells)),
        "hexzmapEntry": 3,
    }
    s03_event_probe = extract_scene2_native_events(s03_scenes)
    s03_native_events = extract_scene2_native_events(
        s03_scenes,
        excluded_sections={24, 25, 30, 31},
    )
    s03_outcome_events = extract_s03_outcome_events(s03_scenes)
    s03_outcome_probe = probe_battle_outcome_candidates(s03_scenes)
    r04_probe = build_next_scenario_probe("R_04.eex", r04)
    s04_probe = build_next_scenario_probe("S_04.eex", s04)

    s04_scenes = parse_scenario_tree(s04)
    s04_init_probe = probe_s01_initialization(s04)
    s04_init_probe["map"] = {
        "filename": "m004.jpg",
        "width": map4_width,
        "height": map4_height,
        "cols": map4_cols,
        "rows": map4_rows,
        "terrainCellCount": len(terrain4_cells),
        "terrainIds": sorted(set(terrain4_cells)),
        "hexzmapEntry": 4,
    }
    s04_event_probe = extract_scene2_native_events(s04_scenes)
    s04_outcome_probe = probe_battle_outcome_candidates(s04_scenes)
    s04_native_events = extract_scene2_native_events(
        s04_scenes,
        excluded_sections={11, 18, 19, 39, 40},
    )
    s04_outcome_events = extract_s04_outcome_events(s04_scenes)
    r05_probe = build_next_scenario_probe("R_05.eex", r05)
    s05_probe = build_next_scenario_probe("S_05.eex", s05)
    r05_story = compile_r05_story(r05)
    s05_scenes = parse_scenario_tree(s05)
    s05_init_probe = probe_s01_initialization(s05)
    s05_init_probe["map"] = {
        "filename": "m005.jpg",
        "width": map5_width,
        "height": map5_height,
        "cols": map5_cols,
        "rows": map5_rows,
        "terrainCellCount": len(terrain5_cells),
        "terrainIds": sorted(set(terrain5_cells)),
        "hexzmapEntry": 5,
    }
    s05_event_probe = extract_scene2_native_events(s05_scenes)
    s05_outcome_probe = probe_battle_outcome_candidates(s05_scenes)
    s05_native_events = extract_scene2_native_events(
        s05_scenes,
        excluded_sections={17, 18, 19, 22, 23, 37, 43, 44},
    )
    s05_outcome_events = extract_s05_outcome_events(s05_scenes)
    r06_probe = build_next_scenario_probe("R_06.eex", r06)
    s06_probe = build_next_scenario_probe("S_06.eex", s06)
    r06_story = compile_r06_story(r06)

    s06_scenes = parse_scenario_tree(s06)
    s06_init_probe = probe_s01_initialization(s06)
    s06_init_probe["map"] = {
        "filename": "m006.jpg",
        "width": map6_width,
        "height": map6_height,
        "cols": map6_cols,
        "rows": map6_rows,
        "terrainCellCount": len(terrain6_cells),
        "terrainIds": sorted(set(terrain6_cells)),
        "hexzmapEntry": 6,
    }
    s06_event_probe = extract_scene2_native_events(s06_scenes)
    s06_outcome_probe = probe_battle_outcome_candidates(s06_scenes)
    s06_native_events = extract_scene2_native_events(
        s06_scenes,
        excluded_sections={13, 22, 23, 24, 25, 26},
    )
    s06_outcome_events = extract_s06_outcome_events(s06_scenes)
    r07_probe = build_next_scenario_probe("R_07.eex", r07)
    s07_probe = build_next_scenario_probe("S_07.eex", s07)

    r07_story = compile_r07_story(r07)
    s07_scenes = parse_scenario_tree(s07)
    s07_init_probe = probe_s01_initialization(s07)
    s07_init_probe["map"] = {
        "filename": "m007.jpg",
        "width": map7_width,
        "height": map7_height,
        "cols": map7_cols,
        "rows": map7_rows,
        "terrainCellCount": len(terrain7_cells),
        "terrainIds": sorted(set(terrain7_cells)),
        "hexzmapEntry": 7,
    }
    s07_event_probe = extract_scene2_native_events(s07_scenes)
    s07_outcome_probe = probe_battle_outcome_candidates(s07_scenes)

    s07_native_events = extract_scene2_native_events(
        s07_scenes,
        excluded_sections={35, 55, 56},
    )
    s07_outcome_events = extract_s07_outcome_events(s07_scenes)
    r08_probe = build_next_scenario_probe("R_08.eex", r08)
    s08_probe = build_next_scenario_probe("S_08.eex", s08)

    r08_story = compile_r08_story(r08)
    s08_scenes = parse_scenario_tree(s08)
    s08_init_probe = probe_s01_initialization(s08)
    s08_init_probe["map"] = {
        "filename": "m008.jpg",
        "width": map8_width,
        "height": map8_height,
        "cols": map8_cols,
        "rows": map8_rows,
        "terrainCellCount": len(terrain8_cells),
        "terrainIds": sorted(set(terrain8_cells)),
        "hexzmapEntry": 8,
    }
    s08_event_probe = extract_scene2_native_events(s08_scenes)
    s08_outcome_probe = probe_battle_outcome_candidates(s08_scenes)
    s08_native_events = extract_scene2_native_events(
        s08_scenes,
        excluded_sections={36, 37, 52, 53},
    )
    s08_outcome_events = extract_s08_outcome_events(s08_scenes)
    r09_probe = build_next_scenario_probe("R_09.eex", r09)
    s09_probe = build_next_scenario_probe("S_09.eex", s09)

    s09_scenes = parse_scenario_tree(s09)
    s09_init_probe = probe_s01_initialization(s09)
    s09_init_probe["map"] = {
        "filename": "m009.jpg",
        "width": map9_width,
        "height": map9_height,
        "cols": map9_cols,
        "rows": map9_rows,
        "terrainCellCount": len(terrain9_cells),
        "terrainIds": sorted(set(terrain9_cells)),
        "hexzmapEntry": 9,
    }
    r09_story = compile_r09_story(r09)
    r09_player_ids = extract_r09_departure_players(r09)
    s09_event_probe = extract_scene2_native_events(
        s09_scenes,
        excluded_sections={56, 57, 74, 75},
    )
    s09_native_events = s09_event_probe
    s09_outcome_probe = probe_battle_outcome_candidates(s09_scenes)
    s09_outcome_events = extract_s09_outcome_events(s09_scenes)
    s09_goal_probe = probe_selected_scenario_sections(
        s09_scenes,
        [
            (2, 1),
            (2, 20),
            (2, 21),
            (2, 31),
            (2, 33),
            (2, 34),
            (2, 51),
        ],
    )

    scene0 = int.from_bytes(s00[10:14], "little")
    section_count = u16(s00, scene0)
    if section_count < 1:
        raise SystemExit("S_00 scene0 has no sections")
    section_len = u16(s00, scene0 + 2)
    sec = s00[scene0 + 4:scene0 + 4 + section_len]
    commands = scan_commands(sec)
    events = extract_opening_events(commands)
    deployment_hints = extract_deployment_hints(commands)

    def character_row(cid):
        off = 0x18C + cid * 0x20
        if off < 0 or off + 0x20 > len(data):
            raise ValueError(f"character out of Data.e5 range: {cid}")
        return off

    def name_of(cid):
        row = character_row(cid)
        raw = data[row:row + 13].split(b"\0", 1)[0]
        return raw.decode("cp949", "replace").strip() or f"인물{cid}"

    def job_profile_of(cid):
        row = character_row(cid)
        job_id = data[row + 26]
        family = detailed_job_to_family(job_id)
        growth_off = JOB_GROWTH_BASE + job_id * JOB_GROWTH_STRIDE
        if growth_off + JOB_GROWTH_STRIDE > len(data):
            raise ValueError(f"job growth row out of range: {job_id}")
        growth = data[growth_off:growth_off + JOB_GROWTH_STRIDE]
        return {
            "jobId": job_id,
            "jobFamily": family,
            "movePoints": int(growth[0]),
            "attackRangeId": int(growth[1]),
            "growthAttack": int(growth[2]),
            "growthDefense": int(growth[3]),
            "growthSpirit": int(growth[4]),
            "growthBurst": int(growth[5]),
            "growthMorale": int(growth[6]),
            "growthHp": int(growth[7]),
            "growthMp": int(growth[8]),
        }

    def combat_profile_of(cid, deploy_level):
        row = character_row(cid)
        job = job_profile_of(cid)

        force = int(data[row + 18])
        command = int(data[row + 19])
        intelligence = int(data[row + 20])
        agility = int(data[row + 21])
        morale = int(data[row + 22])
        initial_hp = int(u16(data, row + 23))
        initial_mp = int(data[row + 25])
        base_level = max(1, int(data[row + 27]))

        level = int(deploy_level) if isinstance(deploy_level, int) and deploy_level > 0 else base_level
        level = max(1, min(level, 99))

        # The classic 6.x panel bridge: base five-stat contribution plus
        # detailed-job per-level growth. We preserve every source component
        # in JSON so later live-memory verification can replace this formula.
        attack_value = force // 2 + job["growthAttack"] * level
        defense_value = command // 2 + job["growthDefense"] * level
        spirit_value = intelligence // 2 + job["growthSpirit"] * level
        burst_value = agility // 2 + job["growthBurst"] * level
        morale_value = morale // 2 + job["growthMorale"] * level
        hp_max = max(
            1,
            initial_hp + job["growthHp"] * max(0, level - base_level),
        )

        return {
            **job,
            "level": level,
            "baseLevel": base_level,
            "force": force,
            "command": command,
            "intelligence": intelligence,
            "agility": agility,
            "morale": morale,
            "initialHp": initial_hp,
            "initialMp": initial_mp,
            "attack": attack_value,
            "defense": defense_value,
            "spirit": spirit_value,
            "burst": burst_value,
            "moralePanel": morale_value,
            "hpMax": hp_max,
        }

    def sprite_of(cid):
        off = 0xD2800 + cid * 2
        return int.from_bytes(exe[off:off + 2], "little")

    def sprite_record_valid(sid):
        expected_mov = 48 * 48 * 11
        expected_atk = 64 * 64 * 12
        expected_spc = 48 * 48 * 5
        md = be_desc(mov, sid)
        ad = be_desc(atk, sid)
        sd = be_desc(spc, sid)
        return (
            md is not None and ad is not None and sd is not None
            and md[0] == expected_mov and md[1] >= expected_mov
            and ad[0] == expected_atk and ad[1] >= expected_atk
            and sd[0] == expected_spc and sd[1] >= expected_spc
        )

    # v0.7-verified initial roster/positions. v0.8 enriches these with the
    # level/job-level/AI values read from the real 0x46/0x47 blocks above.
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

    def deployment_hint(faction, cid, x, y):
        exact = deployment_hints.get((faction, cid, x, y))
        if exact is not None:
            return exact
        for (side, person, _x, _y), hint in deployment_hints.items():
            if side == faction and person == cid:
                return hint
        return {}

    def append_unit(cid, faction, scripted, x, y, direction, source):
        sid = sprite_of(cid)
        if not sprite_record_valid(sid):
            print(f"skip actor {cid}: invalid sprite {sid}")
            return False

        hint = deployment_hint(faction, cid, x, y)
        profile = combat_profile_of(cid, hint.get("level"))
        units.append({
            "characterId": cid,
            "name": name_of(cid),
            "spriteId": sid,
            **profile,
            "deployLevel": hint.get("level"),
            "deployJobLevel": hint.get("jobLevel"),
            "aiPolicy": hint.get("ai"),
            "reinforcement": bool(hint.get("reinforcement", 0)),
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


    # S_01 second battle: keep the playable party from S_00/R_01
    # in slot order, then use the verified 0x46/0x47 deployment records.
    s01_units = []

    def make_s01_unit(
        cid,
        faction,
        hidden,
        x,
        y,
        direction,
        deploy_level,
        deploy_job_level,
        ai_policy,
        reinforcement,
        source,
    ):
        sid = sprite_of(cid)
        if not sprite_record_valid(sid):
            print(f"skip S01 actor {cid}: invalid sprite {sid}")
            return False

        profile = combat_profile_of(cid, deploy_level)
        s01_units.append({
            "characterId": cid,
            "name": name_of(cid),
            "spriteId": sid,
            **profile,
            "deployLevel": deploy_level,
            "deployJobLevel": deploy_job_level,
            "aiPolicy": ai_policy,
            "reinforcement": bool(reinforcement),
            "faction": faction,
            "scripted": bool(hidden),
            "visible": not bool(hidden),
            "x": int(x),
            "y": int(y),
            "direction": int(direction),
            "source": source,
        })
        return True

    continuing_party = [cid for cid, _x, _y, _direction in player]
    for slot in sorted(
        s01_init_probe["playerSlots"],
        key=lambda row: row["slot"],
    ):
        slot_index = int(slot["slot"])
        if slot_index < 0 or slot_index >= len(continuing_party):
            continue
        cid = continuing_party[slot_index]
        make_s01_unit(
            cid,
            PLAYER,
            False,
            slot["x"],
            slot["y"],
            slot["direction"],
            None,
            None,
            0,
            False,
            f"S_01:0x4B:{slot_index}",
        )

    for index, row in enumerate(s01_init_probe["friendRecords"]):
        make_s01_unit(
            row["person"],
            ALLY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            False,
            f"S_01:0x46:{index}",
        )

    for index, row in enumerate(s01_init_probe["enemyRecords"]):
        make_s01_unit(
            row["person"],
            ENEMY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            row["reinforcement"] != 0,
            f"S_01:0x47:{index}",
        )

    s01_objective_text = (
        s01_init_probe["objectiveTexts"][0]
        if s01_init_probe["objectiveTexts"]
        else ""
    )
    s01_popup_text = (
        s01_init_probe["objectivePopups"][0]
        if s01_init_probe["objectivePopups"]
        else ""
    )
    s01_turn_match = re.search(r"(\d+)턴", s01_objective_text)
    s01_turn_limit = int(s01_turn_match.group(1)) if s01_turn_match else 20

    s01_battle = {
        "version": 29,
        "source": "RS/S_01.eex",
        "battleMode": "enemy-annihilation",
        "mapId": 1,
        "map": "m001.jpg",
        "widthTiles": map1_cols,
        "heightTiles": map1_rows,
        "terrainFile": "terrain1.bin",
        "terrainTypeCount": TERRAIN_TYPE_COUNT,
        "movementCostFile": "movement_costs.bin",
        "movementCostFamilyCount": JOB_FAMILY_COUNT,
        "terrainPowerFile": "terrain_power.bin",
        "jobRestraintFile": "job_restraint.bin",
        "battleObjectives": {
            "phase1": {
                "objectiveText": s01_objective_text,
                "popupText": s01_popup_text,
                "turnLimit": s01_turn_limit,
            },
            "phase2": {
                "objectiveText": "",
                "popupText": "",
                "turnLimit": s01_turn_limit,
            },
            "protectedCharacterIds": [0, 118],
            "phase1TransitionEvents": [],
        },
        "battleEvents": s01_native_events,
        "outcomeEvents": s01_outcome_events,
        "r02Story": r02_story,
        "s02InitProbe": s02_init_probe,
        "outcomeProbe": s01_outcome_probe,
        "nextScenarioProbe": {
            "R_02.eex": r02_probe,
            "S_02.eex": s02_probe,
        },
        "battleEventSummary": {
            "candidateCount": len(s01_native_events),
            "coreSupportedCount": sum(
                1 for event in s01_native_events
                if event["coreSupported"]
            ),
            "sections": [
                {
                    "section": event["section"],
                    "coreSupported": event["coreSupported"],
                    "unsupportedTriggerIds": event["unsupportedTriggerIds"],
                    "unsupportedActionIds": event["unsupportedActionIds"],
                    "unsupportedActions": event["unsupportedActions"],
                    "nestedBranchCount": event["nestedBranchCount"],
                    "nestedSupported": event["nestedSupported"],
                }
                for event in s01_native_events
            ],
        },
        "terrainIds": sorted(set(terrain1_cells)),
        "combatModel": COMBAT_MODEL,
        "damageModel": DAMAGE_MODEL,
        "supportedAttackRangeIds": [0, 1],
        "units": s01_units,
        "openingEvents": [],
    }
    (battle_dir / "battle1.json").write_text(
        json.dumps(s01_battle, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    # S_02 third battle: reuse the continuing three-player party,
    # then apply the original 0x46/0x47 deployment table.
    s02_units = []

    def make_s02_unit(
        cid,
        faction,
        hidden,
        x,
        y,
        direction,
        deploy_level,
        deploy_job_level,
        ai_policy,
        reinforcement,
        source,
    ):
        sid = sprite_of(cid)
        if not sprite_record_valid(sid):
            print(f"skip S02 actor {cid}: invalid sprite {sid}")
            return False

        profile = combat_profile_of(cid, deploy_level)
        s02_units.append({
            "characterId": cid,
            "name": name_of(cid),
            "spriteId": sid,
            **profile,
            "deployLevel": deploy_level,
            "deployJobLevel": deploy_job_level,
            "aiPolicy": ai_policy,
            "reinforcement": bool(reinforcement),
            "faction": faction,
            "scripted": bool(hidden),
            "visible": not bool(hidden),
            "x": int(x),
            "y": int(y),
            "direction": int(direction),
            "source": source,
        })
        return True

    for slot in sorted(
        s02_init_probe["playerSlots"],
        key=lambda row: row["slot"],
    ):
        slot_index = int(slot["slot"])
        if slot_index < 0 or slot_index >= len(continuing_party):
            continue
        cid = continuing_party[slot_index]
        make_s02_unit(
            cid,
            PLAYER,
            False,
            slot["x"],
            slot["y"],
            slot["direction"],
            None,
            None,
            0,
            False,
            f"S_02:0x4B:{slot_index}",
        )

    for index, row in enumerate(s02_init_probe["friendRecords"]):
        make_s02_unit(
            row["person"],
            ALLY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            False,
            f"S_02:0x46:{index}",
        )

    for index, row in enumerate(s02_init_probe["enemyRecords"]):
        make_s02_unit(
            row["person"],
            ENEMY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            row["reinforcement"] != 0,
            f"S_02:0x47:{index}",
        )

    s02_objective_text = (
        s02_init_probe["objectiveTexts"][0]
        if s02_init_probe["objectiveTexts"]
        else ""
    )
    s02_popup_text = (
        s02_init_probe["objectivePopups"][0]
        if s02_init_probe["objectivePopups"]
        else ""
    )
    s02_turn_match = re.search(r"(\d+)턴", s02_objective_text)
    s02_turn_limit = int(s02_turn_match.group(1)) if s02_turn_match else 20

    protected_names = {"유비", "공손찬", "간옹"}
    protected_candidates = set(continuing_party)
    protected_candidates.update(
        row["person"]
        for row in s02_init_probe["friendRecords"]
    )
    s02_protected_ids = sorted(
        cid
        for cid in protected_candidates
        if name_of(cid) in protected_names
    )
    if {name_of(cid) for cid in s02_protected_ids} != protected_names:
        raise SystemExit(
            "S02 protected-name mapping incomplete: "
            + repr([
                (cid, name_of(cid))
                for cid in sorted(protected_candidates)
                if name_of(cid) in protected_names
            ])
        )

    s02_battle = {
        "version": 31,
        "source": "RS/S_02.eex",
        "battleMode": "enemy-annihilation",
        "mapId": 2,
        "map": "m002.jpg",
        "widthTiles": map2_cols,
        "heightTiles": map2_rows,
        "terrainFile": "terrain2.bin",
        "terrainTypeCount": TERRAIN_TYPE_COUNT,
        "movementCostFile": "movement_costs.bin",
        "movementCostFamilyCount": JOB_FAMILY_COUNT,
        "terrainPowerFile": "terrain_power.bin",
        "jobRestraintFile": "job_restraint.bin",
        "battleObjectives": {
            "phase1": {
                "objectiveText": s02_objective_text,
                "popupText": s02_popup_text,
                "turnLimit": s02_turn_limit,
            },
            "phase2": {
                "objectiveText": "",
                "popupText": "",
                "turnLimit": s02_turn_limit,
            },
            "protectedCharacterIds": s02_protected_ids,
            "protectedCharacters": [
                {
                    "characterId": cid,
                    "name": name_of(cid),
                }
                for cid in s02_protected_ids
            ],
            "phase1TransitionEvents": [],
        },
        "battleEvents": s02_native_events,
        "outcomeEvents": s02_outcome_events,
        "r03Story": r03_story,
        "outcomeProbe": s02_outcome_probe,
        "nextScenarioProbe": {
            "R_03.eex": r03_probe,
            "S_03.eex": s03_probe,
        },
        "s03InitProbe": s03_init_probe,
        "s03EventProbe": {
            "candidateCount": len(s03_event_probe),
            "coreSupportedCount": sum(
                1 for event in s03_event_probe
                if event["coreSupported"]
            ),
            "sections": [
                {
                    "section": event["section"],
                    "coreSupported": event["coreSupported"],
                    "unsupportedTriggerIds": event["unsupportedTriggerIds"],
                    "unsupportedActionIds": event["unsupportedActionIds"],
                    "unsupportedActions": event["unsupportedActions"],
                    "nestedBranchCount": event["nestedBranchCount"],
                    "nestedSupported": event["nestedSupported"],
                }
                for event in s03_event_probe
            ],
        },
        "s03OutcomeProbe": s03_outcome_probe,
        "battleEventSummary": {
            "candidateCount": len(s02_native_events),
            "coreSupportedCount": sum(
                1 for event in s02_native_events
                if event["coreSupported"]
            ),
            "sections": [
                {
                    "section": event["section"],
                    "coreSupported": event["coreSupported"],
                    "unsupportedTriggerIds": event["unsupportedTriggerIds"],
                    "unsupportedActionIds": event["unsupportedActionIds"],
                    "unsupportedActions": event["unsupportedActions"],
                    "nestedBranchCount": event["nestedBranchCount"],
                    "nestedSupported": event["nestedSupported"],
                }
                for event in s02_native_events
            ],
        },
        "terrainIds": sorted(set(terrain2_cells)),
        "combatModel": COMBAT_MODEL,
        "damageModel": DAMAGE_MODEL,
        "supportedAttackRangeIds": [0, 1],
        "units": s02_units,
        "openingEvents": [],
    }

    (battle_dir / "battle2.json").write_text(
        json.dumps(s02_battle, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    # S_03 fourth battle: rescue Sun Jian while preserving the verified
    # original slots, allies, enemies and event tree.
    s03_units = []

    def make_s03_unit(
        cid,
        faction,
        hidden,
        x,
        y,
        direction,
        deploy_level,
        deploy_job_level,
        ai_policy,
        reinforcement,
        source,
    ):
        sid = sprite_of(cid)
        if not sprite_record_valid(sid):
            print(f"skip S03 actor {cid}: invalid sprite {sid}")
            return False

        profile = combat_profile_of(cid, deploy_level)
        s03_units.append({
            "characterId": cid,
            "name": name_of(cid),
            "spriteId": sid,
            **profile,
            "deployLevel": deploy_level,
            "deployJobLevel": deploy_job_level,
            "aiPolicy": ai_policy,
            "reinforcement": bool(reinforcement),
            "faction": faction,
            "scripted": bool(hidden),
            "visible": not bool(hidden),
            "x": int(x),
            "y": int(y),
            "direction": int(direction),
            "source": source,
        })
        return True

    for slot in sorted(
        s03_init_probe["playerSlots"],
        key=lambda row: row["slot"],
    ):
        slot_index = int(slot["slot"])
        if slot_index < 0 or slot_index >= len(continuing_party):
            continue
        cid = continuing_party[slot_index]
        make_s03_unit(
            cid,
            PLAYER,
            False,
            slot["x"],
            slot["y"],
            slot["direction"],
            None,
            None,
            0,
            False,
            f"S_03:0x4B:{slot_index}",
        )

    for index, row in enumerate(s03_init_probe["friendRecords"]):
        make_s03_unit(
            row["person"],
            ALLY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            False,
            f"S_03:0x46:{index}",
        )

    for index, row in enumerate(s03_init_probe["enemyRecords"]):
        make_s03_unit(
            row["person"],
            ENEMY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            row["reinforcement"] != 0,
            f"S_03:0x47:{index}",
        )

    s03_objective_text = (
        s03_init_probe["objectiveTexts"][0]
        if s03_init_probe["objectiveTexts"]
        else ""
    )
    s03_popup_text = (
        s03_init_probe["objectivePopups"][0]
        if s03_init_probe["objectivePopups"]
        else ""
    )
    s03_turn_limit = 15
    if s03_init_probe["turnLimit"]:
        params = s03_init_probe["turnLimit"][0]
        if len(params) >= 2:
            s03_turn_limit = max(1, int(params[1]))

    s03_candidates = set(continuing_party)
    s03_candidates.update(
        row["person"] for row in s03_init_probe["friendRecords"]
    )
    s03_protected_names = {"유비", "조조"}
    s03_protected_ids = sorted(
        cid
        for cid in s03_candidates
        if name_of(cid) in s03_protected_names
    )
    if {name_of(cid) for cid in s03_protected_ids} != s03_protected_names:
        raise SystemExit(
            "S03 protected-name mapping incomplete: "
            + repr([
                (cid, name_of(cid))
                for cid in sorted(s03_candidates)
                if name_of(cid) in s03_protected_names
            ])
        )

    sun_jian_rows = [
        row for row in s03_init_probe["friendRecords"]
        if name_of(row["person"]) == "손견"
    ]
    if len(sun_jian_rows) != 1:
        raise SystemExit(
            "S03 Sun Jian mapping ambiguous: "
            + repr([
                (row["person"], name_of(row["person"]))
                for row in s03_init_probe["friendRecords"]
            ])
        )
    sun_jian = sun_jian_rows[0]
    if int(sun_jian["ai"]) != 4:
        raise SystemExit(
            f"S03 Sun Jian expected AI policy 4, got {sun_jian['ai']}"
        )
    s03_rescue = {
        "characterId": int(sun_jian["person"]),
        "name": name_of(sun_jian["person"]),
        "x": int(sun_jian["targetX"]),
        "y": int(sun_jian["targetY"]),
    }

    s03_battle = {
        "version": 33,
        "source": "RS/S_03.eex",
        "battleMode": "rescue-character",
        "mapId": 3,
        "map": "m003.jpg",
        "widthTiles": map3_cols,
        "heightTiles": map3_rows,
        "terrainFile": "terrain3.bin",
        "terrainTypeCount": TERRAIN_TYPE_COUNT,
        "movementCostFile": "movement_costs.bin",
        "movementCostFamilyCount": JOB_FAMILY_COUNT,
        "terrainPowerFile": "terrain_power.bin",
        "jobRestraintFile": "job_restraint.bin",
        "battleObjectives": {
            "phase1": {
                "objectiveText": s03_objective_text,
                "popupText": s03_popup_text,
                "turnLimit": s03_turn_limit,
                "goal": {
                    "type": "rescue-character",
                    **s03_rescue,
                },
            },
            "phase2": {
                "objectiveText": "",
                "popupText": "",
                "turnLimit": s03_turn_limit,
            },
            "protectedCharacterIds": s03_protected_ids,
            "protectedCharacters": [
                {
                    "characterId": cid,
                    "name": name_of(cid),
                }
                for cid in s03_protected_ids
            ],
            "phase1TransitionEvents": [],
        },
        "battleEvents": s03_native_events,
        "outcomeEvents": s03_outcome_events,
        "outcomeProbe": s03_outcome_probe,
        "nextScenarioProbe": {
            "R_04.eex": r04_probe,
            "S_04.eex": s04_probe,
        },

        "s04InitProbe": s04_init_probe,
        "s04EventProbe": {
            "candidateCount": len(s04_event_probe),
            "coreSupportedCount": sum(
                1 for event in s04_event_probe
                if event["coreSupported"]
            ),
            "sections": [
                {
                    "section": event["section"],
                    "coreSupported": event["coreSupported"],
                    "unsupportedTriggerIds": event["unsupportedTriggerIds"],
                    "unsupportedActionIds": event["unsupportedActionIds"],
                    "unsupportedActions": event["unsupportedActions"],
                    "nestedBranchCount": event["nestedBranchCount"],
                    "nestedSupported": event["nestedSupported"],
                }
                for event in s04_event_probe
            ],
        },
        "s04OutcomeProbe": s04_outcome_probe,
        "battleEventSummary": {
            "candidateCount": len(s03_native_events),
            "coreSupportedCount": sum(
                1 for event in s03_native_events
                if event["coreSupported"]
            ),
            "sections": [
                {
                    "section": event["section"],
                    "coreSupported": event["coreSupported"],
                    "unsupportedTriggerIds": event["unsupportedTriggerIds"],
                    "unsupportedActionIds": event["unsupportedActionIds"],
                    "unsupportedActions": event["unsupportedActions"],
                    "nestedBranchCount": event["nestedBranchCount"],
                    "nestedSupported": event["nestedSupported"],
                }
                for event in s03_native_events
            ],
        },
        "terrainIds": sorted(set(terrain3_cells)),
        "combatModel": COMBAT_MODEL,
        "damageModel": DAMAGE_MODEL,
        "supportedAttackRangeIds": [0, 1],
        "units": s03_units,
        "openingEvents": [],
    }
    (battle_dir / "battle3.json").write_text(
        json.dumps(s03_battle, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


    # S_04 fifth battle: direct jump from S_03, kill Hua Xiong.
    s04_units = []

    def make_s04_unit(
        cid,
        faction,
        hidden,
        x,
        y,
        direction,
        deploy_level,
        deploy_job_level,
        ai_policy,
        reinforcement,
        source,
    ):
        sid = sprite_of(cid)
        if not sprite_record_valid(sid):
            print(f"skip S04 actor {cid}: invalid sprite {sid}")
            return False

        profile = combat_profile_of(cid, deploy_level)
        s04_units.append({
            "characterId": cid,
            "name": name_of(cid),
            "spriteId": sid,
            **profile,
            "deployLevel": deploy_level,
            "deployJobLevel": deploy_job_level,
            "aiPolicy": ai_policy,
            "reinforcement": bool(reinforcement),
            "faction": faction,
            "scripted": bool(hidden),
            "visible": not bool(hidden),
            "x": int(x),
            "y": int(y),
            "direction": int(direction),
            "source": source,
        })
        return True

    for slot in sorted(
        s04_init_probe["playerSlots"],
        key=lambda row: row["slot"],
    ):
        slot_index = int(slot["slot"])
        if slot_index < 0 or slot_index >= len(continuing_party):
            continue
        cid = continuing_party[slot_index]
        make_s04_unit(
            cid,
            PLAYER,
            False,
            slot["x"],
            slot["y"],
            slot["direction"],
            None,
            None,
            0,
            False,
            f"S_04:0x4B:{slot_index}",
        )

    for index, row in enumerate(s04_init_probe["friendRecords"]):
        make_s04_unit(
            row["person"],
            ALLY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            False,
            f"S_04:0x46:{index}",
        )

    for index, row in enumerate(s04_init_probe["enemyRecords"]):
        make_s04_unit(
            row["person"],
            ENEMY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            row["reinforcement"] != 0,
            f"S_04:0x47:{index}",
        )

    s04_objective_text = (
        s04_init_probe["objectiveTexts"][0]
        if s04_init_probe["objectiveTexts"]
        else ""
    )
    s04_popup_text = (
        s04_init_probe["objectivePopups"][0]
        if s04_init_probe["objectivePopups"]
        else ""
    )
    s04_turn_match = re.search(r"(\d+)턴", s04_objective_text)
    s04_turn_limit = int(s04_turn_match.group(1)) if s04_turn_match else 5

    huaxiong_rows = [
        row for row in s04_init_probe["enemyRecords"]
        if name_of(row["person"]) == "화웅"
    ]
    if len(huaxiong_rows) != 1:
        raise SystemExit(
            "S04 Hua Xiong mapping ambiguous: "
            + repr([
                (row["person"], name_of(row["person"]))
                for row in s04_init_probe["enemyRecords"]
                if "화웅" in name_of(row["person"])
            ])
        )
    huaxiong_id = int(huaxiong_rows[0]["person"])

    s04_protected_ids = [0, 1, 2]
    for cid in s04_protected_ids:
        if name_of(cid) not in {"유비", "관우", "장비"}:
            raise SystemExit(
                f"S04 protected mapping unexpected: {cid}={name_of(cid)}"
            )

    s04_battle = {
        "version": 35,
        "source": "RS/S_04.eex",
        "battleMode": "kill-character",
        "mapId": 4,
        "map": "m004.jpg",
        "widthTiles": map4_cols,
        "heightTiles": map4_rows,
        "terrainFile": "terrain4.bin",
        "terrainTypeCount": TERRAIN_TYPE_COUNT,
        "movementCostFile": "movement_costs.bin",
        "movementCostFamilyCount": JOB_FAMILY_COUNT,
        "terrainPowerFile": "terrain_power.bin",
        "jobRestraintFile": "job_restraint.bin",
        "battleObjectives": {
            "phase1": {
                "objectiveText": s04_objective_text,
                "popupText": s04_popup_text,
                "turnLimit": s04_turn_limit,
                "goal": {
                    "type": "kill-character",
                    "characterId": huaxiong_id,
                    "name": name_of(huaxiong_id),
                },
            },
            "phase2": {
                "objectiveText": "",
                "popupText": "",
                "turnLimit": s04_turn_limit,
            },
            "protectedCharacterIds": s04_protected_ids,
            "protectedCharacters": [
                {
                    "characterId": cid,
                    "name": name_of(cid),
                }
                for cid in s04_protected_ids
            ],
            "phase1TransitionEvents": [],
        },
        "battleEvents": s04_native_events,
        "outcomeEvents": s04_outcome_events,
        "outcomeProbe": s04_outcome_probe,
        "nextScenarioProbe": {
            "R_05.eex": r05_probe,
            "S_05.eex": s05_probe,
        },
        "r05Story": r05_story,
        "s05InitProbe": s05_init_probe,
        "s05EventProbe": {
            "candidateCount": len(s05_event_probe),
            "coreSupportedCount": sum(
                1 for event in s05_event_probe
                if event["coreSupported"]
            ),
            "sections": [
                {
                    "section": event["section"],
                    "coreSupported": event["coreSupported"],
                    "unsupportedTriggerIds": event["unsupportedTriggerIds"],
                    "unsupportedActionIds": event["unsupportedActionIds"],
                    "unsupportedActions": event["unsupportedActions"],
                    "nestedBranchCount": event["nestedBranchCount"],
                    "nestedSupported": event["nestedSupported"],
                }
                for event in s05_event_probe
            ],
        },
        "s05OutcomeProbe": s05_outcome_probe,
        "battleEventSummary": {
            "candidateCount": len(s04_native_events),
            "coreSupportedCount": sum(
                1 for event in s04_native_events
                if event["coreSupported"]
            ),
            "sections": [
                {
                    "section": event["section"],
                    "coreSupported": event["coreSupported"],
                    "unsupportedTriggerIds": event["unsupportedTriggerIds"],
                    "unsupportedActionIds": event["unsupportedActionIds"],
                    "unsupportedActions": event["unsupportedActions"],
                    "nestedBranchCount": event["nestedBranchCount"],
                    "nestedSupported": event["nestedSupported"],
                }
                for event in s04_native_events
            ],
        },
        "terrainIds": sorted(set(terrain4_cells)),
        "combatModel": COMBAT_MODEL,
        "damageModel": DAMAGE_MODEL,
        "supportedAttackRangeIds": [0, 1],
        "units": s04_units,
        "openingEvents": [],
    }
    (battle_dir / "battle4.json").write_text(
        json.dumps(s04_battle, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


    # S_05 sixth battle: defeat Lü Bu after the original R_05 story.
    s05_units = []

    def make_s05_unit(
        cid,
        faction,
        hidden,
        x,
        y,
        direction,
        deploy_level,
        deploy_job_level,
        ai_policy,
        reinforcement,
        source,
    ):
        sid = sprite_of(cid)
        if not sprite_record_valid(sid):
            print(f"skip S05 actor {cid}: invalid sprite {sid}")
            return False
        profile = combat_profile_of(cid, deploy_level)
        s05_units.append({
            "characterId": cid,
            "name": name_of(cid),
            "spriteId": sid,
            **profile,
            "deployLevel": deploy_level,
            "deployJobLevel": deploy_job_level,
            "aiPolicy": ai_policy,
            "reinforcement": bool(reinforcement),
            "faction": faction,
            "scripted": bool(hidden),
            "visible": not bool(hidden),
            "x": int(x),
            "y": int(y),
            "direction": int(direction),
            "source": source,
        })
        return True

    for slot in sorted(
        s05_init_probe["playerSlots"],
        key=lambda row: row["slot"],
    ):
        slot_index = int(slot["slot"])
        if slot_index < 0 or slot_index >= len(continuing_party):
            continue
        cid = continuing_party[slot_index]
        make_s05_unit(
            cid,
            PLAYER,
            False,
            slot["x"],
            slot["y"],
            slot["direction"],
            None,
            None,
            0,
            False,
            f"S_05:0x4B:{slot_index}",
        )

    for index, row in enumerate(s05_init_probe["friendRecords"]):
        make_s05_unit(
            row["person"],
            ALLY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            False,
            f"S_05:0x46:{index}",
        )

    for index, row in enumerate(s05_init_probe["enemyRecords"]):
        make_s05_unit(
            row["person"],
            ENEMY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            row["reinforcement"] != 0,
            f"S_05:0x47:{index}",
        )

    s05_objective_text = (
        s05_init_probe["objectiveTexts"][0]
        if s05_init_probe["objectiveTexts"]
        else ""
    )
    s05_popup_text = (
        s05_init_probe["objectivePopups"][0]
        if s05_init_probe["objectivePopups"]
        else ""
    )
    s05_turn_match = re.search(r"(\d+)턴", s05_objective_text)
    s05_turn_limit = int(s05_turn_match.group(1)) if s05_turn_match else 20

    lubu_rows = [
        row for row in s05_init_probe["enemyRecords"]
        if name_of(row["person"]) == "여포"
    ]
    if len(lubu_rows) != 1:
        raise SystemExit(
            "S05 Lu Bu mapping ambiguous: "
            + repr([
                (row["person"], name_of(row["person"]))
                for row in s05_init_probe["enemyRecords"]
                if "여포" in name_of(row["person"])
            ])
        )
    lubu_id = int(lubu_rows[0]["person"])

    s05_candidates = set(continuing_party)
    s05_candidates.update(
        row["person"] for row in s05_init_probe["friendRecords"]
    )
    s05_protected_names = {"유비", "관우", "장비", "공손찬", "원소"}
    s05_protected_ids = sorted(
        cid for cid in s05_candidates
        if name_of(cid) in s05_protected_names
    )
    if {name_of(cid) for cid in s05_protected_ids} != s05_protected_names:
        raise SystemExit(
            "S05 protected-name mapping incomplete: "
            + repr([(cid, name_of(cid)) for cid in sorted(s05_candidates)
                    if name_of(cid) in s05_protected_names])
        )

    s05_battle = {
        "version": 40,
        "source": "RS/S_05.eex",
        "battleMode": "kill-character",
        "mapId": 5,
        "map": "m005.jpg",
        "widthTiles": map5_cols,
        "heightTiles": map5_rows,
        "terrainFile": "terrain5.bin",
        "terrainTypeCount": TERRAIN_TYPE_COUNT,
        "movementCostFile": "movement_costs.bin",
        "movementCostFamilyCount": JOB_FAMILY_COUNT,
        "terrainPowerFile": "terrain_power.bin",
        "jobRestraintFile": "job_restraint.bin",
        "battleObjectives": {
            "phase1": {
                "objectiveText": s05_objective_text,
                "popupText": s05_popup_text,
                "turnLimit": s05_turn_limit,
                "goal": {
                    "type": "kill-character",
                    "characterId": lubu_id,
                    "name": name_of(lubu_id),
                },
            },
            "phase2": {
                "objectiveText": "",
                "popupText": "",
                "turnLimit": s05_turn_limit,
            },
            "protectedCharacterIds": s05_protected_ids,
            "protectedCharacters": [
                {"characterId": cid, "name": name_of(cid)}
                for cid in s05_protected_ids
            ],
            "phase1TransitionEvents": [],
        },
        "r05Story": r05_story,
        "battleEvents": s05_native_events,
        "outcomeEvents": s05_outcome_events,
        "outcomeProbe": s05_outcome_probe,
        "nextScenarioProbe": {
            "R_06.eex": r06_probe,
            "S_06.eex": s06_probe,
        },
        "r06Story": r06_story,
        "s06InitProbe": s06_init_probe,
        "s06EventProbe": {
            "candidateCount": len(s06_event_probe),
            "coreSupportedCount": sum(
                1 for event in s06_event_probe
                if event["coreSupported"]
            ),
            "sections": [
                {
                    "section": event["section"],
                    "coreSupported": event["coreSupported"],
                    "unsupportedTriggerIds": event["unsupportedTriggerIds"],
                    "unsupportedActionIds": event["unsupportedActionIds"],
                    "unsupportedActions": event["unsupportedActions"],
                    "nestedBranchCount": event["nestedBranchCount"],
                    "nestedSupported": event["nestedSupported"],
                }
                for event in s06_event_probe
            ],
        },
        "s06OutcomeProbe": s06_outcome_probe,
        "battleEventSummary": {
            "candidateCount": len(s05_native_events),
            "coreSupportedCount": sum(
                1 for event in s05_native_events if event["coreSupported"]
            ),
            "sections": [
                {
                    "section": event["section"],
                    "coreSupported": event["coreSupported"],
                    "unsupportedTriggerIds": event["unsupportedTriggerIds"],
                    "unsupportedActionIds": event["unsupportedActionIds"],
                    "unsupportedActions": event["unsupportedActions"],
                    "nestedBranchCount": event["nestedBranchCount"],
                    "nestedSupported": event["nestedSupported"],
                }
                for event in s05_native_events
            ],
        },
        "terrainIds": sorted(set(terrain5_cells)),
        "combatModel": COMBAT_MODEL,
        "damageModel": DAMAGE_MODEL,
        "supportedAttackRangeIds": [0, 1],
        "units": s05_units,
        "openingEvents": [],
    }

    (battle_dir / "battle5.json").write_text(
        json.dumps(s05_battle, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    # S_06: Zhao Yun and Xiahou Bo are the two forced player characters.
    # Gongsun Zan is an allied protected character; Wen Chou is the kill target.
    s06_units = []

    def make_s06_unit(
        cid,
        faction,
        hidden,
        x,
        y,
        direction,
        deploy_level,
        deploy_job_level,
        ai_policy,
        reinforcement,
        source,
    ):
        sid = sprite_of(cid)
        if not sprite_record_valid(sid):
            print(f"skip S06 actor {cid}: invalid sprite {sid}")
            return False
        profile = combat_profile_of(cid, deploy_level)
        s06_units.append({
            "characterId": cid,
            "name": name_of(cid),
            "spriteId": sid,
            **profile,
            "deployLevel": deploy_level,
            "deployJobLevel": deploy_job_level,
            "aiPolicy": ai_policy,
            "reinforcement": bool(reinforcement),
            "faction": faction,
            "scripted": bool(hidden),
            "visible": not bool(hidden),
            "x": int(x),
            "y": int(y),
            "direction": int(direction),
            "source": source,
        })
        return True

    s06_player_ids = [4, 19]
    if [name_of(cid) for cid in s06_player_ids] != ["조운", "하후박"]:
        raise SystemExit(
            "S06 player mapping mismatch: "
            + repr([(cid, name_of(cid)) for cid in s06_player_ids])
        )

    s06_slots = sorted(
        s06_init_probe["playerSlots"],
        key=lambda row: row["slot"],
    )
    if len(s06_slots) < len(s06_player_ids):
        raise SystemExit(
            "S06 player slot count too small: "
            + repr(s06_slots)
        )
    for slot, cid in zip(s06_slots, s06_player_ids):
        make_s06_unit(
            cid,
            PLAYER,
            False,
            slot["x"],
            slot["y"],
            slot["direction"],
            None,
            None,
            0,
            False,
            f"S_06:0x4B:{slot['slot']}",
        )

    for index, row in enumerate(s06_init_probe["friendRecords"]):
        make_s06_unit(
            row["person"],
            ALLY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            False,
            f"S_06:0x46:{index}",
        )

    for index, row in enumerate(s06_init_probe["enemyRecords"]):
        make_s06_unit(
            row["person"],
            ENEMY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            row["reinforcement"] != 0,
            f"S_06:0x47:{index}",
        )

    s06_objective_text = (
        s06_init_probe["objectiveTexts"][0]
        if s06_init_probe["objectiveTexts"]
        else ""
    )
    s06_popup_text = (
        s06_init_probe["objectivePopups"][0]
        if s06_init_probe["objectivePopups"]
        else ""
    )
    s06_turn_match = re.search(r"(\d+)턴", s06_objective_text)
    s06_turn_limit = int(s06_turn_match.group(1)) if s06_turn_match else 20

    wenchou_rows = [
        row for row in s06_init_probe["enemyRecords"]
        if name_of(row["person"]) == "문추"
    ]
    if len(wenchou_rows) != 1:
        raise SystemExit(
            "S06 Wen Chou mapping ambiguous: "
            + repr([
                (row["person"], name_of(row["person"]))
                for row in s06_init_probe["enemyRecords"]
                if row["person"] >= 0
            ])
        )
    wenchou_id = int(wenchou_rows[0]["person"])
    if wenchou_id != 109:
        raise SystemExit(
            f"S06 Wen Chou id mismatch: {wenchou_id}"
        )

    s06_protected_ids = [4, 19, 148]
    expected_s06_protected = {"조운", "하후박", "공손찬"}
    if {name_of(cid) for cid in s06_protected_ids} != expected_s06_protected:
        raise SystemExit(
            "S06 protected mapping mismatch: "
            + repr([
                (cid, name_of(cid))
                for cid in s06_protected_ids
            ])
        )

    s06_battle = {
        "version": 44,
        "source": "RS/S_06.eex",
        "battleMode": "kill-character",
        "mapId": 6,
        "map": "m006.jpg",
        "widthTiles": map6_cols,
        "heightTiles": map6_rows,
        "terrainFile": "terrain6.bin",
        "terrainTypeCount": TERRAIN_TYPE_COUNT,
        "movementCostFile": "movement_costs.bin",
        "movementCostFamilyCount": JOB_FAMILY_COUNT,
        "terrainPowerFile": "terrain_power.bin",
        "jobRestraintFile": "job_restraint.bin",
        "battleObjectives": {
            "phase1": {
                "objectiveText": s06_objective_text,
                "popupText": s06_popup_text,
                "turnLimit": s06_turn_limit,
                "goal": {
                    "type": "kill-character",
                    "characterId": wenchou_id,
                    "name": name_of(wenchou_id),
                },
            },
            "phase2": {
                "objectiveText": "",
                "popupText": "",
                "turnLimit": s06_turn_limit,
            },
            "protectedCharacterIds": s06_protected_ids,
            "protectedCharacters": [
                {"characterId": cid, "name": name_of(cid)}
                for cid in s06_protected_ids
            ],
            "phase1TransitionEvents": [],
        },
        "battleEvents": s06_native_events,
        "outcomeEvents": s06_outcome_events,
        "outcomeProbe": s06_outcome_probe,
        "nextScenarioProbe": {
            "R_07.eex": r07_probe,
            "S_07.eex": s07_probe,
        },

        "r07Story": r07_story,
        "s07InitProbe": s07_init_probe,
        "s07EventProbe": s07_event_probe,
        "s07OutcomeProbe": s07_outcome_probe,
        "battleEventSummary": {
            "candidateCount": len(s06_native_events),
            "coreSupportedCount": sum(
                1 for event in s06_native_events
                if event["coreSupported"]
            ),
            "sections": [
                {
                    "section": event["section"],
                    "coreSupported": event["coreSupported"],
                    "unsupportedTriggerIds": event["unsupportedTriggerIds"],
                    "unsupportedActionIds": event["unsupportedActionIds"],
                    "unsupportedActions": event["unsupportedActions"],
                    "nestedBranchCount": event["nestedBranchCount"],
                    "nestedSupported": event["nestedSupported"],
                }
                for event in s06_native_events
            ],
        },
        "terrainIds": sorted(set(terrain6_cells)),
        "combatModel": COMBAT_MODEL,
        "damageModel": DAMAGE_MODEL,
        "supportedAttackRangeIds": [0, 1],
        "units": s06_units,
        "openingEvents": [],
    }
    (battle_dir / "battle6.json").write_text(
        json.dumps(s06_battle, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


    # S_07: Liu Bei's four-person detachment joins Gongsun Zan at Jieqiao.
    # Victory is Yuan Shao (101) defeated; Liu Bei (0) and 30 turns are loss gates.
    s07_units = []

    def make_s07_unit(
        cid,
        faction,
        hidden,
        x,
        y,
        direction,
        deploy_level,
        deploy_job_level,
        ai_policy,
        reinforcement,
        source,
    ):
        sid = sprite_of(cid)
        if not sprite_record_valid(sid):
            print(f"skip S07 actor {cid}: invalid sprite {sid}")
            return False
        profile = combat_profile_of(cid, deploy_level)
        s07_units.append({
            "characterId": cid,
            "name": name_of(cid),
            "spriteId": sid,
            **profile,
            "deployLevel": deploy_level,
            "deployJobLevel": deploy_job_level,
            "aiPolicy": ai_policy,
            "reinforcement": bool(reinforcement),
            "faction": faction,
            "scripted": bool(hidden),
            "visible": not bool(hidden),
            "x": int(x),
            "y": int(y),
            "direction": int(direction),
            "source": source,
        })
        return True

    s07_player_ids = [0, 1, 2, 26]
    expected_s07_players = ["유비", "관우", "장비", "간옹"]
    if [name_of(cid) for cid in s07_player_ids] != expected_s07_players:
        raise SystemExit(
            "S07 player mapping mismatch: "
            + repr([(cid, name_of(cid)) for cid in s07_player_ids])
        )

    s07_slots = sorted(
        s07_init_probe["playerSlots"],
        key=lambda row: row["slot"],
    )
    if len(s07_slots) < len(s07_player_ids):
        raise SystemExit(
            "S07 player slot count too small: "
            + repr(s07_slots)
        )
    for slot, cid in zip(s07_slots, s07_player_ids):
        make_s07_unit(
            cid,
            PLAYER,
            False,
            slot["x"],
            slot["y"],
            slot["direction"],
            None,
            None,
            0,
            False,
            f"S_07:0x4B:{slot['slot']}",
        )

    for index, row in enumerate(s07_init_probe["friendRecords"]):
        make_s07_unit(
            row["person"],
            ALLY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            False,
            f"S_07:0x46:{index}",
        )

    for index, row in enumerate(s07_init_probe["enemyRecords"]):
        make_s07_unit(
            row["person"],
            ENEMY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            row["reinforcement"] != 0,
            f"S_07:0x47:{index}",
        )

    s07_objective_text = (
        s07_init_probe["objectiveTexts"][0]
        if s07_init_probe["objectiveTexts"]
        else ""
    )
    s07_popup_text = (
        s07_init_probe["objectivePopups"][0]
        if s07_init_probe["objectivePopups"]
        else ""
    )
    s07_turn_match = re.search(r"(\d+)턴", s07_objective_text)
    s07_turn_limit = int(s07_turn_match.group(1)) if s07_turn_match else 30

    yuan_shao_id = 101
    if name_of(yuan_shao_id) != "원소":
        raise SystemExit(
            f"S07 Yuan Shao mapping mismatch: "
            f"{yuan_shao_id}={name_of(yuan_shao_id)}"
        )
    if name_of(0) != "유비":
        raise SystemExit(f"S07 Liu Bei mapping mismatch: 0={name_of(0)}")

    s07_battle = {
        "version": 48,
        "source": "RS/S_07.eex",
        "battleMode": "kill-character",
        "mapId": 7,
        "map": "m007.jpg",
        "widthTiles": map7_cols,
        "heightTiles": map7_rows,
        "terrainFile": "terrain7.bin",
        "terrainTypeCount": TERRAIN_TYPE_COUNT,
        "movementCostFile": "movement_costs.bin",
        "movementCostFamilyCount": JOB_FAMILY_COUNT,
        "terrainPowerFile": "terrain_power.bin",
        "jobRestraintFile": "job_restraint.bin",
        "battleObjectives": {
            "phase1": {
                "objectiveText": s07_objective_text,
                "popupText": s07_popup_text,
                "turnLimit": s07_turn_limit,
                "goal": {
                    "type": "kill-character",
                    "characterId": yuan_shao_id,
                    "name": name_of(yuan_shao_id),
                },
            },
            "phase2": {
                "objectiveText": "",
                "popupText": "",
                "turnLimit": s07_turn_limit,
            },
            "protectedCharacterIds": [0],
            "protectedCharacters": [
                {"characterId": 0, "name": name_of(0)}
            ],
            "phase1TransitionEvents": [],
        },
        "battleEvents": s07_native_events,
        "outcomeEvents": s07_outcome_events,
        "outcomeProbe": s07_outcome_probe,
        "nextScenarioProbe": {
            "R_08.eex": r08_probe,
            "S_08.eex": s08_probe,
        },

        "r08Story": r08_story,
        "s08InitProbe": s08_init_probe,
        "s08EventProbe": s08_event_probe,
        "s08OutcomeProbe": s08_outcome_probe,
        "battleEventSummary": {
            "candidateCount": len(s07_native_events),
            "coreSupportedCount": sum(
                1 for event in s07_native_events
                if event["coreSupported"]
            ),
            "sections": [
                {
                    "section": event["section"],
                    "coreSupported": event["coreSupported"],
                    "unsupportedTriggerIds": event["unsupportedTriggerIds"],
                    "unsupportedActionIds": event["unsupportedActionIds"],
                    "unsupportedActions": event["unsupportedActions"],
                    "nestedBranchCount": event["nestedBranchCount"],
                    "nestedSupported": event["nestedSupported"],
                }
                for event in s07_native_events
            ],
        },
        "terrainIds": sorted(set(terrain7_cells)),
        "combatModel": COMBAT_MODEL,
        "damageModel": DAMAGE_MODEL,
        "supportedAttackRangeIds": [0, 1],
        "units": s07_units,
        "openingEvents": [],
    }
    (battle_dir / "battle7.json").write_text(
        json.dumps(s07_battle, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


    # S_08: Beihai rescue. Five-player Liu Bei detachment supports Kong Rong.
    # Victory is complete enemy annihilation; Liu Bei (0), Kong Rong (149),
    # or the 20-turn limit are the verified loss gates.
    s08_units = []

    def make_s08_unit(
        cid,
        faction,
        hidden,
        x,
        y,
        direction,
        deploy_level,
        deploy_job_level,
        ai_policy,
        reinforcement,
        source,
    ):
        sid = sprite_of(cid)
        if not sprite_record_valid(sid):
            print(f"skip S08 actor {cid}: invalid sprite {sid}")
            return False
        profile = combat_profile_of(cid, deploy_level)
        s08_units.append({
            "characterId": cid,
            "name": name_of(cid),
            "spriteId": sid,
            **profile,
            "deployLevel": deploy_level,
            "deployJobLevel": deploy_job_level,
            "aiPolicy": ai_policy,
            "reinforcement": bool(reinforcement),
            "faction": faction,
            "scripted": bool(hidden),
            "visible": not bool(hidden),
            "x": int(x),
            "y": int(y),
            "direction": int(direction),
            "source": source,
        })
        return True

    # The R08/S08 script provides five player slots. Ma Yunlu (20) is the
    # fifth active participant and has a dedicated S08 Kong Rong event.
    s08_player_ids = [0, 1, 2, 26, 20]
    s08_slots = sorted(
        s08_init_probe["playerSlots"],
        key=lambda row: row["slot"],
    )
    if len(s08_slots) < len(s08_player_ids):
        raise SystemExit(
            "S08 player slot count too small: "
            + repr(s08_slots)
        )
    for slot, cid in zip(s08_slots, s08_player_ids):
        make_s08_unit(
            cid,
            PLAYER,
            False,
            slot["x"],
            slot["y"],
            slot["direction"],
            None,
            None,
            0,
            False,
            f"S_08:0x4B:{slot['slot']}",
        )

    for index, row in enumerate(s08_init_probe["friendRecords"]):
        make_s08_unit(
            row["person"],
            ALLY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            False,
            f"S_08:0x46:{index}",
        )

    for index, row in enumerate(s08_init_probe["enemyRecords"]):
        make_s08_unit(
            row["person"],
            ENEMY,
            row["hidden"] != 0,
            row["x"],
            row["y"],
            row["direction"],
            row["level"],
            row["jobLevel"],
            row["ai"],
            row["reinforcement"] != 0,
            f"S_08:0x47:{index}",
        )

    s08_objective_text = (
        s08_init_probe["objectiveTexts"][0]
        if s08_init_probe["objectiveTexts"]
        else ""
    )
    s08_popup_text = (
        s08_init_probe["objectivePopups"][0]
        if s08_init_probe["objectivePopups"]
        else ""
    )
    s08_turn_match = re.search(r"(\d+)턴", s08_objective_text)
    s08_turn_limit = int(s08_turn_match.group(1)) if s08_turn_match else 20

    if name_of(0) != "유비":
        raise SystemExit(f"S08 Liu Bei mapping mismatch: 0={name_of(0)}")
    if name_of(149) != "공융":
        raise SystemExit(f"S08 Kong Rong mapping mismatch: 149={name_of(149)}")

    s08_battle = {
        "version": 48,
        "source": "RS/S_08.eex",
        "battleMode": "enemy-annihilation",
        "mapId": 8,
        "map": "m008.jpg",
        "widthTiles": map8_cols,
        "heightTiles": map8_rows,
        "terrainFile": "terrain8.bin",
        "terrainTypeCount": TERRAIN_TYPE_COUNT,
        "movementCostFile": "movement_costs.bin",
        "movementCostFamilyCount": JOB_FAMILY_COUNT,
        "terrainPowerFile": "terrain_power.bin",
        "jobRestraintFile": "job_restraint.bin",
        "battleObjectives": {
            "phase1": {
                "objectiveText": s08_objective_text,
                "popupText": s08_popup_text,
                "turnLimit": s08_turn_limit,
                "goal": {
                    "type": "enemy-annihilation",
                },
            },
            "phase2": {
                "objectiveText": "",
                "popupText": "",
                "turnLimit": s08_turn_limit,
            },
            "protectedCharacterIds": [0, 149],
            "protectedCharacters": [
                {"characterId": 0, "name": name_of(0)},
                {"characterId": 149, "name": name_of(149)},
            ],
            "phase1TransitionEvents": [],
        },
        "battleEvents": s08_native_events,
        "outcomeEvents": s08_outcome_events,
        "outcomeProbe": s08_outcome_probe,
        "nextScenarioProbe": {
            "R_09.eex": r09_probe,
            "S_09.eex": s09_probe,
        },
        "s09InitProbe": s09_init_probe,
        "s09EventProbe": s09_event_probe,
        "s09OutcomeProbe": s09_outcome_probe,
        "s09GoalProbe": s09_goal_probe,
        "battleEventSummary": {
            "candidateCount": len(s08_native_events),
            "coreSupportedCount": sum(
                1 for event in s08_native_events
                if event["coreSupported"]
            ),
            "sections": [
                {
                    "section": event["section"],
                    "coreSupported": event["coreSupported"],
                    "unsupportedTriggerIds": event["unsupportedTriggerIds"],
                    "unsupportedActionIds": event["unsupportedActionIds"],
                    "unsupportedActions": event["unsupportedActions"],
                    "nestedBranchCount": event["nestedBranchCount"],
                    "nestedSupported": event["nestedSupported"],
                }
                for event in s08_native_events
            ],
        },
        "terrainIds": sorted(set(terrain8_cells)),
        "combatModel": COMBAT_MODEL,
        "damageModel": DAMAGE_MODEL,
        "supportedAttackRangeIds": [0, 1],
        "units": s08_units,
        "openingEvents": [],
    }
    (battle_dir / "battle8.json").write_text(
        json.dumps(s08_battle, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

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
        "version": 31,
        "source": "RS/S_00.eex",
        "mapId": 0,
        "map": "m000.jpg",
        "widthTiles": MAP_WIDTH,
        "heightTiles": MAP_HEIGHT,
        "terrainFile": "terrain0.bin",
        "terrainTypeCount": TERRAIN_TYPE_COUNT,
        "movementCostFile": "movement_costs.bin",
        "movementCostFamilyCount": JOB_FAMILY_COUNT,
        "terrainPowerFile": "terrain_power.bin",
        "jobRestraintFile": "job_restraint.bin",
        "rawCombatSemantics": {
            "terrainPowerNeutralCandidate": 100,
            "jobRestraintNeutralCandidate": 100,
            "appliedToDamage": False,
            "reason": "exact application order is not yet verified",
        },
        "scenarioDiagnostics": scenario_diagnostics,
        "battleObjectives": objective_model,
        "outcomeEvents": outcome_events,
        "nextScenarioProbe": next_scenario_probe,
        "r01Story": r01_story,
        "s01InitProbe": s01_init_probe,
        "battleEvents": native_scene2_events,
        "battleEventSummary": {
            "candidateCount": len(native_scene2_events),
            "coreSupportedCount": sum(
                1 for event in native_scene2_events
                if event["coreSupported"]
            ),
            "sections": [
                {
                    "section": event["section"],
                    "coreSupported": event["coreSupported"],
                    "unsupportedTriggerIds": event["unsupportedTriggerIds"],
                    "unsupportedActionIds": event["unsupportedActionIds"],
                    "unsupportedActions": event["unsupportedActions"],
                    "nestedBranchCount": event["nestedBranchCount"],
                    "nestedSupported": event["nestedSupported"],
                }
                for event in native_scene2_events
            ],
        },
        "outcomeEventSummary": {
            key: {
                "scene": value.get("scene"),
                "section": value.get("section"),
                "supported": value.get("supported", False),
                "actionCount": len(value.get("actions", [])),
                "nestedBranchCount": value.get("nestedBranchCount", 0),
                "unsupportedActionIds": value.get(
                    "unsupportedActionIds",
                    [],
                ),
            }
            for key, value in outcome_events.items()
        },
        "terrainIds": terrain_ids,
        "combatModel": COMBAT_MODEL,
        "damageModel": DAMAGE_MODEL,
        "supportedAttackRangeIds": [0, 1],
        "units": units,
        "openingEvents": events,
    }
    (battle_dir / "battle0.json").write_text(
        json.dumps(battle, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    sprite_ids = sorted({
        u["spriteId"]
        for u in (
            units
            + s01_units
            + s02_units
            + s03_units
            + s04_units
            + s05_units
            + s06_units
            + s07_units
            + s08_units
        )
    })
    for sid in sprite_ids:
        specs = (
            ("unit_mov", mov, 48, 48, 11),
            ("unit_atk", atk, 64, 64, 12),
            ("unit_spc", spc, 48, 48, 5),
        )
        for label, blob, width, height, frames in specs:
            expected = width * height * frames
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
        "s01 map=",
        map1_width,
        "x",
        map1_height,
        "tiles=",
        map1_cols,
        "x",
        map1_rows,
        "terrain ids=",
        sorted(set(terrain1_cells)),
    )
    print(
        "s03 map=",
        map3_width,
        "x",
        map3_height,
        "tiles=",
        map3_cols,
        "x",
        map3_rows,
        "terrain ids=",
        sorted(set(terrain3_cells)),
    )

    print(
        "s04 map=",
        map4_width,
        "x",
        map4_height,
        "tiles=",
        map4_cols,
        "x",
        map4_rows,
        "terrain ids=",
        sorted(set(terrain4_cells)),
    )
    print(
        "s06 map=",
        map6_width,
        "x",
        map6_height,
        "tiles=",
        map6_cols,
        "x",
        map6_rows,
        "terrain ids=",
        sorted(set(terrain6_cells)),
    )
    print(
        "r06 story supported=",
        r06_story["supported"],
        "scenes=",
        r06_story["sceneCount"],
        "unsupported=",
        r06_story["unsupportedActionIds"],
    )
    print(
        "s06 init slots=",
        s06_init_probe["playerSlots"],
        "forced=",
        s06_init_probe["forcedPlayers"],
        "friends=",
        len(s06_init_probe["friendRecords"]),
        "enemies=",
        len(s06_init_probe["enemyRecords"]),
        "objectives=",
        s06_init_probe["objectiveTexts"],
        "events=",
        len(s06_event_probe),
        "core-supported=",
        sum(1 for e in s06_event_probe if e["coreSupported"]),
    )
    print(
        "s06 outcome candidates=",
        s06_outcome_probe,
    )
    print(
        "post-S06 probes=",
        {
            "R_07.eex": {
                "found": r07_probe.get("found"),
                "sceneCount": r07_probe.get("sceneCount"),
                "sectionCounts": r07_probe.get("sectionCounts"),
            },
            "S_07.eex": {
                "found": s07_probe.get("found"),
                "sceneCount": s07_probe.get("sceneCount"),
                "sectionCounts": s07_probe.get("sectionCounts"),
            },
        },
    )
    print(
        "s06 battle units=",
        len(s06_units),
        "players=",
        [(u["characterId"], u["name"]) for u in s06_units if u["faction"] == PLAYER],
        "protected=",
        [(cid, name_of(cid)) for cid in s06_protected_ids],
        "target=",
        (wenchou_id, name_of(wenchou_id)),
        "native-events=",
        len(s06_native_events),
        "core-supported=",
        sum(1 for e in s06_native_events if e["coreSupported"]),
    )
    print(
        "s05 map=",
        map5_width,
        "x",
        map5_height,
        "tiles=",
        map5_cols,
        "x",
        map5_rows,
        "terrain ids=",
        sorted(set(terrain5_cells)),
    )
    print(
        "r05 story supported=",
        r05_story["supported"],
        "scenes=",
        r05_story["sceneCount"],
        "unsupported=",
        r05_story["unsupportedActionIds"],
    )
    print(
        "s05 init slots=",
        s05_init_probe["playerSlots"],
        "friends=",
        len(s05_init_probe["friendRecords"]),
        "enemies=",
        len(s05_init_probe["enemyRecords"]),
        "objectives=",
        s05_init_probe["objectiveTexts"],
        "events=",
        len(s05_event_probe),
        "core-supported=",
        sum(1 for e in s05_event_probe if e["coreSupported"]),
    )
    print(
        "s05 units=",
        len(s05_units),
        "visible=",
        sum(1 for u in s05_units if u["visible"]),
        "native-events=",
        len(s05_native_events),
        "core-supported=",
        sum(1 for e in s05_native_events if e["coreSupported"]),
        "turnLimit=",
        s05_turn_limit,
        "protected=",
        [(cid, name_of(cid)) for cid in s05_protected_ids],
        "target=",
        (lubu_id, name_of(lubu_id)),
    )
    print(
        "s04 init forced players=",
        s04_init_probe["forcedPlayers"],
        "player slots=",
        s04_init_probe["playerSlots"],
        "friends=",
        len(s04_init_probe["friendRecords"]),
        "enemies=",
        len(s04_init_probe["enemyRecords"]),
        "objectives=",
        s04_init_probe["objectiveTexts"],
        "events=",
        len(s04_event_probe),
        "core-supported=",
        sum(1 for e in s04_event_probe if e["coreSupported"]),
    )
    print(
        "s04 outcome candidates=",
        s04_outcome_probe,
    )
    print(
        "s04 units=",
        len(s04_units),
        "visible=",
        sum(1 for u in s04_units if u["visible"]),
        "events=",
        len(s04_native_events),
        "core-supported=",
        sum(1 for e in s04_native_events if e["coreSupported"]),
        "turnLimit=",
        s04_turn_limit,
        "protected=",
        [(cid, name_of(cid)) for cid in s04_protected_ids],
        "target=",
        (huaxiong_id, name_of(huaxiong_id)),
    )
    print(
        "s03 init forced players=",
        s03_init_probe["forcedPlayers"],
        "player slots=",
        s03_init_probe["playerSlots"],
        "friends=",
        len(s03_init_probe["friendRecords"]),
        "enemies=",
        len(s03_init_probe["enemyRecords"]),
        "objectives=",
        s03_init_probe["objectiveTexts"],
        "events=",
        len(s03_event_probe),
        "core-supported=",
        sum(1 for e in s03_event_probe if e["coreSupported"]),
    )
    print(
        "s03 units=",
        len(s03_units),
        "visible=",
        sum(1 for u in s03_units if u["visible"]),
        "events=",
        len(s03_native_events),
        "core-supported=",
        sum(1 for e in s03_native_events if e["coreSupported"]),
        "turnLimit=",
        s03_turn_limit,
        "protected=",
        [(cid, name_of(cid)) for cid in s03_protected_ids],
        "rescue=",
        s03_rescue,
    )
    print(
        "s02 map=",
        map2_width,
        "x",
        map2_height,
        "tiles=",
        map2_cols,
        "x",
        map2_rows,
        "terrain ids=",
        sorted(set(terrain2_cells)),
    )
    print(
        "s02 init forced players=",
        s02_init_probe["forcedPlayers"],
        "player slots=",
        s02_init_probe["playerSlots"],
        "friends=",
        len(s02_init_probe["friendRecords"]),
        "enemies=",
        len(s02_init_probe["enemyRecords"]),
        "objectives=",
        s02_init_probe["objectiveTexts"],
    )
    print(
        "s01 init forced players=",
        s01_init_probe["forcedPlayers"],
        "player slots=",
        s01_init_probe["playerSlots"],
        "friends=",
        len(s01_init_probe["friendRecords"]),
        "enemies=",
        len(s01_init_probe["enemyRecords"]),
    )
    print(
        "s01 units=",
        len(s01_units),
        "visible=",
        sum(1 for u in s01_units if u["visible"]),
        "events=",
        len(s01_native_events),
        "core-supported=",
        sum(1 for e in s01_native_events if e["coreSupported"]),
        "turnLimit=",
        s01_turn_limit,
    )
    print(
        "s02 units=",
        len(s02_units),
        "visible=",
        sum(1 for u in s02_units if u["visible"]),
        "events=",
        len(s02_native_events),
        "core-supported=",
        sum(1 for e in s02_native_events if e["coreSupported"]),
        "turnLimit=",
        s02_turn_limit,
        "protected=",
        [
            (cid, name_of(cid))
            for cid in s02_protected_ids
        ],
    )
    print(
        "s02 outcome supported=",
        {
            "victory": s02_outcome_events["victory"]["supported"],
            "defeat26": s02_outcome_events["defeatByCharacter"]["26"]["supported"],
            "genericDefeat": s02_outcome_events["genericDefeat"]["supported"],
            "postBattle": s02_outcome_events["postBattle"]["supported"],
        },
    )
    print(
        "r03 story supported=",
        r03_story["supported"],
        "scenes=",
        r03_story["sceneCount"],
        "unsupported=",
        r03_story["unsupportedActionIds"],
    )
    print(
        "s01 outcome supported=",
        {
            "victory": s01_outcome_events["victory"]["supported"],
            "defeat118": s01_outcome_events["defeatByCharacter"]["118"]["supported"],
            "defeat0": s01_outcome_events["defeatByCharacter"]["0"]["supported"],
            "defeat36": s01_outcome_events["defeatByCharacter"]["36"]["supported"],
            "genericDefeat": s01_outcome_events["genericDefeat"]["supported"],
            "postBattle": s01_outcome_events["postBattle"]["supported"],
        },
    )
    print(
        "r02 story supported=",
        r02_story["supported"],
        "scenes=",
        r02_story["sceneCount"],
        "unsupported=",
        r02_story["unsupportedActionIds"],
    )
    print(
        "units=", len(units),
        "visible=", sum(1 for u in units if u["visible"]),
        "deployment hints=", len(deployment_hints),
    )
    print(
        "players=",
        [
            {
                "id": u["characterId"],
                "job": u["jobId"],
                "family": u["jobFamily"],
                "move": u["movePoints"],
                "range": u["attackRangeId"],
                "level": u["level"],
                "atk": u["attack"],
                "def": u["defense"],
                "hp": u["hpMax"],
            }
            for u in units
            if u["faction"] == PLAYER
        ],
    )
    print(
        "scenario scenes=", scenario_diagnostics["sceneCount"],
        "sections=", scenario_diagnostics["sectionCounts"],
        "commands=", scenario_diagnostics["commandCount"],
    )
    print(
        "scenario relevant=",
        scenario_diagnostics["relevantCommands"],
    )
    print("objective model=", objective_model)
    print(
        "native scene2 events=",
        len(native_scene2_events),
        "core-supported=",
        sum(1 for event in native_scene2_events if event["coreSupported"]),
    )
    print(
        "terrain power sample family0=",
        list(terrain_power_blob[:TERRAIN_TYPE_COUNT]),
    )
    print(
        "restraint sample family0=",
        list(restraint_blob[:JOB_RESTRAINT_STRIDE]),
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
