#!/usr/bin/env python3
"""Apply the save-import fix to an apktool-decoded copy of the Modern (PodarSmarty) app.

Usage: patch_modern_apk.py <decoded_dir>

The Modern app's source is not public, so the fix is applied to its decoded code:
  1. onPageFinished also runs the script that keeps the game's file input alive
     (the same script the legacy app injects, read from MainActivity.kt).
  2. The file picker shows all files instead of filtering on one MIME type,
     which can leave a .prsv greyed out.
  3. The activity's launch mode changes from singleInstance to singleTask.
     Android cancels a file picker's result straight away when the app that
     opened it is singleInstance, so the picked save never reached the game.
     The manifest also gains the REQUEST_INSTALL_PACKAGES permission, which the
     in-app updater needs to hand a downloaded build to Android's installer.
  4. Hooks call importfix.SaveSync (modern-patch/src), which the workflow
     compiles and adds to the APK as classes3.dex. It adds "Copy Pokémon caught",
     "Sync saves" and "Restore backup" entries to the drawer's Tools list and an
     on-screen log for the game's Import Data. Its scripts and name tables are copied into
     the APK's assets/savesync folder.
"""
import re
import shutil
import struct
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
KOTLIN = REPO / "app/src/main/java/com/example/pokerogueoffline/MainActivity.kt"
APP = "smali_classes2/labs/smarty/offlinerogue"
SECTIONS = APP + "/ui/composable/sections"
TOOL = "Llabs/smarty/offlinerogue/viewmodel/Tool"
ASSETS = REPO / "modern-patch/assets/savesync"
# The drawer's Tools list holds a string resource id per entry. The added entries
# use small numbers no real resource has, and get their labels from code.
# SaveSync.onDrawerEntry receives the same numbers. Listed in drawer order;
# labels are smali string literals (\\u00e9 is é).
DRAWER_ENTRIES = [
    (1, "Copy Pok\\u00e9mon caught", "savesync://starters"),
    (0, "Sync saves", "savesync://menu"),
    (2, "Restore backup", "savesync://restore"),
    (3, "Screen layout", "savesync://layout"),
]
ORIGINAL_TOOLS = 6

# Binary AndroidManifest.xml constants
RES_STRING_POOL = 0x0001
RES_XML_RESOURCE_MAP = 0x0180
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103
STRING_POOL_UTF8 = 1 << 8
TYPE_STRING = 0x03
INSTALL_PERMISSION = "android.permission.REQUEST_INSTALL_PACKAGES"
ATTR_LAUNCH_MODE = 0x0101001D
LAUNCH_SINGLE_TASK = 2
LAUNCH_SINGLE_INSTANCE = 3


def patch_launch_mode(manifest: Path) -> None:
    """Rewrite android:launchMode singleInstance -> singleTask in the binary manifest.

    The manifest stays in its compiled form (apktool -r), so this edits the one
    4-byte value in place and leaves every other byte untouched.
    """
    data = bytearray(manifest.read_bytes())
    resource_ids = []
    patched = 0
    offset = 8  # skip the file header chunk
    while offset < len(data):
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", data, offset)
        if chunk_size < 8:
            sys.exit("manifest: malformed chunk")
        if chunk_type == RES_XML_RESOURCE_MAP:
            count = (chunk_size - header_size) // 4
            resource_ids = list(struct.unpack_from(f"<{count}I", data, offset + header_size))
        elif chunk_type == RES_XML_START_ELEMENT:
            body = offset + header_size
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, body + 8)
            for index in range(attr_count):
                attr = body + attr_start + index * attr_size
                name_index = struct.unpack_from("<I", data, attr + 4)[0]
                if name_index < len(resource_ids) and resource_ids[name_index] == ATTR_LAUNCH_MODE:
                    value = struct.unpack_from("<I", data, attr + 16)[0]
                    if value != LAUNCH_SINGLE_INSTANCE:
                        sys.exit(f"manifest: expected launchMode singleInstance, found {value}")
                    struct.pack_into("<I", data, attr + 16, LAUNCH_SINGLE_TASK)
                    patched += 1
        offset += chunk_size
    if patched != 1:
        sys.exit(f"manifest: expected one launchMode attribute, patched {patched}")
    manifest.write_bytes(data)


def _pool_strings(data: bytearray, pool: int) -> list:
    """The strings of a binary XML string pool (UTF-16, as manifests are compiled), in order."""
    header_size, _, count, _, _, strings_start, _ = struct.unpack_from("<HIIIIII", data, pool + 2)
    out = []
    for index in range(count):
        at = pool + strings_start + struct.unpack_from("<I", data, pool + header_size + 4 * index)[0]
        size = struct.unpack_from("<H", data, at)[0]
        out.append(data[at + 2:at + 2 + 2 * size].decode("utf-16-le", "replace"))
    return out


def _encode_pool_string(text: str) -> bytes:
    if len(text) > 0x7FFF:
        sys.exit("manifest: string too long")
    return struct.pack("<H", len(text)) + text.encode("utf-16-le") + b"\0\0"


def add_permission(manifest: Path, permission: str) -> None:
    """Add <uses-permission android:name="..."/> to the binary manifest.

    The in-app updater hands the downloaded APK to Android's installer, which only
    listens to apps that declare REQUEST_INSTALL_PACKAGES. The manifest stays in its
    compiled form, so this appends one string to the string pool and copies an
    existing uses-permission element, pointing the copy at the new string. Appending
    keeps every existing string index, so nothing else in the file has to change.
    """
    data = bytearray(manifest.read_bytes())
    pool = 8
    chunk_type, header_size, pool_size = struct.unpack_from("<HHI", data, pool)
    if chunk_type != RES_STRING_POOL:
        sys.exit("manifest: no string pool where one was expected")
    count, style_count, flags, strings_start, styles_start = struct.unpack_from("<IIIII", data, pool + 8)
    if style_count or styles_start or flags & STRING_POOL_UTF8:
        sys.exit("manifest: the string pool has styles or is UTF-8, which this patch does not handle")
    strings = _pool_strings(data, pool)
    if permission in strings:
        sys.exit(f"manifest: {permission} is already declared")

    # 1. The new string: one more offset entry, and its text after the existing texts.
    text_length = pool_size - strings_start
    encoded = _encode_pool_string(permission)
    encoded += b"\0" * (-(4 + len(encoded)) % 4)  # the chunk stays a multiple of 4 bytes
    offsets_end = pool + header_size + 4 * count
    data[pool + pool_size:pool + pool_size] = encoded
    data[offsets_end:offsets_end] = struct.pack("<I", text_length)
    grown = 4 + len(encoded)
    struct.pack_into("<I", data, pool + 4, pool_size + grown)
    struct.pack_into("<I", data, pool + 8, count + 1)
    struct.pack_into("<I", data, pool + 20, strings_start + 4)
    new_index = count

    # 2. A copy of the first plain uses-permission element, placed right after it.
    offset = pool + pool_size + grown
    start = None
    while offset < len(data):
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", data, offset)
        if chunk_size < 8:
            sys.exit("manifest: malformed chunk")
        body = offset + header_size
        if chunk_type == RES_XML_START_ELEMENT and start is None:
            name = struct.unpack_from("<I", data, body + 4)[0]
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, body + 8)
            attr = body + attr_start
            if (strings[name] == "uses-permission" and attr_count == 1
                    and strings[struct.unpack_from("<I", data, attr + 4)[0]] == "name"
                    and data[attr + 15] == TYPE_STRING):
                start = (offset, chunk_size, attr)
        elif chunk_type == RES_XML_END_ELEMENT and start is not None:
            if strings[struct.unpack_from("<I", data, body + 4)[0]] != "uses-permission":
                sys.exit("manifest: the uses-permission element has children")
            element = bytearray(data[start[0]:offset + chunk_size])
            attr = start[2] - start[0]
            struct.pack_into("<I", element, attr + 8, new_index)   # the value as written
            struct.pack_into("<I", element, attr + 16, new_index)  # the value as typed data
            data[offset + chunk_size:offset + chunk_size] = element
            struct.pack_into("<I", data, 4, len(data))
            manifest.write_bytes(data)
            return
        offset += chunk_size
    sys.exit("manifest: no plain uses-permission element to copy")


def load_shim() -> str:
    match = re.search(
        r'KEEP_FILE_INPUT_ALIVE_JS = """(.*?)"""\.trimIndent\(\)', KOTLIN.read_text(), re.S
    )
    if not match:
        sys.exit("could not find KEEP_FILE_INPUT_ALIVE_JS in MainActivity.kt")
    one_line = " ".join(match.group(1).split())
    # The script is embedded in a smali string literal, so it must stay simple.
    if any(ch in one_line for ch in '"\\') or "//" in one_line:
        sys.exit("script contains characters that are unsafe in a smali string")
    return one_line.replace("'", "\\'")


def replace_once(text: str, old: str, new: str, what: str) -> str:
    if text.count(old) != 1:
        sys.exit(f"expected exactly one match for {what}, found {text.count(old)}")
    return text.replace(old, new)


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    root = Path(sys.argv[1]) / SECTIONS

    client = root / "LocalWebViewClient.smali"
    text = client.read_text()
    header = (
        ".method public onPageFinished(Landroid/webkit/WebView;Ljava/lang/String;)V\n"
        "    .locals 1\n"
    )
    text = replace_once(text, header, header.replace(".locals 1", ".locals 2"), "onPageFinished header")
    super_call = (
        "    invoke-super {p0, p1, p2}, Llabs/smarty/offlinerogue/ui/composable/"
        "AccompanistWebViewClient;->onPageFinished(Landroid/webkit/WebView;Ljava/lang/String;)V\n"
    )
    injected = (
        super_call
        + "\n"
        + "    invoke-static {p1, p2}, Limportfix/SaveSync;->"
        + "onPageFinished(Landroid/webkit/WebView;Ljava/lang/String;)V\n"
        + "\n"
        + f'    const-string v0, "{load_shim()}"\n'
        + "\n"
        + "    const/4 v1, 0x0\n"
        + "\n"
        + "    invoke-virtual {p1, v0, v1}, Landroid/webkit/WebView;->"
        + "evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V\n"
    )
    text = replace_once(text, super_call, injected, "onPageFinished super call")
    client.write_text(text)

    chrome = root / "LocalWebChromeClient.smali"
    text = chrome.read_text()
    text = replace_once(
        text,
        'const-string p2, "application/octet-stream"',
        'const-string p2, "*/*"',
        "file picker MIME type",
    )
    chrome.write_text(text)

    # Register the sync helper on the game WebView, next to the app's own bridge.
    game_view = root / "GameViewKt.smali"
    text = game_view.read_text()
    add_bridge = (
        "    invoke-virtual {p2, p0, p1}, Landroid/webkit/WebView;->"
        "addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V\n"
    )
    attach = (
        add_bridge
        + "\n"
        + "    invoke-static {p2}, Limportfix/SaveSync;->attach(Landroid/webkit/WebView;)V\n"
    )
    text = replace_once(text, add_bridge, attach, "addJavascriptInterface call")

    # Report what the file picker returned, for the on-screen import log. p1 is the Uri.
    picker_result = (
        "    iget-object v0, p0, Lkotlin/jvm/internal/Ref$ObjectRef;->element:Ljava/lang/Object;\n"
        "\n"
        "    const/4 v1, 0x0\n"
        "\n"
        "    if-nez v0, :cond_0\n"
        "\n"
        '    const-string p0, "chromeClient"\n'
    )
    text = replace_once(
        text,
        picker_result,
        "    invoke-static {p1}, Limportfix/SaveSync;->onPickerResult(Landroid/net/Uri;)V\n\n"
        + picker_result,
        "file picker result handler",
    )
    game_view.write_text(text)

    # Let the helper know the activity, so it can show its dialog.
    activity = Path(sys.argv[1]) / APP / "MainActivity.smali"
    text = activity.read_text()
    super_create = (
        "    invoke-super {p0, p1}, Landroidx/activity/ComponentActivity;->"
        "onCreate(Landroid/os/Bundle;)V\n"
    )
    text = replace_once(
        text,
        super_create,
        super_create
        + "\n"
        + "    invoke-static {p0}, Limportfix/SaveSync;->setActivity(Landroid/app/Activity;)V\n",
        "MainActivity.onCreate super call",
    )
    activity.write_text(text)

    patch_tools_list(root)
    patch_launch_mode(Path(sys.argv[1]) / "AndroidManifest.xml")
    add_permission(Path(sys.argv[1]) / "AndroidManifest.xml", INSTALL_PERMISSION)
    shutil.copytree(ASSETS, Path(sys.argv[1]) / "assets/savesync")
    print("patched the launch mode, the web view hooks, MainActivity and the Tools list")


def patch_tools_list(root: Path) -> None:
    """Add this patch's entries at the end of the drawer's Tools list."""
    tools = root / "ToolsSectionKt.smali"
    text = tools.read_text()

    # 1. More elements in the static list.
    array_start = (
        "    const/4 v0, 0x6\n"
        "\n"
        "    .line 59\n"
        f"    new-array v0, v0, [{TOOL};\n"
    )
    text = replace_once(
        text, array_start, array_start.replace("const/4 v0, 0x6", f"const/16 v0, {hex(ORIGINAL_TOOLS + len(DRAWER_ENTRIES))}"), "Tools array size"
    )
    last_entry = (
        "    const/4 v2, 0x5\n"
        "\n"
        "    aput-object v1, v0, v2\n"
    )
    sync_entry = last_entry
    for offset, (title_id, _label, url) in enumerate(DRAWER_ENTRIES):
        sync_entry += (
            "\n"
            + f"    new-instance v1, {TOOL}$Website;\n"
            + "\n"
            + f"    const/4 v2, {hex(title_id)}\n"
            + "\n"
            + f'    const-string v3, "{url}"\n'
            + "\n"
            + f"    invoke-direct {{v1, v2, v3}}, {TOOL}$Website;-><init>(ILjava/lang/String;)V\n"
            + "\n"
            + f"    const/16 v2, {hex(ORIGINAL_TOOLS + offset)}\n"
            + "\n"
            + "    aput-object v1, v0, v2\n"
        )
    text = replace_once(text, last_entry, sync_entry, "last Tools entry")

    # 2. A tap on either entry closes the drawer and opens its dialog instead of
    #    the tool sheet. p1 is the tapped Tool, p2 the "close drawer" callback.
    #    Real title ids are large resource ids, so a small number means one of ours.
    open_tool = (
        "    invoke-virtual {p0, p1}, Llabs/smarty/offlinerogue/viewmodel/DrawerViewModel;->"
        f"setBottomSheetTool({TOOL};)V\n"
    )
    open_sync = (
        f"    invoke-virtual {{p1}}, {TOOL};->getTitle()I\n"
        "\n"
        "    move-result v0\n"
        "\n"
        f"    const/4 v1, {hex(max(entry[0] for entry in DRAWER_ENTRIES))}\n"
        "\n"
        "    if-gt v0, v1, :not_save_tool\n"
        "\n"
        "    invoke-interface {p2}, Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;\n"
        "\n"
        "    invoke-static {v0}, Limportfix/SaveSync;->onDrawerEntry(I)V\n"
        "\n"
        "    sget-object p0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;\n"
        "\n"
        "    return-object p0\n"
        "\n"
        "    :not_save_tool\n"
    ) + open_tool
    text = replace_once(text, open_tool, open_sync, "tool tap handler")
    tools.write_text(text)

    # 3. The entries' labels: our ids get their text here; every other id is looked up as before.
    #    v1 holds the title id and v3 receives the label; v3 doubles as scratch for the comparison.
    label = root / "ToolsSectionKt$ToolsSection$1$1.smali"
    text = label.read_text()
    lookup = (
        "    invoke-static {v1, v15, v2}, Landroidx/compose/ui/res/StringResources_androidKt;->"
        "stringResource(ILandroidx/compose/runtime/Composer;I)Ljava/lang/String;\n"
        "\n"
        "    move-result-object v3\n"
    )
    with_sync_label = ""
    for title_id, entry_label, _url in DRAWER_ENTRIES:
        with_sync_label += (
            f"    const/4 v3, {hex(title_id)}\n"
            "\n"
            f"    if-ne v1, v3, :save_tool_not_{title_id}\n"
            "\n"
            f'    const-string v3, "{entry_label}"\n'
            "\n"
            "    goto :save_tool_label_done\n"
            "\n"
            f"    :save_tool_not_{title_id}\n"
        )
    with_sync_label += lookup + "\n" + "    :save_tool_label_done\n"
    text = replace_once(text, lookup, with_sync_label, "tool label lookup")
    label.write_text(text)


if __name__ == "__main__":
    main()
