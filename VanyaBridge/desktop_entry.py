"""Packaged Windows desktop entry point with optional end-to-end startup check."""
import sys
import traceback
from pathlib import Path


def smoke_test():
    import tempfile
    import tkinter as tk
    from desktop.main import Desktop
    from backend.runtime import Runtime

    with tempfile.TemporaryDirectory(prefix='vanyabridge-smoke-') as tmp:
        window = tk.Tk()
        window.withdraw()
        runtime = Runtime('127.0.0.1', 0, str(Path(tmp) / 'data'))
        try:
            # Exercise real TLS/certificate, SQLite initialization, server startup
            # and the full Tkinter widget construction in the bundled executable.
            runtime.start()
            view = Desktop(window, runtime)
            window.update_idletasks()
            window.update()
            runtime.call('snapshot')
            window.update()
            assert view.chatlist is not None
        finally:
            runtime.stop()
            window.destroy()


if __name__ == '__main__':
    if '--smoke-test' in sys.argv:
        try:
            smoke_test()
        except BaseException:
            Path(sys.argv[sys.argv.index('--smoke-test') + 1]).write_text(
                traceback.format_exc(), encoding='utf-8'
            ) if len(sys.argv) > sys.argv.index('--smoke-test') + 1 else None
            raise
        sys.exit(0)

    from desktop.main import main
    main()
