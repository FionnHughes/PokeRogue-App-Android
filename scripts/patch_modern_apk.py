#!/usr/bin/env python3
"""Apply the save-import fix to an apktool-decoded copy of the Modern (PodarSmarty) app.

Usage: patch_modern_apk.py <decoded_dir>

The Modern app's source is not public, so the fix is applied to its decoded code:
  1. onPageFinished also runs the script that keeps the game's file input alive
     (the same script the legacy app injects, read from MainActivity.kt).
  2. The file picker shows all files instead of filtering on one MIME type,
     which can leave a .prsv greyed out.
  3. Hooks call importfix.SaveSync (modern-patch/src), which the workflow
     compiles and adds to the APK as classes3.dex. It adds a "Sync saves" entry
     to the drawer's Tools list and an on-screen log for the game's Import Data.
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
KOTLIN = REPO / "app/src/main/java/com/example/pokerogueoffline/MainActivity.kt"
APP = "smali_classes2/labs/smarty/offlinerogue"
SECTIONS = APP + "/ui/composable/sections"
TOOL = "Llabs/smarty/offlinerogue/viewmodel/Tool"
# The drawer's Tools list holds a string resource id per entry. The sync entry
# uses id 0, which no real resource has, and gets its label from code instead.
SYNC_TITLE_ID = "0x0"
SYNC_LABEL = "Sync saves"


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
    print("patched LocalWebViewClient, LocalWebChromeClient, GameViewKt, MainActivity and the Tools list")


def patch_tools_list(root: Path) -> None:
    """Add a "Sync saves" entry at the end of the drawer's Tools list."""
    tools = root / "ToolsSectionKt.smali"
    text = tools.read_text()

    # 1. One more element in the static list.
    array_start = (
        "    const/4 v0, 0x6\n"
        "\n"
        "    .line 59\n"
        f"    new-array v0, v0, [{TOOL};\n"
    )
    text = replace_once(text, array_start, array_start.replace("0x6", "0x7"), "Tools array size")
    last_entry = (
        "    const/4 v2, 0x5\n"
        "\n"
        "    aput-object v1, v0, v2\n"
    )
    sync_entry = (
        last_entry
        + "\n"
        + f"    new-instance v1, {TOOL}$Website;\n"
        + "\n"
        + f"    const/4 v2, {SYNC_TITLE_ID}\n"
        + "\n"
        + '    const-string v3, "savesync://menu"\n'
        + "\n"
        + f"    invoke-direct {{v1, v2, v3}}, {TOOL}$Website;-><init>(ILjava/lang/String;)V\n"
        + "\n"
        + "    const/4 v2, 0x6\n"
        + "\n"
        + "    aput-object v1, v0, v2\n"
    )
    text = replace_once(text, last_entry, sync_entry, "last Tools entry")

    # 2. A tap on that entry closes the drawer and opens the sync dialog instead
    #    of the tool sheet. p1 is the tapped Tool, p2 the "close drawer" callback.
    open_tool = (
        "    invoke-virtual {p0, p1}, Llabs/smarty/offlinerogue/viewmodel/DrawerViewModel;->"
        f"setBottomSheetTool({TOOL};)V\n"
    )
    open_sync = (
        f"    invoke-virtual {{p1}}, {TOOL};->getTitle()I\n"
        "\n"
        "    move-result v0\n"
        "\n"
        "    if-nez v0, :not_save_sync\n"
        "\n"
        "    invoke-interface {p2}, Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;\n"
        "\n"
        "    invoke-static {}, Limportfix/SaveSync;->openMenu()V\n"
        "\n"
        "    sget-object p0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;\n"
        "\n"
        "    return-object p0\n"
        "\n"
        "    :not_save_sync\n"
    ) + open_tool
    text = replace_once(text, open_tool, open_sync, "tool tap handler")
    tools.write_text(text)

    # 3. The entry's label: id 0 means "Sync saves"; every other id is looked up as before.
    label = root / "ToolsSectionKt$ToolsSection$1$1.smali"
    text = label.read_text()
    lookup = (
        "    invoke-static {v1, v15, v2}, Landroidx/compose/ui/res/StringResources_androidKt;->"
        "stringResource(ILandroidx/compose/runtime/Composer;I)Ljava/lang/String;\n"
        "\n"
        "    move-result-object v3\n"
    )
    with_sync_label = (
        "    if-nez v1, :save_sync_lookup\n"
        "\n"
        f'    const-string v3, "{SYNC_LABEL}"\n'
        "\n"
        "    goto :save_sync_label_done\n"
        "\n"
        "    :save_sync_lookup\n"
        + lookup
        + "\n"
        + "    :save_sync_label_done\n"
    )
    text = replace_once(text, lookup, with_sync_label, "tool label lookup")
    label.write_text(text)


if __name__ == "__main__":
    main()
