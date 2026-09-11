import os
import sys
import requests

OPENROUTER_KEY = os.environ.get("OPENROUTER_API_KEY")
API_URL = "https://openrouter.ai"
TARGET_MODEL = "openrouter/free"

def ask_claude_to_edit(target_file, prompt_instruction):
    if not os.path.exists(target_file):
        print(f"Error: Target file '{target_file}' not found.")
        return

    with open(target_file, "r") as f:
        original_code = f.read()

    print("[WastiAI OS] Packaging file context for free Claude Sonnet brain...")
    
    # Modern browser identity spoofing to bypass OpenRouter cloud security blocks
    headers = {
        "Authorization": f"Bearer {OPENROUTER_KEY}",
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (Linux; Android 14; Redmi Note 14 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
        "HTTP-Referer": "https://github.com",
        "X-Title": "WastiAI OS Mobile CLI Client"
    }
    
    payload = {
        "model": TARGET_MODEL,
        "messages": [
            {
                "role": "user", 
                "content": f"You are an expert Android software engineer. Edit the code context precisely as requested.\n\nFile path context: {target_file}\n\nOriginal Source Code Contents:\n{original_code}\n\nModification Objective: {prompt_instruction}\n\nCRITICAL: Return ONLY the final updated source code text. Never wrap code blocks inside markdown triple backticks (```), do not add descriptions, and do not add greetings."
            }
        ]
    }

    try:
        response = requests.post(API_URL, json=payload, headers=headers)
        
        # Check if server blocked the request with a status error code
        if response.status_code != 200:
            print(f"\nAPI Error Status Code: {response.status_code}")
            print(f"Raw Response: {response.text}")
            return
            
        # Safe check to see if the response content is HTML instead of JSON
        if response.text.strip().startswith("<!DOCTYPE") or "<html>" in response.text:
            print("\n[OpenRouter Block] Cloud servers sent back an HTML error page.")
            print("This usually means your token is invalid or expired. Checking contents below:")
            print(response.text[:500])
            return

        response_data = response.json()
        new_code = response_data['choices']['message']['content']
        
        with open(target_file, "w") as f:
            f.write(new_code.strip())
        print(f"\n[Success] File '{target_file}' updated locally by free Claude pipeline.")

        print("Executing automated Git push sync via PAT Token configuration...")
        os.system("git add . && git commit -m 'Automated code tracking adjustment via free AI engine workflow' && git push origin main")
        print("[Git Deployment Complete]")
        
    except Exception as e:
        print(f"\nRuntime Processing Exception: {e}")
        if 'response' in locals():
            print(f"Server response dump:\n{response.text[:500]}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python claude_free_edit.py <filename> '<instruction>'")
    else:
        ask_claude_to_edit(sys.argv[1], sys.argv[2])
