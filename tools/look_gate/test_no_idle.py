"""A copy of the instance for look gate clients and other combat clients - without downtime Dynamic FPS (tools/prod_client.py)."""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import prod_client  # noqa: E402


class NoIdleTest(unittest.TestCase):
    def conf(self, dest):
        with open(os.path.join(dest, "config", "dynamic_fps.json")) as f:
            return json.load(f)

    def test_empty_config_gets_idle_off(self):
        # 25.10: empty player config ({}) - default settings, simple after 300 s, scenes went exactly 15 fps
        with tempfile.TemporaryDirectory() as dest:
            os.makedirs(os.path.join(dest, "config"))
            with open(os.path.join(dest, "config", "dynamic_fps.json"), "w") as f:
                f.write("{}")
            prod_client.no_idle(dest)
            self.assertEqual(0, self.conf(dest)["idle"]["timeout"])

    def test_other_settings_kept(self):
        with tempfile.TemporaryDirectory() as dest:
            os.makedirs(os.path.join(dest, "config"))
            with open(os.path.join(dest, "config", "dynamic_fps.json"), "w") as f:
                json.dump({"idle": {"timeout": 300, "condition": "on_battery"}, "states": {"hidden": {"frame_rate_target": 0}}}, f)
            prod_client.no_idle(dest)
            conf = self.conf(dest)
            self.assertEqual({"timeout": 0, "condition": "on_battery"}, conf["idle"])
            self.assertEqual({"hidden": {"frame_rate_target": 0}}, conf["states"])

    def test_old_idle_time_removed(self):
        # review #463: Dynamic FPS 3.11.4 when loading transfers the old idle_time to idle.timeout on top of our 0
        with tempfile.TemporaryDirectory() as dest:
            os.makedirs(os.path.join(dest, "config"))
            with open(os.path.join(dest, "config", "dynamic_fps.json"), "w") as f:
                json.dump({"idle_time": 300, "idle": {"timeout": 300}}, f)
            prod_client.no_idle(dest)
            conf = self.conf(dest)
            self.assertNotIn("idle_time", conf)
            self.assertEqual(0, conf["idle"]["timeout"])

    def test_missing_or_broken_config(self):
        for text in (None, "not json", "[]"):
            with tempfile.TemporaryDirectory() as dest:
                if text is not None:
                    os.makedirs(os.path.join(dest, "config"))
                    with open(os.path.join(dest, "config", "dynamic_fps.json"), "w") as f:
                        f.write(text)
                prod_client.no_idle(dest)
                self.assertEqual(0, self.conf(dest)["idle"]["timeout"])


if __name__ == "__main__":
    unittest.main()
