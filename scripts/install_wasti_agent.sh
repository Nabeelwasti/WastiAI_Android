#!/usr/bin/env bash
# ====================================================================
#   WASTI AI OS — SOVEREIGN AGENT DOWNLOAD & INSTALLER SUITE
#   Supports all 12 Open-Source Models with Multi-Path Fallbacks
# ====================================================================
set -euo pipefail

MODEL_ID="${1:-wasti-llama}"
TARGET_DIR="${WASTI_MODELS_DIR:-$HOME/.wasti_ai/models}"
mkdir -p "$TARGET_DIR"

echo "===================================================================="
echo "  WASTI AI OS: Installing Agent Model [$MODEL_ID]"
echo "  Target Directory: $TARGET_DIR"
echo "===================================================================="

case "$MODEL_ID" in
  wasti-llama|llama)
    FILE_NAME="Llama-3.2-1B-Instruct-Q4_K_M.gguf"
    EXPECTED_SHA="6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83"
    PRIMARY_URL="https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
    MIRROR_URL="https://hf-mirror.com/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
    ;;
  wasti-qwen|qwen)
    FILE_NAME="qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
    EXPECTED_SHA="cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046"
    PRIMARY_URL="https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
    MIRROR_URL="https://hf-mirror.com/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
    ;;
  wasti-gemma|gemma)
    FILE_NAME="gemma-2-2b-it-Q4_K_M.gguf"
    EXPECTED_SHA="e0aee85060f168f0f2d8473d7ea41ce2f3230c1bc1374847505ea599288a7787"
    PRIMARY_URL="https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf"
    MIRROR_URL="https://hf-mirror.com/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf"
    ;;
  wasti-deepseek|deepseek)
    FILE_NAME="DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
    EXPECTED_SHA="ba9acb0bcdb38fa9b8c20fc3133d15797087583ec06149849f28c0768c9998d7"
    PRIMARY_URL="https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
    MIRROR_URL="https://hf-mirror.com/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
    ;;
  wasti-smollm|smollm)
    FILE_NAME="SmolLM2-1.7B-Instruct-Q4_K_M.gguf"
    EXPECTED_SHA="decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33"
    PRIMARY_URL="https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf"
    MIRROR_URL="https://hf-mirror.com/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf"
    ;;
  wasti-phi|phi)
    FILE_NAME="Phi-3.5-mini-instruct-Q4_K_M.gguf"
    EXPECTED_SHA="e4165e3a71af97f1b4820da61079826d8752a2088e313af0c7d346796c38eff5"
    PRIMARY_URL="https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf"
    MIRROR_URL="https://hf-mirror.com/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf"
    ;;
  wasti-granite|granite)
    FILE_NAME="granite-3.0-2b-instruct-Q4_K_M.gguf"
    EXPECTED_SHA="25704b2701341c9d917663e6334035a0b0b10fdf57946238cd5554b5517dffad"
    PRIMARY_URL="https://huggingface.co/bartowski/granite-3.0-2b-instruct-GGUF/resolve/main/granite-3.0-2b-instruct-Q4_K_M.gguf"
    MIRROR_URL="https://hf-mirror.com/bartowski/granite-3.0-2b-instruct-GGUF/resolve/main/granite-3.0-2b-instruct-Q4_K_M.gguf"
    ;;
  wasti-mistral|mistral)
    FILE_NAME="Mistral-7B-Instruct-v0.3-Q4_K_M.gguf"
    EXPECTED_SHA="fa6747812bcb2a4b06ab74b2aa610b00147b9d6e73db35a680ef7b2fd109b750"
    PRIMARY_URL="https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf"
    MIRROR_URL="https://hf-mirror.com/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf"
    ;;
  wasti-falcon|falcon)
    FILE_NAME="Falcon3-7B-Instruct-Q4_K_M.gguf"
    EXPECTED_SHA="7a3ece746facd5da3d2622093ee2c0f785c63002aaf0f4eba4036b2f75734099"
    PRIMARY_URL="https://huggingface.co/tiiuae/Falcon3-7B-Instruct-GGUF/resolve/main/Falcon3-7B-Instruct-Q4_K_M.gguf"
    MIRROR_URL="https://hf-mirror.com/tiiuae/Falcon3-7B-Instruct-GGUF/resolve/main/Falcon3-7B-Instruct-Q4_K_M.gguf"
    ;;
  wasti-stablelm|stablelm)
    FILE_NAME="stablelm-2-1_6b-chat-Q4_K_M.gguf"
    EXPECTED_SHA="fd5a2a9cd60ccb5ac855e2f800ead83c88d9e72944b25adae4bfafa95e2b995e"
    PRIMARY_URL="https://huggingface.co/stabilityai/stablelm-2-1_6b-chat-GGUF/resolve/main/stablelm-2-1_6b-chat-Q4_K_M.gguf"
    MIRROR_URL="https://hf-mirror.com/stabilityai/stablelm-2-1_6b-chat-GGUF/resolve/main/stablelm-2-1_6b-chat-Q4_K_M.gguf"
    ;;
  wasti-glm|glm)
    FILE_NAME="glm-4-9b-chat-Q4_K_M.gguf"
    EXPECTED_SHA="79484cdc3b60985b2f8023d72418fc6d64eb77c170566fb7c87a4094bfdd1797"
    PRIMARY_URL="https://huggingface.co/THUDM/glm-4-9b-chat-GGUF/resolve/main/glm-4-9b-chat-Q4_K_M.gguf"
    MIRROR_URL="https://hf-mirror.com/THUDM/glm-4-9b-chat-GGUF/resolve/main/glm-4-9b-chat-Q4_K_M.gguf"
    ;;
  wasti-commandr|commandr)
    FILE_NAME="c4ai-command-r-v01-Q4_K_M.gguf"
    EXPECTED_SHA="eb51571cec874d650ca72cceed8c299b390dbae9b1edc2ca0b743fefabdad34b"
    PRIMARY_URL="https://huggingface.co/CohereForAI/c4ai-command-r-v01-GGUF/resolve/main/c4ai-command-r-v01-Q4_K_M-00001-of-00005.gguf"
    MIRROR_URL="https://hf-mirror.com/CohereForAI/c4ai-command-r-v01-GGUF/resolve/main/c4ai-command-r-v01-Q4_K_M-00001-of-00005.gguf"
    ;;
  *)
    echo "❌ Unknown model identifier: $MODEL_ID"
    echo "Available: wasti-llama, wasti-qwen, wasti-gemma, wasti-deepseek, wasti-smollm, wasti-phi, wasti-granite, wasti-mistral, wasti-falcon, wasti-stablelm, wasti-glm, wasti-commandr"
    exit 1
    ;;
esac

TARGET_FILE="$TARGET_DIR/$FILE_NAME"
TEMP_FILE="$TARGET_FILE.part"

echo "Checking existing files..."
if [ -f "$TARGET_FILE" ]; then
    echo "Existing file detected at $TARGET_FILE. Verifying hash..."
    CALC_SHA=$(sha256sum "$TARGET_FILE" | awk '{print $1}')
    if [ "$CALC_SHA" = "$EXPECTED_SHA" ]; then
        echo "✔ Verified existing model artifact: $FILE_NAME"
        exit 0
    fi
    echo "⚠ Existing checksum invalid. Re-downloading..."
    rm -f "$TARGET_FILE"
fi

echo "Attempting download from primary repository: $PRIMARY_URL"
if ! curl -L -C - --fail --retry 3 --retry-delay 5 -o "$TEMP_FILE" "$PRIMARY_URL"; then
    echo "⚠ Primary mirror failed or blocked. Attempting secondary mirror: $MIRROR_URL"
    curl -L -C - --fail --retry 3 --retry-delay 5 -o "$TEMP_FILE" "$MIRROR_URL"
fi

echo "Verifying SHA-256 integrity..."
CALC_SHA=$(sha256sum "$TEMP_FILE" | awk '{print $1}')
if [ "$CALC_SHA" != "$EXPECTED_SHA" ]; then
    echo "❌ Cryptographic integrity verification failed!"
    echo "   Expected: $EXPECTED_SHA"
    echo "   Computed: $CALC_SHA"
    rm -f "$TEMP_FILE"
    exit 1
fi

mv "$TEMP_FILE" "$TARGET_FILE"
echo "===================================================================="
echo "  ✔ SUCCESS: $MODEL_ID is fully installed and verified."
echo "  Path: $TARGET_FILE"
echo "===================================================================="
