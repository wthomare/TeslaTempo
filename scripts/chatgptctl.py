#!/usr/bin/env python3
"""Run the same ChatGPT setup CLI locally and in the relay image."""
import runpy
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "services" / "relay"))
runpy.run_module("relay.chatgpt_cli", run_name="__main__")
