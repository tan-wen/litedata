import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from smoke import Client, SmokeFailure, ensure_subject_area


class DictionaryFixtureTest(unittest.TestCase):
    def test_existing_standard_is_reused_without_extra_requests(self):
        client = Client()
        client.request = Mock()
        ensure_subject_area(client, "trade", '<select name="subjectArea"><option value="trade">交易</option></select>')
        client.request.assert_not_called()

    def test_missing_standard_is_created_with_csrf_and_cached(self):
        client = Client()
        client.request = Mock(side_effect=[(200, '<form action="/dictionaries/SUBJECT_AREA/items"><input name="_csrf" value="fixture"></form>'), (200, '字典项已保存')])
        ensure_subject_area(client, "chemical_park", "", "化工园区")
        ensure_subject_area(client, "chemical_park", "")
        self.assertEqual(2, client.request.call_count)
        fields = client.request.call_args.args[2]
        self.assertEqual("fixture", fields["_csrf"])
        self.assertEqual("chemical_park", fields["value"])
        self.assertEqual("化工园区", fields["label"])

    def test_inactive_standard_is_not_reenabled(self):
        client = Client()
        client.request = Mock()
        with self.assertRaisesRegex(SmokeFailure, "inactive"):
            ensure_subject_area(client, "trade", '<select name="subjectArea"><option value="trade" disabled>交易</option></select>')
        client.request.assert_not_called()
