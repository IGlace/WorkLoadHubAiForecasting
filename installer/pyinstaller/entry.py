"""Frozen entry point: `whf.exe <command>` behaves exactly like `uv run whf <command>`."""

import multiprocessing

from whf.cli import app

if __name__ == "__main__":
    multiprocessing.freeze_support()  # scikit-learn/joblib may spawn helpers in a frozen app
    app()
