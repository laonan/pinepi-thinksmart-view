#!/usr/bin/env python3
"""Generate the four public fixed prompts with Baidu. Never accepts message bodies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
from urllib.parse import urlencode
from urllib.request import Request, urlopen

PROMPTS = {
    'notice_text': '有新的文字消息',
    'notice_image': '有新的图片消息',
    'notice_audio': '有新的音频消息',
    'notice_video': '有新的视频消息',
}


def fetch(request, *, limit=2 * 1024 * 1024):
    with urlopen(request, timeout=30) as response:
        data = response.read(limit + 1)
        if len(data) > limit:
            raise ValueError('response_too_large')
        return response.headers.get_content_type(), data


def generate(config, destination, voice):
    # Only credentials are read. No arbitrary text or media URLs can enter a request.
    credentials = json.loads(config.read_text())
    keys = {name: credentials[name] for name in ('api_key', 'secret_key')}
    if not all(isinstance(value, str) and value.strip() for value in keys.values()):
        raise ValueError('invalid_credentials')
    _, raw = fetch(Request('https://aip.baidubce.com/oauth/2.0/token', data=urlencode({
        'grant_type': 'client_credentials', 'client_id': keys['api_key'], 'client_secret': keys['secret_key'],
    }).encode()))
    token = json.loads(raw).get('access_token')
    if not isinstance(token, str) or not token:
        raise ValueError('authentication_failed')
    # Generate all four before replacing any repository assets.
    generated = {}
    for name, text in PROMPTS.items():
        fields = {'tex': text, 'tok': token, 'cuid': 'pinepi-home-build-prompts', 'ctp': 1,
                  'lan': 'zh', 'spd': 5, 'pit': 5, 'vol': 5, 'per': voice, 'aue': 3}
        mime, audio = fetch(Request('https://tsn.baidu.com/text2audio', data=urlencode(fields).encode()))
        if mime not in ('audio/mp3', 'audio/mpeg') or len(audio) < 128:
            raise ValueError('speech_generation_failed')
        generated[name] = audio
    destination.mkdir(parents=True, exist_ok=True)
    for name, audio in generated.items():
        with tempfile.NamedTemporaryFile(dir=destination, delete=False) as output:
            temporary = Path(output.name)
            output.write(audio)
        try:
            os.chmod(temporary, 0o644)
            temporary.replace(destination / f'{name}.mp3')
        finally:
            temporary.unlink(missing_ok=True)
    return {name: {'text': PROMPTS[name], 'sha256': hashlib.sha256(audio).hexdigest()}
            for name, audio in generated.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True, help='private JSON: api_key, secret_key')
    parser.add_argument('--voice', type=int, default=0, help='Baidu per voice ID, default 度小美')
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[2] /
                        'android/app/src/main/res/raw')
    args = parser.parse_args()
    try:
        manifest = generate(args.config, args.output, args.voice)
    except Exception as error:
        # Never print upstream response bodies, request URLs, tokens or config values.
        parser.exit(1, f'Prompt generation failed ({type(error).__name__}); check credentials and Baidu permissions.\n')
    print(json.dumps({'provider': 'baidu', 'voice': args.voice, 'files': manifest}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
