"""Validated terminal protocol. This module never calls a speech provider."""
from datetime import datetime
import ipaddress
import json
import re
from urllib.parse import urlsplit

MAX_FRAME_BYTES = 64 * 1024
MEDIA_TYPES = {
    'IMAGE': {'image/jpeg', 'image/png'},
    'AUDIO': {'audio/mpeg'},
    'VIDEO': {'video/mp4'},
}


class ProtocolError(ValueError):
    pass


def _require(condition, reason):
    if not condition:
        raise ProtocolError(reason)


def _string(value, maximum, *, nonempty=False):
    _require(isinstance(value, str), 'invalid_string')
    try:
        _require(len(value.encode('utf-16-le')) // 2 <= maximum, 'string_too_long')
    except UnicodeError:
        raise ProtocolError('invalid_unicode') from None
    _require(not nonempty or bool(value.strip()), 'empty_string')
    return value


def media_url(value):
    _string(value, 4096, nonempty=True)
    try:
        parsed = urlsplit(value)
        _require(not any(c.isspace() or ord(c) < 32 for c in value)
                 and '\\' not in value and parsed.hostname is not None
                 and parsed.username is None and parsed.password is None
                 and not parsed.fragment and parsed.port != 0, 'invalid_media_url')
        if parsed.scheme == 'https':
            return value
        networks = ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')
        address = ipaddress.IPv4Address(parsed.hostname)
        _require(parsed.scheme == 'http' and any(address in ipaddress.ip_network(n) for n in networks),
                 'invalid_media_url')
        return value
    except (ValueError, TypeError):
        raise ProtocolError('invalid_media_url') from None


def _asset(value, allowed):
    _require(isinstance(value, dict) and set(value) == {'url', 'mime_type'}, 'invalid_asset')
    media_url(value['url'])
    _require(isinstance(value['mime_type'], str) and value['mime_type'] in allowed, 'unsupported_mime_type')


def validate_message(message):
    """Validate without coercion, rewriting, truncation or provider side effects."""
    _require(isinstance(message, dict), 'invalid_message')
    _require(message.get('type') == 'home.message' and type(message.get('version')) is int
             and message['version'] == 2, 'unsupported_protocol')
    required = {'type', 'version', 'id', 'source', 'created_at', 'kind'}
    _require(required <= message.keys(), 'missing_fields')
    ident = _string(message['id'], 80, nonempty=True)
    _require(re.fullmatch(r'[A-Za-z0-9_-]{1,80}', ident) is not None, 'invalid_id')
    _string(message['source'], 100, nonempty=True)
    _string(message.get('title', ''), 200)
    timestamp = _string(message['created_at'], 40, nonempty=True)
    try:
        _require(re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})', timestamp),
                 'invalid_created_at')
        dt = datetime.fromisoformat(timestamp.replace('Z', '+00:00'))
        _require(dt.utcoffset() is not None and dt.year >= 1970, 'invalid_created_at')
    except ValueError:
        raise ProtocolError('invalid_created_at') from None
    kind = message['kind']
    _require(isinstance(kind, str) and kind in {'TEXT', *MEDIA_TYPES}, 'unsupported_kind')
    severity = message.get('severity', 'NORMAL')
    _require(isinstance(severity, str) and severity in ('NORMAL', 'CRITICAL'), 'invalid_severity')
    allowed = required | {'title', 'severity'}
    if kind == 'TEXT':
        allowed |= {'text', 'text_mode', 'speech'}
        text = _string(message.get('text'), 8192, nonempty=True)
        _require(len(text.encode('utf-8')) <= 32 * 1024, 'text_too_long')
        mode = message.get('text_mode')
        _require(mode in ('TTS', 'PRIVATE'), 'invalid_text_mode')
        if mode == 'PRIVATE':
            _require('speech' not in message, 'private_speech_forbidden')
        else:
            _asset(message.get('speech'), {'audio/mpeg'})
    else:
        allowed |= {'media'}
        _asset(message.get('media'), MEDIA_TYPES[kind])
    _require(message.keys() <= allowed, 'unexpected_fields')
    _require(len(json.dumps(message, ensure_ascii=False).encode('utf-8')) <= MAX_FRAME_BYTES, 'frame_too_large')
    return message
