"""Exercise the packaged desktop and runtime with isolated temporary data."""
import socket
import tempfile
import time
import tkinter as tk

from backend.runtime import Runtime
from desktop.main import Desktop, messagebox


def run_smoke():
    # An ephemeral port minimizes collisions with another application.
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix='vanya-smoke-') as data:
        window = tk.Tk()
        runtime = Runtime(port=port, data=data)
        error = []
        def report_error(title, message):
            error.append(f'{title}: {message}')
            window.quit()
        messagebox.showerror = report_error
        try:
            try:
                runtime.start()
            except RuntimeError as exc:
                raise RuntimeError(getattr(runtime, "failure_detail", str(exc))) from exc
            desktop = Desktop(window, runtime)
            window.update()
            if not window.winfo_viewable():
                raise RuntimeError('Desktop window is not visible')
            desktop.submit('статус')
            deadline = time.monotonic() + 15

            def check():
                tasks = desktop.tasks
                if tasks and tasks[0]['state'] in ('COMPLETED', 'FAILED', 'CANCELLED'):
                    if tasks[0]['state'] != 'COMPLETED' or not tasks[0].get('result', {}).get('os'):
                        error.append('Status command did not complete')
                    window.quit()
                elif time.monotonic() >= deadline:
                    error.append('Status command timed out')
                    window.quit()
                else:
                    window.after(100, check)

            window.after(100, check)
            window.mainloop()
            if error:
                raise RuntimeError(error[0])
        finally:
            runtime.stop()
            window.destroy()
        if runtime.thread.is_alive():
            raise RuntimeError('Server did not stop')
