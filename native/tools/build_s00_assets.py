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
        (2, 20),
        (2, 21),
        (2, 31),
        (2, 33),
        (2, 34),
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

    if cid == 0x31 and len(params) >= 2 and int(params[0]) == 0:
        return {"type": "hide", "characterId": int(params[1])}

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

    if cid == 0x0B and len(params) >= 2:
        return {
            "type": "setVariable",
            "variableId": int(params[0]),
            "value": int(params[1]),
        }

    if cid == 0x5D and len(params) >= 2:
        return {"type": "turnLimit", "value": int(params[1])}

    if cid == 0x19 and params and isinstance(params[0], str):
        return {"type": "objective", "text": params[0]}

    if cid == 0x1A and params and isinstance(params[0], str):
        return {"type": "objectivePopup", "text": params[0]}

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
        if (
            int(params[1]) == 7
            and int(params[2]) == 0
            and int(params[3]) == 2
        ):
            return {
                "type": "unitHpEqualsZero",
                "characterId": int(params[0]),
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


def extract_scene2_native_events(scenes):
    if len(scenes) < 2:
        return []

    excluded_sections = {1, 20, 21, 31, 33, 34}
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

        actions = []
        unsupported_action_ids = []
        nested_branch_count = 0

        for node in body_node["children"]:
            if node["children"]:
                nested_branch_count += 1
                continue
            action = native_action_from_node(node)
            if action is None:
                unsupported_action_ids.append(node["commandId"])
            elif action["type"] != "noop":
                actions.append(action)

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
            "nestedBranchCount": nested_branch_count,
        })

    return events



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

        hexz = read_member_by_basename(game2, "Hexzmap.e5")
        if hexz is None:
            hexz = read_member_by_basename(game1, "Hexzmap.e5")

    if hexz is None:
        raise SystemExit("Hexzmap.e5 not found in game1/game2")

    if len(s00) != 31318 or not s00.startswith(b"EEX"):
        raise SystemExit("Unexpected S_00.eex revision")

    terrain_cells = extract_hexzmap_cells(hexz, 0, MAP_WIDTH, MAP_HEIGHT)
    (battle_dir / "terrain0.bin").write_bytes(terrain_cells)

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
        "version": 10,
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
                    "nestedBranchCount": event["nestedBranchCount"],
                }
                for event in native_scene2_events
            ],
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

    sprite_ids = sorted({u["spriteId"] for u in units})
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
