import base64
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from server import Server


class LicenseServerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.server = Server(('127.0.0.1', 0), str(Path(self.temp.name) / 'license.sqlite3'), 'a' * 40)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def call(self, path, body=None, admin=False):
        headers = {'Authorization': 'Bearer ' + 'a' * 40} if admin else {}
        if body is not None:
            headers['Content-Type'] = 'application/json'
        request = Request(self.base + path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
        try:
            with urlopen(request) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            with error:
                return error.code, json.load(error)

    def proof(self, key, pair, action, nonce):
        public = pair.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        signature = pair.sign(f'{action}\n{key}\n{nonce}'.encode(), ec.ECDSA(hashes.SHA256()))
        return {'key': key, 'public_key': base64.b64encode(public).decode(), 'nonce': nonce,
                'signature': base64.b64encode(signature).decode(), 'model': 'Test phone'}

    def test_bind_check_replay_revoke(self):
        self.assertEqual(401, self.call('/admin/api/licenses')[0])
        status, created = self.call('/admin/api/create', {'label': 'Alice'}, admin=True)
        self.assertEqual(201, status)
        key = created['key']
        phone = ec.generate_private_key(ec.SECP256R1())
        other = ec.generate_private_key(ec.SECP256R1())
        first = self.proof(key, phone, 'activate', 'nonce-activation-0001')
        self.assertEqual(200, self.call('/api/activate', first)[0])
        self.assertEqual(403, self.call('/api/activate', first)[0])
        self.assertEqual(403, self.call('/api/activate', self.proof(key, other, 'activate', 'nonce-activation-0002'))[0])
        self.assertEqual(200, self.call('/api/check', self.proof(key, phone, 'check', 'nonce-check-0000001'))[0])
        _, listed = self.call('/admin/api/licenses', admin=True)
        self.assertEqual('Test phone', listed['licenses'][0]['model'])
        self.assertIsNotNone(listed['licenses'][0]['first_seen'])
        self.assertEqual(200, self.call('/admin/api/revoke', {'id': listed['licenses'][0]['id']}, admin=True)[0])
        self.assertEqual(403, self.call('/api/check', self.proof(key, phone, 'check', 'nonce-check-0000002'))[0])


if __name__ == '__main__':
    unittest.main()
