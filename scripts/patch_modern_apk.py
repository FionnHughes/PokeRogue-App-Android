#!/usr/bin/env python3
"""Apply the save-import fix to an apktool-decoded copy of the Modern (PodarSmarty) app.

Usage: patch_modern_apk.py <decoded_dir>

The Modern app's source is not public, so the fix is applied to its decoded code:
  1. onPageFinished also runs the script that keeps the game's file input alive
     (the same script the legacy app injects, read from MainActivity.kt).
  2. The file picker shows all files instead of filtering on one MIME type,
     which can leave a .prsv greyed out.
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
KOTLIN = REPO / "app/src/main/java/com/example/pokerogueoffline/MainActivity.kt"
SECTIONS = "smali_classes2/labs/smarty/offlinerogue/ui/composable/sections"


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
    print("patched LocalWebViewClient and LocalWebChromeClient")


if __name__ == "__main__":
    main()
