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
  4. Hooks call importfix.SaveSync (modern-patch/src), which the workflow
     compiles and adds to the APK as classes3.dex. It adds "Copy Pokémon caught"
     and "Sync saves" entries to the drawer's Tools list and an on-screen log
     for the game's Import Data. Its scripts and name tables are copied into
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
# use ids 0 and 1, which no real resource has, and get their labels from code.
# SaveSync.onDrawerEntry receives the same numbers.
SYNC_TITLE_ID = "0x0"
SYNC_LABEL = "Sync saves"
COPY_TITLE_ID = "0x1"
COPY_LABEL = "Copy Pok\\u00e9mon caught"  # smali escape for é


# Binary AndroidManifest.xml constants
RES_XML_RESOURCE_MAP = 0x0180
RES_XML_START_ELEMENT = 0x0102
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
    shutil.copytree(ASSETS, Path(sys.argv[1]) / "assets/savesync")
    print("patched the launch mode, the web view hooks, MainActivity and the Tools list")


def patch_tools_list(root: Path) -> None:
    """Add "Copy Pokémon caught" and "Sync saves" at the end of the drawer's Tools list."""
    tools = root / "ToolsSectionKt.smali"
    text = tools.read_text()

    # 1. Two more elements in the static list.
    array_start = (
        "    const/4 v0, 0x6\n"
        "\n"
        "    .line 59\n"
        f"    new-array v0, v0, [{TOOL};\n"
    )
    text = replace_once(
        text, array_start, array_start.replace("const/4 v0, 0x6", "const/16 v0, 0x8"), "Tools array size"
    )
    last_entry = (
        "    const/4 v2, 0x5\n"
        "\n"
        "    aput-object v1, v0, v2\n"
    )
    sync_entry = last_entry
    for index, title_id, url in ((6, COPY_TITLE_ID, "savesync://starters"), (7, SYNC_TITLE_ID, "savesync://menu")):
        sync_entry += (
            "\n"
            + f"    new-instance v1, {TOOL}$Website;\n"
            + "\n"
            + f"    const/4 v2, {title_id}\n"
            + "\n"
            + f'    const-string v3, "{url}"\n'
            + "\n"
            + f"    invoke-direct {{v1, v2, v3}}, {TOOL}$Website;-><init>(ILjava/lang/String;)V\n"
            + "\n"
            + f"    const/4 v2, {hex(index)}\n"
            + "\n"
            + "    aput-object v1, v0, v2\n"
        )
    text = replace_once(text, last_entry, sync_entry, "last Tools entry")

    # 2. A tap on either entry closes the drawer and opens its dialog instead of
    #    the tool sheet. p1 is the tapped Tool, p2 the "close drawer" callback.
    #    Real title ids are large resource ids, so "at most 1" means one of ours.
    open_tool = (
        "    invoke-virtual {p0, p1}, Llabs/smarty/offlinerogue/viewmodel/DrawerViewModel;->"
        f"setBottomSheetTool({TOOL};)V\n"
    )
    open_sync = (
        f"    invoke-virtual {{p1}}, {TOOL};->getTitle()I\n"
        "\n"
        "    move-result v0\n"
        "\n"
        "    const/4 v1, 0x1\n"
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

    # 3. The entries' labels: ids 0 and 1 are ours; every other id is looked up as before.
    label = root / "ToolsSectionKt$ToolsSection$1$1.smali"
    text = label.read_text()
    lookup = (
        "    invoke-static {v1, v15, v2}, Landroidx/compose/ui/res/StringResources_androidKt;->"
        "stringResource(ILandroidx/compose/runtime/Composer;I)Ljava/lang/String;\n"
        "\n"
        "    move-result-object v3\n"
    )
    with_sync_label = (
        "    if-nez v1, :save_tool_not_sync\n"
        "\n"
        f'    const-string v3, "{SYNC_LABEL}"\n'
        "\n"
        "    goto :save_tool_label_done\n"
        "\n"
        "    :save_tool_not_sync\n"
        "    const/4 v3, 0x1\n"
        "\n"
        "    if-ne v1, v3, :save_tool_lookup\n"
        "\n"
        f'    const-string v3, "{COPY_LABEL}"\n'
        "\n"
        "    goto :save_tool_label_done\n"
        "\n"
        "    :save_tool_lookup\n"
        + lookup
        + "\n"
        + "    :save_tool_label_done\n"
    )
    text = replace_once(text, lookup, with_sync_label, "tool label lookup")
    label.write_text(text)


if __name__ == "__main__":
    main()
