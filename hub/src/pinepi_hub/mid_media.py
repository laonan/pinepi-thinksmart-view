"""Private Mid media handoff. Credentials are sent only to the configured Mid origin."""
import asyncio
import hashlib
from http.client import HTTPException
import json
import math
import os
from pathlib import Path
import re
import shutil
import tempfile
import threading
import time
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request, ProxyHandler, HTTPRedirectHandler, build_opener
from urllib.error import HTTPError, URLError

from .broadcast_mid import read_config
from .message_protocol import validate_message, media_url

KINDS = {'IMAGE': {'image/jpeg': '.jpg', 'image/png': '.png'}, 'AUDIO': {'audio/mpeg': '.mp3'}, 'VIDEO': {'video/mp4': '.mp4'}}
LIMITS = {'IMAGE': 8 * 1024 * 1024, 'AUDIO': 16 * 1024 * 1024, 'VIDEO': 100 * 1024 * 1024}


class MediaError(ValueError):
    """Body-free, URL-free reason suitable for upstream status and logs."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def require(condition, reason):
    if not condition:
        raise MediaError(reason)


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(256 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


class MidMediaCache:
    def __init__(self, config, directory, base_url, link, capacity_bytes=512 * 1024 * 1024):
        require(isinstance(capacity_bytes, int) and capacity_bytes > 0, 'invalid_media_cache_capacity')
        self.config, self.directory, self.link = config, Path(directory), link
        self.base_url = base_url.rstrip('/') + '/'
        media_url(self.base_url + 'probe.mp3')
        self.capacity_bytes = capacity_bytes
        self.lock = asyncio.Lock()
        self.io_lock = threading.Lock()
        self.opener = build_opener(ProxyHandler({}), NoRedirect())
        if self.directory.is_dir():
            for partial in self.directory.glob('.mid-incoming-*'):
                if partial.is_file() and not partial.is_symlink() and time.time() - partial.stat().st_mtime > 600:
                    partial.unlink(missing_ok=True)

    def descriptor(self, description, upstream_url):
        require(isinstance(description, dict), 'invalid_media_descriptor')
        require(description.get('auth_mode') == 'DEVICE', 'private_media_requires_device_auth')
        media_id, digest, size = description.get('media_id'), description.get('sha256'), description.get('size_bytes')
        require(isinstance(media_id, str) and re.fullmatch('[0-9a-f]{32}', media_id), 'invalid_media_id')
        require(isinstance(digest, str) and re.fullmatch('[0-9a-f]{64}', digest), 'invalid_media_sha256')
        require(type(size) is int and size > 0, 'invalid_media_size')
        for name in ('expires_at', 'ticket_expires_at'):
            value = description.get(name)
            require(type(value) in (int, float) and math.isfinite(value), 'invalid_media_expiry')
        require(description['expires_at'] > time.time(), 'media_expired')
        try:
            parsed, trusted = urlsplit(description.get('url', '')), urlsplit(upstream_url)
            same_origin = (parsed.hostname == trusted.hostname and (parsed.port or 443) == (trusted.port or 443))
            query = parse_qs(parsed.query, keep_blank_values=True)
            require(parsed.scheme == 'https' and same_origin and parsed.username is None and parsed.password is None
                    and not parsed.fragment and parsed.path == '/api/media/' + media_id
                    and set(query) == {'ticket'} and len(query['ticket']) == 1 and 0 < len(query['ticket'][0]) <= 4096,
                    'untrusted_media_download_url')
        except (ValueError, TypeError, AttributeError):
            raise MediaError('untrusted_media_download_url') from None
        return description

    def refresh(self, descriptor, upstream_url, device_token):
        upstream = urlsplit(upstream_url)
        origin = 'https://' + upstream.netloc
        request = Request(origin + '/api/media/' + descriptor['media_id'] + '/ticket', data=b'', method='POST',
                          headers={'Authorization': 'Bearer ' + device_token})
        try:
            with self.opener.open(request, timeout=30) as response:
                raw = response.read(16385)
                require(len(raw) <= 16384, 'media_ticket_response_too_large')
                renewed = self.descriptor(json.loads(raw), upstream_url)
                require(all(renewed.get(k) == descriptor.get(k) for k in ('media_id', 'sha256', 'size_bytes', 'mime_type', 'expires_at')),
                        'media_ticket_metadata_changed')
                return renewed
        except MediaError:
            raise
        except Exception:
            raise MediaError('media_ticket_refresh_failed') from None

    def download(self, descriptor, target, upstream_url, device_token, cancelled):
        with self.io_lock:
            require(not cancelled.is_set(), 'media_download_cancelled')
            if target.is_file() and not target.is_symlink() and target.stat().st_size == descriptor['size_bytes'] and sha256_file(target) == descriptor['sha256']:
                return
            require(not target.is_symlink(), 'invalid_media_cache_path')
            self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
            files = list(self.directory.glob('mid-*'))
            replacing = target.is_file()
            require(len(files) + (0 if replacing else 1) <= 10000, 'media_cache_file_limit')
            used = sum(path.stat().st_size for path in files if path.is_file())
            orphan_bytes = sum(path.stat().st_size for path in self.directory.glob('.mid-incoming-*') if path.is_file())
            replaced_bytes = target.stat().st_size if replacing else 0
            require(used - replaced_bytes + orphan_bytes + descriptor['size_bytes'] <= self.capacity_bytes, 'media_cache_full')
            require(shutil.disk_usage(self.directory).free > descriptor['size_bytes'] + 16 * 1024 * 1024, 'media_cache_disk_full')
            current = descriptor
            for attempt in range(3):
                require(not cancelled.is_set(), 'media_download_cancelled')
                partial = None
                try:
                    request = Request(current['url'], headers={'Authorization': 'Bearer ' + device_token})
                    with self.opener.open(request, timeout=30) as response:
                        require(response.status == 200, 'unexpected_media_response')
                        require(response.headers.get_content_type() == descriptor['mime_type'], 'media_content_type_mismatch')
                        count, digest, started = 0, hashlib.sha256(), time.monotonic()
                        with tempfile.NamedTemporaryFile(dir=self.directory, prefix='.mid-incoming-', delete=False) as stream:
                            partial = Path(stream.name)
                            for chunk in iter(lambda: response.read(256 * 1024), b''):
                                require(not cancelled.is_set(), 'media_download_cancelled')
                                require(time.monotonic() - started < 120, 'media_download_timeout')
                                count += len(chunk)
                                require(count <= descriptor['size_bytes'], 'media_size_mismatch')
                                stream.write(chunk)
                                digest.update(chunk)
                            require(count == descriptor['size_bytes'] and digest.hexdigest() == descriptor['sha256'], 'media_integrity_mismatch')
                            stream.flush()
                            os.fsync(stream.fileno())
                        require(not cancelled.is_set(), 'media_download_cancelled')
                        os.replace(partial, target)
                        fd = os.open(self.directory, os.O_RDONLY)
                        try: os.fsync(fd)
                        finally: os.close(fd)
                        return
                except HTTPError as error:
                    code = error.code
                    error.close()
                    if 300 <= code < 400:
                        raise MediaError('media_redirect_forbidden') from None
                    if code in (401, 404, 410):
                        raise MediaError('media_download_denied_' + str(code)) from None
                    if attempt == 2:
                        raise MediaError('media_download_failed') from None
                except MediaError:
                    raise
                except (OSError, URLError, HTTPException):
                    if attempt == 2:
                        raise MediaError('media_download_failed') from None
                finally:
                    if partial is not None: partial.unlink(missing_ok=True)
                current = self.refresh(descriptor, upstream_url, device_token)
            raise MediaError('media_download_failed')

    async def accept(self, envelope):
        require(isinstance(envelope, dict) and envelope.get('type') == 'media.delivery', 'invalid_media_delivery')
        require(isinstance(envelope.get('delivery_id'), str) and re.fullmatch('[0-9a-f]{32}', envelope['delivery_id']), 'invalid_media_delivery_id')
        descriptions = envelope.get('downloads')
        require(isinstance(descriptions, list) and len(descriptions) == 1, 'invalid_media_downloads')
        upstream_url, _, device_token = read_config(self.config)
        descriptor = self.descriptor(descriptions[0], upstream_url)
        original = envelope.get('event')
        require(isinstance(original, dict), 'invalid_media_event')
        kind = original.get('kind')
        require(isinstance(kind, str) and kind in KINDS and isinstance(descriptor.get('mime_type'), str)
                and descriptor['mime_type'] in KINDS[kind], 'media_kind_mismatch')
        require(descriptor['size_bytes'] <= LIMITS[kind], 'media_terminal_size_limit')
        require(original.get('media') == {'url': 'mid://' + descriptor['media_id'], 'mime_type': descriptor['mime_type']}, 'invalid_media_reference')
        filename = 'mid-' + descriptor['media_id'] + '-' + descriptor['sha256'] + KINDS[kind][descriptor['mime_type']]
        message = dict(original, media={'url': self.base_url + filename, 'mime_type': descriptor['mime_type']})
        validate_message(message)  # Strict terminal schema, before network or storage.
        async with self.lock:
            require(self.link.notifications is not None, 'media_notification_store_missing')
            existing = next((m for m in self.link.notifications.messages if m['id'] == message['id']), None)
            require(existing is None or existing == message, 'media_message_id_conflict')
            cancelled = threading.Event()
            try:
                await asyncio.to_thread(self.download, descriptor, self.directory / filename, upstream_url, device_token, cancelled)
                # File + strict message/reference must be durable before the caller sends cached ACK.
                await self.link.publish_notification(message)
            finally:
                cancelled.set()
        return {'type': 'media.cached', 'media_id': descriptor['media_id'], 'delivery_id': envelope['delivery_id'], 'sha256': descriptor['sha256']}
