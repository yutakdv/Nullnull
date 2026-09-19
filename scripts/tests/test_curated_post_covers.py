"""#183: the curated posts, the cover photos and the URLs that serve them name the same bytes.

Three artefacts, three owners, no link between them. `ops/curated-posts.json` is the plan the owner approves and the
importer stores (a URL and a checksum per cover); `docs/contest/covers/*.jpg` is what the release plan copies into the
assembly and CloudFront serves at `<PublicUrl>/covers/<file>` (infra/src/staging.ts, staging_operator.stage_covers).
Nothing at runtime compares them: the importer checks that a checksum is hex, not that it is the checksum of the file
the URL returns. A photo replaced without its checksum, or a URL pointing at a file that is not deployed, would publish
a post whose cover is broken or is not the one approved - and the gate would stay green.
"""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
PLAN = ROOT / "ops" / "curated-posts.json"
COVERS = ROOT / "docs" / "contest" / "covers"


class CuratedPostCovers(unittest.TestCase):
    def setUp(self):
        self.posts = json.loads(PLAN.read_text(encoding="utf-8"))["posts"]
        self.photos = sorted(COVERS.glob("*.jpg"))

    def test_every_cover_is_served_from_the_covers_path_of_one_https_distribution(self):
        self.assertTrue(self.posts, "the plan lists no posts")
        hosts = set()
        for post in self.posts:
            with self.subTest(post=post["id"]):
                url = urlsplit(post["cover"]["url"])
                # V021's media_assets_origin_url_check: an absolute https URL or nothing.
                self.assertEqual("https", url.scheme)
                self.assertTrue(url.hostname and url.hostname.endswith(".cloudfront.net"), url.hostname)
                self.assertEqual(f"/covers/{Path(post['_source_file']).name}", url.path)
                self.assertFalse(url.query or url.fragment)
                hosts.add(url.hostname)
        # One distribution. Two hosts would mean a stale domain survived an edit of the others.
        self.assertEqual(1, len(hosts), hosts)

    def test_each_checksum_is_the_sha256_of_the_photo_that_is_deployed(self):
        for post in self.posts:
            with self.subTest(post=post["id"]):
                photo = ROOT / post["_source_file"]
                self.assertEqual(COVERS, photo.parent, "a cover outside the deployed directory is never served")
                self.assertEqual(hashlib.sha256(photo.read_bytes()).hexdigest(), post["cover"]["checksum"])

    def test_every_deployed_photo_is_one_posts_cover(self):
        # stage_covers ships every .jpg in the folder; one no post names is served for nothing, and two posts on one
        # photo would mean one of them lost its own.
        named = sorted(Path(post["_source_file"]).name for post in self.posts)
        self.assertEqual(sorted(p.name for p in self.photos), named)

    def test_the_plan_holds_no_placeholder(self):
        # staging_operator.curation_plan refuses one too, but only when the owner is already at the command.
        self.assertNotIn("<BE:", PLAN.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
