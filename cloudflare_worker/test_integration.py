"""Run against local wrangler dev with a migrated D1 database."""
import base64
import json
import os
import secrets
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


BASE = os.environ.get('DMA_TEST_WORKER_URL', 'http://127.0.0.1:8787')
TOKEN = os.environ.get('DMA_TEST_ADMIN_TOKEN', 'a' * 40)


def call(path, body=None, admin=False):
    headers = {'Authorization': 'Bearer ' + TOKEN} if admin else {}
    if body is not None:
        headers['Content-Type'] = 'application/json'
    request = Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
    try:
        with urlopen(request) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        with error:
            return error.code, json.load(error)


def proof(code, pair, action, nonce=None):
    nonce = nonce or secrets.token_hex(16)
    public = pair.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    signature = pair.sign(f'{action}\n{code}\n{nonce}'.encode(), ec.ECDSA(hashes.SHA256()))
    return {'key': code, 'public_key': base64.b64encode(public).decode(), 'nonce': nonce,
            'signature': base64.b64encode(signature).decode(), 'model': 'Worker test phone'}


class WorkerTests(unittest.TestCase):
    def test_activation_binding_replay_revocation(self):
        self.assertEqual(401, call('/admin/api/licenses')[0])
        self.assertEqual(200, call('/health')[0])
        status, created = call('/admin/api/create', {'label': 'Worker test'}, admin=True)
        self.assertEqual(201, status)
        code = created['key']
        phone = ec.generate_private_key(ec.SECP256R1())
        other = ec.generate_private_key(ec.SECP256R1())
        first = proof(code, phone, 'activate')
        self.assertEqual(200, call('/api/activate', first)[0])
        self.assertEqual(403, call('/api/activate', first)[0])
        self.assertEqual(403, call('/api/activate', proof(code, other, 'activate'))[0])
        self.assertEqual(200, call('/api/check', proof(code, phone, 'check'))[0])
        _, listed = call('/admin/api/licenses', admin=True)
        item = next(item for item in listed['licenses'] if item['label'] == 'Worker test' and item['device_id'])
        self.assertEqual('Worker test phone', item['model'])
        self.assertEqual(200, call('/admin/api/revoke', {'id': item['id']}, admin=True)[0])
        self.assertEqual(403, call('/api/check', proof(code, phone, 'check'))[0])


if __name__ == '__main__':
    unittest.main()
