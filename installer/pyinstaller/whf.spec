# PyInstaller spec for the WorkloadHub AI Forecasting service (one-folder mode).
# Build from the repository root: uv run --directory service pyinstaller ../installer/pyinstaller/whf.spec
from pathlib import Path

from PyInstaller.utils.hooks import collect_all, collect_data_files

HERE = Path(SPECPATH)  # noqa: F821 (SPECPATH is injected by PyInstaller)
ROOT = HERE.parents[1]

whf_datas = collect_data_files("whf", includes=["db/schema.sql", "ai/skills/**/SKILL.md"])
copilot_datas, copilot_binaries, copilot_hidden = collect_all("copilot")
# holidays ships per-country .mo translation catalogs as package data (no pyinstaller-hooks-contrib
# hook covers it); without these, e.g. Morocco's calendar raises FileNotFoundError at runtime.
holidays_datas = collect_data_files("holidays")

a = Analysis(  # noqa: F821
    [str(HERE / "entry.py")],
    pathex=[str(ROOT / "service" / "src")],
    binaries=copilot_binaries,
    datas=whf_datas + copilot_datas + holidays_datas,
    hiddenimports=[
        "uvicorn.logging",
        "uvicorn.loops.auto",
        "uvicorn.protocols.http.auto",
        "uvicorn.protocols.websockets.auto",
        "uvicorn.lifespan.on",
        "sklearn.ensemble._hist_gradient_boosting",
        *copilot_hidden,
    ],
    excludes=["tkinter", "matplotlib", "IPython", "pytest", "hypothesis", "notebook"],
    noarchive=False,
)
pyz = PYZ(a.pure)  # noqa: F821
exe = EXE(  # noqa: F821
    pyz,
    a.scripts,
    exclude_binaries=True,
    name="whf",
    console=True,  # the app spawns it with windowsHide, so no console window appears
    disable_windowed_traceback=False,
)
coll = COLLECT(exe, a.binaries, a.datas, name="whf")  # noqa: F821
