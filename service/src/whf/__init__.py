"""WorkloadHub AI Forecasting service."""

import os

os.environ.setdefault("OMP_NUM_THREADS", "2")  # keep scikit-learn's OpenMP pool small on shared hosts

__version__ = "0.1.0"
