# PyInstaller spec for the WorkloadHub AI Forecasting service (one-folder mode).
# Build from the repository root: uv run --directory service pyinstaller ../installer/pyinstaller/whf.spec
#
# Analysis, PYZ, EXE, COLLECT and SPECPATH below are names PyInstaller injects into this file's
# globals when it execs the spec; they are not imported or defined here.
from pathlib import Path

from PyInstaller.utils.hooks import collect_all, collect_data_files

HERE = Path(SPECPATH)
ROOT = HERE.parents[1]

whf_datas = collect_data_files("whf", includes=["db/schema.sql", "ai/skills/**/SKILL.md"])
copilot_datas, copilot_binaries, copilot_hidden = collect_all("copilot")
# holidays ships per-country .mo translation catalogs as package data (no pyinstaller-hooks-contrib
# hook covers it); without these, e.g. Morocco's calendar raises FileNotFoundError at runtime.
# Morocco's MA class supports only ar (its default), en_US and fr (holidays.countries.morocco.MA
# .supported_languages), so only those three locale trees are needed; the full `holidays` locale/
# directory covers ~180 languages and would otherwise bloat every frozen build for locales v1 never uses.
holidays_datas = collect_data_files("holidays", includes=["locale/en_US/**", "locale/ar/**", "locale/fr/**"])

a = Analysis(
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
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    exclude_binaries=True,
    name="whf",
    console=True,  # the app spawns it with windowsHide, so no console window appears
    disable_windowed_traceback=False,
)
coll = COLLECT(exe, a.binaries, a.datas, name="whf")
