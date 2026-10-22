// Jarvis desktop shell. Starts the bundled PostgreSQL and the backend (the bundled Java runtime with the Spring Boot
// jar), shows its page in a window and lives in the tray. Both, and everything the backend starts (MCP servers), end
// with the shell.
//
// Development: JARVIS_BACKEND_URL=http://localhost:8080 uses a backend already running (./mvnw spring-boot:run)
// instead of the bundled one.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod database;

use std::fs::{self, File};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};

use tauri::menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, RunEvent, Url, WebviewWindow, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};

/// Fixed, not random: the page keeps its origin, and with it the conversation id in localStorage, across starts.
const PORT: u16 = 47321;
/// Passed by the autostart entry: start in the tray, without showing the window.
const HIDDEN: &str = "--hidden";
/// The first start creates the database schema and connects the MCP servers.
const READY_TIMEOUT: Duration = Duration::from_secs(180);

/// What the shell started: the database and the backend. Ending the shell ends them (see `ProcessTree`).
#[derive(Default)]
struct Started {
    database: Option<database::Database>,
    backend: Option<(Child, ProcessTree)>,
}

struct Backend(Mutex<Started>);

fn main() {
    tauri::Builder::default()
        // First: a second start only brings the running Jarvis to the front
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show(app)))
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec![HIDDEN])))
        .manage(Backend(Mutex::new(Started::default())))
        .setup(|app| {
            tray(app.handle())?;
            let window = app.get_webview_window("main").expect("main window");
            fit(&window)?;
            if !std::env::args().any(|arg| arg == HIDDEN) {
                window.show()?;
            }
            let handle = app.handle().clone();
            thread::spawn(move || open_backend(&handle, &window));
            Ok(())
        })
        // Closing the window keeps Jarvis in the tray; "Esci" ends it
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .build(tauri::generate_context!())
        .expect("Jarvis shell")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                stop_backend(app);
            }
        });
}

/// Starts or finds the backend, waits until it answers, then shows its page instead of the splash screen.
fn open_backend(app: &AppHandle, window: &WebviewWindow) {
    let url = match std::env::var("JARVIS_BACKEND_URL") {
        Ok(url) if !url.is_empty() => url,
        _ => match start_backend(app, window) {
            Ok(()) => format!("http://127.0.0.1:{PORT}/"),
            Err(error) => return fail(window, &format!("Non riesco ad avviare Jarvis: {error}")),
        },
    };
    let address = match Url::parse(&url) {
        Ok(parsed) => parsed,
        Err(error) => return fail(window, &format!("Indirizzo del backend non valido ({url}): {error}")),
    };
    let started = Instant::now();
    loop {
        if answers(&address) {
            let _ = window.navigate(address);
            return;
        }
        if let Some(status) = exited(app) {
            return fail(window, &format!("Jarvis si è chiuso all'avvio ({status}).\nDettagli in {}", console_log(app).display()));
        }
        if started.elapsed() > READY_TIMEOUT {
            return fail(window, "Jarvis non risponde. Riprova più tardi o controlla i log in ~/.jarvis/logs.");
        }
        thread::sleep(Duration::from_millis(500));
    }
}

fn start_backend(app: &AppHandle, window: &WebviewWindow) -> Result<(), String> {
    // Without the \\?\ prefix Tauri gives on Windows: initdb finds postgres.exe next to itself from its own path, and
    // cannot with that form
    let resources = dunce::simplified(&app.path().resource_dir().map_err(|e| e.to_string())?).to_path_buf();
    let home = jarvis_home(app)?;
    let (database, password) = database::start(&resources.join("postgres").join("bin"), &home,
        |message| { let _ = window.eval(&format!("setStatus({message:?})")); })
        .map_err(|e| format!("database: {e}"))?;
    app.state::<Backend>().0.lock().unwrap().database = Some(database);
    let _ = window.eval("setStatus('Avvio di Jarvis…')");

    let backend = resources.join("backend");
    let java = backend.join("runtime").join("bin").join(if cfg!(windows) { "java.exe" } else { "java" });
    let jar = backend.join("jarvis.jar");
    if !java.is_file() || !jar.is_file() {
        return Err(format!("backend mancante in {} (esegui prepare-backend.ps1)", backend.display()));
    }
    // ~/.jarvis as working directory: the backend imports ./.env and ./jarvis-local.yml, never from a random folder
    let console = File::create(console_log(app)).map_err(|e| e.to_string())?;
    let mut command = Command::new(&java);
    command
        .arg(format!("-Djavax.net.ssl.trustStoreType={}", TRUST_STORE))
        .arg("-jar")
        .arg(&jar)
        .current_dir(&home)
        .env("JARVIS_PORT", PORT.to_string())
        .env("JARVIS_DB_URL", format!("jdbc:postgresql://127.0.0.1:{}/{}", database::PORT, database::NAME))
        .env("JARVIS_DB_USER", database::USER)
        .env("JARVIS_DB_PASSWORD", password)
        // The backend runs whisper.cpp and Piper itself (LocalVoice): it only needs where they are
        .env("JARVIS_VOICE_HOME", resources.join("voice"))
        .stdin(Stdio::null())
        .stdout(console.try_clone().map_err(|e| e.to_string())?)
        .stderr(console);
    ProcessTree::prepare(&mut command);
    let child = command.spawn().map_err(|e| format!("{}: {e}", java.display()))?;
    let tree = ProcessTree::adopt(&child)?;
    app.state::<Backend>().0.lock().unwrap().backend = Some((child, tree));
    Ok(())
}

/// The certificates Java trusts: the system's, where IT also installs corporate CAs (TLS inspection). The JDK's own
/// cacerts would not have them. Linux keeps the default for now (to extend: the distribution's CA bundle).
#[cfg(windows)]
const TRUST_STORE: &str = "Windows-ROOT";
#[cfg(target_os = "macos")]
const TRUST_STORE: &str = "KeychainStore-ROOT"; // to verify on macOS
#[cfg(all(unix, not(target_os = "macos")))]
const TRUST_STORE: &str = "PKCS12";

/// The backend first, so it does not see its database disappear, then the database, cleanly.
fn stop_backend(app: &AppHandle) {
    let state = app.state::<Backend>();
    let mut started = state.0.lock().unwrap();
    if let Some((mut child, tree)) = started.backend.take() {
        tree.end();
        let _ = child.kill();
        let _ = child.wait();
    }
    if let Some(database) = started.database.take() {
        database.stop();
    }
}

/// Exit status of the backend the shell started, if it has already ended.
fn exited(app: &AppHandle) -> Option<String> {
    let state = app.state::<Backend>();
    let mut started = state.0.lock().unwrap();
    let (child, _) = started.backend.as_mut()?;
    child.try_wait().ok().flatten().map(|status| status.to_string())
}

/// Whether the backend serves its page: a plain HTTP request, no client library needed for one GET.
fn answers(url: &Url) -> bool {
    let Some(address) = url.socket_addrs(|| None).ok().and_then(|addresses| addresses.into_iter().next()) else {
        return false;
    };
    let host = url.host_str().unwrap_or("127.0.0.1");
    request(address, host).map(|status| status.starts_with("HTTP/1.1 200")).unwrap_or(false)
}

fn request(address: SocketAddr, host: &str) -> std::io::Result<String> {
    let mut stream = TcpStream::connect_timeout(&address, Duration::from_secs(1))?;
    stream.set_read_timeout(Some(Duration::from_secs(2)))?;
    write!(stream, "GET / HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n")?;
    let mut head = [0u8; 16];
    let read = stream.read(&mut head)?;
    Ok(String::from_utf8_lossy(&head[..read]).into_owned())
}

fn fail(window: &WebviewWindow, message: &str) {
    let _ = window.show();
    let _ = window.eval(&format!("showError({message:?})"));
}

fn jarvis_home(app: &AppHandle) -> Result<PathBuf, String> {
    let home = app.path().home_dir().map_err(|e| e.to_string())?.join(".jarvis");
    fs::create_dir_all(home.join("logs")).map_err(|e| e.to_string())?;
    Ok(home)
}

/// Console output of the backend, useful when it fails before its own log starts.
fn console_log(app: &AppHandle) -> PathBuf {
    jarvis_home(app).unwrap_or_else(|_| Path::new(".").to_path_buf()).join("logs").join("backend-console.log")
}

/// On a screen smaller than the window, the window takes the whole screen instead of overflowing it.
fn fit(window: &WebviewWindow) -> tauri::Result<()> {
    if let Some(monitor) = window.current_monitor()? {
        let (screen, size) = (monitor.size(), window.outer_size()?);
        if size.width > screen.width * 95 / 100 || size.height > screen.height * 90 / 100 {
            window.maximize()?;
        }
    }
    Ok(())
}

fn show(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn tray(app: &AppHandle) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Apri Jarvis", true, None::<&str>)?;
    let autostart = CheckMenuItem::with_id(app, "autostart", "Avvia con il sistema", true,
        app.autolaunch().is_enabled().unwrap_or(false), None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Esci", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &autostart, &PredefinedMenuItem::separator(app)?, &quit])?;
    let toggle = autostart.clone();
    TrayIconBuilder::with_id("jarvis")
        .icon(app.default_window_icon().expect("app icon").clone())
        .tooltip("Jarvis")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(move |app, event| match event.id().as_ref() {
            "open" => show(app),
            "autostart" => {
                // The item has already toggled itself: follow it, and put it back if the system refuses
                let wanted = toggle.is_checked().unwrap_or(false);
                let launcher = app.autolaunch();
                let applied = if wanted { launcher.enable() } else { launcher.disable() };
                if applied.is_err() {
                    let _ = toggle.set_checked(!wanted);
                }
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                show(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

/// Ends the backend together with what it started (npx, Node MCP servers). On Windows a job object closes them
/// even if the shell crashes. On macOS and Linux a process group, ended on exit (a crash of the shell would leave
/// it running: to extend, e.g. with a watchdog in the backend).
#[cfg(windows)]
struct ProcessTree(win32job::Job);

#[cfg(windows)]
impl ProcessTree {
    fn prepare(command: &mut Command) {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000; // no console window for java.exe
        command.creation_flags(CREATE_NO_WINDOW);
    }

    fn adopt(child: &Child) -> Result<Self, String> {
        use std::os::windows::io::AsRawHandle;
        let job = win32job::Job::create().map_err(|e| e.to_string())?;
        let mut info = job.query_extended_limit_info().map_err(|e| e.to_string())?;
        info.limit_kill_on_job_close();
        job.set_extended_limit_info(&mut info).map_err(|e| e.to_string())?;
        job.assign_process(child.as_raw_handle() as isize).map_err(|e| e.to_string())?;
        Ok(ProcessTree(job))
    }

    fn end(self) {
        drop(self.0); // closing the last handle ends every process of the job
    }
}

#[cfg(unix)]
struct ProcessTree(i32);

#[cfg(unix)]
impl ProcessTree {
    fn prepare(command: &mut Command) {
        use std::os::unix::process::CommandExt;
        command.process_group(0);
    }

    fn adopt(child: &Child) -> Result<Self, String> {
        Ok(ProcessTree(child.id() as i32))
    }

    fn end(self) {
        unsafe {
            libc::kill(-self.0, libc::SIGTERM);
        }
    }
}
