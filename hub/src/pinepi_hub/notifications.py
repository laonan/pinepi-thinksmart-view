"""Bounded v2 notification adapter and legacy Mid text compatibility."""
import hashlib
import json
import os
from pathlib import Path
from uuid import uuid4
from .message_protocol import ProtocolError, validate_message
from .baidu_tts import validate_submission


MAX_TEXT_UNITS = 8192  # UTF-16 code units, matching Kotlin String.length.
MAX_TEXT_BYTES = 32 * 1024
MAX_FRAME_BYTES = 64 * 1024
MAX_STATE_BYTES = 512 * 1024


def decode_notification(packet, *, allow_hub_tts=False):
    if not isinstance(packet, dict):
        return None, 'invalid_event'
    event = packet.get('event', packet)
    if not isinstance(event, dict):
        return None, 'invalid_event'
    # Explicit protocol fields must never fall through to the legacy text adapter.
    if any(key in event for key in ('version', 'kind', 'text_mode', 'speech', 'media', 'severity')) or event.get('type') == 'home.message':
        try:
            validator = validate_submission if allow_hub_tts else validate_message
            return validator(event), None
        except ProtocolError as error:
            return None, str(error)
    title, text = event.get('title', ''), event.get('content')
    if not isinstance(title, str) or not isinstance(text, str) or not text.strip():
        return None, 'unsupported_or_empty_text'
    source = event.get('source', 'Broadcast Mid')
    if not isinstance(source, str):
        return None, 'invalid_source'
    source = source.strip() or 'Broadcast Mid'
    try:
        if len(source.encode('utf-16-le')) // 2 > 100:
            return None, 'source_too_long'
        if len(title.encode('utf-16-le')) // 2 > 200:
            return None, 'title_too_long'
        if (len(text.encode('utf-16-le')) // 2 > MAX_TEXT_UNITS
                or len(text.encode('utf-8')) > MAX_TEXT_BYTES):
            return None, 'content_too_long'
        upstream_id = event.get('id')
        ident = ('mid_' + hashlib.sha256(upstream_id.encode()).hexdigest()
                 if isinstance(upstream_id, str) and upstream_id else 'mid_' + uuid4().hex)
        result = {'type': 'home.message', 'version': 1, 'kind': 'TEXT', 'id': ident,
                  'source': source, 'title': title, 'text': text}
        if len(json.dumps(result, ensure_ascii=False).encode()) > MAX_FRAME_BYTES:
            return None, 'frame_too_large'
    except UnicodeError:
        return None, 'invalid_unicode'
    return result, None


def text_notification(packet):
    return decode_notification(packet)[0]


class NotificationStore:
    """Up to ten unacknowledged notifications, oldest first for replay."""
    def __init__(self, path: Path):
        self.path = path
        self.messages = []
        if path.exists():
            if path.stat().st_size > MAX_STATE_BYTES:
                raise ValueError('notification state too large')
            rows = json.loads(path.read_text())
            if not isinstance(rows, list) or len(rows) > 10:
                raise ValueError('invalid notification state')
            for row in rows:
                if (not isinstance(row, dict) or row.get('type') != 'home.message'
                        or not isinstance(row.get('id'), str)):
                    raise ValueError('invalid notification record')
                if row.get('version') == 2:
                    validate_message(row)
            self.messages = rows

    def add(self, message):
        if message.get('version') == 2:
            validate_message(message)
        if any(row['id'] == message['id'] for row in self.messages):
            return False
        rows = [*self.messages, message][-10:]
        self._save(rows)
        return True

    def acknowledge(self, ident):
        rows = [row for row in self.messages if row["id"] != ident]
        if len(rows) != len(self.messages):
            self._save(rows)

    def _save(self, rows):
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        temporary = self.path.with_suffix('.tmp')
        try:
            with open(temporary, 'w', opener=lambda p, flags: os.open(p, flags, 0o600)) as stream:
                json.dump(rows, stream, ensure_ascii=False)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
        finally:
            temporary.unlink(missing_ok=True)
        self.messages = rows
