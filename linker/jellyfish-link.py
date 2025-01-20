import argparse
from typing import Dict, List, Tuple, Set
import re
import glob


def main():
    parser = argparse.ArgumentParser(description="Jellyfish Linker")

    parser.add_argument("input_files", nargs="+", help="input .ll files")
    parser.add_argument(
        "-o", "--output", type=str, required=True, help="output .ll file path"
    )

    args = parser.parse_args()

    input_files = []
    for file_pattern in args.input_files:
        files = glob.glob(file_pattern)
        input_files += files

    infos: list = []
    for file_path in input_files:
        c = read_ll(file_path)
        info = collect(c)
        infos.append(info)
    out_ll = merge(infos)
    write_ll(args.output, out_ll)


def read_ll(file_path: str) -> str:
    with open(file_path, "r", encoding="utf-8") as file:
        content = file.read()
        return content
    assert False


def write_ll(file_path: str, content: str):
    print("write")
    with open(file_path, "w", encoding="utf-8") as file:
        file.write(content)


def collect(content: str) -> Tuple[dict, dict, dict, dict]:
    pt_struct = r"(%(.+)\s=\stype\s\{(.*)\}\n)"
    struct_defs: Dict[str, Tuple[str, List[str]]] = dict()

    for struct in re.findall(pt_struct, content):
        struct_all = struct[0]
        struct_name = struct[1]
        element_types: list = [t.strip() for t in struct[2].split(",")]
        assert len(element_types) == struct[2].count(",") + 1
        struct_defs[struct_name] = (struct_all, element_types)

    pt_global = r"(@(.*?)\s=\s(.*)\n)"
    global_defs: Dict[str, Tuple[str, str]] = dict()
    for g in re.findall(pt_global, content):
        global_all = g[0]
        global_name = g[1]
        global_value = g[2]
        global_defs[global_name] = (global_all, global_value)

    pt_func_decl = r"(declare\s(.*)\s@(.*)\((.*)\)\n)"
    func_decls: Dict[str, Tuple[str, str, List[str]]] = dict()
    for f in re.findall(pt_func_decl, content):
        func_all = f[0]
        ret_type = f[1]
        func_name = f[2]
        param_types = [t.strip() for t in f[3].split(",")]
        func_decls[func_name] = (func_all, ret_type, param_types)

    pt_func_def = r"(define\s(.*)\s@(.*)\((.*)\)\s(\{\n([^\}]*\n)*\}\n))"
    func_defs: Dict[str, Tuple[str, str, List[str], str]] = dict()
    for f in re.findall(pt_func_def, content):
        func_all = f[0]
        ret_type = f[1]
        func_name = f[2]
        param_types = [t.strip() for t in f[3].split(",")]
        func_body = f[4]
        func_defs[func_name] = (func_all, ret_type, param_types, func_body)
    return (struct_defs, global_defs, func_decls, func_defs)


def _original_func_name(name: str) -> str:
    if name[0] == '"' and name[-1] == '"':
        name = name[1:-1]
    rdot_pos = name.rfind(".")
    if rdot_pos != -1 and name[rdot_pos + 1 :].isdigit():
        name = name[0:rdot_pos]
    return name


def _wrap_func_name(s: str) -> str:
    special_chars = ["<", ">", "$", "#"]
    for c in special_chars:
        if c in s:
            return f'"{s}"'
    return s


def _simutaneous_replace(s: str, replacements: dict):
    """
    multi-substring replacement (efficient one)
    https://gist.github.com/bgusach/a967e0587d6e01e889fd1d776c5f3729
    """
    if not replacements:
        return string

    replacements = {key: val for key, val in replacements.items()}

    rep_sorted = sorted(replacements, key=len, reverse=True)
    rep_escaped = map(re.escape, rep_sorted)

    pattern = re.compile("|".join(rep_escaped), 0)

    return pattern.sub(lambda match: replacements[match.group(0)], s)


def merge(records: List[Tuple[dict, dict, dict, dict]]) -> str:
    struct_names: Set[str] = set()
    merged_structs: List[str] = []
    global_names: Set[str] = set()
    merged_globals: List[str] = []
    str_pool: dict[str, str] = dict()
    str_id: int = 0

    func_type_map: Dict[str, List[str]] = dict()
    merged_funcs: Dict[int, List[str]] = dict()

    rename_map: Dict[int, Dict[str, str]] = dict()

    index = -1
    for record in records:
        index += 1
        rename_map[index] = dict()
        merged_funcs[index] = []
        for struct_name, struct_info in record[0].items():
            if struct_name not in struct_names:
                struct_names.add(struct_name)
                merged_structs.append(struct_info[0])

        for global_name, global_info in record[1].items():
            if global_name.startswith("jellyfish.str"):
                str_content = global_info[1]
                if str_content not in str_pool.keys():
                    str_id += 1
                    new_str_name = f"jellyfish.str.{str_id}"
                    str_pool[str_content] = new_str_name
                    merged_globals.append(f"@{new_str_name} = {str_content}\n")

                rename_map[index][f"@{global_name}"] = f"@{str_pool[str_content]}"

            elif global_name not in global_names:
                global_names.add(global_name)
                merged_globals.append(global_info[0])

        for func_name, func_info in record[3].items():
            origin_name: str = _original_func_name(func_name)
            ret_type = func_info[1]
            param_types = func_info[2]
            type_signature: str = "+".join([ret_type] + param_types)

            is_included = False
            if origin_name not in func_type_map.keys():
                func_type_map[origin_name] = [type_signature]
                is_included = True
            if type_signature not in func_type_map[origin_name]:
                func_type_map[origin_name].append(type_signature)
                is_included = True
            index4sig = func_type_map[origin_name].index(type_signature)
            unified_name = _wrap_func_name(
                origin_name if index4sig == 0 else f"{origin_name}.{index4sig}"
            )
            rename_map[index][f"@{func_name}"] = f"@{unified_name}"

            if is_included:
                merged_funcs[index].append(func_info[0])

    index = -1
    for record in records:
        index += 1
        for func_name, func_info in record[2].items():
            origin_name: str = _original_func_name(func_name)
            ret_type = func_info[1]
            param_types = func_info[2]
            type_signature: str = "+".join([ret_type] + param_types)

            is_included = False
            if origin_name not in func_type_map.keys():
                func_type_map[origin_name] = [type_signature]
                is_included = True
            if type_signature not in func_type_map[origin_name]:
                func_type_map[origin_name].append(type_signature)
                is_included = True
            index4sig = func_type_map[origin_name].index(type_signature)
            unified_name = _wrap_func_name(
                origin_name if index4sig == 0 else f"{origin_name}.{index4sig}"
            )
            rename_map[index][f"@{func_name}"] = f"@{unified_name}"
            if is_included:
                merged_funcs[index].append(func_info[0])

    all_merged_funcs: List[str] = []
    for i in merged_funcs.keys():
        merged_funcs[i].sort()
        module_content = "\n".join(merged_funcs[i])
        all_merged_funcs.append(_simutaneous_replace(module_content, rename_map[i]))

    merged_structs.sort()
    merged_globals.sort()
    all_merged_funcs.sort()

    return (
        "".join(merged_structs)
        + "\n"
        + "".join(merged_globals)
        + "\n"
        + "\n".join(all_merged_funcs)
    )


def generate(record: Tuple[dict, dict, dict, dict], module_name: str) -> str:
    res = f"; ModuleID = '{module_name}'\nsource_filename = \"jellyfish-link\"\n\n"
    for struct_name, struct_info in record[0].items():
        elem_types: List[str] = struct_info[1]
        res += "%{0} = type {{{1}}}\n".format(struct_name, ",".join(elem_types))
    res += "\n"
    for global_name, global_info in record[1].items():
        global_value = global_info[1]
        res += "@{0} = {1}\n".format(global_name, global_value)
    for func_name, func_info in record[2].items():
        ret_type = func_info[1]
        param_types = func_info[2]
        res += "declare {0} @{1}({2})\n\n".format(
            ret_type, func_name, ",".join(param_types)
        )

    for func_name, func_info in record[3].items():
        ret_type = func_info[1]
        param_types = func_info[2]
        func_body = func_info[3]
        res += "define {0} @{1}({2}){3}\n".format(
            ret_type, func_name, ",".join(param_types), func_body
        )
    return res


if __name__ == "__main__":
    main()
