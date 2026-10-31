"""Baidu short-text synthesis for explicitly permitted narration only."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from .message_protocol import media_url, validate_message


class SpeechError(ValueError):
    """Public, body-free error suitable for the control API."""


def fetch(request, limit):
    try:
        with urlopen(request, timeout=30) as response:
            data = response.read(limit + 1)
            if len(data) > limit:
                raise SpeechError('baidu_response_too_large')
            return response.headers.get_content_type(), data
    except SpeechError:
        raise
    except Exception:
        # Never propagate urllib exceptions: they may include URLs or request details.
        raise SpeechError('baidu_connection_failed') from None


def validate_submission(message):
    """Hub producer input (HTTP or Mid) may omit TTS speech; terminal wire JSON stays strict."""
    if (isinstance(message, dict) and message.get('kind') == 'TEXT'
            and message.get('text_mode') == 'TTS' and 'speech' not in message):
        probe = dict(message, speech={'url': 'https://pinepi.invalid/pending.mp3', 'mime_type': 'audio/mpeg'})
        validate_message(probe)
        return message
    return validate_message(message)


class BaiduSpeech:
    MAX_AUDIO = 2 * 1024 * 1024
    MAX_CACHE = 256 * 1024 * 1024

    def __init__(self, config, directory, base_url):
        self.config = Path(config)
        self.directory = Path(directory)
        self.base_url = base_url.rstrip('/') + '/'
        media_url(self.base_url + 'probe.mp3')
        self.token = None
        self.token_until = 0
        self.credentials_fingerprint = None

    def prepare(self, message):
        validate_submission(message)
        # Guard BEFORE reading credentials or making any provider request.
        if message['kind'] != 'TEXT' or message.get('text_mode') != 'TTS' or 'speech' in message:
            return message
        text = message['text']
        try:
            if len(text.encode('gbk')) >= 1024:
                raise SpeechError('tts_text_exceeds_1023_gbk_bytes')
        except UnicodeEncodeError:
            raise SpeechError('tts_text_not_gbk_encodable') from None
        try:
            config = json.loads(self.config.read_text())
            api_key, secret_key = config['api_key'], config['secret_key']
            if not all(isinstance(v, str) and v.strip() for v in (api_key, secret_key)):
                raise ValueError()
            voice, speed = config.get('voice', 0), config.get('speed', 5)
            if type(voice) is not int or voice < 0 or type(speed) is not int or not 0 <= speed <= 15:
                raise ValueError()
        except Exception:
            raise SpeechError('baidu_configuration_invalid') from None
        digest = hashlib.sha256(json.dumps([text, voice, speed, 'baidu-mp3-v1'], ensure_ascii=False).encode()).hexdigest()
        filename = f'tts-{digest}.mp3'
        target = self.directory / filename
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        if not target.exists():
            if sum(p.stat().st_size for p in self.directory.glob('tts-*.mp3')) + self.MAX_AUDIO > self.MAX_CACHE:
                raise SpeechError('tts_cache_full')
            fingerprint = hashlib.sha256((api_key + '\0' + secret_key).encode()).digest()
            if self.token is None or time.monotonic() >= self.token_until or fingerprint != self.credentials_fingerprint:
                _, raw = fetch(Request('https://aip.baidubce.com/oauth/2.0/token', data=urlencode({
                    'grant_type': 'client_credentials', 'client_id': api_key, 'client_secret': secret_key,
                }).encode()), 64 * 1024)
                try:
                    result = json.loads(raw)
                    token = result['access_token']
                    lifetime = int(result.get('expires_in', 300))
                    if not isinstance(token, str) or not token or lifetime < 1:
                        raise ValueError()
                except Exception:
                    raise SpeechError('baidu_authentication_failed') from None
                self.token, self.token_until = token, time.monotonic() + max(1, lifetime - 60)
                self.credentials_fingerprint = fingerprint
            mime, audio = fetch(Request('https://tsn.baidu.com/text2audio', data=urlencode({
                'tex': text, 'tok': self.token, 'cuid': 'pinepi-home-hub', 'ctp': 1,
                'lan': 'zh', 'spd': speed, 'pit': 5, 'vol': 5, 'per': voice, 'aue': 3,
            }).encode()), self.MAX_AUDIO)
            if mime not in ('audio/mpeg', 'audio/mp3') or len(audio) < 128:
                raise SpeechError('baidu_synthesis_failed')
            temporary = None
            try:
                with tempfile.NamedTemporaryFile(dir=self.directory, delete=False) as stream:
                    temporary = Path(stream.name)
                    stream.write(audio)
                    stream.flush()
                    os.fsync(stream.fileno())
                temporary.replace(target)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
        elif target.stat().st_size < 128 or target.stat().st_size > self.MAX_AUDIO:
            raise SpeechError('tts_cache_invalid')
        prepared = dict(message, speech={'url': self.base_url + filename, 'mime_type': 'audio/mpeg'})
        return validate_message(prepared)
