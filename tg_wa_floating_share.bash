#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# tg_wa_floating_share.sh
#
# Ready-to-run Android Floating Share Script for:
#   - Graph Messenger (ir.ilmili.telegraph) -> WhatsApp (com.whatsapp)
#   - WhatsApp (com.whatsapp) -> Graph Messenger (ir.ilmili.telegraph)
#
# How to use without compiling an APK:
#   1. Install Termux, Termux:API, and Termux:Float (or Termux:Widget).
#   2. Run `bash tg_wa_floating_share.sh --install` once in Termux.
#   3. Launch the floating window via Termux:Float (or tap "Termux" in the
#      Android Share menu after copying a caption / sharing a post).
# ==============================================================================

set -euo pipefail

GRAPH_PKG="ir.ilmili.telegraph"
WHATSAPP_PKG="com.whatsapp"

SHARED_CACHE_DIR="$HOME/storage/shared/Download/TG_WA_Bridge"
mkdir -p "$SHARED_CACHE_DIR"

# Detect MIME type from file extension
get_mime_type() {
    local file="$1"
    local ext="${file##*.}"
    ext=$(echo "$ext" | tr '[:upper:]' '[:lower:]')
    case "$ext" in
        jpg|jpeg) echo "image/jpeg" ;;
        png)      echo "image/png" ;;
        webp)     echo "image/webp" ;;
        gif)      echo "image/gif" ;;
        mp4|mkv|mov) echo "video/mp4" ;;
        pdf)      echo "application/pdf" ;;
        *)        echo "*/*" ;;
    esac
}

# Find the most recently saved/shared media file from Graph Messenger or WhatsApp
find_latest_media() {
    local source_mode="$1" # "tg" or "wa"
    local search_dirs=()

    if [[ "$source_mode" == "tg" ]]; then
        search_dirs=(
            "/sdcard/Telegram/Telegram Images"
            "/sdcard/Telegram/Telegram Video"
            "/sdcard/Pictures/Telegram"
            "/sdcard/Movies/Telegram"
            "/sdcard/Android/media/ir.ilmili.telegraph/Telegram/Telegram Images"
            "/sdcard/Android/media/ir.ilmili.telegraph/Telegram/Telegram Video"
            "/sdcard/Download"
        )
    else
        search_dirs=(
            "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"
            "/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video"
            "/sdcard/WhatsApp/Media/WhatsApp Images"
            "/sdcard/WhatsApp/Media/WhatsApp Video"
            "/sdcard/Download"
        )
    fi

    local newest_file=""
    local newest_time=0

    for dir in "${search_dirs[@]}"; do
        if [[ -d "$dir" ]]; then
            while IFS= read -r -d '' file; do
                local mtime
                mtime=$(stat -c %Y "$file" 2>/dev/null || echo 0)
                if (( mtime > newest_time )); then
                    newest_time=$mtime
                    newest_file="$file"
                fi
            done < <(find "$dir" -maxdepth 2 -type f \( -iname "*.jpg" -o -iname "*.jpeg" -o -iname "*.png" -o -iname "*.mp4" -o -iname "*.webp" \) -print0 2>/dev/null)
        fi
    done

    echo "$newest_file"
}

# Read caption from Android clipboard via Termux:API (or empty fallback)
get_caption() {
    local clip=""
    if command -v termux-clipboard-get >/dev/null 2>&1; then
        clip=$(termux-clipboard-get 2>/dev/null || true)
    fi
    echo "$clip"
}

# Fire Android ACTION_SEND Intent with both EXTRA_STREAM and EXTRA_TEXT
share_post() {
    local target_pkg="$1"
    local media_path="$2"
    local caption="$3"

    if [[ -n "$media_path" && -f "$media_path" ]]; then
        # Copy to public bridge cache so target app can read URI without permission denial
        local filename
        filename="bridge_$(date +%s)_$(basename "$media_path")"
        local staged_path="$SHARED_CACHE_DIR/$filename"
        cp "$media_path" "$staged_path"

        local mime
        mime=$(get_mime_type "$staged_path")
        local file_uri="file://$staged_path"

        echo "Sharing media: $(basename "$media_path")"
        echo "Caption: ${caption:-<none>}"

        # Launch target package with both EXTRA_STREAM and EXTRA_TEXT
        am start \
            --grant-read-uri-permission \
            -a android.intent.action.SEND \
            -t "$mime" \
            --eu android.intent.extra.STREAM "$file_uri" \
            --es android.intent.extra.TEXT "$caption" \
            -p "$target_pkg" >/dev/null 2>&1 || \
        termux-share -a send -c "$mime" "$staged_path"
    else
        # Text-only post fallback
        am start \
            -a android.intent.action.SEND \
            -t "text/plain" \
            --es android.intent.extra.TEXT "$caption" \
            -p "$target_pkg" >/dev/null 2>&1
    fi
}

# Install hook for Termux Share Sheet ("termux-file-editor") and Termux:Float
install_hooks() {
    mkdir -p "$HOME/bin" "$HOME/.termux/float" "$HOME/.shortcuts"
    cp "$0" "$HOME/bin/termux-file-editor"
    cp "$0" "$HOME/.termux/float/tg_wa_floating_share.sh"
    cp "$0" "$HOME/.shortcuts/TG_WA_Share"
    chmod +x "$HOME/bin/termux-file-editor" "$HOME/.termux/float/tg_wa_floating_share.sh" "$HOME/.shortcuts/TG_WA_Share"
    echo "Installed successfully!"
    echo " - Floating window script: ~/.termux/float/tg_wa_floating_share.sh"
    echo " - Share menu hook: ~/bin/termux-file-editor"
}

# Entry point
if [[ "${1:-}" == "--install" ]]; then
    install_hooks
    exit 0
fi

# If invoked directly from Android Share Menu with a shared file path ($1)
if [[ -n "${1:-}" && -f "${1:-}" ]]; then
    SHARED_FILE="$1"
    CAPTION=$(get_caption)
    echo "=========================================="
    echo "   TG ⇄ WA Share Bridge (Media + Caption)"
    echo "=========================================="
    echo "File   : $(basename "$SHARED_FILE")"
    echo "Caption: ${CAPTION:-<empty clipboard>}"
    echo ""
    echo "Select destination app:"
    echo "  [1] WhatsApp (com.whatsapp)"
    echo "  [2] Graph Messenger (ir.ilmili.telegraph)"
    read -r -n 1 -p "Choice (1/2): " choice
    echo ""
    if [[ "$choice" == "2" ]]; then
        share_post "$GRAPH_PKG" "$SHARED_FILE" "$CAPTION"
    else
        share_post "$WHATSAPP_PKG" "$SHARED_FILE" "$CAPTION"
    fi
    exit 0
fi

# Interactive Floating Button Loop (for Termux:Float)
while true; do
    clear
    CAPTION_PREVIEW=$(get_caption | head -c 45 | tr '\n' ' ')
    echo "┌────────────────────────────────────────┐"
    echo "│  TG (Graph) ⇄ WhatsApp Floating Share  │"
    echo "├────────────────────────────────────────┤"
    echo "│ Clip: ${CAPTION_PREVIEW:-<copy caption first>} "
    echo "├────────────────────────────────────────┤"
    echo "│ [1] Graph Messenger ➔ WhatsApp         │"
    echo "│     (Latest TG Media + Copied Caption) │"
    echo "│                                        │"
    echo "│ [2] WhatsApp ➔ Graph Messenger         │"
    echo "│     (Latest WA Media + Copied Caption) │"
    echo "│                                        │"
    echo "│ [3] Caption Text Only ➔ WhatsApp       │"
    echo "│ [4] Caption Text Only ➔ Graph Msg      │"
    echo "│ [q] Close Floating Button              │"
    echo "└────────────────────────────────────────┘"
    read -r -n 1 -p "Tap action [1-4/q]: " action
    echo ""

    case "$action" in
        1)
            MEDIA=$(find_latest_media "tg")
            CAPTION=$(get_caption)
            share_post "$WHATSAPP_PKG" "$MEDIA" "$CAPTION"
            ;;
        2)
            MEDIA=$(find_latest_media "wa")
            CAPTION=$(get_caption)
            share_post "$GRAPH_PKG" "$MEDIA" "$CAPTION"
            ;;
        3)
            CAPTION=$(get_caption)
            share_post "$WHATSAPP_PKG" "" "$CAPTION"
            ;;
        4)
            CAPTION=$(get_caption)
            share_post "$GRAPH_PKG" "" "$CAPTION"
            ;;
        q|Q)
            exit 0
            ;;
    esac
    sleep 1
done